# Compose 规范 · 测试

## 1. 现实约束

本仓库 CI 不跑测试，仪器测试基本未使用（只有 espresso 依赖声明，`androidTest` 为空）。
因此策略是：**把值得测的逻辑挤出 Composable，用 JVM 单元测试覆盖。**

依赖（`app/build.gradle.kts`）：JUnit4 + `mockito-inline` + `mockito-kotlin` +
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
  用 `ImporterFixtures`（`app/src/test/.../data/ImporterFixtures.kt`）造 `LegacySnapshot`，
  验证 `LegacyImporter` 的计数与字段、`GROUP_ORDER_TRIGGERS` 的 `groupSortOrder` 同步、
  `dedupeKey` 回填、`deleteProfiles` 三表一致。
- **分页**：用 `androidx.paging:paging-testing`：
  ```kotlin
  val items = profileDao.pageServers("", "").asSnapshot()
  assertEquals(listOf("a", "b"), items.map { it.guid })
  ```
  也可断言 `LoadState`（首屏 / append / 空结果）。
- `SettingKinds` / `SettingsStore` 的 key→kind 映射要有一个"覆盖全部 `PREF_*` / `CACHE_*`"的
  守卫测试：任何常量既不在 kind 表里、也不是字符串，就应报错。

## 6. 不测什么

- 不测 `BaseScreen` / `BaseViewModel` 这类基座的框架行为（改动它们时人工验证）。
- 不测 Compose 的布局与像素。
- 不测 Room 自身生成的代码。
- 不为覆盖率而给 getter/setter 写测试。

## 7. 完成标准

提交前本地必须通过：

```bash
cd V2rayNG
./gradlew :app:compileFdroidReleaseKotlin
./gradlew test
```
