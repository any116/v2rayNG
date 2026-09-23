# Compose 规范 · 导航与 Preview

## 1. 导航模型

本项目**不使用 Navigation-Compose**：一屏一 Activity，导航即 Intent。

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

1. 新增目标 → 在 `AppRoute` 加成员 + 实现 `intent(context)` + 在
   `AppRoute.Companion` 加 `EXTRA_*` 常量 + manifest 注册 Activity。
   **不要**在 Activity 里手写 `Intent(this, XxxActivity::class.java)`。
2. ViewModel 侧只调 `navigate(AppRoute.Xxx(...))`，不接触 `Intent`。
3. 参数只传可序列化的标量（String / Int / Boolean / 枚举值 / 字符串数组），不传对象；
   接收方用 id 从数据层重新取。
4. 外部链接一律 `AppRoute.OpenUrl(url)`，`BaseScreen` 会走 `Utils.openUri`。
5. 返回值走 `BaseResult`（见 `state-events.md` §7），
   `BaseScreen` 内部用 `rememberLauncherForActivityResult(BaseResultContract())` 启动。
6. 目标 Activity 读参数在 Activity 或 `SavedStateHandle` 里，
   转成 ViewModel 的构造参数或初始 State，Composable 不读 Intent。

## 2. 返回键

- 普通屏：系统默认返回即可；需要在返回前保存/确认的，用 `BackHandler`
  把返回翻成 Action（如 `ScannerScreen`、`FormPagedDropdownField` 的弹出层），
  ViewModel 决定 `finishWith` 什么结果。
- `MainActivity` 覆写 `onKeyDown` 做 `moveTaskToBack(false)`，这是主屏特例，不要复制到别处。

## 3. Preview 规范

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
