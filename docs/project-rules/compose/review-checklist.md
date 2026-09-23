# Code Review Checklist

提交前自查 + Review 别人时逐条对照。分为"必须阻断（Blocker）"与"需要讨论（Discuss）"。

---

## A. 架构分层（Blocker）

- [ ] ViewModel 里没有 `import com.v2ray.ang.handler.*` / `core.*` / `service.*`
- [ ] ViewModel 里没有 `import com.v2ray.ang.data.*Dao` / `AppDatabase` / `Prefs`
- [ ] ViewModel 里没有 `Context` / `Application` / `Resources` / `stringResource`
- [ ] ViewModel 里没有 `androidx.compose.*`（`@Immutable` / `@Stable` 注解除外）
- [ ] Repository 里没有 `import com.v2ray.ang.ui.*`（既有 `DropdownOption` 例外除外）
- [ ] Composable 里没有直接调 DAO / `Prefs` / `handler/`
- [ ] Composable 里没有 `LocalContext.current as XxxActivity`；平台能力走 `LocalPlatformActions`
- [ ] 新增的类放对了包：Room 实体/DAO→`data/`（`data/entities/`）、纯 DTO→`dto/`、
      屏专属模型→`XxxContract.kt`、数据门面→`data/repository/`、跨进程数据源→`handler/`、
      manifest 入口→`receiver/`
- [ ] 没有新增 SharedPreferences / DataStore / Room 2 / 第二种持久化；
      MMKV 只在 `data/legacy/MmkvLegacyReader.kt`（只读）
- [ ] 没有引入 Navigation-Compose、material-icons-extended
- [ ] 没有新增 XML 业务布局、没有新增 `AndroidView`（除非 PR 中说明了不可替代的理由）

## B. SSOT（Blocker）

- [ ] 这一屏只有一个 `XxxUiState : BaseUiState`，`@Immutable`，全 `val`
- [ ] 状态修改只通过 `setState { copy(...) }`，没有第二个 `MutableStateFlow` 承载 UI 状态
      （分页切片、计数切片、`ThemeManager` 属于既定例外）
- [ ] `isLoading` 没有被塞进 UiState
- [ ] 一次性效果（消息、导航、弹窗触发、Bitmap）没有进 UiState
- [ ] Composable 没有把 State 里的值 `remember` 成第二份可变副本
- [ ] 表单输入的唯一来源是 UiState，不是 Composable 局部 `mutableStateOf`
- [ ] 大列表数据没有塞进 UiState（用 Paging 3 的 `Flow<PagingData<T>>`）

## C. UDF（Blocker）

- [ ] Composable 与 ViewModel 之间只有 `onAction(action)` 一条写通道
- [ ] 除 `uiState` / `isLoading` / `events` / 切片访问器的读取型订阅外，
      Composable 没调用任何 ViewModel 成员
- [ ] 没有把 `viewModel` 传给非根 Composable
- [ ] `onAction` 的 `when` 是穷尽的（没有 `else` 兜底）
- [ ] Action 命名表达用户意图，不是 setter
- [ ] 一次性效果走 `BaseEvent`；`BaseEvent.Platform` 由 Activity 翻译或屏内 `onEvent` 截获
- [ ] 提示文案用 `BaseText` 描述，由 UI 渲染；ViewModel 没有直接弹 toast/Snackbar
- [ ] 子页结果通过 `BaseResult` 回传，父页用 `onResult` → Action 处理
- [ ] Activity 里没有业务判断，只有 VM 构造、平台能力翻译、系统回调

## D. 数据层（Room 3 / Repository）（Blocker）

- [ ] Repository 继承 `BaseRepository`，阻塞调用在 `withIO { }` 内，构造注入 `@IoDispatcher`
- [ ] 没有在 Repository 里裸写 `Dispatchers.IO`；没有 `launch` / 自建 `CoroutineScope`
- [ ] 需要被 mock 的方法声明了 `open`
- [ ] 列表查询用轻量投影，没有 `SELECT *` 全表读 profiles
- [ ] 搜索走 `normalizeLike()` + SQL `LIKE`（不是正则），搜索字段是 remarks/description/server
- [ ] 跨表写入走 `@Transaction`；删除同时清 `profiles` / `profile_stats` / `profile_raw`
- [ ] `IN (:list)` 超过 `SQLITE_VAR_LIMIT` 时已 `chunked(...)`
- [ ] 没有手写 `groupSortOrder`（由 `GROUP_ORDER_TRIGGERS` 维护）
- [ ] 分页 `PagingConfig` 参数合理；分页下拉用 `enablePlaceholders = false`
- [ ] 新增偏好项走了完整流程（AppConfig → `BoolPref`/`StringPref` → `isUiOnly` 分类 →
      旧 key 登记进 `SettingKinds` → UI 项）
- [ ] 需要重启内核才生效的设置项，`uiOnly` 判定正确

## E. 协程（Blocker）

- [ ] ViewModel 用 `launch(...)` 包装，没有裸 `viewModelScope.launch`
- [ ] `loading = true` 只用在用户可感知的阻塞操作上
- [ ] 长循环里有 `currentCoroutineContext().ensureActive()`
- [ ] 可被取代的 Job 存成字段并在启动前 `cancel()`；`onCleared()` 里清理干净
- [ ] 同 key 的连续写入做了串行化（`previous?.join()`）
- [ ] 必须落盘的写入用 `withContext(NonCancellable)` 包裹
- [ ] 没有 `GlobalScope`、`runBlocking`、`Thread`
- [ ] 搜索用 `debounce`（空串走 0）+ `distinctUntilChanged`，没有自己起循环

## F. Compose 结构（Blocker）

- [ ] 文件按 `XxxActivity/Contract/Screen/ViewModel` 四件套组织，超长文件已拆分
- [ ] 每个可复用 Composable 有 `modifier: Modifier = Modifier` 且作用在最外层
- [ ] 参数顺序正确（必填数据 → 必填回调 → modifier → 可选参数）
- [ ] Content 层是无状态的，能被 Preview 直接调用
- [ ] 只放进 `ui/compose/` 的组件确实被 ≥2 个 feature 使用
- [ ] 复用了既有共享组件（`AppTopBar`/`ConfirmDialog`/`SelectListDialog`/`SettingsXxxItem`/
      `FormTextField`/`FormDropdownField`/`FormPagedDropdownField`/`AppDropdownMenuItems`/
      `verticalScrollbar`），没有重复造轮子
- [ ] 使用 `BaseScreen` 的屏：底部有 `NavigationBarsSpacer()` /
      `NavigationBarsBottomPadding()`，或底栏自身已处理 `WindowInsets.navigationBars`；
      没有用固定 dp 猜导航栏高度

## G. 性能与重组（Blocker）

- [ ] `uiState` 只被 `BaseScreen` 收集一次；栏区用了收窄切片（`map` + `distinctUntilChanged` + `remember`）
- [ ] `dispatch` 用 `remember(viewModel) { viewModel::onAction }` 固定引用
- [ ] 多于 3 个回调打包成了 `@Stable class`，并在列表外 `remember` 一次
- [ ] 所有 lazy 列表项有 `key`；异构列表有 `contentType`
- [ ] Paging 列表用 `items.itemKey { it.guid }` / `items.itemContentType { … }`
- [ ] Paging 占位行（`items[index] == null`）已处理
- [ ] `collectAsLazyPagingItems()` 的结果被 `remember` 住
- [ ] 列表项内部没有订阅大流、没有做计算
- [ ] composition 中没有正则、排序、过滤、IO、字符串重拼装
- [ ] 默认参数不是新建对象（用 `Companion.Default` 单例）
- [ ] 颜色/`TextFieldColors` 等对象没有在每次重组时重建
- [ ] 高频状态读取下沉到 lambda-based modifier（`offset {}` / `graphicsLayer {}` / `drawBehind {}`）
- [ ] `LaunchedEffect` 的 key 稳定；回调用 `rememberUpdatedState` 包裹
- [ ] `LaunchedEffect(Unit)` 只用于真正的一次性初始化
- [ ] 等待布局就绪的 effect 有 `withTimeoutOrNull` 兜底
- [ ] 一次性事件处理完清了标志（`finally { onXxxHandled() }`）
- [ ] `HorizontalPager` 有 `key`，`beyondViewportPageCount` 合理
- [ ] 按 key 缓存的 Map（滚动状态、pager、计数流）有 `retain` / `removeAll` 清理路径
- [ ] 没有为了加 padding 而多包一层容器；层级没有无谓加深
- [ ] Bitmap 用完即弃，没有进 State 或缓存
- [ ] 若是性能改动，PR 描述里给出了改前/改后的重组次数或帧率对比

## H. 主题与资源（Blocker）

- [ ] 没有 `Color(0xFF…)` 字面量；用 `MaterialTheme.colorScheme` 或 `LocalAppColors`
- [ ] 没有新建全局 dimens/spacing 对象；尺寸是文件内 `private val` 且命名表达用途
- [ ] 没有硬编码用户可见文案；新文案进了 `res/values/strings.xml`
- [ ] 字体只用 `MaterialTheme.typography.*`
- [ ] 字符串数组用 `rememberStringOptions(@ArrayRes)`
- [ ] 图片有固定尺寸与 placeholder

## I. 无障碍（Blocker）

- [ ] 可点击图标有 `contentDescription`（描述动作，`acc_` 前缀资源）
- [ ] 装饰性图标 `contentDescription = null`
- [ ] 开关/单选用了 `toggleable` / `selectable` + 正确的 `role`，内层控件未重复注册回调
- [ ] 触控目标 ≥ 48.dp
- [ ] 长文本有 `maxLines` + `TextOverflow`，没有写死 `fontSize`
- [ ] 状态不只靠颜色表达

## J. 多进程与系统组件（Blocker）

- [ ] 新增的服务→UI 通知走了 `AppConfig.MSG_*` → `MainServiceEvent` → `MainViewModel` 唯一链路
- [ ] 没有在第二个 Repository 里注册 `BROADCAST_ACTION_ACTIVITY`
- [ ] Receiver 里只有翻译转发，没有业务逻辑和 IO
- [ ] `PendingIntent` 带 `FLAG_IMMUTABLE`
- [ ] 启停内核只经 `LauncherManager`
- [ ] API 版本判断用 `Build.VERSION_CODES.*` 常量，不用数字
- [ ] Worker 用 `@HiltWorker` + `@Assisted`（Context / WorkerParameters），只注入进程无关依赖
- [ ] Worker 改名/移动时递增 `SubscriptionUpdater.WORKER_SCHEMA_VERSION`
- [ ] `:daemon` / `:tasks` / `:bg` 组件需要 DAO/设置时走 `PlatformDependencies`，
      没有自己 new 数据库或 Hilt 图

## K. 依赖注入（Hilt）（Blocker）

- [ ] ViewModel 用 `@HiltViewModel` + `@Inject constructor`；Activity 用 `@AndroidEntryPoint`
- [ ] `@Inject constructor` 参数没有全带默认值（否则 Dagger 报
      `may only contain one injected constructor`）
- [ ] 没有新增自定义 `ViewModelProvider.Factory` / `baseViewModels` 调用
- [ ] 没有把现有 object 又包一层 `@Singleton` 影子
- [ ] 不在 `attachBaseContext` / 属性初始化器里访问注入字段
- [ ] 新模块只放接缝，不放业务

## L. 通用工程（Blocker）

- [ ] 依赖版本走 `libs.versions.toml`，没有硬编码
- [ ] 日志用 `LogUtil` + `AppConfig.TAG`，没有 `Log.x` / `printStackTrace()`
- [ ] 没有提交调试代码（`composeCompiler` 报告块、临时打印、注释掉的实验代码）
- [ ] 改 Room schema 时递增 `AppDatabase.version`、提供 `Migration`、重新导出并提交 `app/schemas/**`
- [ ] `./gradlew :app:compileFdroidReleaseKotlin` 通过
- [ ] `./gradlew test` 通过

## M. 需要讨论（Discuss，非阻断）

- [ ] 新增了 handler：是否真的跨进程复用？能否直接写进 Repository？
- [ ] 新增了 `ui/compose/` 共享组件：现在是否已有 ≥2 个使用方？
- [ ] 新增了 Action：是否与已有 Action 语义重叠？
- [ ] 新增了 dto：是否只服务一屏（应下沉到 `XxxContract.kt`）？
- [ ] 新增了 Room 表/列：是否真的需要索引？查询计划是否走索引？
- [ ] 使用了 Material3 alpha API：是否有稳定替代？升级 `1.5.0-alpha28` 时会不会断？
- [ ] 分页参数（pageSize / prefetch / placeholders）是否适合这个列表的规模？
- [ ] 新增的常量是否应该进 `AppConfig`（跨模块）还是留在文件内（局部）？
