# 最小质量判定 Task 4：规则版本与发布 API 工程记录

日期：2026-09-30。依据：[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[规则规范](../11-quality-rule-specification.md)。本记录是工程验证，不代替 Owner 验收或真实规则发布许可。

## 交付与边界

V16 新增规则集版本和规则内容表，保存请求定义、目录/引擎版本、requiredIssueRefs、selectedCaseRefs、内容摘要、作者/审核者、原因、Git 来源、原始 YAML、受限 AST 和 golden fixture 摘要。已发布版本在数据库层拒绝 UPDATE/DELETE，历史版本保留；新版本只能在前版已发布后建立。`createRuleSet` 和 `publishRuleSet` 沿用项目权限、Idempotency、Audit 和 `If-Match`，作者不能审核自己的版本。

当前发布门禁只接受仓库中两条固定演示 YAML，逐项核对打包资源、来源摘要、AST、Fact Catalog v2 绑定，并实际运行版本化 golden cases。其他结构有效的规则可以保存为无 YAML 来源的 Draft，但发布明确拒绝。此限制与现有只含 JSON 规则定义的 API 契约一致，不把客户端内容伪称为 Git YAML。没有实际发布任何规则、创建 Company 资源、部署或启动 Task 5。

## 复核与验证

独立规范和代码质量复核发现并关闭目录版本小数截断、尾随 JSON、规则版本超出数据库范围、并发 Draft 唯一冲突误报以及层间依赖问题；增加相应拒绝路径，并保持应用层只通过解析端口使用 adapter。CI 首轮还暴露 PostgreSQL `Instant` 测试绑定和旧迁移断言在 V16 后失效；恢复演练在隔离数据库中移除 V16 对象再重放 V15→V16。幂等重放按 JSON 内容比较，不要求字段顺序相同。

本机重跑 `ArchitectureTest`、`ApplicationContextTest`、`RulePublicationGateTest` 和既有 `QualityRuleGoldenTest` 通过；质量 Node 契约 7/7、契约验证 schemas=7 / positive=22 / negative=9 / operations=36 通过。本机无 Docker，PostgreSQL 行为以最终固定提交的 GitHub M1 全量测试为准。

最终实施提交：中文 `74ec9213cabbe7564e1483d4874ff10370e781dc`，英文 `82c54816830d0003990db50136288613708b4adf`。Pair Gate 与 EnglishOnly 通过；两个提交的非 Markdown 内容一致。固定提交 CI 均为 success：

| 分支 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 | [36685730380](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685730380) | [36685730421](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685730421) | [36685730391](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685730391) |
| 英文 | [36685750264](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685750264) | [36685750130](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685750130) | [36685750135](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685750135) |

## 剩余边界与下一步执行计划

TDR-026 仍为 Proposed / REVIEW_REQUIRED；本段实现没有改变其治理状态。当前只证明演示规则发布机制，真实发布须另行获得相应授权；Task 5 的正式源事实绑定、异步评估和历史重放尚未实施。

当前结果：Task 4 实施与双语固定提交 CI 已通过工程检查，未进行 Owner 验收。Git 状态：实施 Subject 已推送，本文档记录另作双语提交。下一步动作：只读核查 Task 5 的正式来源端口、Job/fencing、V17 空位和测试环境，形成实施前置结论。前置条件：本记录双语配对及固定提交 CI 核查；Task 5 实施需后续明确授权。验收目标：Owner 能依据本记录审阅 Task 4 的范围和证据；下一阶段不凭 Task 4 结果宣称质量评估完成。
