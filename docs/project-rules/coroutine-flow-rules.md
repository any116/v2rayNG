# 协程与 Flow 规范

## 1. 唯一的启动入口

ViewModel 里禁止裸 `viewModelScope.launch`，统一用 `BaseViewModel.launch`：

```kotlin
protected fun launch(
    loading: Boolean = false,
    context: CoroutineContext = EmptyCoroutineContext,
    onError: (Throwable) -> Unit = { toastError() },
    block: suspend CoroutineScope.() -> Unit,
): Job
```

- `loading = true`：引用计数式加载态，嵌套安全；只在**用户可感知的阻塞操作**上开
  （导入、导出、删除、更新订阅、保存）。后台预取、事件监听一律 `false`。
- `onError`：默认弹通用错误 toast。后台任务传 `onError = {}` 静默，
  需要留痕时传 `{ LogUtil.e(AppConfig.TAG, "…", it) }`。
- `CancellationException` 由框架原样抛出，不要自己 catch `Throwable` 后吞掉
  （`BaseViewModel.launch` 与 `BaseRepository.runIO` 都已正确处理取消）。

## 2. Dispatcher 选择

数据层不再出现裸 `Dispatchers.IO`：两个 dispatcher 由 `di/Qualifiers.kt` 的
`@IoDispatcher` / `@DefaultDispatcher` 注入（`di/DispatcherModule.kt` 提供）。

| 场景 | 用什么 |
| --- | --- |
| Repository 内阻塞调用（Room、文件、网络、Root） | 构造注入的 `@IoDispatcher`，经 `withIO { }` |
| DAO 查询协程上下文 | `DatabaseModule` 的 `setQueryCoroutineContext(io)`，不要每处自选 |
| ViewModel 调 Repository 的 `suspend` 方法 | 默认（Main.immediate），线程由 `withIO` 内部决定 |
| CPU 密集（过滤、正则、排序、大 JSON） | `launch(context = Dispatchers.Default)` |
| Service / Worker 内的阻塞段 | 注入 `@IoDispatcher` 或 `withContext(io)` |

禁止在业务代码里写 `Dispatchers.IO`；测试需要一个确定性 dispatcher 时注入
`Dispatchers.Unconfined` / `StandardTestDispatcher`。

## 3. Job 生命周期

- 可被新请求取代的任务，Job 存成字段，启动前先 `cancel()`：`writeJob`、
  `filterJob`、`batchTestId` 对应的取消逻辑。
- 按 key 并发的任务用 `ConcurrentHashMap<String, Job>`。
- `onCleared()` 里取消全部字段 Job 与 map 里的 Job。
- 长循环体内必须 `currentCoroutineContext().ensureActive()`（样板：`SubRepository.updateAll`、
  `UserAssetRepository.downloadAll`）。
- Repository 不再持有 `Closeable` 资源；进程级资源（receiver 注册、`@ApplicationScope`）
  由 Hilt 图与 `AngApplication` 管理，ViewModel 不要尝试关闭它们。

## 4. 去抖、节流、合并

- **搜索去抖**：`MutableStateFlow` + `.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }`
  + `.distinctUntilChanged()`（`MainViewModel`，常量 300ms）。
  空串必须走 `0L`，否则首屏会被无意义地延迟一帧。
- **下拉框筛选去抖**：`FormPagedDropdownField` 内 `snapshotFlow { draftQuery }.collectLatest { delay(250) }`。
- **高频写结果合并**：`TestResultWriter` 用 `ConcurrentHashMap` 累积 + 单协程 400ms 循环 flush，
  不要每个测速回调都写一次库（每次写都会让主列表 `PagingSource` 失效）。
  样板见 `service/TestResultWriter.kt`。
- **分页查询切换**：`debouncedQuery.flatMapLatest { repo.serverPager(groupId, it) }`，
  切组/搜索自动取消旧 `PagingSource`。
- **启动顺序**：用 `CompletableDeferred` 表达"首屏就绪"（`MainViewModel.firstPageReady`），
  重资产初始化（`repo.prepare()` = `SettingsManager.initAssets` + `SubscriptionUpdater.sync`）
  等首屏之后再做。

## 5. Flow 的位置

| 类型 | 放哪 | 参数 |
| --- | --- | --- |
| UI 状态 | `BaseViewModel._uiState` | `MutableStateFlow` + `asStateFlow()` |
| 大列表 | Repository 的 `Pager`，ViewModel 内按 key 缓存 | `Flow<PagingData<T>>`，`cachedIn(viewModelScope)` |
| 计数切片 | ViewModel 内 `ConcurrentHashMap<String, StateFlow<Int>>` | `stateIn(..., WhileSubscribed(TIMEOUT), initial)` |
| 一次性事件 | `BaseViewModel._events` | `Channel(UNLIMITED)` + `receiveAsFlow()` |
| 跨进程/系统事件 | Repository | `MutableSharedFlow(replay = 0, extraBufferCapacity = 64, DROP_OLDEST)` |
| 全局主题 | `ThemeRepository`（`ThemeStore` 接口） | `MutableStateFlow` + `asStateFlow()` |

- 事件用 `Channel` 不用 `SharedFlow`：`receiveAsFlow()` 单消费者、
  UI STOPPED 时缓冲、RESUMED 时补发，且不会重放。
- UI 收集一律 `collectAsStateWithLifecycle()`；收事件一律
  `repeatOnLifecycle(Lifecycle.State.STARTED)`（`BaseEventEffect` 已封装）。
- 不要在 Composable 里 `collect { }` 后自己 `mutableStateOf` 存一份。
- **Paging 数据链**（Paging 3 官方形态）：
  `DAO PagingSource → Repository Pager → ViewModel Flow → cachedIn(viewModelScope)
  → Compose collectAsLazyPagingItems()`。
  `PagingData` 单独暴露，**不要**塞进 `BaseUiState`。
- **缓存 Pager**：`MainViewModel.pagers` 用容量 6 的 LRU `LinkedHashMap` 缓存每个分组的
  `Flow<PagingData<...>>`，`synchronized` 访问（Composable 主线程 + 协程都会碰它）。
  分组列表变化后 `removeAll { it !in validIds }` 清理解除引用。

## 6. 取消与不可取消

用户已确认的写入不能因为退出页面而丢失，用 `withContext(NonCancellable)` 包裹落盘那一小段
（样板：`MainViewModel.selectServer` 的 `setSelectedGuid`、`SettingsViewModel.persist`），
而不是把整个协程改成不可取消。

## 7. 并发原语选择

| 需求 | 用什么 |
| --- | --- |
| 保护可变 Map/List | `Mutex` + `withLock`（挂起，不阻塞线程） |
| 高并发只读 + 偶写的 key→value | `ConcurrentHashMap` |
| 布尔脏标、一次性开关、幂等注册 | `AtomicBoolean` / `compareAndSet` |
| 需要容量上限的按 key 缓存 | 访问序 `LinkedHashMap` + `@Synchronized` |
| 跨进程互斥 | `java.io.RandomAccessFile` 的 `FileLock`（样板：`LegacyMigrationGate`） |

- 禁止 `GlobalScope`、`runBlocking`、裸 `Thread`。
- `@Volatile` 仅用于简单标量；`SettingsStore.snapshot` 的多步操作（`putAll` + `retainAll`）
  必须用锁（`synchronized(snapshotLock)`），单键读写走 `ConcurrentHashMap` 自带原子性。
