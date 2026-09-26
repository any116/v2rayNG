# 连接测试请求生命周期

## 身份
两类测试各有唯一 `requestId`（UUID），由 `MainViewModel` 在主线程登记/注销：
`currentTestId`（当前服务器）、`batchTestId` + `batchGroupId`（批量）。
所有服务→UI 的测试消息都带 `requestId`，不匹配即丢弃。

## 消息
| 方向 | key | content |
| --- | --- | --- |
| UI→守护 | `MSG_MEASURE_DELAY`（有序广播，`sendMsg2ServiceForResult`） | `requestId` |
| 守护→UI | `MSG_MEASURE_DELAY_RESULT` | `ConnectionTestResponse(requestId, result)` |
| 守护→UI | `MSG_MEASURE_DELAY_CANCELED` | `requestId` |
| UI→测试服务 | `MSG_MEASURE_CONFIG_START` / `_CANCEL` | `TestServiceMessage`（`requestId` 为空的取消＝全部取消） |
| 测试服务→UI | `_NOTIFY` / `_SUCCESS` / `_FINISH` / `_CANCELED` | `TestNotification(requestId, payload)` |

## 当前服务器测试
有序广播确认只在"真的接受并启动测量"时置 `RESULT_OK`；若无守护进程接收，
`MainRepository.testCurrentServer` 会经 `sendMsg2ServiceForResult` 的 `handled = false`
回调主动 `emitDelayCanceled(requestId)`，绝不留悬挂请求。
测量协程用 `finally` 兜底：未回结果则补发取消。

## 批量测试
先取消旧请求、清空延迟、快照目标列表，再登记新请求。服务端按工作单元记录所属请求，
`units.remove(unit)` 是原子归属声明——完成与取消只能由声明成功的一方发出。
取消、`onDestroy`、启动时的防御覆盖都逐请求回 `_CANCELED`，每个请求独立 `runCatching`，
单个失败不阻断其余。

## 结果落库（Room 3 / Paging 3 时代的关键变化）
- 批量结果不再逐条经 `MSG_MEASURE_CONFIG_SUCCESS` 推给 UI 再更新内存状态。
  服务侧把每个 `RealPingEvent.Result` 交给 `service/TestResultWriter` 合并，
  每 400ms（`FLUSH_INTERVAL_MS`，与旧 `DELAY_REFRESH_INTERVAL_MS` 对齐）在**单个事务**里
  `upsertStats` 写入 `profile_stats`。
- `TestResultWriter.flush()` 是**落盘屏障**：`Mutex` 覆盖"取批次 + 写库"全过程，空队列
  判断也在锁内，显式最终 flush 不会越过仍在提交中的窗口 flush。写失败会把批次重新入队
  （`putIfAbsent`，不覆盖写入期间到达的新结果）并向上抛：定时循环记日志、下个窗口重试；
  完成阶段的显式调用失败时**不得**继续排序/清理/宣告完成。
- `stop()` 在 `NonCancellable` 里先 `cancelAndJoin` 定时循环、再最终 flush；
  `CoreTestService.onDestroy` 用 `CoroutineStart.UNDISPATCHED` 启动清理协程后才
  `serviceScope.cancel()`——否则"launch 后立刻 cancel"可能让清理根本没执行、缓冲结果丢失。
- 完成阶段的异步后处理计入 `CoreTestService.finalizing`：`stopIfIdle` 只有它归零才允许停服务。
- `CoreTestService` / `SubscriptionUpdateService` 首次访问 DAO 前先等 `StorageBootstrap`；
  未就绪时测试请求按取消处理、订阅更新直接跳过，不用冷快照继续。
- UI 侧刷新是**数据库失效驱动**的：`profile_stats` 变化 → `MainRepository.serverPager`
  的 `PagingSource` 失效 → `LazyPagingItems` 自动重取受影响行。
  因此 `MainViewModel.handleServiceEvent` 里 `MeasureConfigSuccess` 是 **no-op**，
  不要再写"批量结果合并刷新"的自定义去抖（那是 MMKV 时代的实现，已删除）。
- 单次写全表会闪屏：写入节流只在写侧做，读侧绝不轮询数据库。

## 失效点
界面取消、`onCleared`、服务停止/未运行/启动成功失败、内核重载、测试服务销毁。
运行状态变化一律释放当前测试请求（`currentTestId = null`），状态栏回落到批量请求状态，
因此不会出现"已停止但仍显示测试中"。

## 已知限制
`SpeedtestManager.socketConnectTime` 是阻塞调用，不参与协作取消，
取消后可能浪费一次探测（已有默认 1.5s 超时）；改为可取消实现前请勿去掉超时。
