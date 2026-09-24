# 最小质量判定 Task 3：规则政策确认

日期：2026-09-24。依据：[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[技术设计](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md)、[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)。

## Owner 决定与范围

Owner 对三项具体政策回复“接受这三项政策并实施 Task 3”：使用 Fact Catalog v2 保留 Traceability 置信度等级；`requiredIssueRefs` 由已发布 Rule Set 显式给出；演示 Case 的 `PASS` 不阻断，`FAIL`、`BLOCKED`、`SKIPPED`、`ERROR`、`TIMEOUT` 阻断，缺失或未终结 Result 使 Evaluation `ERROR`。该回复授权实施 Task 3，不是规则发布、产品或里程碑验收。

## 实施边界

Task 3 只交付目录绑定、纯规则求值、聚合、矩阵测试和版本化演示规则。输入引用归属、已发布 Rule Set 与 `selectedCaseRefs` 非空校验、Evidence 完整性、数据库作业及真实串联仍由后续任务在正式边界处理。不得用单元测试事实冒充真实输入验收；不改变 v1 Catalog、Core Contract 或现有模块摘要。

这三项政策按现有版本化目录和规则集来源落实，未提出冻结架构语义变更。TDR-026 的其余评审与 V0.2 Architecture Review 状态仍按原治理执行；本记录不代录 TDR 全文接受或 Owner Gate 批准。
