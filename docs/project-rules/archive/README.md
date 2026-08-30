# 迁移计划存档

本目录按项目要求保存迁移计划。

“archive”仅代表存放位置，不代表计划已经完成。

| 文件 | 状态 | 前置条件 |
|---|---|---|
| android-view-migration-plan.md | 待实施 | 基于现有 Compose 架构 |
| factory-viewmodel-to-hilt-plan.md | 待实施 | 保持现有业务和数据契约 |
| mmkv-to-room3-paging3-plan.md | 待实施 | Hilt 全部验收通过 |

实施后更新各计划：
- 实际完成范围。
- 未完成项。
- 验证命令与设备。
- 与方案不同的决定。
- 回滚和后续维护结论。

数据迁移不得绕过 Hilt 前置验收。
