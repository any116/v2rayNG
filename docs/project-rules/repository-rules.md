# 数据层规范（Room 3 / Repository / handler）

## 0. 唯一持久层

结构化数据全部存在 **Room 3**（`androidx.room3`）里，数据库文件 `v2rayng.db`，
`AppDatabase` 版本 `1`，schema 导出到 `app/schemas/`（随版本管理提交）。

| 表 | 实体 | 说明 |
| --- | --- | --- |
| `profiles` | `ProfileItem` | 配置元数据 + 协议字段；列表查询走轻量投影，不载入全部列 |
| `profile_stats` | `ServerAffiliationInfo` | 测速延迟 |
| `profile_raw` | `ProfileRaw` | CUSTOM 配置原文（单独表，无外键） |
| `subscriptions` | `SubscriptionItem` | 订阅 / 分组 |
| `assets` | `AssetUrlItem` | 用户资源 URL |
| `routing_rules` | `RulesetItem` | 路由规则（含顺序） |
| `settings` | `SettingsEntry` | `key/value/kind` 标量偏好 |

禁止：新增 `SharedPreferences`、`DataStore`、Room 2、任何第二种可写持久化。
MMKV 只允许出现在 `data/legacy/MmkvLegacyReader.kt`，且**只读**（一次性旧数据导入）。

## 1. BaseRepository

所有 Repository 继承 `data/repository/BaseRepository`，它只做一件事：线程收敛。
构造参数接 `@IoDispatcher io: CoroutineDispatcher`（默认值可保留在抽象类自身，
具体子类必须显式注入，见 `hilt-rules.md` §2）。

`BaseRepository` 提供三个工具方法：

- **`withIO`**：单次阻塞调用，异常上抛（由 ViewModel 统一处理）。
- **`runIO(fallback) { ... }`**：带降级的调用，内部捕获异常并记录日志，返回 fallback，
  适用于可预期失败且允许返回默认值的场景。
- **`flowIO`**：为 `Flow` 切换线程（`flowOn(io)`）。

```kotlin
open class XxxRepository @Inject constructor(
    private val app: Application,
    private val profileDao: ProfileDao,
    @IoDispatcher io: CoroutineDispatcher,
) : BaseRepository(io) {
    open suspend fun load(): XxxData = withIO { profileDao.findByGuid(guid) }
}
```

- **凡是会阻塞的调用（Room、文件、网络、`PackageManager`、Root shell）必须在 `withIO { }` 内。**
- 不阻塞的极轻量读取（如一次 `settings.bool(...)` 快照读）可以做成同步 `fun`，
  供 ViewModel 在 `init` 里构造初始状态用（样板：`MainRepository.selectedGroupId()`、
  `confirmRemove()`、`doubleColumnDisplay()`）。这类方法必须明确无 IO 风险。
- 不要在 Repository 里自己 `withContext(Dispatchers.IO)`，用 `withIO`。
- 不要在 Repository 里 `launch`。Repository 没有 scope；需要后台任务由 ViewModel 发起。

## 2. Repository 的四类职责

1. **门面**：把 DAO / handler 的 API 收口成 `suspend`，屏蔽表结构与 `SettingsStore` 细节。
2. **投影/聚合**：Room 返回轻量 `ServerRowProjection`，Repository 把它组装成 UI 直接能画的
   `ServerRowItem`，包括 `AngConfigManager.generateDescription(...)` 与
   `protocolDescription(...)` 这类展示串拼装。**展示串在 Repository 拼，不在 Composable 拼。**
3. **分页**：大列表只暴露 `Flow<PagingData<T>>`，内部用 `Pager` 包 DAO 的 `PagingSource`。
   样板 `MainRepository.serverPager(groupId, query)`：
   - `PagingConfig(pageSize = 40, initialLoadSize = 80, prefetchDistance = 20,
     enablePlaceholders = true, jumpThreshold = 240)`；
   - `enablePlaceholders = true` 让 `LazyColumn` 知道 `itemCount`，
     支持滚动到尚未加载的区域（定位选中项）；
   - `pagingSourceFactory = { profileDao.pageServers(groupId, escaped) }`；
   - 搜索串在进入 DAO 前必须 `trim().normalizeLike()`（小写 + LIKE 转义）。
   - 计数器用小页 `PagingConfig(enablePlaceholders = false)`（样板：`ServerRepository.chainCandidatePager`）。
4. **事件源**：需要监听跨进程事件时，在 Repository 内注册 receiver，对外只给
   `SharedFlow`（`replay = 0`、`extraBufferCapacity = 64`、`onBufferOverflow = DROP_OLDEST`）。
   样板 `MainRepository.serviceEvents`。Repository 声明为 `@Singleton`，
   由 Hilt 保证进程内唯一注册（不要自己 `Closeable` 反注册）。

## 3. DAO / 查询规范

- 所有 DAO 在 `data/AppDao.kt`（按域分为 `ProfileDao` / `SubscriptionDao` / `AssetDao` /
  `RoutingDao` / `SettingsDao`），用 `@Dao` 标注；Paging 返回类型需要
  `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)`。
- 列表查询**只读轻量投影**，不要 `SELECT *` 把 `profiles` 全表读进内存。
  投影是一个普通 `data class`（如 `ServerRowProjection`），列名与 SQL 别名一一对应。
- 搜索语义是 **SQL `LIKE`**（不区分大小写），**不是正则**：先 `normalizeLike()`，
  再 `LOWER(col) LIKE '%' || :query || '%' ESCAPE '\'`。
  搜索字段固定为 `remarks / description / server`，改语义必须先写"搜索真值测试"。
- 计数、去重、定位都用 SQL 完成，不要在 Kotlin 里遍历：
  - 计数：`observeCounts` / `observeTotalCount`（`LEFT JOIN` 而非相关子查询，
    否则空分组会消失）；
  - 定位：`indexOf` 用 `ROW_NUMBER() OVER (...)`（依赖 BundledSQLiteDriver，SQLite ≥ 3.25）；
  - 拖拽邻居：`neighboursAt`（不要用 `LazyColumn` 的 `layoutInfo`，
    placeholders 下 placeholder 行的 key 不是 guid）；
  - 去重：`duplicateGuids`（`dedupeKey` 唯一化，保留最小 `sortOrder`）。
- 排序是**稀疏 `sortOrder`**（步长 `ProfileItem.SORT_STEP = 1024`），
  拖拽用中点插入；相邻间隔不足时 `renormalize(subscriptionId)` 在事务内先读顺序再逐行重写，
  按延迟排序同理（`guidsByDelay` + `sortByDelay`）。**不要用"单条 UPDATE 边写边算"的写法**：
  SQLite 不保证相关子查询读到更新前的排名，实测会产出重复 `sortOrder`、破坏拖拽间距。
  `groupSortOrder` 是订阅顺序的冗余列，由 `data/DatabaseCallbacks.kt` 的
  `GROUP_ORDER_TRIGGERS` 在 SQL 层维护，**不要手写**。
- 写操作优先用 `@Upsert` / `@Insert(REPLACE)`；跨表一致性用 `@Transaction` 组合方法
  （样板：`ProfileDao.deleteProfiles`、`replaceGroup`、`SubscriptionDao.removeWithDefault`）。
  投影删除必须同时清理 `profiles` / `profile_stats` / `profile_raw` 三张表。
- `IN (:list)` 参数受 SQLite 变量上限约束，超过 `ProfileDao.SQLITE_VAR_LIMIT`（900）必须先
  `chunked(...)`。
- 轻量派生列（`dedupeKey`）允许惰性回填：`backfillDedupeKeys` 分批写，避免首次导入时
  在事务里算几千个 SHA-256（ANR 风险）。算法版本存 `SettingsStore.KEY_DEDUPE_ALGO_VERSION`，
  变更时清空重算。

## 4. 设置读取：SettingsStore 与 Prefs

`data/SettingsStore.kt`（`@Singleton`）是标量偏好的**进程内同步快照**：

- 读：`bool / string / int / long / float / stringSet`，纯内存，绝不触库；
  `refresh()` 未跑完时返回 `null`（并打 warn）。
- 写：`putBool / putString / ...` 为 `suspend`，写完 `poke` 本地快照；
  非挂起调用点（locale 交接、widget 状态）用 `setBoolAsync / writeAsync`，
  先 `poke` 再异步落盘。事务内已被别的 DAO 写入的值用 `poke` 只更新内存。
- 就绪：`awaitReady()` 挂起直到首次 `refresh()` 完成（失败也会完成，降级到编码默认值）。
  UI 启动门（`MainActivity`）与 `MainRepository.awaitReady()` 都以它为准。
- 跨进程：`observe(scope)` 订阅 `invalidationTracker.createFlow("settings")`，
  别的进程写入后本进程会 `refresh()`。这是跨进程最终一致，不是强一致。
- 首次启动由 `SettingsDefaults.ENTRIES` + `seedDefaults()` 写入编码默认值。

`data/Prefs.kt` 是给**无法注入的对象单例**（`handler/`、`core/`、`service/` 里的
`object` 与无构造注入的组件）用的同步门面，读写都转发给 `SettingsStore`。
有构造函数注入的类**必须直接接 `SettingsStore`**，不要绕 `Prefs`。

## 5. 可测试性

Repository 声明为 `open class`，需要被 mock 的方法声明 `open`
（样板：`MainRepository`、`ServerRepository` 全量 `open`）。测试用 `mockito-kotlin` 的
`mock<XxxRepository>()` 直接替换，ViewModel 无需启动 Hilt 即可测。

DAO 层另外用 Room 3 的 testing 支持做真库测试：
`testImplementation(libs.androidx.room3.testing)` + `sqlite-bundled-jvm`
（JVM 单元测试里用 `BundledSQLiteDriver` 打开内存库），
Paging 用 `androidx.paging.testing` 的 `LoadState`/`asSnapshot()` 断言。

不要为了"接口纯洁"给每个 Repository 抽 interface——当前唯一一例是
`ThemeStore` ← `ThemeRepository`（因为它要跨 Hilt 内外共享，见 `hilt-rules.md` §4）。

## 6. 新增偏好项的标准流程

1. 在 `AppConfig` 加 `PREF_XXX` 常量；
2. 在 `SettingsRepository` 的 `BoolPref` / `StringPref` 枚举加一项（带默认值）；
   若该项必须在设置页显示默认值，再在 `SettingsDefaults.ENTRIES` 加一条；
3. 若该项影响内核配置（需要重启服务生效），确保 `SettingsChangeManager.isUiOnly(key)`
   返回 `false`；纯 UI 项加进 `uiOnlyKeys` 返回 `true`；
4. 若旧版本已存在该 key，把它登记进 `data/legacy/SettingKinds.kt`
   （`BOOLEAN_KEYS` / `LONG_KEYS` / `INT_KEYS` / `SET_KEYS`），否则旧值会按字符串导入而丢失；
5. 在 `SettingsScreen` 加对应的 `SettingsSwitchItem` / `SettingsListItem` / `SettingsEditItem`。

## 7. 写入串行化

同一 key 的连续写入必须串行，避免"后发先至"。ViewModel 侧用 Job 链
（样板：`SettingsViewModel.persist()`）：

```kotlin
val previous = writeJob
writeJob = launch {
    previous?.join()
    withContext(NonCancellable) { write() }
}
```

需要"页面关闭前必须写完"的场景，在 `exit()` 里 `writeJob?.join()` 之后再 `finishWith(...)`。
单条 SQL UPDATE 已在 DAO 事务内完成排序的（如 `ProfileDao.moveProfileToIndex`），
调用方**不需要**再建 Job 链。

## 8. 错误处理

- Repository 不吞异常，也不弹 toast。可预期失败返回 `null` / 空集合 / 布尔；
  不可预期失败让异常上抛，由 `BaseViewModel.launch` 的 `onError` 统一处理。
  需要降级时用 `runIO(fallback)`。
- 需要记录时用 `runCatching { }.onFailure { LogUtil.e(AppConfig.TAG, "…", it) }`。
- 反注册、关闭、清理这类动作一律 `runCatching` 包住，不能因为清理失败影响主流程。

## 9. 数据库打开与旧数据导入（改数据库前必读）

- 数据库构建在 `di/DatabaseModule.kt`：`BundledSQLiteDriver`、`setQueryCoroutineContext(io)`、
  `enableMultiInstanceInvalidation()`（**每个进程都要开**，否则跨进程 Flow/Paging 不失效）、
  `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`（只覆盖"用户装回旧版"，
  不覆盖升级路径——升级缺 Migration 就该崩，不能静默丢数据）。
- 打开前必须先跑 `LegacyMigrationGate.runIfNeeded(...)`：它同时持有
  **进程内 `Mutex`** 与 **跨进程 `FileLock`**（`files/legacy_import.lock`），
  内层先 `DatabaseIntegrity.verifyOrThrow(app)`（`PRAGMA integrity_check` + `busy_timeout`；
  **检查失败只抛异常，绝不移动/删除数据库文件**——"检查没跑成"不等于"库损坏"），
  再在 `immediateTransaction` 里执行 `LegacyImporter.plan(snapshot)` 生成、`importInto` 执行的计划，
  最后按计划主键校验：导入前统计已有主键数，导入后要求
  `行数增量 = 计划主键数 − 已有主键数` 且所有计划主键都存在，并写 `LEGACY_IMPORT_STATE=done`。
  校验不符会回滚并下次重试；**不要假设目标主键在导入前都不存在**（重试时可能已有种子数据）。
- `AngApplication` 的启动协程串行执行"完整性检查 → 旧数据导入 → 快照刷新 →（仅主进程）播种默认值"，
  全部成功才 `StorageBootstrap.complete()`；任何一步失败都 `fail()` 并把等待方阻塞住。
  读取设置/数据库的入口（服务、Boot/Tasker/Tile/快捷方式、UI）必须先等该屏障，
  失败时显式跳过或报错重试，**禁止用编码默认值继续**。
- 改 schema：改实体后递增 `AppDatabase.version`，提供 `Migration`，
  重新导出 `app/schemas/**` 并提交；`DatabaseModule` 的 `onCreate/onOpen` 回调负责安装
  `GROUP_ORDER_TRIGGERS`，新增触发器要同时在 `DatabaseCallbacks.kt` 与回调里可见。
