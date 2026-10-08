# Compose 规范 · 主题、尺寸与资源

## 1. 颜色

只有两个来源：

1. `MaterialTheme.colorScheme.*` —— M3 语义色，`ui/components/Theme.kt` 里已定义完整的
   `LightColor` / `DarkColor`（含 `surfaceContainer*` 全系列）。
2. `LocalAppColors.current` —— `AppSemanticColors`（`pingBad`、`fabInactive`、`fabContent`、
   `divider`、`toastBackground/Success/Error/Info/Content`、`onCameraPreview`），
   随深浅色自动切换（`LightSemanticColors` / `DarkSemanticColors`）。

规则：

- **Composable 里禁止出现 `Color(0xFF…)` 字面量。** 需要新语义色时加进 `AppSemanticColors`
  并同时补 Light / Dark 两份。
- `Theme.kt` 顶层的 `colorPing` / `colorPingRed` / `colorConfigType` / `colorFabActive` /
  `toastIconCircleBg` / `toastTextColor` 是不随主题变化的品牌色/覆盖层色，
  只在既有位置沿用，不要扩充这一组。
- 需要知道当前是否深色时用 `LocalDarkTheme.current`，不要重复调 `isSystemInDarkTheme()`
  （那会忽略用户在设置里选的 Light/Dark 覆盖）。
- 主题切换：`ThemeManager.setMode(AppThemeMode)`（内部转发 `ThemeStore.setThemeModeAsync`），
  它更新 `ThemeRepository` 的 `MutableStateFlow` 并落库；
  `resolveDarkTheme()` 负责三态解析（Light / Dark / System）。不要自己读 pref 判断深浅色。
- 动态取色由 `ThemeManager.dynamicColorEnabled` 控制，仅 Android 12+ 生效；
  设置页开关走 `SettingsRepository.setBool(BoolPref.DYNAMIC_COLOR, …)`（内部转 `ThemeRepository`）。

## 2. 尺寸：不建立全局 dimens

**本项目不设 `Dimens` / `Spacing` / `AppTheme.dimens` 这类全局尺寸令牌对象。**
理由：v2rayNG 只有一套手机端布局，没有多端/多密度主题化需求，全局令牌只会让每个尺寸
多一次跳转、并诱导出"为了统一而统一"的错误复用。

正确做法——**文件内私有常量**，命名表达用途。现有样例：

```kotlin
// ui/components/Common.kt
private val ItemHorizontalPad = 16.dp
private val ItemVerticalPad = 12.dp
private val AppIconSize = 40.dp
private val DividerInset = 12.dp
private val DragElevation = 4.dp

// ui/components/SettingsItem.kt
private val ItemPad = 16.dp
private const val DisabledAlpha = 0.38f
private const val SwitchScale = 0.8f

// ui/components/FormFields.kt
internal val FieldHorizontalPad = 16.dp
internal val FieldVerticalPad = 4.dp
```

- 只在本文件用 → `private val` 放文件顶部。
- 同 feature 多文件用 → `internal val` 放该 feature 的一个文件里。
- 跨 feature 用 → 才放 `ui/components/` 对应组件文件里（`Common.kt` 的
  `AppIconSize`、`ItemHorizontalPad`；`FormFields.kt` 的 `FieldHorizontalPad`）。
- 数值不得内联在布局代码中间（`padding(16.dp)` 只在一次性、无语义的场合允许）。
- 触控目标最小 48.dp，见 `accessibility.md`。

## 3. 字体

只用 `MaterialTheme.typography.*`。需要变体时 `.copy(fontWeight = …)`，
不要自定义 `TextStyle` 常量池。等宽场景（日志、JSON 编辑）用
`fontFamily = FontFamily.Monospace`。

## 4. 字符串

- 全部经 `stringResource(R.string.xxx)`；带参数用 `stringResource(id, arg)`。
- 复数用 `pluralStringResource`。
- ViewModel 侧用 `BaseText`（见 `state-events.md` §4）。
- 字符串数组用 `rememberStringOptions(@ArrayRes id)`（`FormFields.kt`，内部基于
  `LocalResources`，语言切换会自动失效重取），不要自己
  `LocalContext.current.resources.getStringArray`。
- 新增文案只改 `res/values/strings.xml`；其他语言由翻译流程补，不要手填机翻。

## 5. 图标与图片

- 矢量图标放 `res/drawable/ic_*_24dp.xml`，用
  `painterResource(R.drawable.ic_xxx_24dp)`。
- **不要引入 `material-icons-extended`**（体积大且与现有图标风格不一致）。
- 应用图标（分应用代理）用 Coil 3（`io.coil-kt.coil3:coil-compose`）+
  `util/AppIconFetcher.kt`，必须给固定尺寸（`AppIconSize = 40.dp`）与 `placeholder`，
  避免列表滚动时布局跳动。
- Bitmap（二维码）只在弹窗里展示，用完即随弹窗销毁，不缓存进 State。

## 6. 形状与高度

- 圆角用 `MaterialTheme.shapes.*`，特例才 `RoundedCornerShape(x.dp)`
  （如 `ToastCornerRadius = 24.dp`）。
- 阴影克制：列表项拖拽时 `DragElevation = 4.dp`，下拉弹层 `PagedDropdownElevation = 3.dp`，
  Snackbar `shadowElevation = 0.dp`。
- 深色下不要用 `elevation` 表达层级，用 `surfaceContainer*` 色阶。

## 7. 动画

- 只用于状态转换的即时反馈（拖拽抬起、展开折叠、Toast 淡入淡出）。
- 用 `animateFloatAsState` / `animateDpAsState` / `AnimatedVisibility`，
  时长走 Material 默认，不要自定 spec 除非有明确理由。
- 列表项**不要**加 `animateItem()` 之外的入场动画；大列表逐项动画会显著掉帧，
  与 Paging 的占位行也不兼容。
- 拖拽反馈用 `HapticFeedback`（`Modifier.dragHandle()` 已封装）。

## 8. Edge-to-edge 与 insets

- `BaseActivity` 已 `enableEdgeToEdge()`；状态栏/导航栏图标颜色由 `AppTheme` 里的
  `WindowCompat.getInsetsController` 跟随深浅色。
- `BaseScreen` 的 `Scaffold` 使用 `contentWindowInsets = WindowInsets(0)`（inset0），
  即**不自动吸收系统栏 inset**，`innerPadding` 只反映 topBar / bottomBar。
- 需要自己处理时用 `Modifier.windowInsetsPadding(WindowInsets.navigationBars)`
  （`AppSnackbarHost` 的做法），不要用固定 dp 猜导航栏高度。
- 因此每一屏都必须自行处理底部导航栏 inset：
  - 有底栏/悬浮内容的屏：底栏自身调用 `navigationBarsPadding()` /
    `windowInsetsPadding(WindowInsets.navigationBars)`（样板：`MainBottomBar`、
    `AppSnackbarHost`），此时滚动内容仍需额外留出与底栏的间距；
  - 无底栏的屏：用 `NavigationBarsBottomPadding()` 取 `contentPadding`，
    或在列表末尾放 `NavigationBarsSpacer()`（样板：`AboutLicenseContent`）。
- 禁止用固定 dp（如 `padding(bottom = 90.dp)`）猜测导航栏高度；
  底部"舒适区"留白只能用于避让自有悬浮内容，不能当作导航栏 inset。
- `MainScreen` 的底栏叠放在内容 `Box` 底部，不能放进 Scaffold 的 `bottomBar` 槽位：
  后者会缩短列表视口，底栏后面就没有可模糊的内容。列表 bottom padding 使用底栏实测总高度
  （含外边距与 navigation bars inset）加 `ListBottomGap`，避免大字体改变底栏高度后遮住末行。
- 主屏悬浮底栏左右外边距为 8.dp。状态文字的 start padding 放在 clickable 内部，
  让点击区域延伸到外侧圆弧，再由底栏 Surface 统一裁剪。状态区禁用 indication，FAB 局部
  使用 `LocalRippleConfiguration provides null` 禁用内部波纹；两者均用细边框保留键盘焦点反馈。
- `MainWaveShadow` 仅在底栏状态区的实际 `onClick` 后显示波动阴影，Surface / FAB 的 shadow
  elevation 均为 0.dp。阴影使用主题 `scrim` 色与软边径向渐变，从点击位置向四周扩散，
  在 640ms 内淡出。动画结束后保持隐藏，空闲时没有纹理或动画循环。
  修饰符放在状态区 Box 上，位于内容 padding 之前；绘制顺序是毛玻璃背景、波动阴影、状态文字。
  `clipRect` 将阴影限制在状态区自己的边界内，Surface 进一步裁剪面板外侧圆角；
  阴影不进入状态区与 FAB 的间隙或 FAB 区域，FAB 点击不触发阴影波动。
  坐标在 pointer input 的 Initial 阶段记录、Final 阶段清理，不消费触摸事件；标准 clickable
  仍负责点击与无障碍语义。键盘/无障碍点击使用状态区中心，取消按压或禁用状态区不会触发。
  阴影渐变内外边缘均渐隐，每帧一次圆形绘制，不需要粒子、轨迹列表或全局边界转换。
- 毛玻璃只模糊 `MainBackdrop` 捕获的列表绘制命令，不模糊底栏的文字/按钮；裁剪区四周
  为模糊采样留出余量。Android 12 以下或软件渲染用较高不透明度的主题表面色回退。
  底栏玻璃着色与回退背景使用 `colorScheme.primaryContainer`，贴近当前主题主色；状态文字使用
  配对的 `onPrimaryContainer`。深浅色模式的玻璃着色不透明度统一为 70%，回退背景为 95%。
  颜色由当前深浅色/动态主题提供。

绘制边界参考：

- [AndroidX GraphicsLayer KDoc](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/layer/GraphicsLayer.kt)：
  默认允许越界，但离屏合成的缓冲区按图层尺寸分配；`clip = false` 不能避免离屏边界裁剪。
- [Material3 Surface 源码](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Surface.kt)：
  Surface 负责自身形状的裁剪，面板内的阴影与内容共用圆角裁剪区域。
