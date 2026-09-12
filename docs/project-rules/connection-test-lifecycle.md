# 连接测试请求生命周期

## 身份
两类测试各有唯一 `requestId`（UUID），由 `MainViewModel` 在主线程登记/注销：
`currentTestId`（当前服务器）、`batchTestId` + `batchGroupId`（批量）。
所有服务→UI 的测试消息都带 `requestId`，不匹配即丢弃。

## 消息
| 方向 | key | content |
| --- | --- | --- |
| UI→守护 | `MSG_MEASURE_DELAY`（有序广播） | `requestId` |
| 守护→UI | `MSG_MEASURE_DELAY_RESULT` | `ConnectionTestResponse` |
| 守护→UI | `MSG_MEASURE_DELAY_CANCELED` | `requestId` |
| UI→测试服务 | `MSG_MEASURE_CONFIG_START` / `_CANCEL` | `TestServiceMessage`（`requestId` 为空的取消＝全部取消） |
| 测试服务→UI | `_NOTIFY` / `_SUCCESS` / `_FINISH` / `_CANCELED` | `TestNotification(requestId, payload)` |

## 当前服务器测试
有序广播确认只在"真的接受并启动测量"时置 `RESULT_OK`；提前返回回取消。
测量协程用 `finally` 兜底：未回结果则补发取消，绝不留悬挂请求。
UI 侧未被处理时由发送方在同一 `serviceEvents` 流补发取消。

## 批量测试
先取消旧请求、快照目标列表，再登记新请求。服务端按工作单元记录所属请求，
`units.remove(unit)` 是原子归属声明——完成与取消只能由声明成功的一方发出。
取消、`onDestroy`、启动时的防御覆盖都逐请求回 `_CANCELED`，
每个请求独立 `runCatching`，单个失败不阻断其余。

## 失效点
界面取消、`onCleared`、服务停止/未运行/启动成功失败、内核重载、测试服务销毁。
运行状态变化一律释放当前测试请求，状态栏回落到批量请求状态，
因此不会出现"已停止但仍显示测试中"。

## 已知限制
`SpeedtestManager.socketConnectTime` 是阻塞调用，不参与协作取消，
取消后可能浪费一次探测（已有 1s 超时）；改为可取消实现前请勿去掉超时。
