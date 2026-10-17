# Compose 规范 · 状态、Action 与一次性事件

## 1. UiState 建模

```kotlin
@Immutable
data class XxxUiState(
    val items: List<XxxItem> = emptyList(),
    val selectedId: String? = null,
    val isRunning: Boolean = false,
) : BaseUiState
```

规则：

1. `@Immutable` + `data class` + 全 `val` + 全部有默认值（便于 Preview 与初始化）。
2. 不放 `isLoading`（`BaseViewModel` 提供，引用计数）。
3. 不放一次性效果（消息、导航、弹窗触发）。
4. 不放 `Context`、`Bitmap` 之外的平台对象；`Bitmap` 只允许经 `BaseEvent.Platform` 传递
   （`MainEvent.ShowQrCode`），不入 State。
5. 派生值优先在 UI 现算（`state.items.isEmpty()`），只有计算昂贵时才存进 State
   （样板：`MainUiState.isFiltering`）。
6. **大列表不进 State**：主列表是 Paging 3 的 `Flow<PagingData<T>>`（见
   `architecture-rules.md` §3.3 与 `performance.md` §4）。计数、行数据都用切片
   `StateFlow` / `Flow<PagingData>`，不塞进 `XxxUiState`。
7. 多态状态用密封接口而不是一堆布尔：
   ```kotlin
   @Immutable
   sealed interface MainStatus {
       data object Disconnected : MainStatus
       data object Connected : MainStatus
       data object Testing : MainStatus
       data class TestProgress(val progress: String) : MainStatus
       data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
   }
   ```
   状态到文案的映射写成 UI 侧的 `@Composable fun MainStatus.asText(): String`，
   ViewModel 不碰 `stringResource`。

## 2. Action 建模

```kotlin
sealed interface XxxAction : BaseAction {
    data object Initialize : XxxAction
    data class SelectItem(val id: String) : XxxAction
    data class ResultReceived(val result: BaseResult) : XxxAction
}
```

- 名字表达用户意图，不表达实现。
- 参数只带 UI 能提供的原始信息（id、index、文本、Uri）或不可变的表单提交快照，
  不携带 `TextFieldState`、可变实体或 ViewModel。
- `onAction` 的 `when` 必须穷尽（不写 `else`），加新 Action 时编译器会提醒你补分支。
- 需要在 `onCreate` 触发的一次性初始化，用显式 `Initialize` Action，
  ViewModel 内用 `initialized` 布尔守卫幂等（`MainViewModel.initialize()`）。

## 3. Event 建模

```kotlin
sealed interface XxxEvent : BaseEvent.Platform {
    data object PickFile : XxxEvent
    data class ShowQrCode(val bitmap: Bitmap) : XxxEvent
}
```

四条路径：

| 事件 | 谁消费 |
| --- | --- |
| `BaseEvent.Message` | `BaseEventEffect` → `LocalAppSnackbar` |
| `BaseEvent.Navigate(route)` | `BaseScreen`：`AppRoute.OpenUrl` 走 `Utils.openUri`，其余走 `BaseResultContract` |
| `BaseEvent.Finish(result)` | `BaseEventEffect` → `Activity.finishWithResult` |
| `BaseEvent.Platform` 子类型 | 先由 `BaseScreen(onEvent = …)` 里的屏内处理器截获（弹窗、滚动定位），未截获的再交给 `XxxActivity.handlePlatformEvent` |

`onEvent` 返回 `true` = 已消费。样板：

```kotlin
onEvent = { event ->
    when (event) {
        is MainEvent.ShowQrCode -> { dialogs.show(MainDialog.QrCode(event.bitmap)); true }
        is MainEvent.LocateProfile -> { locateTarget = event.target; true }
        is MainEvent -> onPlatformEvent(event)   // 交给 Activity
        else -> false                            // 交给 BaseScreen 默认处理
    }
}
```

## 4. 文案：BaseText

ViewModel 里禁止出现 `Context` 和 `stringResource`。用 `BaseText`：

```kotlin
toast(R.string.title_del_config_count, count)         // 简写
toast(BaseText.of(R.string.msg_x, BaseText.of(R.string.title_y)))  // 可嵌套
toastSuccess() / toastError() / toastInfo(resId, …)
```

UI 侧由 `BaseText.asString(context)` 解析。数据层产生的原始错误串用 `BaseText.Raw`。

## 5. 弹窗

弹窗是 UI 局部状态，**不进 UiState**。每屏定义：

```kotlin
sealed interface XxxDialog {
    data object DeleteAll : XxxDialog
    data class DeleteOne(val id: String) : XxxDialog
    data class QrCode(val bitmap: Bitmap) : XxxDialog
}
```

宿主用 `@Stable class`（`MainDialogHost`）持有 `var current by mutableStateOf<XxxDialog?>(null)`，
对外暴露稳定的 `show` / `dismiss` / `requestRemove` lambda 引用——
这样传给子组件的回调引用恒定，不会因为宿主重组而使子树失效。

需要读 UiState 才能决定行为的开关（如"删除前是否确认"），
用 `SideEffect { dialogs.confirmRemove = state.confirmRemove }` 同步进宿主，
而不是把宿主变成 Composable 参数。

渲染集中在一个 `XxxDialogs(dialog, onDismiss, onAction, …)` 里，`when` 穷尽分发。

## 6. 表单与编辑页

- 编辑中的文本由 UI-owned `TextFieldState` 唯一持有。共享表单、下拉、搜索和 `InputField`
  直接接收 state，不提供逐字符 String 回调；flags 由同一个 feature 草稿持有者管理。
- Main、AppPicker、PerAppProxy、Logcat 共用 `SearchFieldController` 管理搜索 state 的保存、
  open/close/back 生命周期；关闭先清空本地 state，再派发屏幕 Action。查询只单向观察 state，
  不把 ViewModel 的已应用 query 回写到输入框。
- UiState 只发布一次性的 `initialForm`、ready 状态、options、业务元数据及校验结果。
  草稿在 ready 后初始化一次，恢复草稿优先，加载前不可保存或编辑默认空表单。
- 保存、确认和需要当前输入的动作直接在点击处理器中读快照，例如
  `onAction(SubEditAction.Save(editor.snapshot()))`。ViewModel 使用
  `save { saveSnapshot(action.form) }` 捕获本次不可变数据，不存可被第二次点击覆盖的 pendingForm，
  不再读取 UiState 的旧初值作为提交内容。Back、取消、删除不提交草稿。
- 不使用 String 双向同步桥、回声队列、registry/flush 或 raw 文本回写 debounce。
  `snapshotFlow` 只用于搜索/筛选等单向派生，查询结果不能回灌输入框。选中下拉项直接编辑 state，
  同步抑制选项引发的查询；已应用 query 属于可恢复的筛选元数据，不是第二份输入草稿。
- 外部结果按明确目标更新字段：证书响应只改 pinnedCA，AppPicker 返回只改 process。
  不通过整个旧表单覆盖请求期间的其它编辑。
- `InputDialog` 只渲染 caller-owned 字段并同步读确认快照；拥有者用
  foundation `TextFieldState.Saver` 保存文本及定向选区。密码使用 `OutlinedSecureTextField`。
- 校验在 ViewModel。失败时 `toastError` 并保持在当前页
  （`save { ... }` 返回 `null`）；错误携带提交值，UI 只对仍匹配该值的当前字段展示错误。
- 保存/删除用 `BaseEditViewModel.save { ... }` / `delete()`：内部有 `mutationJob` 防重入，
  成功即 `finishWith(result)`。
- 需要跨进程死亡保活的草稿使用 `rememberSaveable` + feature Saver，保存初始化状态、flags 和
  动态字段的身份/顺序；未初始化的恢复值不能阻止真实加载。`EditFormSaver.consume()` 仅一次性兼容并移除旧数据。
- 快速连续写入用 Job 链串行（`SettingsViewModel.persist()`），离开页面前 `writeJob?.join()`。

## 7. 结果回传

```kotlin
sealed interface BaseResult { Cancelled / Saved / Deleted / Changed / Selected }
```

- 子页：`finishWithResult(BaseResult.Saved(...))`。
- 父页：`BaseScreen(onResult = { viewModel.onAction(XxxAction.ResultReceived(it)) })`。
- ViewModel 在 `handleResult` 里按 `result.refreshList` / `result.restartService` 决定后续动作。
- `autoToastResult = true`（默认）时框架已经替你弹成功提示，别重复弹。
- `BaseResult.Selected` 用于"选择器"语义（如 `AppPicker`），`refreshList` / `notify` 都为 false。
