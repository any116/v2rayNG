# Compose 规范 · 性能与重组控制

Kotlin 2.4 的 Compose 编译器插件默认开启 **Strong Skipping**：
只要参数类型稳定（或是 lambda），Composable 就能被跳过。因此本章的重点是
**让参数稳定 + 让订阅范围最小**，而不是到处塞 `remember`。

## 1. 十条硬规则（减少重组）

1. **一屏只订阅一次 `uiState`**：只有 `BaseScreen` 收集它。
2. **槽位内单独收窄切片**：topBar / bottomBar 需要的字段自己收，不从 content 传出去。
3. **大列表不进 UiState**：走 Paging 3 的 `Flow<PagingData<T>>`，在 content 里
   `collectAsLazyPagingItems()`。
4. **稳定的 dispatch 引用**：`val dispatch = remember(viewModel) { viewModel::onAction }`。
5. **多回调打包成 `@Stable class`**，不要传 4 个匿名 lambda。
6. **所有 `LazyColumn` / `LazyVerticalGrid` 的 `items` 必须给 `key`**，
   异构列表再给 `contentType`。
7. **不在 composition 里做计算**：过滤、排序、字符串拼装全在 ViewModel/Repository/SQL。
8. **默认参数不得是新建对象**：提成 `companion object` 的 `Default` 单例。
9. **高频 State 读取要下沉**：把读取推到真正需要它的最内层 Composable 或
   lambda-based modifier 里。
10. **副作用的 key 必须稳定**，回调用 `rememberUpdatedState` 包裹。

## 2. 订阅收窄（本项目的标准模式）

`MainScreen` 是范本，三个层次互不重叠：

```kotlin
BaseScreen(
    viewModel = viewModel,
    topBar = {
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()  // 只影响顶栏
        val search by rememberSearchState(viewModel)
        MainTopBar(isLoading = isLoading, isSearchActive = search.isActive, query = search.query, …)
    },
    bottomBar = {
        val bottom by rememberBottomState(viewModel)                        // 只影响底栏
        MainBottomBar(statusText = bottom.status.asText(), isRunning = bottom.isRunning, …)
    },
) { state, _ -> MainContent(args = MainPagerArgs(…), …) }
```

窄切片的标准写法：

```kotlin
@Immutable
private data class MainBottomState(val status: MainStatus, val isRunning: Boolean)

@Composable
private fun rememberBottomState(viewModel: MainViewModel): State<MainBottomState> {
    val flow = remember(viewModel) {
        viewModel.uiState.map { MainBottomState(it.status, it.isRunning) }.distinctUntilChanged()
    }
    val initial = remember(viewModel) {
        viewModel.uiState.value.let { MainBottomState(it.status, it.isRunning) }
    }
    return flow.collectAsStateWithLifecycle(initialValue = initial)
}
```

要点：`remember` 住 flow（否则每次重组都建新流并重新收集）、
`distinctUntilChanged()`、给 `initialValue` 避免首帧闪烁。

同样地，content 层再把 state 拆成 `@Immutable` 的参数包
（`MainPagerArgs`），让下游只依赖它真正用到的字段。

## 3. 稳定性

| 类型 | 注解 | 说明 |
| --- | --- | --- |
| UiState、参数包、事件载荷 | `@Immutable` | 全 `val`，字段本身也必须稳定 |
| 持有可变句柄/缓存的类 | `@Stable` | 如 `MainSlices`、`GroupScrollStates`、`MainDialogHost`、`MainScreenHandles`、`AppSnackbarController`、`ScrollbarConfig` |
| 值对象包装集合 | 自定义类 + `equals` | 如 `StringOptions` 包 `List<String>` |
| Paging 行模型 | `@Immutable` `data class` | `ServerRowItem`、`DropdownOption` |

- `List` / `Map` / `Set` 在 Compose 里被视为**不稳定**。如果它作为高频参数出现在热路径上，
  用 `@Immutable` 的包装类（`StringOptions`）或 `kotlinx.collections.immutable`
  （本项目未引入，优先用包装类）。
- `@Stable class` 里暴露的 lambda 要是**字段**而不是方法引用表达式：
  ```kotlin
  @Stable
  class MainDialogHost(private val onAction: (MainAction) -> Unit) {
      val show: (MainDialog) -> Unit = { current = it }      // 引用恒定
      val dismiss: () -> Unit = { current = null }
  }
  ```
- 回调打包：
  ```kotlin
  @Stable
  class ServerRowCallbacks(
      val onSelect: (String) -> Unit,
      val onEdit: (String, EConfigType) -> Unit,
      val onShare: (String, EConfigType) -> Unit,
      val onMore: (String, EConfigType) -> Unit,
      val onRemove: (String) -> Unit,
  )
  ```
  在列表外层 `remember(handles) { ServerRowCallbacks(...) }` 一次，所有行共用。

## 4. 列表与 Paging 3

```kotlin
val items = remember(groupId, handles) { handles.slices.servers(groupId) }
    .collectAsLazyPagingItems()
LazyColumn(state = scrollStates.list(groupId), contentPadding = contentPadding) {
    items(
        count = items.itemCount,
        key = items.itemKey { it.guid },
        contentType = items.itemContentType { ServerRowContentType },
    ) { index ->
        val row = items[index]
        if (row == null) ServerRowPlaceholder() else ServerRow(row, …, callbacks)
    }
}
```

- `key` 用稳定业务 id（`guid`），不要用 index。
- `contentType` 让不同类型的行复用各自的槽（Paging 的 `itemContentType`）。
- **占位行必须处理**：`enablePlaceholders = true` 时 `items[index]` 可能为 `null`，
  画一个和真实行同高同宽的骨架（`ServerRowPlaceholder`），避免滚动条跳动。
- **`collectAsLazyPagingItems()` 的结果要 `remember` 住**，不要每帧重建流。
- **滚动状态按分组缓存**：`GroupScrollStates` 用 `HashMap<String, LazyListState>`，
  切分组回来时位置不丢；分组列表变化后调 `retain(validIds)` 清理，防止泄漏。
- `HorizontalPager` 设 `beyondViewportPageCount = 0`，并给 `key = { groups[it].id }`。
- 分页切换用 `snapshotFlow { pagerState.settledPage }.distinctUntilChanged()`，
  不要监听 `currentPage`（滑动过程中每帧都变）。
- 行内不要 `collectAsStateWithLifecycle` 一个大流；行只接收已经算好的数据。
- 拖拽排序统一用 `ReorderableListItem` / `ReorderableGridItem` + `rememberReorderable*State`，
  拖拽结束 dispatch `MoveServer(groupId, movedGuid, toIndex)`，重排由 DAO 事务完成
  （`to.index` 是移除被拖行**之后**的下标）。
- 定位选中项不要扫全表：位置由 SQL 的 `indexOf` 给出，再 `scrollToItem`；
  等布局就绪要有超时（`withTimeoutOrNull(LocateLayoutTimeoutMs)`，600ms）。
- **下拉框用 Paging 时**用 `FormPagedDropdownField`：它用 bare `Popup` + `LazyColumn`
  而不是 `ExposedDropdownMenu`（后者内部是 `verticalScroll`，无法嵌套同方向懒列表），
  输入去抖 250ms，并区分 refresh / append 的 `LoadState`。

## 5. 修饰符与布局

- 能用 lambda 版就用 lambda 版：`Modifier.offset { }`、`Modifier.graphicsLayer { }`、
  `Modifier.drawBehind { }` —— 它们在 layout/draw 阶段读状态，不触发重组。
- 需要"按父约束的比例限宽"时用 `Modifier.layout { }` 而不是 `BoxWithConstraints`
  （后者是 SubcomposeLayout，代价高）。范本：`SnackBar.kt` 的 `maxWidthFraction`。
- 不要为了加内边距而多包一层 `Box`；直接把 padding 加在目标组件的 `modifier` 上。
- 层级越浅越好：一个列表行不应该超过 3~4 层嵌套容器。

## 6. 避免每次重组重建对象

- 默认参数：`config: ScrollbarConfig = ScrollbarConfig.Default`，
  **不能**写成 `= ScrollbarConfig()`——那会每帧新建、新建 modifier、重启淡出动画。
- 颜色集合：`appFieldColors()` 集中构建，内部对 `selectionColors` 做 `remember(secondary)`。
- 派生集合、Regex、DateFormat：`remember(key) { … }` 或直接放 ViewModel。
- 枚举列表遍历（`XxxMenuItem.entries`）是常量，可直接用。

## 7. 副作用

| 用途 | API |
| --- | --- |
| 进入组合时执行一次/按 key 重启 | `LaunchedEffect(key)` |
| 每次成功重组后同步非 Compose 状态 | `SideEffect { }`（如把 `state.confirmRemove` 同步进 DialogHost） |
| 观察 Compose 状态变成 Flow | `snapshotFlow { … }` |
| 离开组合时清理 | `DisposableEffect` |
| 让 effect 内的回调永远是最新的 | `rememberUpdatedState(onXxx)` |

- `LaunchedEffect(Unit)` 只在"整个屏幕生命周期一次"时用（样板：`MainActivity` 的
  `awaitReady` 门）；其余必须给真实 key。
- effect 里等待布局就绪要有超时：
  ```kotlin
  withTimeoutOrNull(LocateLayoutTimeoutMs) {          // 600ms
      snapshotFlow { list.layoutInfo.viewportSize.height to list.layoutInfo.totalItemsCount }
          .first { (height, count) -> height > 0 && count > targetIndex }
  }
  ```
- 一次性"定位/滚动"类事件处理完必须回调清标志（`finally { onLocateHandled() }`），
  否则配置变化后会重放。

## 8. ViewModel / 数据层侧的性能配合

这些常量已经在 `MainViewModel` / `MainRepository` / `TestResultWriter` 里定型，
新页面沿用同一套思路：

| 常量 | 值 | 位置 | 作用 |
| --- | --- | --- | --- |
| `PAGE_SIZE` | 40 | `MainRepository` | Paging 每页条数 |
| `INITIAL_LOAD_SIZE` | 80 | `MainRepository` | 首屏一次加载 |
| `PREFETCH_DISTANCE` | 20 | `MainRepository` | 提前预取 |
| `JUMP_THRESHOLD` | 240 | `MainRepository` | 允许跳转的距离 |
| `SEARCH_DEBOUNCE_MS` | 300 | `MainViewModel` | 搜索去抖（空串走 0） |
| `MAX_CACHED_PAGERS` | 6 | `MainViewModel` | 每分组 Pager 的 LRU 上限 |
| `COUNT_SHARING_TIMEOUT_MS` | 5000 | `MainViewModel` | 计数切片停止订阅前的保留时间 |
| `FLUSH_INTERVAL_MS` | 400 | `TestResultWriter` | 测速结果合并落库窗口 |
| `QueryDebounceMillis` | 250 | `FormPagedDropdownField` | 下拉筛选去抖 |

- 过滤/排序下沉到 **SQL**（`LIKE`、`ORDER BY`、`ROW_NUMBER`），Kotlin 侧只做展示串拼装。
- 分页数据链在 `viewModelScope` 里 `cachedIn`，切组回来不重建 `PagingSource`。
- 高频写库（测速结果）在写侧合并，读侧靠 `PagingSource` 失效刷新，绝不轮询。

## 9. 度量

- 本地开重组计数：Android Studio Layout Inspector → Recomposition counts。
  滚动一屏列表，行的重组次数应接近可见行数，稳定后不再增长。
- 需要编译器报告时临时在 `app/build.gradle.kts` 加：
  ```kotlin
  composeCompiler {
      metricsDestination = layout.buildDirectory.dir("compose_metrics")
      reportsDestination = layout.buildDirectory.dir("compose_reports")
  }
  ```
  看 `*-composables.txt` 里 `restartable skippable` 与 `unstable` 参数。
  **诊断完删掉，不要提交。**
- 性能改动的 PR 描述里要写清：改前/改后在什么操作下、哪个 Composable 的重组次数变化。

## 10. 内存

- `ConcurrentHashMap` 缓存必须有清理路径：分组列表变化后
  `pagers.keys.removeAll { it !in validIds }`、`GroupScrollStates.retain(validIds)`、
  `countFlows.keys.removeAll { it !in validIds }`。
- Bitmap 用完即弃，不进 State、不进缓存（二维码只在弹窗生命周期内存在）。
- Coil 的图标加载给固定尺寸，避免解码原始大图。
