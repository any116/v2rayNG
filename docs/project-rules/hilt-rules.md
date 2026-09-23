# 依赖注入规范（Hilt）

## 1. 唯一的 DI 框架

生产代码使用 Hilt（`com.google.dagger.hilt.android`，KSP 处理器）。
`AngApplication` 标 `@HiltAndroidApp`，Activity / Service 标 `@AndroidEntryPoint`，
ViewModel 标 `@HiltViewModel` + `@Inject constructor`，Activity 侧用标准
`private val viewModel: XxxViewModel by viewModels()`。

`ui/base/BaseViewModelFactory.kt`（`BaseViewModelFactory` / `baseViewModels {}`）已从生产路径移除，
仅为历史兼容保留。**不得新增自定义 `ViewModelProvider.Factory`，不得手工 new ViewModel。**
`baseViewModels` 在源码里应当始终只出现在它自身的定义处。

## 2. @Inject 构造函数禁止全参数默认值

**Kotlin 在一个构造函数的全部参数都带默认值时会合成一个无参构造函数重载，并把
`@Inject` 复制到该重载上。** Dagger 随后报 `may only contain one injected constructor`，
并连带产生 `[Dagger/MissingBinding]`。

因此 `@Inject constructor` 的参数一律不写默认值：

```kotlin
// 错：唯一参数带默认值 → 合成出 @Inject XxxRepository()
open class XxxRepository @Inject constructor(
    @IoDispatcher io: CoroutineDispatcher = Dispatchers.IO,
) : BaseRepository(io)

// 对
open class XxxRepository @Inject constructor(
    private val profileDao: ProfileDao,
    @IoDispatcher io: CoroutineDispatcher,
) : BaseRepository(io)
```

`BaseRepository` 自身的 `io` 默认值可以保留：它是抽象类，永不被注入。
单元测试显式传 dispatcher（`Dispatchers.Unconfined` 或 `StandardTestDispatcher`），
这比依赖默认值更可控，见 `repository-rules.md` §5。

## 3. 模块划分

`di/` 下只放**接缝**，不放业务：

| 文件 | 提供 |
| --- | --- |
| `Qualifiers.kt` | `@IoDispatcher` / `@DefaultDispatcher` / `@ApplicationScope` |
| `DispatcherModule` | 两个 dispatcher（unscoped）+ `@ApplicationScope CoroutineScope`（`:@Singleton`） |
| `DatabaseModule` | `AppDatabase`（`@Singleton`）+ 五个 DAO（unscoped，`db.xxxDao()`） |
| `NetworkModule` | 复用 `HttpUtil.sharedClient`（`@Singleton`） |
| `ThemeModule` | `@Binds ThemeRepository → ThemeStore`（`@Singleton`） |
| `ServiceEntryPoint` | `@EntryPoint` + `PlatformDependencies` 门面（见 §6） |

- dispatcher 与 DAO 绑定**故意 unscoped**：`Dispatchers.IO` / `Dispatchers.Default`
  本身就是进程单例，DAO 也从属于 `@Singleton` 的 `AppDatabase`。
  `@ApplicationScope` 只给"必须活过任何屏、且没有别的 owner"的工作
  （当前唯一用途：跨进程设置快照订阅 `settings.observe(appScope)`）。
- `DatabaseModule.provideDatabase` 里 `build()` 是**惰性**的：不会在这里打开数据库文件，
  所以它能在主线程（Application 字段注入阶段）安全求值。完整性检查放在
  `LegacyMigrationGate`，由 `AngApplication` 的 bootstrap 协程在首次解析 DAO 之前调用。
- **不要把现有 object 包一层新的 `@Singleton` 类再宣称完成了依赖隔离。**
  既有 object 就是那个实例，模块只负责把它 re-export 进图里。

## 4. object 接缝的桥接

`handler/` 下的 object（`SettingsManager`、`AngConfigManager`、`SubscriptionUpdater` …）
保持 object，Repository 直接调用，**不注入**。它们必须跨进程可用，而 Service / Worker
进程里没有 UI 侧的图；需要 DAO/设置时它们走 `PlatformDependencies`（§6），
用 `by lazy` 延迟到真正被调用时再解析，避免在 `Application` 构造期就碰 Hilt。

只有当一个 object 同时被 Hilt 内外共享、且需要在测试里替换时，才抽接口桥接。
**当前唯一一例：`ThemeStore` ← `ThemeRepository`**（`ThemeManager` object 与注入路径共用它）。
抽接口需要评审，理由写进 KDoc。

## 5. Application

`AngApplication` 带 `@HiltAndroidApp`。四条铁律：

1. `attachBaseContext` 里**不得**访问任何注入字段 —— 那时还没注入
   （它只做 `ContextCompat.getContextForLanguage` 与 `application = this`）。
2. 注入在 `super.onCreate()` 内部完成，所以依赖注入字段的对象必须在
   `super.onCreate()` **之后**构建，不能写成属性初始化器
   （样板：`buildWorkManagerConfiguration()` 是函数而非 `val`）。
3. 每个进程都会创建自己的 Application 和自己的 Hilt 图。任何 singleton 都不得在
   注入阶段读数据库快照；`SettingsStore.refresh()` 排在 `super.onCreate()` 之后，
   由 `appScope.launch { ... }` 在后台跑。
4. `SettingsStore` / `AppDatabase` 现在**直接注入**（`@Inject lateinit var`），
   不要再包 `Provider<...>`：`Room.databaseBuilder(...).build()` 是惰性的，
   不会因为解析单例就打开文件。

`onCreate` 的 bootstrap 顺序（改动前必读）：
`AppLocaleManager.initialize` → `WorkManager.initialize` → `appScope.launch`：
`LegacyMigrationGate.runIfNeeded` → `settings.refresh()` →
（仅主进程）`seedDefaults()` / 规则集种子 / 默认订阅 → `LogUtil.refreshLogLevel()` →
存储模式日志 → `settings.observe(appScope)` → `ThemeManager.refresh()`。

## 6. 平台组件与非注入组件

- Activity / Service 用 `@AndroidEntryPoint`（当前：全部 Activity +
  `SubscriptionUpdateService`、`CoreTestService` 等 `:daemon`/`:tasks` 服务）。
- **Assisted ViewModel**：需要构造期传入、又无法从 `SavedStateHandle` 拿到的参数
  （样板：`ShortcutViewModel` 的 `ShortcutCommand`），用
  `@HiltViewModel(assistedFactory = Xxx.Factory::class)` + `@AssistedInject` +
  `@AssistedFactory interface Factory`，Activity 侧通过
  `viewModels(extrasProducer = { defaultViewModelCreationExtras
  .withCreationCallback<Xxx.Factory> { it.create(arg) } })` 创建。
  仅用于真实需要 assisted 参数的场景，普通屏继续用 `@Inject constructor`。
- 系统创建、无法走 `@AndroidEntryPoint` 的入口（部分 `BroadcastReceiver`、
  `TileService`、Glance receiver、`object` 单例）用受限的 `EntryPoint`：
  `di/ServiceEntryPoint.kt` + `PlatformDependencies`。
  允许的成员只有进程无关的依赖：`@IoDispatcher`、五个 DAO、`SettingsStore`、`ThemeStore`。
- `PlatformDependencies` 只允许出现在系统入口适配层、`handler/` 的 object 内部、
  以及 `Prefs` / `Theme.kt` 的门面里；
  **不得**退化成普通 Repository 的 Service Locator（有构造注入的类必须直接接依赖）。
- Widget 不注入 Activity ViewModel；注入对象不跨进程传递。
- Worker 用 `@HiltWorker` + `@AssistedInject`，`Context` 与 `WorkerParameters` 必须 `@Assisted`，
  只能注入进程无关的依赖（`@IoDispatcher`、handler 用到的 DAO）。
  细则见 `service-ipc-rules.md` §5。

## 7. 验收

- `./gradlew :app:compileFdroidReleaseKotlin` 通过（KSP 会暴露 Dagger 的
  `may only contain one injected constructor` / `MissingBinding`）。
- 新增注入点要确认：只有一处绑定、没有第二个 `@Singleton` 影子、
  不在 `attachBaseContext` / 属性初始化器里访问。
