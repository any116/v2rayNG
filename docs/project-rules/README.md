# 项目级规范索引

本目录是 v2rayNG 的强制规范库。写代码前按下表对照阅读；
**规范与源码冲突时以源码为准**，并在同一次改动里修正规范。

## 文件一览

| 文件 | 覆盖领域 | 什么时候必须读 |
| --- | --- | --- |
| [`architecture-rules.md`](architecture-rules.md) | 分层、包归属、MVVM/SSOT/UDF 落地、Activity 职责边界 | 新增屏、挪文件、决定"这个类该放哪"时 |
| [`repository-rules.md`](repository-rules.md) | Repository、Room 3 DAO/Entity、SettingsStore、线程、可测试性 | 新增数据访问逻辑、加 Repository / DAO 方法时 |
| [`coroutine-flow-rules.md`](coroutine-flow-rules.md) | `launch` 包装、Scope、Job 管理、Flow 位置、Paging 3 数据链、去抖与串行化 | 写任何协程、Flow 或分页时 |
| [`hilt-rules.md`](hilt-rules.md) | Hilt 模块、Scope、`@Inject` 约束、EntryPoint、Worker 注入 | 加依赖、动 `di/`、加 ViewModel/Worker 时 |
| [`service-ipc-rules.md`](service-ipc-rules.md) | 多进程、广播、`MSG_*` 常量、Worker、Receiver、Glance 组件、通知 | 动 `service/`、`receiver/`、`core/` 时 |
| [`connection-test-lifecycle.md`](connection-test-lifecycle.md) | 连接测试 `requestId` 生命周期与失效点 | 改测速 / 延迟测试时 |
| [`compose/structure.md`](compose/structure.md) | 目录/文件/函数命名、参数契约、脚手架用法 | 写任何 Composable 时 |
| [`compose/state-events.md`](compose/state-events.md) | UiState/Action/Event 建模、Dialog 宿主、表单与结果回传 | 设计一屏的状态时 |
| [`compose/theme-styles.md`](compose/theme-styles.md) | 颜色、尺寸、字符串、图标、图片、动画 | 用颜色/尺寸/资源时 |
| [`compose/performance.md`](compose/performance.md) | 重组防范、稳定性、列表与 Paging、副作用、度量 | 写列表、发现卡顿、Review 性能时 |
| [`compose/navigation-preview.md`](compose/navigation-preview.md) | AppRoute、BaseResult、Preview 规范 | 加导航目标或 Preview 时 |
| [`compose/accessibility.md`](compose/accessibility.md) | contentDescription、语义合并、触控目标 | 加图标按钮/可点击行时 |
| [`compose/testing.md`](compose/testing.md) | 测试分层、DAO/Paging 测试、可测边界、模板 | 写测试时 |
| [`compose/review-checklist.md`](compose/review-checklist.md) | 提交前自查 + Review 清单 | 每次提 PR 前、每次 Review 时 |
| [`archive/README.md`](archive/README.md) | 历史迁移计划存档索引（Hilt / Room 3 / View 收尾） | 查阅或收尾历史迁移计划时 |

## 四条不可协商的底线

1. **SSOT**：一屏一个 `StateFlow<UiState>`，由 `BaseViewModel` 持有，
   `setState { }` 是唯一写入口。同一份数据不得在 State 与 Composable 局部状态里各存一份。
   大列表不进 `UiState`，走 Paging 3 的 `Flow<PagingData<T>>` 切片（见 §3 与 `compose/performance.md`）。
2. **UDF**：UI 只能通过 `onAction(action)` 与 ViewModel 通信。
   Composable 里不得出现除 `onAction` 之外的 ViewModel 成员调用
   （唯一例外：`BaseScreen` / 槽位内对 `viewModel.uiState`、`viewModel.isLoading`、
   `viewModel.events` 的**读取型**订阅）。
3. **分层**：`ui/` → `data/repository/` → `data/`（Room 3 DAO）/ `handler/` → 网络 / 内核。
   ViewModel 不得 import `handler/`、`core/`、`service/`、`data/*Dao`、`android.content.Context`。
4. **唯一持久层**：Room 3（`androidx.room3`）是唯一可写持久层。
   禁止新增 `SharedPreferences` / `DataStore` / Room 2 / MMKV 写入。
   仅 `data/legacy/MmkvLegacyReader.kt` 允许**只读**访问旧 MMKV 目录（一次性导入）。

## 技术基线（改依赖前先核对）

| 项 | 版本 |
| --- | --- |
| Kotlin / AGP | 2.4.20 / 9.4.1（KSP 2.3.11） |
| Compose BOM / Material3 | 2026.09.00 / 1.5.0-alpha28（显式覆盖 BOM） |
| Hilt / androidx.hilt | 2.60.1 / 1.4.0 |
| Room 3 / SQLite driver | 3.0.3 / sqlite-bundled 2.6.2 |
| Paging | 3.5.1 |
| Glance | 1.3.0-alpha02 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 |

全部版本收敛在 `V2rayNG/gradle/libs.versions.toml`，`build.gradle.kts` 一律用 `libs.xxx`。
