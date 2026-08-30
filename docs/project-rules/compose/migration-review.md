# Compose 迁移与 Review

## 1. 当前边界

主要页面已是 Compose。

当前迁移重点：
- About 开源许可 WebView。
- RemoteViews 桌面组件。
- 预览与测试隔离。
- 现有状态和平台接缝的收口。

不再编写从 RecyclerView、Fragment 大规模迁移的通用路线图。

## 2. 允许保留

- AppCompatActivity。
- Manifest。
- Android 字符串、图标和主题资源。
- 系统需要的 appwidget-provider XML。
- 必要的平台互操作。
- 未迁移完成的兼容资源。

Compose 化不等于删除所有 Android XML。

## 3. 修改范围

按 Feature 或具体基础设施迁移。
不要把 View 收尾、Hilt、数据库和导航重写放进同一个大改动。

## 4. Review Checklist

- [ ] 页面沿用 BaseActivity/BaseScreen。
- [ ] 业务输入通过 BaseAction。
- [ ] 内容组件不创建 Repository。
- [ ] ViewModel 不保存 Activity/Context。
- [ ] BaseEvent 单消费者。
- [ ] 平台事件有明确处理路径。
- [ ] 取消语义正确。
- [ ] 保存失败保留输入。
- [ ] 编辑器结果不重复提示。
- [ ] 状态恢复范围明确。
- [ ] 系统栏和 IME 没有重复避让。
- [ ] 列表 key 稳定。
- [ ] 稳定性注解符合真实对象图。
- [ ] 图片沿用 Coil。
- [ ] 固定文案资源化。
- [ ] 无障碍和 RTL 可用。
- [ ] Preview 不访问真实存储。
- [ ] 测试覆盖修改行为。
- [ ] 数据和日志已脱敏。
- [ ] 文档区分当前实现与迁移目标。

## 5. 自动检查

现有 Android lint 与编译检查按实际工程执行。

未来自定义规则优先检测：
- UI 新增 MMKV 直接读写。
- Composable 内阻塞 IO。
- Feature 中新增硬编码用户文案。
- 重复事件收集。
- 错误的 DI 创建路径。

必须随规则提交检测实现、测试、白名单和存量基线。
不能只在文档声明“CI 硬卡”。

## 6. 审查报告

分别输出：
1. Standards：规范符合性。
2. Spec：需求和验收符合性。

每条问题包含位置、影响和建议。
无问题时明确说明检查范围，不笼统宣称全仓库无问题。
