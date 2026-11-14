# Compose 规范 · 导航与 Preview

## 1. 导航模型

应用内页面使用**单 Activity + Navigation Compose + 类型安全路由**。`AppNavigation.kt` 是唯一的
页面图与转场入口；`AppRoute` / `BaseRoute` 只描述目标与参数，Android 入口由 `ExternalEntry` 构建。
普通页面不再保留 Activity 包装层。

```kotlin
sealed interface AppRoute : BaseRoute {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data class ServerEdit(
        val configType: Int = EConfigType.VMESS.value,
        val guid: String = "",
        val subscriptionId: String = "",
        val isRunning: Boolean = false
    ) : AppRoute

    data class OpenUrl(val url: String) : AppRoute
}
```

当前目标集合：`Main`、`SubSetting`、`PerAppProxy`、`RoutingSetting`、`UserAsset`、
`Settings`、`Logcat`、`CheckUpdate`、`Backup`、`About`、`Scanner`、`ServerEdit`、
`RoutingEdit`、`SubEdit`、`UserAssetUrl`、`AppPicker`、`OpenUrl`。

规则：

1. 新增图内目标：在 `AppRoute` 加 `@Serializable` 成员，在图中注册 `composable<AppRoute.Xxx>`
   并补齐 `navigate` 分支；不要新增 Activity、Intent、字符串模板或 `navArgument`。
2. ViewModel 只调用 `navigate(AppRoute.Xxx(...))`，参数由 `SavedStateHandle.toRoute()` 读取。
   属性名就是参数名；`AppRoute.EXTRA_*` 只保留为测试种子键，不再用于兼容 Intent。
3. 传 String / Int / Boolean / 字符串集合，不传完整实体；协议类型使用 `EConfigType.value`。
   `AppPicker.selected` 由 Navigation 的 `StringListType` 编解码，不手工 join/split。
4. `AppRoute.OpenUrl` 由 `BaseScreen` 走 `Utils.openUri`，不进入页面栈。
5. 图内导航与 Finish 必须由 `LocalAppNavigator` 处理；处理失败不得启动 Activity。
   仅没有 navigator 的外部宿主可通过 `ExternalEntry.fromRoute(AppRoute.Main)` 交还 Main；
   其它目标返回 null，由宿主记录日志并忽略。图内失败同样记录日志并忽略，不抛异常或关闭 Activity。
   事件消费层返回 true 表示请求已消费（含记录日志后忽略），不是导航执行成功。
6. 返回值由 `savedStateHandle` 传递，父页在 RESUMED 后消费一次；没有 `BaseResultContract`。
   系统入口的 Activity 结果（如 Tasker）保留自身协议，不把页面导航改为 ActivityResult。
   pop 失败时恢复父 entry 的旧结果值，不调用 `SavedStateHandle.remove`，避免断开已订阅的 StateFlow。
7. 扫码是普通 `AppRoute.Scanner`，用 `BaseResult.Selected` 回传；父页在 `ResultReceived` 中处理。
   `ScScannerActivity` 只把扫描请求转交 Main，权限和选图仍属 `PlatformActions`。
8. 启动失败时 Main 内部以 Backup 为根挂载恢复图，关闭根恢复页后重试启动，不新起 Activity。
   MAIN 根退出退到后台；其他返回仍遵守页面的保存/确认逻辑。恢复图不消费外部入口，
   恢复期间收到的扫描请求保留到重试成功、主图挂载后再消费；Backup 的 `AppBackHandler` 将系统返回派发为 Back Action。
9. Main 统一使用 `stateUnchanged|adjustResize`，编辑页保留 `.imePadding()`，不要叠加第二份 IME 避让。

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
