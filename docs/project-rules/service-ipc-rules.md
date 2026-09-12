# 服务、多进程与 IPC 规范

## 1. 进程拓扑

| 进程 | 组件 |
| --- | --- |
| 默认（UI） | 所有 Activity、`MainRepository` 的 receiver |
| `:RunSoLibV2RayDaemon` | `CoreVpnService`、`CoreProxyOnlyService`、`CoreTestService`、`TProxyService`、`DialerNativeService` 等 |
| `:bg` | WorkManager（`AngApplication` 里以 `${ANG_PACKAGE}:bg` 配置） |

跨进程**不能**共享内存对象。唯一的共享状态是 MMKV，唯一的通信手段是广播与 Messenger。

## 2. 消息协议

- 所有消息 key 是 `AppConfig.MSG_*` 整型常量，禁止字符串魔法值。
- 发送统一走 `helper/MessageHelper`：`sendMsg2Service`、`sendMsg2TestService`。
- UI 侧接收只有一个入口：`MainRepository` 内部的 `BroadcastReceiver`
  （`IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)`，flags 用 `Utils.receiverFlags()`），
  翻译成 `MainServiceEvent` 密封接口后 `tryEmit`。
- 新增一种服务→UI 的通知：
  1. `AppConfig` 加 `MSG_*` 常量；
  2. `MainServiceEvent` 加一个成员；
  3. receiver 的 `when` 加一条映射；
  4. `MainViewModel.handleServiceEvent` 加一条处理。
  **不要**在别的 Repository 里再注册一个 `BROADCAST_ACTION_ACTIVITY` 接收器。
- 注册必须配对反注册：Repository 实现 `Closeable`，用 `AtomicBoolean` 做幂等，
  `ViewModel.onCleared()` 调 `close()`，反注册用 `runCatching` 包住。

## 3. Service 规范

- 前台服务的通知统一走 `handler/NotificationManager` + `helper/NotificationHelper`，
  渠道定义在 `enums/NotificationChannelType`。
- Service 不 import `ui/`、不 import `repository/`；需要数据直接用 `handler/`。
- 启停内核的唯一入口是 `core/LauncherManager`（`startService` / `stopService` /
  `startServiceFromToggle`），Activity、`QSTileService`、`WidgetProvider`、
  `TaskerReceiver` 都只能调它。
- VPN 权限（`VpnService.prepare`）与运行时权限只能在 Activity 里请求；
  ViewModel 通过 `MainEvent.StartService(requireVpnPermission, requireLocalNetwork)`
  把"需要什么"描述出去，由 `MainActivity` 决定怎么要。
- API 版本分支写法：用 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.XXX`
  （如本地网络权限用 `CINNAMON_BUN`），不要用数字字面量。

## 4. Receiver 规范

- manifest 注册的三个：`BootReceiver`（开机自启）、`TaskerReceiver`（第三方自动化）、
  `WidgetProvider`（桌面小组件）。
- 只允许"翻译 + 转发"，禁止业务判断与 IO。需要耗时工作时启动 Service 或入队 Worker。
- `WidgetProvider` 继承 `GlanceAppWidgetReceiver`，UI 在 `widget/SwitchWidget.kt`；改样式改 Composable，不要新建 `res/layout`。
  该接收器必须与 WorkManager 同进程（`:bg`）。Glance 从进程内存解析运行中的 session，跨进程 `update()` 会命中 `getSession()` 为 null 的空指针。
  组件状态只经 `handler/WidgetStateManager`：MMKV 存快照，`MSG_REGISTER_CLIENT` 有序广播取真值，不要在 `:bg` 读 `CoreServiceManager`。
- `PendingIntent` 必须带 `FLAG_IMMUTABLE`。

## 5. Worker 与 WorkManager

### 初始化只有一条路径

`AngApplication.onCreate()` 手动 `WorkManager.initialize(this, config)`，manifest 里
`WorkManagerInitializer` 保持 `tools:node="remove"`。**不实现 `Configuration.Provider`** ——
两种初始化并存会产生难以定位的进程级差异。

`Configuration` 必须在 `super.onCreate()` 之后构建，因为它要用到注入的
`HiltWorkerFactory`。`setWorkerFactory` 不会破坏非 Hilt 的 Worker：WorkManager 走
`createWorkerWithDefaultFallback`，`HiltWorkerFactory` 返回 null 时自动回退到反射。

### Worker 用 @HiltWorker

```kotlin
@HiltWorker
class XxxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params)
```

- `Context` 与 `WorkerParameters` 必须是 `@Assisted`。
- `@HiltWorker` **只支持顶层类**，不能嵌套在 object 里。
- Worker 跑在 `:bg`，只能注入进程无关的依赖。**Worker 不得依赖 `repository/`**，
  数据一律直接调 `handler/`（见 `repository-rules.md` 第 5 节的表）。

### 改 Worker 类名 = 数据迁移

WorkManager 把 **Worker 的全限定类名持久化在库里**。重命名或移动 Worker 之后，
旧版本入队的行仍然指向旧类名，在 `KEEP` 策略下会永久失败。

做法：`SubscriptionUpdater.WORKER_SCHEMA_VERSION` 递增，`sync()` 检测到版本不一致时
本次改用 `ExistingPeriodicWorkPolicy.UPDATE`（改写 work spec 含类名，**保留**原周期），
完成后写回版本号。不要用 `REPLACE` —— 它会重置计时。

### 验收

主进程未打开时任务可执行；`:bg` 被杀后恢复；唯一任务名去重；重试与取消；
**升级路径**：旧类名的行被改写且周期未被重置。
