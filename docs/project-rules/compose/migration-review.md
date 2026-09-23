# Compose 迁移与 Review

## 1. 当前边界

主要页面已是 Compose；Hilt、Room 3、Paging 3、Glance 桌面组件、AboutLibraries 许可页
均已完成迁移。**不再编写从 RecyclerView / Fragment 大规模迁移的通用路线图。**

当前仍保留的非 Compose 边界（允许，且不应无理由扩大）：

- `AppCompatActivity`（`BaseActivity`）与 manifest 注册。
- Android 字符串、图标与主题资源。
- 系统需要的 `appwidget-provider` XML 与 `res/layout` 的 Glance/RemoteViews 资源。
- `GlanceAppWidget`（`ui/widget/SwitchWidget.kt`）——它不是普通 Compose，
  不复用 `MaterialTheme`，只共享业务状态与格式化逻辑。
- 必要的平台互操作。

仍待收尾的历史计划见 [`archive/`](archive/README.md)；收尾时按计划里的验收清单更新状态。

## 2. 允许保留

- AppCompatActivity / Manifest。
- Android 字符串、图标和主题资源。
- 系统需要的 `appwidget-provider` XML。
- 必要的平台互操作。
- 未迁移完成的兼容资源。

Compose 化不等于删除所有 Android XML。文档要区分"当前实现"与"迁移目标"。

## 3. 修改范围

按 Feature 或具体基础设施迁移。
不要把 View 收尾、Hilt、数据库和导航重写放进同一个大改动。

## 4. Review Checklist

- [ ] 页面沿用 BaseActivity / BaseScreen。
- [ ] 业务输入通过 BaseAction。
- [ ] 内容组件不创建 Repository。
- [ ] ViewModel 不保存 Activity / Context。
- [ ] Repository 通过 Hilt 构造注入（无手工 new）。
- [ ] BaseEvent 单消费者。
- [ ] 平台事件有明确处理路径。
- [ ] 取消语义正确。
- [ ] 保存失败保留输入。
- [ ] 编辑器结果不重复提示。
- [ ] 状态恢复范围明确（`SavedStateHandle` / `EditFormSaver` 的 dirty 门）。
- [ ] 系统栏和 IME 没有重复避让（`contentWindowInsets = WindowInsets(0)` 前提下的自处理）。
- [ ] 列表 key 稳定；Paging 列表用 `itemKey` / `itemContentType`。
- [ ] 稳定性注解符合真实对象图。
- [ ] 图片沿用 Coil（coil3）。
- [ ] 固定文案资源化。
- [ ] 无障碍和 RTL 可用。
- [ ] Preview 不访问真实存储 / 数据库。
- [ ] 测试覆盖修改行为。
- [ ] 数据和日志已脱敏。
- [ ] 文档区分当前实现与迁移目标。

## 5. 自动检查

现有 Android lint 与编译检查按实际工程执行（CI 不跑 lint / test，本地必须自己跑）。
未来自定义规则优先检测：

- UI 新增直接 Room / `Prefs` 读写。
- Composable 内阻塞 IO。
- Feature 中新增硬编码用户文案。
- 重复事件收集。
- 错误的 DI 创建路径（手工 new ViewModel / Factory）。
- ViewModel 直接 import DAO / handler。

必须随规则提交检测实现、测试、白名单和存量基线。
不能只在文档声明"CI 硬卡"。

## 6. 审查报告

分别输出：

1. Standards：规范符合性。
2. Spec：需求和验收符合性。

每条问题包含位置、影响和建议。
无问题时明确说明检查范围，不笼统宣称全仓库无问题。
