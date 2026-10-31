# Compose 规范 · 导航与 Preview

## 1. 导航模型

应用内页面使用**单 Activity + Navigation Compose**；`ui/AppNavigation.kt` 是唯一的应用内
导航图与转场入口。`ui/AppRoute.kt` 仍集中定义目标与参数，并保留兼容 Intent，但应用内正常
导航优先由 `LocalAppNavigator` 交给 Compose 图处理。

`ui/AppRoute.kt` 里每个目标自己构造 Intent：

```kotlin
sealed interface AppRoute : BaseRoute {
    data object Settings : AppRoute {
        override fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
    data class ServerEdit(
        val configType: EConfigType,
        val guid: String = "",
        val subscriptionId: String = "",
        val isRunning: Boolean = false,
    ) : AppRoute
    data class OpenUrl(val url: String) : AppRoute   // intent() 返回 null，由宿主处理
    companion object { const val EXTRA_GUID = "guid" /* … */ }
}
```

当前目标集合：`Main`、`SubSetting`、`PerAppProxy`、`RoutingSetting`、`UserAsset`、
`Settings`、`Logcat`、`CheckUpdate`、`Backup`、`About`、`Scanner`、`ServerEdit`、
`RoutingEdit`、`SubEdit`、`UserAssetUrl`、`AppPicker`、`OpenUrl`。

规则：

1. 新增应用内目标 → 在 `AppRoute` 加成员 + 在 `AppNavigation.kt` 注册 destination + 在
   `AppRoute.Companion` 加 `EXTRA_*` 常量。只有外部入口才需要 manifest 注册 Activity。
   **不要**在 Activity 里手写 `Intent(this, XxxActivity::class.java)`。
2. ViewModel 侧只调 `navigate(AppRoute.Xxx(...))`，不接触 `Intent`。
3. 参数只传可序列化的标量（String / Int / Boolean / 枚举值 / 字符串数组），不传对象；
   接收方用 id 从数据层重新取。
4. 外部链接一律 `AppRoute.OpenUrl(url)`，`BaseScreen` 会走 `Utils.openUri`。
5. 返回值走 `BaseResult`（见 `state-events.md` §7）。Compose destination 通过
   `NavBackStackEntry.savedStateHandle` 回传；兼容 Activity 包装层才使用
   `rememberLauncherForActivityResult(BaseResultContract())`。
6. Compose destination 从 `NavBackStackEntry` / `SavedStateHandle` 读参数；兼容 Activity
   入口在 Activity 或 `SavedStateHandle` 里读参数，转成 ViewModel 的构造参数或初始 State，
   Composable 不读 Intent。

## 2. Compose 转场

`AppNavigation.kt` 统一提供前进与返回转场：

- 前进：当前页向左退出，目标页从右侧进入，并淡出/淡入；
- 返回：当前页向右退出，上一页从左侧进入，并淡出/淡入；
- 转场只由 `NavHost` 管理，禁止再给 Compose 内容套 Activity 级 `res/anim`。

需要 app-owned 返回逻辑（搜索、表单结果、页面内子状态）的页面才使用
`AppBackHandler`；普通 destination 交给 Navigation Compose 的 back dispatcher。
主屏侧栏是局部手势特例：`MainDrawerBackHandler` 直接消费预测返回进度，以驱动同一个
`MainPushDrawerState`，只在侧栏可见且 MAIN destination 为 RESUMED 时启用。
提交关闭、取消回到原目标，结束后立即清理 `LocalPredictiveBackProgress` 的降载标志；
关闭或被子页覆盖时不拦截返回。
菜单导航立即走原来的 `MainAction.Navigate`，不先收起侧栏、不等待动画；展开目标继续由
UI Saver 保存，返回主页后仍显示已展开侧栏。应用内导航请求防重复派发，MAIN 的 ON_RESUME
解除该保护；外部链接等不能保证触发页面切换的操作不进入此等待。
保留在后台的 MAIN 不移动焦点、不接受侧栏手势，不与新 destination 抢输入或返回。

## 3. 返回键

- 普通屏：Navigation Compose 默认返回即可；需要在返回前保存/确认的，用 `BackHandler`
  把返回翻成 Action（如 `ScannerScreen`、`FormPagedDropdownField` 的弹出层），
  ViewModel 决定 `finishWith` 什么结果。
- `MainActivity` 通过 `OnBackPressedDispatcher` 将系统返回退到后台；仅保留
  `onKeyDown` 对 `KEYCODE_BUTTON_B` 的手柄兼容，这是主屏特例，不要复制到别处。

### 3.1 预测性返回进度与降载

`PredictiveBackLayout` 通过 `LocalPredictiveBackProgress` 暴露只读的预测性返回状态：

- `progress` 只由页面自己的 `PredictiveBackHandler` 提供，是每帧高频值，只能在 draw/layout lambda
  中读取，不能让 MAIN 根节点订阅；
- `isInProgress` 是低频的开始/取消/提交信号，用于在 MAIN 的顶栏、底栏和波形/FAB 绘制期间降载；
- Android 16+ 使用 `PRIORITY_SYSTEM_NAVIGATION_OBSERVER` 观察根 Activity 的**提交**，observer 不消费返回，
  因而保留系统 home preview；该 observer 不提供 `onBackStarted/onBackProgressed`；
- Android 14/15 没有公开的等价非消费进度观察 API，不能在根页面启用一个空的
  `PredictiveBackHandler` 代替，否则会抢占系统返回；这些版本在 Activity `ON_PAUSE` 时只做提交后的降载；
- 子页返回只有在真正开始 destination pop 时保持抑制，MAIN destination 在完成返回转场并重新
  `ON_RESUME` 后清理；局部返回（搜索/子页面）在 action 完成后立即清理，Activity 重新 `ON_RESUME` 也会清理。

## 4. Preview 规范

**每个可复用组件与每个 Content 层 Composable 至少一个 Preview。**

```kotlin
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun XxxContentPreview() = AppTheme {
    XxxContent(
        state = XxxUiState(items = listOf(/* 2~3 条代表性假数据 */)),
        onAction = {},
    )
}
```

规则：

1. Preview 函数 `private`，命名 `XxxPreview`，无参数。
2. **必须包 `AppTheme { }`**，否则 `LocalAppColors` / `LocalAppSnackbar` 未提供会崩。
3. 深浅色成对提供（`uiMode = UI_MODE_NIGHT_YES`）。
4. Preview 只喂 `UiState` 与空回调，绝不构造 ViewModel、Repository、Context 依赖。
5. Paging 组件的 Preview 用 `rememberPreviewDropdownItems(values)` 之类的
   `PagingData.from(...)` + `collectAsLazyPagingItems()` 造数据，
   不要连真库（样板：`FormPagedDropdownField` 的 preview）。
6. 假数据要覆盖边界：空列表、超长文本、极值（现有样例用
   `versionName = "2.3.6"`、`coreVersion` 之类）。
7. 需要 `PlatformActions` 的组件在 Preview 中会自动落到 `NoPlatformActions`，无需额外处理。
8. Preview 依赖 `debugImplementation(libs.androidx.compose.ui.tooling)`，
   `@Preview` 注解来自 `ui-tooling-preview`（已在 `implementation` 中），
   不要把 tooling 提到 release 依赖。
9. Preview 不得访问真实存储、数据库、网络；不确定时用假数据而不是真 Repository。
