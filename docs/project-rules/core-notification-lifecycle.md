# 内核前台通知生命周期

适用：`handler/NotificationManager`、`handler/CoreNotification`、三个内核 Service，以及
`CoreServiceManager.ReceiveMessageHandler`。修改这些通知路径时与 `service-ipc-rules.md` 一起阅读。

## 1. 问题与源码定位

反馈：服务长期运行，期间自动切换夜间模式、长时间息屏且不打开应用，通知栏中的「停止」和
「服务重启」按钮消失。

旧实现可确认的缺陷：

- `ACTION_SCREEN_OFF → stopSpeedNotification() → updateNotification("", 0, 0)` 清空正文，
  却仍设置 `BigTextStyle().bigText("")`，把不需要展开正文的状态也交给空的大文本模板。
- `ACTION_SCREEN_ON` 只启动网速任务；`ACTION_USER_PRESENT` 虽已注册却没有处理分支。
  恢复通知依赖后续流量采样，关闭网速显示、采样任务异常后残留非空 Job 时都缺少主动恢复。
- VPN / 仅代理 / Root 三种 Service 均未在 `onConfigurationChanged` 刷新通知；
  夜间模式或系统语言变化后的重绘没有守护进程侧的刷新入口。
- 同一个可变 `NotificationCompat.Builder` 被 IO 采样、主线程广播及服务启停/重载并发读写，
  构建、发布与 `stopForeground` 未串行化；仅 `Job.cancel()` 也无法拦截正在查询内核的旧采样结果。

每次发布完整通知，由系统事件独立触发恢复，不依赖 SystemUI 的缓存行为。

## 2. 状态与发布约束

- `notificationContent: CoreNotificationContent?` 是进程内通知数据；`null` 表示通知已撤销。
  只保留不可变的节点标题、可选网速正文与图标，**不缓存 Builder / Style / RemoteViews**。
- `buildCoreNotification` 每次新建 Builder。启动占位、运行、网速更新、息屏和恢复均无条件设置：
  - 点击正文打开主界面的 PendingIntent；
  - 停止 / 重启两个 action，顺序固定；
  - ongoing、only-alert-once、LOW 优先级。
- 正文为 null、空串或纯空白时，不设置 `BigTextStyle`，使用系统标准带 action 的模板。
  有正文（包括零速率文案）时才使用大文本样式。
- 沿用通知 ID `1` 与 `AppConfig.RAY_NG_CHANNEL_ID`。两个广播 PendingIntent 的 request code
  分别为 `1` / `2`，继续带 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`、本应用 package 和
  `MSG_STATE_STOP` / `MSG_STATE_RESTART`；不要因为 Intent extras 不参与身份比较而合并 request code。
- `notificationLock` 同时保护状态、采样 Job 的替换和实际 `notify/startForeground/stopForeground`
  调用。不能只在锁内构建、锁外发布，否则旧通知仍可能越过停止操作重新出现。
- 内核流量查询在锁外执行。发布前在锁内检查采样 Job 仍 active 且身份等于当前 Job；
  息屏、停止或重启后，旧采样不能覆盖新状态。旧任务的 `finally` 也不能清掉新任务。

## 3. 事件行为

| 事件 | 必须执行 |
| --- | --- |
| `onStartCommand`（含重复启动、系统重建） | 先 `ensureForeground()`，构建完整占位或当前通知；不等待数据库 |
| 启动/重载节点 | `showNotification(profile)` 更新节点标题，重新构建完整通知 |
| Service `onConfigurationChanged` | VPN / 仅代理 / Root 都在 `super` 后调用 `refreshNotification()` |
| 应用语言广播 | `refreshNotification(localeCode)` 使用显式语言，跨进程快照传播期间仍能正确显示 |
| `ACTION_SCREEN_OFF` | 取消采样、清空网速数据，发布无大文本样式但保留按钮的通知 |
| `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` | 先刷新完整通知，再按设置恢复采样；不等待流量或 UI 进程 |
| 停止服务 | 取消采样并清空状态，再移除前台通知；服务引用已丢失也要清理本地状态 |
| 停止后的配置/亮屏/解锁回调 | `refreshNotification()` 因状态为空而不发布；不能复活通知或启动内核 |

刷新读取当前本地化资源，不重启内核，不修改用户选择的通知渠道重要性，也不创建新渠道来绕过用户设置。

## 4. 采样与省电

- 网速轮询只在设置启用、内核运行且设备可交互时启动。息屏期间的网络重载不能偷偷重开轮询。
- Job 身份先保存再启动；只用 `isActive` 判断是否已有任务，异常退出会释放当前 Job 引用。
- 采样使用 `PlatformDependencies.ioDispatcher(service)`；不新增裸 `Dispatchers.IO`。
- 每轮先等待 3 秒；间隔使用任务局部的 `SystemClock.elapsedRealtime()`，不受系统校时影响，
  跳过过短间隔时不前移基准。任务恢复后重新计时。
- 不增加闹钟、WakeLock 或息屏轮询来维持按钮。持续零流量可继续跳过重复的网速通知，
  但配置/亮屏/解锁刷新不受这项优化影响。

## 5. 回归与验收

### JVM 测试

`CoreNotificationTest` 使用真实通知工厂与数据、mock Android Builder 边界，检查：

1. null / 空 / 空白正文不设置大文本样式，仍生成两个按钮。
2. 启动、网速、息屏、唤醒、零流量、切换节点的数据序列，每次使用新的 Builder 并设置完整 action。
3. 重建重新解析 action 文案，不沿用旧语言。

### 设备验收矩阵

三种运行模式均需覆盖，分别开启和关闭网速显示：

1. 启动服务后展开通知，确认按钮可见；打开网页后验证网速更新不移除按钮。
2. 保持应用在后台，手动和自动切换日/夜模式，确认通知使用当前样式，两个按钮仍可用。
   可用 `adb shell cmd uimode night yes/no` 加速检查；完成后恢复原来的夜间模式设置。
3. 息屏数小时/过夜，期间切换夜间模式，亮屏及解锁后不打开应用，立即展开通知检查按钮。
   同时覆盖无流量和退出 Doze 的情况；**不要只在恢复网速后才检查**。
4. 仅让 UI 进程被回收、保留 `:daemon` 服务，重复第 2、3 项。强行停止整个应用会杀死服务，
   不属于「UI 长期未打开」场景。
5. 从通知执行重启和停止；重启后只剩一条带两按钮的通知，停止后即使亮屏/解锁/切主题也不再出现。
   快速反复息屏、亮屏、重启，观察是否有旧采样导致通知回弹。
6. 息屏时触发网络切换/内核重载，确认未恢复网速轮询；亮屏后按设置恢复。
7. 服务运行中切换应用语言、系统语言，检查按钮和渠道名称；模拟系统重建服务后再次检查按钮。

在通知权限及渠道允许显示的前提下，验收的是**展开后的应用前台通知**。系统折叠、锁屏隐私隐藏、
关闭通知权限/渠道，以及用户强停应用不是 action 丢失，修复不绕过这些系统/用户选择。
必要时用 `adb shell dumpsys notification` 核对本应用 ID `1` 的 actions；转储可能含节点名称，分享前脱敏。
