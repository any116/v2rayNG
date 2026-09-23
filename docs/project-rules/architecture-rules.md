# 架构规范：MVVM / SSOT / UDF 在 v2rayNG 的落地

参考 Android 官方 Guide to app architecture（UI layer / Domain / Data layer、UDF、
UI State as single source of truth）与 Room 3 / Paging 3 官方指南。本文件只写"在本仓库怎么做"。

## 1. 分层与依赖方向

```
┌───────────────────────────── UI Layer ─────────────────────────────────┐
│ ui/<feature>/XxxScreen.kt      纯 Composable，无状态，读 UiState / LazyPagingItems │
│ ui/<feature>/XxxActivity.kt    Compose 宿主 + 平台能力翻译（@AndroidEntryPoint）    │
│ ui/<feature>/XxxViewModel.kt   状态持有者（@HiltViewModel），产出 UiState / 消费 Action │
│ ui/<feature>/XxxContract.kt    UiState / Action / Event 类型定义          │
│ ui/base/ ui/compose/           基座与共享组件                            │
└───────────────────────────────┬────────────────────────────────────────┘
                                │ 只能调用 suspend / 普通函数
┌───────────────────────────────▼──── Data Layer ────────────────────────┐
│ data/repository/  UI 进程数据门面：聚合、线程收敛、投影、事件流、Paging Pager │
│ data/             Room 3：AppDatabase / AppDao / SettingsStore / 迁移    │
│ handler/          进程无关的数据源与领域逻辑（object，跨进程可用）          │
│ dto/ enums/ fmt/ util/ extension/  纯数据与纯函数                        │
└───────────────────────────────┬────────────────────────────────────────┘
                                │
┌───────────────────────────────▼──── Platform ──────────────────────────┐
│ core/ service/ root/ receiver/  内核、前台服务、Root、系统入口            │
│ di/                               Hilt 接缝（Module / Qualifier / EntryPoint）│
└────────────────────────────────────────────────────────────────────────┘
```

依赖只能向下。硬性禁止：

- `ui/**/*ViewModel.kt` 不得 import `com.v2ray.ang.handler.*`、`com.v2ray.ang.core.*`、
  `com.v2ray.ang.service.*`、`com.v2ray.ang.data.*Dao`、`com.v2ray.ang.data.AppDatabase`、
  `android.content.Context`、`android.app.Application`、`android.widget.*`、
  任何 `androidx.compose.*`（`@Immutable` / `@Stable` 注解除外）。
- `data/repository/` 不得 import `ui/`（`ui.base.BaseResult` 也不行——结果类型由 ViewModel 组装）。
  唯一历史例外：`data/repository/ServerRepository` 与 `RoutingRepository` 引用
  `ui.compose.DropdownOption`（纯数据类），**不要新增同类**；新下拉模型先放进 `dto/`。
- `data/`、`handler/`、`core/`、`service/` 不得 import `data/repository/` 与 `ui/`。
- Composable 不得 import `handler/`、`data/repository/`、`core/`
  （`ui.compose.Theme` 对 `ThemeRepository` 的引用是唯一历史例外，不要新增同类）。
- Composable 不得 import `Prefs`（见 `compose/structure.md` 的例外清单：
  仅 `ui/compose/PrefsState.kt` 的两个 `rememberSettingBool/String` 允许，供无 ViewModel 的小开关使用）。

## 2. 包归属决策（本仓库的最终答案）

### 2.1 Room 实体与 Repository 归入 `data/`

已完成的迁移（`refactor: move dto/entities + repository into data package`）：

| 包 | 放什么 |
| --- | --- |
| `data/` | `AppDatabase`、`AppDao`（全部 DAO + 投影）、`SettingsStore`、`Prefs`、`SettingsDefaults`、`LegacyImporter`、`LegacyMigrationGate`、`DatabaseIntegrity`、`DatabaseCallbacks`、`DedupeKey` |
| `data/entities/` | Room `@Entity`：`ProfileItem`、`ServerAffiliationInfo`、`ProfileRaw`、`SubscriptionItem`、`AssetUrlItem`、`RulesetItem`、`SettingsEntry`（+ `WebDavConfig` 值对象） |
| `data/legacy/` | `MmkvLegacyReader`（唯一允许 import `com.tencent.mmkv` 的文件）、`SettingKinds` |
| `data/repository/` | 每屏一个 Repository（或一组屏共用一个），继承 `BaseRepository` |
| `dto/` | 跨层传递的**不可变**数据类。UI 直接消费的行模型（`ServerRowItem`、`GroupMapItem`、`ConnectionTestResult`）也在这里 |
| `ui/<feature>/XxxContract.kt` | **只有这一屏用**的 UI 模型（`MainStatus`、`MainPagerArgs` 等） |

新增数据类时先问：会被第二个 feature 用到吗？会被 Repository 用到吗？不会就留在该屏的
`XxxContract.kt`，别污染 `dto/`。**会被 Room 序列化落盘的才放进 `data/entities/`。**

### 2.2 `receiver/` 保持独立，不放进 `ui/`

**不迁入 `ui/`。** `BootReceiver`、`TaskerReceiver`、`WidgetProvider` 的共同特征：

- manifest 静态注册，是系统进程唤起 App 的入口，生命周期与任何 Activity/Composition 无关；
- 没有 ViewModel、没有 Composition；`WidgetProvider` 用 Glance，不是普通 Compose；
- 直接依赖 `core/LauncherManager` 与 `core/CoreServiceManager`。放进 `ui/` 会让 UI 包
  反向依赖 core/service，破坏第 1 节的依赖方向。

**规则：**

- manifest 注册的 `BroadcastReceiver` / `GlanceAppWidgetReceiver` → `receiver/`。
- **代码注册**、生命周期绑定某个数据流的 `BroadcastReceiver` → **写成 Repository 的私有成员**，
  对外只暴露 Flow。样板是 `MainRepository`：内部匿名 receiver 把 `AppConfig.MSG_*`
  翻译成 `MainServiceEvent`，通过 `serviceEvents: SharedFlow<MainServiceEvent>` 出去。
  该 Repository 是 `@Singleton`，**不需要** `Closeable`：receiver 的生命周期等于进程
  （`receiverRegistered: AtomicBoolean` 保证幂等）。
- Receiver 里禁止写业务逻辑，只允许"翻译 + 转发"：转给 `LauncherManager`、
  转给 Service、或 `tryEmit` 到 Flow。

### 2.3 `handler/` 不解散，与 `data/repository/` 分工明确

**handler 不彻底融入 repository。** 原因：`SettingsManager`、`AngConfigManager`、
`SubscriptionUpdater`、`NotificationManager`、`WidgetStateManager` 被
`CoreVpnService`、`CoreTestService`、`SubscriptionUpdateService`、`SubscriptionUpdateWorker`、
`QSTileService`、`BootReceiver` 等**跑在 `:daemon` / `:tasks` / `:bg` 进程**的组件直接调用。
那些进程里没有 UI 侧的注入点（除 `@AndroidEntryPoint` 的 Service 外），
强行让它们走 Repository 只是给非 UI 代码加一层空壳。

**职责切分：**

| 层 | 定位 | 允许被谁调用 |
| --- | --- | --- |
| `data/`（DAO / SettingsStore） | Room 访问与设置快照，无业务聚合 | `data/repository/`、`handler/`、`core/`、`service/` |
| `data/repository/` | UI 进程的数据门面：`suspend` 化、线程收敛、投影、聚合多个 handler、暴露 Flow/Paging | 只有 ViewModel |
| `handler/` | 领域逻辑，`object` 或无状态类，跨进程可用 | Repository、`service/`、`receiver/`、`core/`、`root/` |

**因此的硬规则：**

1. ViewModel 只能调 Repository，一个 handler / DAO 调用都不许出现在 ViewModel 里。
2. 只被一个 Repository 用、且不跨进程的逻辑，**不要新建 handler**，直接写进 Repository。
3. handler 里禁止出现 `StateFlow` / `MutableStateFlow` 形式的 **UI 状态**；
   跨进程状态用 Room + `invalidationTracker` / 广播，UI 状态归 ViewModel。
   （`WidgetStateManager` 的 `StateFlow<WidgetRunState>` 是 `:bg` 进程内的组件状态，
   与 UI 进程不共享，是允许的形态；`SettingsChangeManager` 只做 key 分类与原子标志，也不持有 UI 状态。）
4. handler 的方法保持同步或 `suspend`，不假设 Dispatcher；切线程是 Repository / Service 的责任。
5. 没有注入构造函数的 `object` 需要 DAO / 设置时，走 `di/ServiceEntryPoint` 暴露的
   `PlatformDependencies`（`PlatformDependencies.profileDao(context)` 等），**不要**自己 new 数据库。

### 2.4 `helper/` 与 `util/`

- `helper/` = 需要 Activity / Context 的能力封装（`FileChooserHelper`、`PermissionHelper`、
  `QRCodeScannerHelper`、`NotificationHelper`、`MessageHelper`）。
  Activity 侧能力必须经 `BaseHelperActivity` → `PlatformActions` → `LocalPlatformActions` 下发。
- `util/` = 无状态纯函数或极薄的系统 API 包装，可在任何进程调用。

## 3. SSOT：状态的唯一来源

1. 一屏一个 `XxxUiState : BaseUiState`，标 `@Immutable`，全部 `val`。
2. `BaseViewModel` 内的 `_uiState` 是唯一可变来源；`setState { copy(...) }` 是唯一写法。
3. **大列表不进 UiState（重要）**：主列表用 Paging 3。
   `MainViewModel.servers(groupId): Flow<PagingData<ServerRowItem>>` 与
   `serverCount(groupId): StateFlow<Int>` 把行数据与计数独立出去，不放进 `MainUiState`。
   这是刻意的性能设计——几千行放进 UiState 会让任何一次状态变更都重算整个列表
   （且 Paging 的 `LazyPagingItems` 也无法塞进 `data class`）。
   这些访问器用 `@Stable class` 打包（样板：`MainSlices` / `MainScreenHandles`）。
   新增大列表页时沿用同一模式。
4. `isLoading` 不进 UiState，由 `BaseViewModel` 的引用计数提供。
5. UI 侧不得把 State 里的值 `remember` 成第二份可变副本。表单输入的唯一来源也是 UiState
   （样板：`BaseEditViewModel` 系列的编辑页）。

## 4. UDF：单向数据流

1. `XxxAction : BaseAction`，用 `sealed interface` + `data object` / `data class`。
   命名是**用户意图**（`ToggleService`、`RemoveServer(guid)`），不是实现动作（`setRunning(true)`）。
2. `XxxViewModel.onAction(action)` 用穷尽 `when` 分发，每个分支只做一件事：
   要么 `setState`，要么 `launch { repo.xxx() }`，要么 `platform(...)` / `navigate(...)`。
   分支体超过 3 行就抽私有方法。
3. 一次性效果走 `BaseEvent`，**永远不进 UiState**：
   - `BaseEvent.Message` —— toast/snackbar；ViewModel 只描述（`BaseText`），UI 负责渲染；
   - `BaseEvent.Navigate(route)` —— 由 `BaseScreen` 消费；
   - `BaseEvent.Finish(result)` —— 关页并回传；
   - `XxxEvent : BaseEvent.Platform` —— 只有 Activity 能做的事
     （VPN 授权、启停内核、扫码、选文件、显示二维码 Bitmap），
     先由 `XxxScreen` 的 `onEvent` 截获本屏能处理的，其余由 `XxxActivity` 的
     `handlePlatformEvent` 翻译。
4. Composable 拿到的永远是 `(state, onAction)`；子组件继续往下传 `onAction` 或更窄的回调，
   不得下传 ViewModel（例外：根 Composable 收 `viewModel` 供 `BaseScreen` 与槽位订阅）。
5. Activity 里禁止业务判断。`MainActivity` 的形态就是上限：`by viewModels()` 拿 VM、
   `requestPermission`、把 `MainEvent` 翻成 `LauncherManager` / `VpnService.prepare()` 调用、
   把结果再 dispatch 回 Action。

## 5. ViewModel 规范

- `@HiltViewModel` + `@Inject constructor(repo, savedStateHandle)`；
  Activity 侧标准 `private val viewModel: XxxViewModel by viewModels()`。
  **不得**手工 new ViewModel，也不得新增自定义 `ViewModelProvider.Factory`
  （`ui/base/BaseViewModelFactory.kt` 仅为历史兼容保留，生产路径禁止使用）。
- `@Inject constructor` 的参数**不得**全部带默认值（见 `hilt-rules.md` §2）。
- 所有协程走 `launch(loading =, context =, onError =) { }`，不用裸 `viewModelScope.launch`。
- 需要长期存活的 Job（预取、去抖、轮询）保存成字段，在 `onCleared()` 里取消。
- 校验失败不抛异常，用 `toastError(...)` + 保持状态；
  编辑页用 `BaseEditViewModel.doSave()` 返回 `null` 表示"留在本页"。
- 需要跨进程死亡保活的表单接 `SavedStateHandle` + `EditFormSaver`。
- **不要**在 ViewModel 里持有 `Closeable` Repository 的生命周期：Repository 现为 Hilt
  `@Singleton` 或无 scope 的构造注入，进程级资源由 Hilt 图管理。

## 6. Activity 规范

- 只继承 `BaseActivity` 或 `BaseHelperActivity`，并标 `@AndroidEntryPoint`。
- `ScreenContent()` 里只允许一个 `XxxScreen(viewModel, ...)` 调用；启动前需要等设置快照时，
  参考 `MainActivity` 的 `awaitReady()` + 超时兜底写法。
- 允许出现的成员：VM 字段、`ActivityResultLauncher`、`handlePlatformEvent`、
  `onNewIntent` / `onKeyDown` 这类系统回调。
- 禁止：持有 UI 状态、直接读写 Room/Prefs、直接调 handler/core（`LauncherManager` 例外，
  它就是平台能力）、弹 Toast（走 Event）。

## 7. 新增一屏的标准动作

1. 在 `ui/<feature>/` 建四件套：`XxxContract.kt` → `XxxViewModel.kt` → `XxxScreen.kt` → `XxxActivity.kt`。
2. 在 `data/repository/` 建 `XxxRepository @Inject constructor(dao…, @IoDispatcher io) : BaseRepository(io)`，
   需要落盘/查询的 `suspend` 方法用 `withIO { }` 包裹。
3. 在 `ui/AppRoute.kt` 加导航目标（成员 + `intent(context)`）；需要传参的用
   `AppRoute.Companion` 里的 `EXTRA_*` 常量。
4. 在 `AndroidManifest.xml` 注册 Activity（`android:exported="false"`，需要时设置 `process`）。
5. 若该屏需要文件/权限/扫码 → 继承 `BaseHelperActivity`。
6. 跑 `./gradlew :app:compileFdroidReleaseKotlin` 与 `./gradlew test`。
