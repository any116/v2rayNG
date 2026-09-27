# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

v2rayNG —— Android 上的 Xray / v2fly 客户端。核心是 Go 编译出的
`libv2ray` AAR，App 负责配置生成、订阅管理、路由规则、分应用代理、VPN / 仅代理两种运行模式。

架构统一为 **MVVM + SSOT + UDF**。仓库中不存在 Fragment、RecyclerView.Adapter、
ViewBinding、业务 XML 布局与业务 `AndroidView`；唯一的非 Compose UI 是 Glance 桌面组件
（`ui/widght/SwitchWidget.kt`）与其 `appwidget-provider` XML。

## Build Commands

Gradle 工程在 `V2rayNG/` 子目录，JDK 21，Gradle Wrapper，Kotlin DSL。

```bash
cd V2rayNG

./gradlew assembleFdroidRelease        # fdroid 渠道（applicationId 带 .fdroid 后缀）
./gradlew assemblePlaystoreRelease     # playstore 渠道
./gradlew assembleFdroidDebug
./gradlew assembleRelease              # CI 用的两渠道全量打包

./gradlew installFdroidDebug

./gradlew test                         # 单元测试（app/src/test/java）
./gradlew :app:compileFdroidReleaseKotlin   # 只做语法/类型检查，最快的验证手段
./gradlew lintFdroidRelease            # 本地 lint（CI 未启用）

./gradlew --stop
./gradlew assembleFdroidDebug --warning-mode all
```

原生依赖不在 Gradle 里构建，由仓库根目录脚本产出后拷进 `V2rayNG/app/libs/`：

```bash
bash compile-hevtun.sh   # 构建 hev-socks5-tunnel（需要 NDK_HOME）→ libs/
```

## SDK / 版本

- `compileSdk 37`、`targetSdk 37`、`minSdk 24`、JVM 17（`coreLibraryDesugaring` 已开启）
- Kotlin 2.4.20、AGP 9.4.1、KSP 2.3.11
- `versionCode 746` / `versionName 2.3.6`（`app/build.gradle.kts`）
- 渠道维度 `distribution`：`fdroid`（`applicationIdSuffix .fdroid`）与 `playstore`
- `generateLocaleConfig = true`，`localeFilters` = en / zh-rCN / zh-rTW / vi / ru / fa / ar / bn / bqi-rIR
- release 当前 `isMinifyEnabled = false`；改动 keep 规则前先确认这一点
- Room schema 导出到 `app/schemas/`（`room3 { schemaDirectory(...) }`），**随版本管理提交**

## Dependency Management

全部版本收敛在 `V2rayNG/gradle/libs.versions.toml`，`build.gradle.kts` 一律用 `libs.xxx`，
禁止硬编码版本号。关键版本：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Compose BOM | `2026.09.00` | foundation / ui / tooling 都由 BOM 定版 |
| Material3 | `1.5.0-alpha28` | **显式覆盖 BOM**，因为用到 `nonInteractiveScrollbar` 等 alpha API |
| Room 3 | `3.0.3` | `androidx.room3:room3-runtime/compiler/paging/testing`，KSP only |
| SQLite driver | `sqlite-bundled` `2.6.2` | `BundledSQLiteDriver`；单测用 `sqlite-bundled-jvm` |
| Paging | `3.5.1` | runtime / compose / common / testing |
| Hilt | `2.60.1` | `hilt-android` + `hilt-android-compiler`(KSP) |
| androidx.hilt | `1.4.0` | `hilt-work` + `hilt-compiler`，@HiltWorker 用 |
| MMKV | 1.3.16 | **仅只读**：`data/legacy/MmkvLegacyReader.kt` 一次性导入旧数据，禁止新增写入 |
| Glance | `1.3.0-alpha02` | 桌面组件（`:bg` 进程） |
| AboutLibraries | `15.2.0` | 许可页；Gradle 插件 `offlineMode = true` 生成 `res/raw/aboutlibraries.json` |
| OkHttp | `5.5.0` | 订阅拉取、测速、更新检查 |
| Coroutines | `1.11.0` | |
| WorkManager | `2.11.2` | 含 `work-multiprocess` |
| CameraX | `1.6.2` | 扫码，配合 zxing core 3.5.4 |
| Coil | `3.6.3` | `io.coil-kt.coil3:coil-compose`，只用于分应用代理的应用图标 |
| reorderable | `3.1.0` | `sh.calvin.reorderable`，列表拖拽排序 |
| Lifecycle | `2.11.0` | 含 `lifecycle-runtime-compose` |

**DI 框架：Hilt**（`com.google.dagger.hilt.android`，KSP 处理器）。
Application 带 `@HiltAndroidApp`，Activity / Service 带 `@AndroidEntryPoint`，ViewModel 带
`@HiltViewModel` + `@Inject constructor`，Activity 侧用标准 `by viewModels<XxxViewModel>()`。
Worker 用 `@HiltWorker` + `@AssistedInject`，`HiltWorkerFactory` 挂在
`AngApplication` 手动构建的 WorkManager `Configuration` 上。

`BaseViewModelFactory` / `baseViewModels {}` 已从生产代码移除（`BaseViewModelFactory.kt`
仅作为死代码保留），不要新增自定义 Factory，也不要手工 new ViewModel。
`@Inject constructor` 的参数**不得**全部带默认值，否则 Kotlin 合成的无参构造函数会
让 Dagger 报 `may only contain one injected constructor`。细则见
`docs/project-rules/hilt-rules.md`。

**唯一持久层：Room 3**。禁止新增 SharedPreferences / DataStore / Room 2 / MMKV 写入。
细则见 `docs/project-rules/repository-rules.md`。

## Architecture

MVVM + SSOT（单一数据源）+ UDF（单向数据流）。数据流永远是：

```
UI(Composable) --Action--> ViewModel --suspend--> data/repository --withIO--> data/ (Room 3) / handler/
UI(Composable) <--StateFlow<UiState>------- ViewModel
UI(Composable) <--Flow<BaseEvent>---------- ViewModel   (一次性效果，不入 State)
UI(Composable) <--Flow<PagingData<T>>------ ViewModel   (大列表，不塞进 UiState)
```

### Source Layout（`V2rayNG/app/src/main/java/com/v2ray/ang/`）

```
AngApplication.kt      # @HiltAndroidApp；WorkManager(:bg)、SettingsStore、存储启动屏障
                       # （完整性检查 → 旧数据导入 → 快照刷新 → 播种，全部成功才放行）
AppConfig.kt           # 所有常量：pref key、广播 key、MSG_*、端口、URL

core/                  # 与 Xray 内核交互（跨进程，无 UI 依赖）
  CoreServiceManager / CoreConfigManager / CoreNativeManager / CoreStartup
  CoreOutboundBuilder / CoreConfigContextBuilder / LauncherManager
data/                  # 唯一持久层（Room 3，数据库 v2rayng.db）
  AppDatabase / AppDao（Profile/Subscription/Asset/Routing/Settings 五个 DAO + 投影）
  SettingsStore（进程内同步快照）/ Prefs（给无注入点的 object 的同步门面）
  SettingsDefaults / LegacyImporter / LegacyMigrationGate / DatabaseIntegrity / StorageBootstrap
  DatabaseCallbacks / DedupeKey
  entities/            # @Entity：ProfileItem / ProfileRaw / ServerAffiliationInfo /
                       # SubscriptionItem / AssetUrlItem / RulesetItem / SettingsEntry
  legacy/              # MmkvLegacyReader（唯一允许 import com.tencent.mmkv 的文件，只读）/ SettingKinds
  repository/          # UI 进程的数据层门面，ViewModel 唯一可依赖的数据入口
                       # BaseRepository + Main/Server/Sub/Settings/Routing/PerAppProxy/
                       # UserAsset/Backup/Logcat/Scanner/Shortcut/UrlScheme/AppList/
                       # CheckUpdate/About/Theme
di/                    # Hilt 接缝：Qualifiers / DispatcherModule / NetworkModule /
                       # DatabaseModule / ThemeModule / ServiceEntryPoint
service/               # 前台服务（跑在 :daemon / :tasks）
  CoreVpnService / CoreProxyOnlyService / CoreRootService / CoreTestService
  SubscriptionUpdateService / RealPingWorkerService / TestResultWriter / QSTileService
  TProxyService / DialerNativeService / DialerWebviewService / NetworkMonitor / ProcessService
handler/               # 进程无关的数据源与领域逻辑（见 architecture-rules.md）
  SettingsManager / AngConfigManager / SubscriptionUpdater / SubscriptionUpdateWorker(@HiltWorker)
  NotificationManager / WebDavManager / UpdateCheckerManager / SpeedtestManager
  CertificateFingerprintManager / SettingsChangeManager / WidgetStateManager / AppLocaleManager
dto/                   # 跨层不可变数据类（ServerRowItem / GroupMapItem / ConnectionTestResult …）
enums/                 # EConfigType / Language / RoutingType / PermissionType / WidgetRunState …
fmt/                   # 各协议分享链接的解析与生成
root/                  # RootManager / RootShell / RootProxyManager / RootLanSharing
receiver/              # BootReceiver / TaskerReceiver / WidgetProvider（Glance，:bg）
contracts/             # ServiceControl / Tun2SocksControl / IDialerService
helper/                # Activity 能力封装：FileChooser / Permission / QRCodeScanner /
                       # Notification / MessageHelper（跨进程消息）
extension/             # Kotlin 扩展（ListExt / StringExt / ToastExt / _Ext）
util/                  # Utils / HttpUtil / JsonUtil / LogUtil / QRCodeDecoder / ZipUtil /
                       # AppIconFetcher …
ui/                    # 全部 Compose UI
  AppRoute.kt          # 所有导航目标，自己构造 Intent
  base/                # BaseActivity / BaseHelperActivity / BaseContract / BaseScreen /
                       # BaseViewModel / BaseEditViewModel / BaseResult / EditFormSaver
                       # （BaseViewModelFactory 为遗留死代码，生产路径禁止使用）
  compose/             # 跨屏共享组件：Theme / Components / Dialog / FormFields /
                       # FormPagedDropdownField / PrefsState / SettingsItem / Menu /
                       # Scrollbar / SnackBar
  main/ settings/ server/ subscription/ routing/ perappproxy/ apppicker/
  userasset/ backup/ logcat/ scanner/ about/ checkupdate/ shortcut/ urlscheme/
  widght/              # Glance 桌面组件 SwitchWidget（包名 com.v2ray.ang.ui.widget，目录名为历史拼写）
```

### Base 层契约（`ui/base/`，共 9 个文件）

- `BaseUiState` / `BaseAction` / `BaseEvent` / `BaseRoute` / `BaseText` / `BaseMessage`
  —— 在 `BaseContract.kt`，是 SSOT/UDF 的类型基座。
- `BaseViewModel<S, A>`：持有唯一 `MutableStateFlow<S>`，`setState { }` 是唯一改状态入口；
  引用计数的 `isLoading`；`Channel(UNLIMITED)` 承载一次性事件；
  统一 `launch(loading, context, onError) { }`。
- `BaseEditViewModel<S, A>`：编辑页父类，子类实现 `doSave()` / `doDelete()`，
  返回 `null` 表示校验失败留在原页；`mutationJob` 负责防重入。
- `BaseScreen(...)`：**唯一**收集 `uiState` 的地方，提供 Scaffold 槽位、事件消费、
  `BaseResultContract` 启动子页并回传 `BaseResult`。
  它**不**订阅 `isLoading`（也无全屏遮罩）；需要顶栏进度时在槽位内自行收集。
- `BaseActivity` / `BaseHelperActivity`：`BaseActivity` 只做 edge-to-edge + `AppTheme` +
  `ScreenContent()`；需要文件、权限、扫码时继承 `BaseHelperActivity`，
  能力通过 `LocalPlatformActions` 下发，**禁止在 Composable 里 `context as XxxActivity`**。
- `BaseRepository`：只做线程收敛，`withIO { }` 包住所有阻塞调用，注入 `@IoDispatcher`。
- `EditFormSaver`：进程死亡后的表单恢复，带 `dirty` 门（没改过就不写快照）。

### 每个功能屏的固定文件集

```
ui/<feature>/
  XxxActivity.kt     # 仅：@AndroidEntryPoint + by viewModels()、ScreenContent()、平台事件翻译
  XxxContract.kt     # XxxUiState / XxxAction / XxxEvent（+ 该屏专用 @Immutable 模型）
  XxxScreen.kt       # 根 Composable，内部只有 BaseScreen + 槽位
  XxxViewModel.kt    # @HiltViewModel，继承 BaseViewModel / BaseEditViewModel
  [XxxDialogs.kt] [XxxTopBar.kt] [XxxSections.kt] [XxxStateHolders.kt] …按需拆分
```

参考实现：读 `ui/main/`（最复杂，含分组 Paging、拖拽、批量测速、搜索去抖）
和 `ui/settings/`（最典型的 pref 型页面）。

### 导航与返回值

无 Navigation-Compose。一屏一 Activity，`AppRoute` 的每个成员自己 `intent(context)`；
`BaseScreen` 用 `BaseResultContract` 启动，子页 `finishWithResult(BaseResult.Saved/Deleted/…)`，
父页在 `onResult` 里 dispatch 一个 Action。`AppRoute.OpenUrl` 返回 `null` intent，由宿主用
`Utils.openUri` 处理。

### 多进程与 IPC

Service 跑在独立进程（`:daemon` / `:tasks`），与 UI 进程之间靠 `MessageHelper` +
`AppConfig.BROADCAST_ACTION_ACTIVITY` 广播 + `AppConfig.MSG_*` 常量通信。
UI 侧的接收点只有一个：`MainRepository` 内部的 `BroadcastReceiver` →
`serviceEvents: SharedFlow<MainServiceEvent>`。跨进程共享状态是 Room 数据库文件，
一致性靠 `enableMultiInstanceInvalidation()` + `SettingsStore.observe()`。
详见 `docs/project-rules/service-ipc-rules.md`。

## 项目级规范（必读）

索引见 [`docs/project-rules/README.md`](docs/project-rules/README.md)。对照表：

| 你要做的事 | 必读 |
| --- | --- |
| 新增/重构任何一屏 | `architecture-rules.md`、`compose/structure.md`、`compose/state-events.md` |
| 动数据读写、加 Repository / DAO | `repository-rules.md`、`architecture-rules.md` |
| 写协程、Flow、分页、去抖、并发 | `coroutine-flow-rules.md` |
| 加依赖、动 `di/`、加 ViewModel / Worker | `hilt-rules.md` |
| 动 Service / Receiver / Worker / 广播 | `service-ipc-rules.md` |
| 改测速 / 延迟测试 | `connection-test-lifecycle.md` |
| 写 Composable、改主题与尺寸 | `compose/structure.md`、`compose/theme-styles.md` |
| 列表卡顿、重组过多 | `compose/performance.md` |
| 加导航目标、写 Preview | `compose/navigation-preview.md` |
| 加图标按钮、可点击行 | `compose/accessibility.md` |
| 写测试 | `compose/testing.md` |
| 提交前自查 / Review 别人 | `compose/review-checklist.md` |
| 查阅历史迁移计划 | `archive/README.md` |

## Coding Conventions

- Kotlin 官方风格（`kotlin.code.style=official`），4 空格缩进，行宽 120。
- 不允许跟随逗号，`.editorconfig` 的 ktlint_standard_trailing-comma-on-declaration-site = disabled 机器约束。
- 命名：`XxxActivity` / `XxxViewModel` / `XxxScreen` / `XxxContract` / `XxxRepository`。
- 日志统一 `LogUtil.d/e(AppConfig.TAG, ...)`，禁止裸 `android.util.Log` 和 `printStackTrace()`。
- 常量进 `AppConfig`；Composable 的尺寸常量用文件内 `private val`（见 theme-styles.md）。
- 新增字符串必须进 `res/values/strings.xml`，禁止硬编码中文/英文面向用户文案。
- 公共 API 与非显然的实现写 KDoc，说明"为什么"而不是"做了什么"。

## Testing Strategy

- 单元测试：`app/src/test/java/`，JUnit4 + `mockito-inline` + `mockito-kotlin` +
  `kotlinx-coroutines-test` + `room3-testing` + `paging-testing` + `sqlite-bundled-jvm`。
  现有样例：`UtilsTest`、`HttpUtilTest`、`ShadowsocksFmtTest`、`ListExtTest`、
  `MainImportMenuTest`、`AppPickerViewModelTest`、`ScannerActivityTest`、
  `RootProxyManagerTest`、`UrlSchemeMergeFragmentTest`、`ZipUtilTest`、`data/ImporterFixtures`。
- 仪器测试目录存在但基本未使用（espresso 依赖仅声明），不要为了凑覆盖率新增。
- 优先测纯函数：`fmt/` 解析、`extension/`、菜单/校验等纯 Kotlin 逻辑，
  以及可用假 Repository 驱动的 ViewModel reducer。
- DAO / 迁移 / 分页用 `room3-testing` 在 JVM 上开内存库
  （`BundledSQLiteDriver` + `sqlite-bundled-jvm`），分页用 `paging-testing` 断言。
- 完成标准：`./gradlew :app:compileFdroidReleaseKotlin` 与 `./gradlew test` 都通过。

## CI/CD

`.github/workflows/build.yml`（触发分支为 `new`，可 `workflow_dispatch`）：
拉取子模块 → 装 SDK 37 / build-tools 37.0.0 / NDK 29.0.14206865 →
`compile-hevtun.sh`（带 cache）→ AAR 拷进 `app/libs/` →
`./gradlew assembleRelease --build-cache --parallel --daemon --warning-mode all` → 发 Release。
CI 不跑 `test`、不跑 `lint`，所以**本地必须自己跑**。

## 核心规则

1. **先检查是否有匹配的 skill**——哪怕只有 1% 可能性也要检查。
2. **设计先于编码**——先确认这屏的 UiState / Action / Event 三件套长什么样，再动手。
3. **测试先于实现**——能纯函数化的逻辑先写测试。
4. **验证先于完成**——声称完成前必须跑
   `./gradlew :app:compileFdroidReleaseKotlin` 和 `./gradlew test`。

## AI 探索项目的方式

1. 先读本文件，建立包结构与分层的心智模型；
2. 读 `ui/base/` 全部 9 个文件——这是所有屏幕的契约，不读会写出违反 UDF 的代码；
3. 读 `AppConfig.kt` 找常量，不要自己造 key；
4. 找一个同类型的现有屏作模板（pref 型看 `settings/`，编辑型看 `server/` 或
   `subscription/`，列表型看 `perappproxy/`，复杂型看 `main/`），保持风格一致；
5. 改数据层前先看 `data/AppDao.kt` 已有的查询/事务，绝大多数已经存在；
   新增偏好项按 `repository-rules.md` §6 的四步流程走。
