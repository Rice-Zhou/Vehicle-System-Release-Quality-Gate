# 06 — Traceability Design

## 1. 目标

完整保留 V0.1 逻辑链并使每条关系可验证、可解释、可快照：

```text
Issue → Commit → Build → Artifact → Release → Test Run → Test Result → Evidence
```

Fixed、Included、Verified 是三个独立事实；任何一个都不能由另一个自动替代。

## 2. 强类型 Edge

| Edge | 建立来源 | HIGH 证明示例 | 验证 |
|---|---|---|---|
| Issue→Commit | SCM message/PR link/人工声明 | Git provider 的结构化 Issue link + commit 存在 | Issue 与 repo/commit 可读取 |
| Commit→Build | CI provenance | 构建系统记录精确 source revision | build metadata 签名/接口复核 |
| Build→Artifact | CI artifact metadata | build job 输出 checksum 与 Artifact 一致 | 下载/仓库 checksum 复核 |
| Artifact→Release | Locked Manifest | Artifact checksum 出现在 Locked Manifest | Manifest digest 与关联复核 |

每条 Edge 保存端点、source type、source reference、verification status、confidence、验证人/程序版本、时间与 reason。多对多通过多条 Edge 自然表达：一个 Issue 多 Commit、一个 Commit 多 Issue、Artifact 跨 Release 复用都不需特殊分支。

## 3. Confidence

- `HIGH`：权威系统结构化元数据直接证明，且可复核。
- `MEDIUM`：两个独立可信来源一致，但缺少端到端 provenance。
- `LOW`：命名、版本文本或单一非权威声明推断。
- `UNKNOWN`：无足够信息或来源不可用。

Confidence 与 `verificationStatus` 正交：HIGH 来源也可能验证失败；UNKNOWN 不能当作 false 或 true。Quality Rule 可要求最小 Confidence，但不能修改 Edge 事实。

## 4. 建立与验证流程

```text
Ingest facts from adapters/CI/Manifest
→ create candidate typed edges
→ validate both endpoints and source proof
→ assign status + confidence
→ compute gaps
→ freeze Traceability Snapshot for Release
```

自动推断只能创建 LOW/UNKNOWN candidate，必须保留推断方法。人工补链使用 MANUAL_ASSERTION，强制填写 actor、reason 和证明引用；人工声明不会自动获得 HIGH。

## 5. Fixed / Included / Verified

- **Fixed**：至少一个有效 Issue→Commit Edge，且 Commit 符合修复策略。
- **Included**：存在从该 Commit 经 Build、Artifact 到目标 Release 的连续 VALID 路径，且每段满足策略要求。
- **Verified**：Included 成立，并有目标 Release 上与该 Issue 验证标准关联的 PASS Test Result 和所需 Evidence。

```text
Commit exists ≠ Fixed proven
Fixed ≠ Included
Included ≠ Verified
```

每个 Issue 的三个状态分别输出 status、reason、path、缺失 edge 和 Evidence references。

## 6. 缺失与不可信关系

- 缺失：生成 `TraceabilityGap`，含期望 edge、影响 Issue/Release、发现时间和可操作说明。
- INVALID：保留错误 edge 与验证诊断，不物理删除。
- LOW/UNKNOWN：报告明确展示；是否阻断由版本化 Quality Rule 决定。
- 外部不可用：本次验证 ERROR，不能沿用旧验证时间宣称当前有效；历史 Snapshot 保持可解释。
- 数据冲突：保留各来源事实，标记 CONFLICT；Quality Evaluation 默认拒绝不一致的 required chain。

## 7. Snapshot 与重放

Traceability Snapshot 固化参与评估的 Edge ID+version、验证状态、confidence、gap 和规范化摘要。创建后不可变。后续补链只影响新 Snapshot 和新 Evaluation，不改写历史结果。

## 8. API/Interface

```text
TraceabilityPort
  ingestFacts(batch)
  verifyRelease(releaseId, policyVersion)
  createSnapshot(releaseId, verificationRunId)
  getIssuePath(releaseId, issueId, snapshotId)
  listGaps(releaseId, snapshotId)
```

GET `/releases/{id}/traceability` 返回路径、Confidence 与 gap；POST `:verify` 异步运行并返回 verificationRunId。

## 9. MVP 与延期

MVP 使用 PostgreSQL 强类型关联表和固定链查询。图数据库、模糊匹配、跨仓库智能推断和 AI 归因延期；只有真实查询/规模证明关系数据库不足时才重新评估。

## 10. 验收

- 覆盖一对多、多对多和 Artifact 复用场景。
- 缺少任一 required edge 时 Included 不成立，并指出精确缺口。
- 只有 Commit 存在时不得显示 Verified。
- 同一 Snapshot 重放路径与 Confidence 不变。
- 人工补链和冲突均可审计。

证据：已知链路 fixture、负向/冲突测试、真实 Release 追溯报告、Snapshot digest 重放记录。
