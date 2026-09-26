# 服务、多进程与 IPC 规范

## 1. 进程拓扑

| 进程 | 组件 |
| --- | --- |
| 默认（UI） | 所有 Activity、`MainRepository` 的 receiver |
| `:daemon` | `CoreVpnService`、`CoreProxyOnlyService`、`CoreRootService`、`QSTileService`、`TaskerReceiver`、`ScSwitchActivity` / `ScStartActivity` / `ScStopActivity` |
| `:tasks` | `CoreTestService`、`SubscriptionUpdateService` |
| `:bg` | WorkManager、`WidgetProvider`（Glance）、`androidx.work.multiprocess.RemoteWorkManagerService` |

跨进程**不能**共享内存对象。唯一的共享状态是 **Room 3 数据库文件**（`v2rayng.db`），
唯一的通信手段是广播、有序广播确认与 Messenger。

Room 的跨进程一致性靠 `DatabaseModule` 的 `enableMultiInstanceInvalidation()`（**每个进程都要开**）
+ `SettingsStore.observe()` 订阅 `settings` 表的失效流；通知只说明"数据变了"，不承担事务与写入互斥。

## 2. 消息协议

- 所有消息 key 是 `AppConfig.MSG_*` 整型常量，禁止字符串魔法值。
- 发送统一走 `helper/MessageHelper`：
  - `sendMsg2Service` / `sendMsg2UI` —— `sendBroadcast`（action = `BROADCAST_ACTION_SERVICE` / `_ACTIVITY`）；
  - `sendMsg2ServiceForResult` —— `sendOrderedBroadcast`，回调 `handled`（守护接收则 `RESULT_OK`，
    无守护则为初始的 `RESULT_CANCELED`），返回状态的真值以它为准（样板：`WidgetStateManager.queryDaemon`）；
  - `sendMsg2TestService` / `sendMsg2SubscriptionService` —— 启动 / 停止对应 Service，内容放在 `"content"` extra。
- UI 侧接收只有一个入口：`MainRepository` 内部的 `BroadcastReceiver`
  （`IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)`，flags 用 `Utils.receiverFlags()`），
  翻译成 `MainServiceEvent` 密封接口后 `tryEmit`。
- 新增一种服务→UI 的通知：
  1. `AppConfig` 加 `MSG_*` 常量；
  2. `MainRepository` 加一个 `MainServiceEvent` 成员；
  3. receiver 的 `when` 加一条映射（用 `serializable<T>("content")` 解包复杂载荷）；
  4. `MainViewModel.handleServiceEvent` 加一条处理。
  **不要**在别的 Repository 里再注册一个 `BROADCAST_ACTION_ACTIVITY` 接收器。
- 注册必须幂等：`MainRepository` 用 `AtomicBoolean.receiverRegistered` 保证只注册一次；
  它是 `@Singleton`，receiver 生命周期等于进程，**不需要也不应该**在 `onCleared` 反注册。

## 3. Service 规范

- 前台服务的通知统一走 `helper/NotificationHelper`，渠道定义在
  `enums/NotificationChannelType`（`NotificationManager` 负责渠道与内核状态文案）。
- Service 不 import `ui/`、不 import `data/repository/`；需要数据直接用 `handler/`
  或 `PlatformDependencies.<dao>()`。
- 启停内核的唯一入口是 `core/LauncherManager`（`startService` / `stopService` /
  `startServiceFromToggle`），Activity、`QSTileService`、`WidgetProvider`、
  `TaskerReceiver` 都只能调它。
- VPN 权限（`VpnService.prepare`）与运行时权限只能在 Activity 里请求；
  ViewModel 通过 `MainEvent.StartService(requireVpnPermission, requireLocalNetwork)`
  把"需要什么"描述出去，由 `MainActivity` 决定怎么要。
- API 版本分支写法：用 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.XXX`
  （如本地网络权限用 `CINNAMON_BUN`），不要用数字字面量。
- 服务在触碰数据库或设置快照之前必须等待 `StorageBootstrap`（`awaitReady()` /
  `awaitReadyOrNull()`）：它由 `AngApplication` 的存储启动协程在完整性检查、旧数据导入、
  快照刷新完成后放行。存储未就绪时模式（VPN / Root / 仅代理）、并发数等都会读成编码默认值，
  必须显式跳过或报错，不能继续启动。
- Service 的 `serviceScope` 必须 `by lazy`：`@Inject lateinit var io` 只在 `super.onCreate()` 之后可用，
  属性初始化器会在构造期崩溃（样板：`SubscriptionUpdateService`）。
- `attachBaseContext` 里只能做 locale 包装（`AppLocaleManager.localizedContext`），不得读注入字段。

## 4. Receiver 规范

- manifest 注册的三个：`BootReceiver`（开机自启）、`TaskerReceiver`（第三方自动化）、
  `WidgetProvider`（桌面小组件）。
- 只允许"翻译 + 转发"，禁止业务判断与 IO。需要耗时工作时启动 Service 或入队 Worker。
- 冷启动型入口（`BootReceiver`、`TaskerReceiver`、`QSTileService`、快捷方式 Activity）在读取
  `Prefs` 或启动服务之前先 `StorageBootstrap.awaitReadyOrNull(...)`；等不到就跳过并记日志，
  绝不用冷快照的编码默认值做决策（自动启动开关、运行模式都会读错）。
- `WidgetProvider` 继承 `GlanceAppWidgetReceiver`，Glance UI 在
  `ui/widget/SwitchWidget.kt`（包 `com.v2ray.ang.ui.widget`；目录名 `ui/widght` 为历史拼写）。
  **改样式改 Composable，不要新建 `res/layout`。**
  该接收器必须与 WorkManager 同进程（`:bg`）。Glance 从进程内存解析运行中的 session，
  跨进程 `update()` 会命中 `getSession()` 为 null 的空指针。
  组件状态只经 `handler/WidgetStateManager`：`Prefs` 存快照，`MSG_REGISTER_CLIENT`
  有序广播取真值，不要在 `:bg` 读 `CoreServiceManager` 的进程内状态。
  点击只记录"请求中"，不得伪装成已连接；连续点击要幂等（`STARTING`/`STOPPING` 期间忽略）。
- `PendingIntent` 必须带 `FLAG_IMMUTABLE`。

## 5. Worker 与 WorkManager

### 初始化只有一条路径

`AngApplication.onCreate()` 手动 `WorkManager.initialize(this, config)`，manifest 里
`WorkManagerInitializer` 保持 `tools:node="remove"`。**不实现 `Configuration.Provider`** ——
两种初始化并存会产生难以定位的进程级差异。

`Configuration` 必须在 `super.onCreate()` 之后构建，因为它要用到注入的
`HiltWorkerFactory`；`setDefaultProcessName("${ANG_PACKAGE}:bg")` 与
`RemoteWorkManagerService` 一起把任务固定到 `:bg`。
`setWorkerFactory` 不会破坏非 Hilt 的 Worker：WorkManager 走
`createWorkerWithDefaultFallback`，`HiltWorkerFactory` 返回 null 时自动回退到反射。

### Worker 用 @HiltWorker

```kotlin
@HiltWorker
class XxxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(io) { /* … */ Result.success() }
}
```

- `Context` 与 `WorkerParameters` 必须是 `@Assisted`。
- `@HiltWorker` **只支持顶层类**，不能嵌套在 object 里。
- Worker 跑在 `:bg`，只能注入进程无关的依赖。**Worker 不得依赖 `data/repository/`**，
  数据一律直接调 `handler/` / `PlatformDependencies.<dao>()`
  （见 `repository-rules.md` §6 的表）。样板：`SubscriptionUpdateWorker`
  只注入 `@IoDispatcher`，然后调 `SubscriptionUpdater` 并发消息给订阅服务。

### 改 Worker 类名 = 数据迁移

WorkManager 把 **Worker 的全限定类名持久化在库里**。重命名或移动 Worker 之后，
旧版本入队的行仍然指向旧类名，在 `KEEP` 策略下会永久失败。

做法：`SubscriptionUpdater.WORKER_SCHEMA_VERSION` 递增（历史：1 = 嵌套
`SubscriptionUpdater$UpdateTask`，2 = 顶层 `SubscriptionUpdateWorker`），
`sync()` 检测到版本不一致时本次改用 `ExistingPeriodicWorkPolicy.UPDATE`
（改写 work spec 含类名，**保留**原周期），完成后写回版本号。
不要用 `REPLACE` —— 它会重置计时。

### 验收

主进程未打开时任务可执行；`:bg` 被杀后恢复；唯一任务名去重；重试与取消；
**升级路径**：旧类名的行被改写且周期未被重置。
