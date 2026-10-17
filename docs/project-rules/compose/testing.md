# Compose 规范 · 测试

## 1. 现实约束

本仓库 CI 不跑测试，仪器测试基本未使用（只有 espresso 依赖声明，`androidTest` 为空）。
因此策略是：**把值得测的逻辑挤出 Composable，用 JVM 单元测试覆盖。**

依赖（`app/build.gradle.kts`）：JUnit 6 + `mockito-inline` + `mockito-kotlin` +
`kotlinx-coroutines-test` + `androidx.room3:room3-testing` + `androidx.paging:paging-testing`
+ `androidx.sqlite:sqlite-bundled-jvm`（JVM 里开内存 SQLite）。
测试目录 `app/src/test/java/`。

## 2. 分层与优先级

| 优先级 | 测什么 | 现有样例 |
| --- | --- | --- |
| P0 | 纯函数：协议解析、字符串/列表扩展、工具 | `ShadowsocksFmtTest`、`UtilsTest`、`HttpUtilTest`、`ListExtTest`、`UrlSchemeMergeFragmentTest`、`UrlSchemeSourceTest`、`ZipUtilTest`、`RootProxyManagerTest` |
| P0 | UI 纯逻辑函数：菜单项计算、校验、可见性判断 | `MainImportMenuTest` |
| P1 | ViewModel reducer：给定 Action，State 如何变 | `AppPickerViewModelTest` |
| P1 | 服务内部执行/限流逻辑 | `RealPingExecutionLimiterTest`、`DialerWebviewServiceTest` |
| P2 | DAO / 迁移 / 分页：用真库 + `PagingSource` 断言 | `data/ImporterFixtures` + 新的 DAO 测试 |
| P3 | Composable UI 测试 | 目前不做（`ScannerActivityTest` 只测非 UI 逻辑） |

## 3. 让逻辑可测的写法

把"根据条件算出该显示哪些菜单项"这类逻辑抽成**顶层纯函数**，而不是写在 Composable 里：

```kotlin
fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
): List<ServerMenuAction> = …
```

Composable 只负责渲染 `serverMenuActions(...)` 的结果。测试就变成纯断言：

```kotlin
@Test
fun complexShareMenuContainsOnlyFullContent() {
    assertEquals(
        listOf(ServerMenuAction.ShareFullContent),
        serverMenuActions(isComplexProfile = true, includeManagementActions = false),
    )
}
```

同理适用于：表单校验（`ServerValidator`）、路由规则解析、延迟着色阈值、状态到文案的映射
（映射本身可测，`stringResource` 那层不测）。

## 4. ViewModel 测试模板

Repository 声明为 `open class` + `open fun`，直接 mock：

```kotlin
class XxxViewModelTest {

    private val repo = mock<XxxRepository> {
        onBlocking { load() } doReturn listOf(item("a"), item("b"))
    }

    @Test
    fun selectingItem_updatesState() = runTest {
        val vm = XxxViewModel(repo, SavedStateHandle())
        vm.onAction(XxxAction.SelectItem("a"))
        assertEquals("a", vm.uiState.value.selectedId)
    }
}
```

- 需要控制调度时用 `StandardTestDispatcher`（`kotlinx-coroutines-test` 已在依赖里），
  Repository 注入 `@IoDispatcher` 时显式传同一个 test dispatcher。
- 断言只看 `uiState.value` 与对 repo 的 `verify`，不要断言内部私有字段。
- 幂等性要测（`AppPickerViewModelTest` 就是防止二次 `initialize` 抹掉用户改动）。
- ViewModel 现在带 `@Inject constructor`，但**单元测试直接 new，不启动 Hilt 容器**。

## 5. DAO / 迁移 / 分页测试

- **DAO / 迁移**：用 `room3-testing` 在 JVM 上开内存库，driver 用
  `sqlite-bundled-jvm` 的 `BundledSQLiteDriver`：
  ```kotlin
  val db = Room.inMemoryDatabaseBuilder<AppDatabase>()
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.Unconfined)
      .build()
  ```
  用 `ImporterFixtures`（`app/src/test/.../data/ImporterFixtures.kt`）构造测试数据库，
  验证 `GROUP_ORDER_TRIGGERS` 的 `groupSortOrder` 同步、`dedupeKey` 回填、
  `deleteProfiles` 三表一致。
- **分页**：用 `androidx.paging:paging-testing`：
  ```kotlin
  val items = profileDao.pageServers("", "").asSnapshot()
  assertEquals(listOf("a", "b"), items.map { it.guid })
  ```
  也可断言 `LoadState`（首屏 / append / 空结果）。

## 6. 不测什么

- 不测 `BaseScreen` / `BaseViewModel` 这类基座的框架行为（改动它们时人工验证）。
- 不测 Compose 的布局与像素。
- 不测 Room 自身生成的代码。
- 不为覆盖率而给 getter/setter 写测试。

## 7. state-based 文本边界回归

以下 JVM 用例直接使用生产 helper、`TextFieldState` 或 Saver，不需要启动 Composition：

- `TextEditorStateTest` 及各 feature 的 `*EditorStateTest`：全部字段映射、无 collector 的最终快照、
  保存后续编辑隔离、一次初始化、未加载状态、定向选区/flags/chain IDs 和顺序的 Saver 恢复。
- `ServerEditSnapshotTest` / `SubEditSnapshotTest` / `RoutingEditSnapshotTest` /
  `UserAssetUrlSnapshotTest`：Action 捕获当前表单，异步校验/保存期间第二次点击不替换首次快照，
  校验结果绑定提交值，加载初值不变、失败加载不能写入、证书/AppPicker 只修改目标字段。
- `InputFieldStateSaverTest`：多字段文本和正反选区的独立快照、可序列化 payload 往返。
- `WebDavInputSnapshotTest` / `BackupViewModelSnapshotTest`：单次 SaveWebDav Action 提交全部最终输入、
  规范化及密码空白保留，加载前不能打开空初值或保存，连续确认按各自快照串行写入。
- `SearchInputOwnershipTest` 及搜索 ViewModel 测试：共享 `SearchFieldController` 的单向查询、
  关闭前清空、丢弃旧查询、恢复选区，非空去抖和空串立即传播。字段内容不从已应用的查询结果回灌。
- `FormPagedDropdownQueryTest`：同文本 pick 抑制 pending 查询、pick 后编辑/Flow 合流、取消、
  250ms 边界及查询元数据恢复。raw 文本已不需要回写 debounce 或同步桥测试。

这些测试不能替代真实 Composition/IME/Android 恢复验证。允许设备验证时还需手工检查：

1. 连续输入、删除、撤回后立即保存，确认业务值和最后可见文本一致；中间选区输入不跳光标。
2. Server 原始文本输入后立即保存，确认提交最后可见文本；未保存退出不回写。首次加载和
   文档切换不能串用草稿；证书请求期间编辑其它字段，响应不得覆盖这些编辑。
3. 多字段及密码弹窗输入后旋转，确认草稿和选区保留、密码隐藏；最后一次输入后立即确认。
   Android 进程恢复需另行验证，不能以 JVM 序列化测试代替。
4. 分页下拉输入后在 debounce 内选中同文本选项，确认不追加筛选、不重新展开；立即保存读取
   选中的真实值。恢复时文本及已应用的筛选元数据都要一致。
5. 加载未完成或失败时不可保存空表单；加载迟于恢复时，草稿、flags、chain 身份和顺序不变。

## 8. 完成标准

提交前本地必须通过：

```bash
cd V2rayNG
./gradlew :app:compileFdroidReleaseKotlin
./gradlew test
```
