# 10 — Deterministic Quality Engine

## 1. 责任

Quality Engine 接收规范化、冻结且可验证的事实，使用已发布 Rule Set 产生不可变 Rule Results 和 Quality Result。它不调用 Jira、不采集 Evidence、不推断缺失数据为通过，也不接受 AI 输出作为权威决定。

```text
Locked Manifest ─┐
Issue Snapshot ──┤
Trace Snapshot ──┼→ Canonical Quality Input → Rule Evaluator
Test Results ────┤                           → Rule Results
Evidence Index ──┤                           → Aggregate
Rule Set ────────┘                           → Quality Result
```

## 2. 输入快照

`QualityInputSnapshot` 固化：releaseId、Manifest ID/digest、Issue Snapshot ID/digest、Traceability Snapshot ID/digest、选定 Test Run/Result ID+digest、AVAILABLE Evidence metadata ID+digest、Rule Set ID/version/digest、engine version、canonicalization version。

创建前验证所有引用属于同一 Release、状态终结、Evidence 完整且版本可解释。不一致时 Evaluation 状态 ERROR，不能产生 PASS。

## 3. Canonical Facts

规则只访问白名单事实路径，例如：

- `release.manifest.artifacts[]`
- `issues[].fixed/included/verified/required/severity`
- `traceability.gaps[]/minimumConfidence`
- `testResults[].caseId/status/attemptNo`
- `evidence.crashes[]/anrs[]/memorySeries[]`

Canonicalization 固定排序、Missing/Empty/Null 语义、单位和十进制定点精度。UNKNOWN/MISSING 与 0/false 不等价；操作符语义以 [11-quality-rule-specification.md](11-quality-rule-specification.md) 第 5 节为唯一规范。

## 4. 求值

1. 读取已发布 Rule Set 和 Input Snapshot。
2. 校验 digest 与 schema/version。
3. 构造不可变 Canonical Facts。
4. 按稳定 `(priority, ruleId, version)` 顺序执行全部适用规则。
5. 每条规则输出 PASS/WARNING/BLOCK/ERROR/NOT_APPLICABLE、matched facts、Evidence refs 和 explanation template parameters。
6. 聚合为最终结果并保存 result digest。

默认不短路，确保完整解释；单条规则异常输出 ERROR 并使整体 Evaluation ERROR，不得忽略。

## 5. 聚合机制

Quality action 优先级：`BLOCK > WARNING > PASS`。若任一适用规则为 BLOCK，则最终 BLOCK；无 BLOCK 且至少一个 WARNING，则 WARNING；所有适用规则 PASS 才为 PASS。

任何 required input 缺失、Rule ERROR、规则集无适用 Gate 规则或 Evidence 完整性错误，都产生 Evaluation ERROR，Release 保持 NOT_EVALUATED/前一结果可见但不复用为当前结果。

## 6. 典型事实到结果

- Required Issue 未 Verified：规则匹配 issue snapshot + trace path + verification result → BLOCK。
- Critical package 出现 ANR：规则匹配 Artifact criticality + ANR fingerprint occurrence + Evidence → BLOCK。
- PSS 连续三次超阈值：规则对统一 MiB 序列做固定窗口计算 → BLOCK/WARNING。
- 非 required Case SKIPPED：按规则决定 WARNING 或 PASS，不由 Orchestrator 决定。

## 7. 重放与审计

重放只读取 Input Snapshot 与精确 Rule/Engine/Canonicalization 版本，不访问实时外部系统。相同输入和版本必须产生相同每规则 status、matched facts、explanation parameters 和 final status；evaluationId/timestamp 可不同。

Result 保存 rule outcomes、Evidence/事实引用、执行版本、耗时和 digest。AI 可在结果之后生成摘要，但 AI 内容单独标记且不参与 result digest。

## 8. Override

Override 创建独立 `GovernanceDecision`：originalQualityResultId、decision、actor、reason、approval、expiresAt（若适用）和 Audit Event。它不能修改或删除算法 Quality Result。UI/报告必须同时展示算法结果与治理决定。

## 9. 版本与回滚

Rule、Rule Set、Engine 和 Canonicalization 分别版本化。发布错误规则时 retire 该版本并发布新版本；历史结果仍引用旧版本。需要重评时创建新 Evaluation，不能覆盖旧结果。

## 10. 性能与优化

MVP 在单个事务一致性读取后执行内存内受限规则；不引入分布式规则服务。先验目标是一套 MVP Release 在合理分钟级以内完成，具体 SLO 在真实数据基准后冻结。优化顺序：查询索引 → 事实预聚合 → 批量读取；不牺牲重放性。

## 11. 验收

- 同一 Snapshot/Rule Set 连续执行至少三次，规则结果 digest 一致。
- 输入缺失、规则异常和 Evidence 损坏不能得到 PASS。
- 每个 BLOCK/WARNING 能导航到规则、事实和 Evidence。
- Override 不改变算法结果。
- Engine 不依赖 Jira SDK、Agent 或 AI 服务。

证据：golden tests、property tests、重放报告、故障注入、解释性报告、依赖边界扫描。
