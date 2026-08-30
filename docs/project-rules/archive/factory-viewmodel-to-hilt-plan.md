# Factory ViewModel 到 Hilt 迁移计划

状态：待实施。

本计划是 MMKV → Room 3/Paging 3 生产迁移的强制前置条件。

## 1. 目标

使用 Hilt 替换生产代码中的 BaseViewModelFactory 和 baseViewModels。

保留：
- BaseActivity。
- BaseScreen。
- BaseViewModel/BaseEditViewModel。
- BaseUiState/BaseAction/BaseEvent。
- AppRoute/BaseResult。
- 当前持久化格式和服务协议。

Hilt 迁移不同时改写数据库或导航架构。

## 2. 当前现状

BaseViewModelFactory 接收：

`(Application, SavedStateHandle) -> ViewModel`

AboutActivity 当前在 lambda 内创建 AboutRepository 和 AboutViewModel。

AngApplication：
- 初始化 MMKV。
- 初始化语言和设置。
- 初始化 WorkManager。
- 刷新主题。

MainRepository：
- 构造时注册 Receiver。
- 发送客户端注册消息。
- 实现 Closeable。
- 当前由 MainViewModel.onCleared 关闭。

这些生命周期行为是迁移重点。

## 3. H0：依赖与实例清单

扫描：
- baseViewModels。
- ViewModelProvider.Factory。
- viewModels 的自定义 factory。
- 手工创建 ViewModel。
- Repository 构造点。
- object Repository/Manager。
- close、registerReceiver、unregisterReceiver。
- Worker 和 WorkManager 初始化。
- SavedStateHandle 与 EditFormSaver。

清单必须逐项记录：
- 创建者。
- 使用者。
- 当前生命周期。
- 是否注册外部资源。
- 是否跨进程。
- 测试替换方式。
- 迁移目标 scope。

## 4. H1：接入 Hilt 基建

### 4.1 Gradle

版本目录增加：
- Dagger Hilt 插件。
- hilt-android。
- hilt-compiler。
- KSP 插件。
- 按需增加 hilt-work 与对应处理器。
- Hilt Android 测试依赖。

具体版本在接入时按当前 Kotlin/AGP/KSP 组合验证，不盲目复制其他项目。

### 4.2 Application

AngApplication 添加 @HiltAndroidApp。

保留现有初始化顺序。
特别检查：
- Application 字段注入何时可用。
- attachBaseContext 不访问尚未注入的依赖。
- 多进程都会创建各自 Application 和 Hilt 图。
- MMKV 初始化早于依赖访问。
- 语言和主题冷启动不闪烁、不改变默认值。

### 4.3 基础模块

新增目标 di 包，提供：
- IO/Default dispatcher 的 qualifier。
- 现有共享 HTTP 客户端。
- 必要的 Application Context。
- 经评审需要接口绑定的数据能力。

不要把所有现有 object 包装成另一个 Singleton 后宣称完成依赖隔离。

## 5. H2：试点 About

AboutActivity 添加 @AndroidEntryPoint。

AboutViewModel：
- 添加 @HiltViewModel。
- 构造函数添加 @Inject。
- 继续继承 BaseViewModel。
- 保留原 Action 和 Event 行为。

AboutRepository 使用构造注入。
需要 Application 的既有读取可暂时保留，后续按收益拆成 asset reader。

Activity 改为标准 `by viewModels<AboutViewModel>()`。

此项目当前是 Activity 作用域，不需要为了 Hilt 增加 Navigation Compose。
也不要求所有 Screen 改成内部调用 hiltViewModel。

验收：
- 冷启动。
- About 页面重建。
- 翻译名单。
- 外链。
- 开源许可入口。
- 事件只执行一次。

## 6. H3：编辑器迁移

覆盖：
- ServerEdit。
- SubEdit。
- RoutingEdit。
- UserAssetUrl。
- 其他使用 BaseEditViewModel 的页面。

SavedStateHandle 由 Hilt ViewModel 创建机制提供。

保留：
- AppRoute extra 名称。
- EditFormSaver 键名与序列化。
- 新建/编辑分支。
- 当前运行配置标识。
- 保存返回结果。
- 删除确认。
- 重复提交保护。

不得在迁移时重置草稿 key 或改变恢复优先级。

必须测试“系统保存状态后重建”，不是只测试配置变化。

## 7. H4：普通页面迁移

建议批次：
1. About、CheckUpdate。
2. Subscription、UserAsset。
3. Settings、Routing。
4. AppPicker、PerAppProxy、Scanner。
5. Backup、Logcat。
6. Main。
7. Shortcut、UrlScheme 等特殊入口。

实际分批按引用关系调整。

每个 Feature 的 Activity、ViewModel、Repository 和测试在同一批完成。

## 8. MainRepository 的 Scope 决策

默认保持页面/ViewModel 所有权：
- 采用 @ViewModelScoped 或明确不共享的绑定。
- MainViewModel 继续负责 close。
- 同一 ViewModel 内只创建一份监听实例。

不能同时：
- 把 MainRepository 设为 Singleton。
- 又在任一页面销毁时关闭该实例。

如果要改为共享服务状态仓库：
1. 单独拆出共享服务连接。
2. 对订阅者使用引用计数或明确连接生命周期。
3. 页面 Repository 不再关闭共享连接。
4. 另立行为测试与评审。

本次 Hilt 迁移优先保持原所有权，不顺带完成该重构。

## 9. Theme 与 Settings

ThemeManager/ThemeRepository 的 object 接缝不得贸然改变：
- Application 启动依赖它。
- Compose 订阅现有流。
- 设置页触发更新。

可先通过注入适配器桥接。
在所有调用者迁移后再统一实例来源。

不产生两份主题 StateFlow，避免页面与 Widget 读取不同状态。

## 10. WorkManager 集成

### 10.1 现有约束

当前已经：
- 手动初始化 WorkManager。
- 移除默认 WorkManagerInitializer。
- 设置默认后台进程。
- 声明 RemoteWorkManagerService。

### 10.2 接入方式

若迁移现有 Worker：
- 使用 @HiltWorker。
- Context、WorkerParameters 采用 assisted 参数。
- 其他依赖从支持的 Hilt 组件获取。
- HiltWorkerFactory 加入既有 Configuration。
- 保留当前进程配置。
- 保证每进程初始化语义明确且不重复。

可以保留手动初始化方式。
若改成 Configuration.Provider，必须同时完整调整旧路径，不能两种初始化并存。

### 10.3 验证

- 应用主进程未打开时后台任务可执行。
- 后台进程被杀后恢复。
- 订阅任务去重。
- 重试和取消。
- 更新后旧工作实例能继续创建。
- Widget 引入 Glance 后所需任务可正常创建。

## 11. 平台组件

Activity、Service、Receiver 按 Hilt 实际支持方式接入。

系统创建的非标准对象使用有限的 EntryPoint 边界。
EntryPoint 只放在系统入口适配层，不成为普通 Repository 的 Service Locator。

Widget 不注入 Activity ViewModel。
进程间不能传递注入对象。

## 12. 测试改造

### 单元测试

直接构造 ViewModel，注入 Fake。
大部分单元测试不启动 Hilt 容器。

### 集成测试

新增 Hilt Android 测试基建：
- 合适的测试 Application。
- Hilt 测试 runner。
- HiltAndroidRule。
- 测试替换模块。

当前 runner 的修改与测试配置一起提交。

### 回归重点

- Application 初始化。
- 保存状态恢复。
- Repository 实例数量。
- Receiver 注册/释放。
- BaseEvent 单次消费。
- WorkManager 多进程。
- 页面退出不会关闭仍被其他页面使用的共享依赖。

## 13. 分批回滚

未迁移页面继续使用原 Factory。

已迁移页面回滚时同时恢复：
- Activity 创建方式。
- ViewModel 构造。
- Repository binding。
- scope。
- 测试。

不保留一个页面两条创建路径作为长期“保险”。

迁移期间不改变 MMKV 格式，因此无需数据回滚。

## 14. H5：清理与门禁

全源码搜索确认：
- 无生产 baseViewModels 调用。
- 无自定义 Factory 遗留。
- 无手工创建 Hilt ViewModel。
- 无为迁移临时加入的重复 singleton。
- 资源型 Repository 生命周期明确。

然后删除：
- BaseViewModelFactory。
- baseViewModels 扩展。
- 不再使用的 import 和临时 binding。

## 15. Hilt 完成验收单

以下全部满足后，才允许启动 Room 3/Paging 3 生产切换：

- [ ] 所有生产 ViewModel 使用 Hilt。
- [ ] 所有特殊入口验证完成。
- [ ] 编辑器草稿恢复通过。
- [ ] MainRepository 生命周期通过。
- [ ] WorkManager 多进程初始化通过。
- [ ] Widget/Receiver 所需依赖入口明确。
- [ ] 全部编译、JVM 测试、lint 通过。
- [ ] 关键真机流程通过。
- [ ] Factory 清理完成。
- [ ] 项目规范更新为 Hilt 默认方式。

仅仅 About 试点成功不构成前置条件完成。
