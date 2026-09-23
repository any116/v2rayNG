# 迁移计划存档

本目录按项目要求保存迁移计划与其实施结论。

“archive”仅代表存放位置。**下面三份计划的实施主体均已完成**，正文保留原始方案，
每份文件头部补了「实施结果」小节：实际完成范围、未完成项、验证方式、与方案的差异、回滚结论。

| 文件 | 状态 | 实施结果摘要 |
|---|---|---|
| factory-viewmodel-to-hilt-plan.md | 已完成 | 全部生产 ViewModel 改为 `@HiltViewModel` + `@Inject`；无生产 `baseViewModels` 调用 |
| mmkv-to-room3-paging3-plan.md | 已完成 | Room 3 承载全部结构化数据；主列表 Paging 3；`MmkvManager` 已删除 |
| android-view-migration-plan.md | 已完成（Widget 增强项未做） | About 许可页改 AboutLibraries M3；桌面组件改 Glance（`:bg`） |

维护约定：

- 新增迁移计划时，先写目标、门禁、回滚，再写实施步骤。
- 实施后不要删除计划，改为在头部补「实施结果」，记录：
  实际完成范围、未完成项、验证命令与设备、与方案不同的决定、回滚与后续维护结论。
- 后续若发现计划与源码冲突，**以源码为准**，并在同一次改动里修正计划。
