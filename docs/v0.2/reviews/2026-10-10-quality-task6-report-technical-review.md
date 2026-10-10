# Task 6 只读报告技术复核

- 日期：2026-10-10；性质：工程可行性复核，不是 TDR 或 Owner 验收。
- 复核基线：TDR-026/TDR-023 与实施计划的中文 `1046378`、英文 `ccb49c9`。本记录只评估报告投影，不实施 Task 6。

## 依据与结论

质量历史查询按 Release 返回 Evaluation，并包含完成结果或可查询错误；`inputSnapshot` 保存来源引用、版本、选择与 Evidence 定位。Traceability 的精确 `snapshotId` 查询返回 `issueSnapshotId`、Manifest ID/摘要和路径/缺口；Test Run 结果查询返回所选 Attempt/Result。现有 Node 报告仅接受 `m1|m2`、旧输入及每文件 1 MiB 上限。Evidence 元数据 GET 会调用完整性校验并写观察记录，下载另有授权与审计。

技术路线可在现有 API 与 Node 渲染器上实施，不需第二质量求值器或新服务。以下三个问题已在双语 TDR 提议和计划中修正，仍待 TDR 正式评审：

| 发现 | 修正与验证目标 |
| --- | --- |
| Traceability GET 不返回 Issue Snapshot 摘要。 | 仅交叉核对 Issue Snapshot ID；摘要保留自固定 Quality Input，报告不声称独立复验。用错 ID/Manifest 摘要夹具验证拒绝。 |
| 固定输入前的 Evaluation ERROR 没有 `inputSnapshot`。 | 显示 NOT_EVALUATED 与原错误，不查询不存在的来源或制造 Quality Result；用固定前与固定后 ERROR 各测一例。 |
| quality 导出边界与旧报告输入限制未分开。 | 独立 `quality-report-export.json`、Schema 与字节上限；保留 `m1|m2` 行为。证明来源类型时显示合成标记，缺证明为 UNKNOWN；测超限、混合 Release、凭据泄漏与旧报告回归。 |

适用且完成求值的 `ruleResults[].evidenceRefs` 当前为该输入全部 Evidence ID，NOT_APPLICABLE/ERROR 时为空，不能标注为单条规则的因果证明。报告只展示已固定的 ID/摘要和可验证的来源关系；Evidence 路由未启用或权限不足时，导航验收保持未完成。真实规则发布、真机数据、三次新 JVM 重放、独立 DB+Payload 恢复和 Owner 验收均不由本复核关闭。TDR-026 仍为 Proposed / REVIEW_REQUIRED；TDR-023 原 M1/M2 Accepted 范围不扩展。

## 后续工程检查

先以隔离 CI 的正式查询响应固定导出 Schema 与正反测试，再实现受控导出和纯渲染。测试必须核对精确 Evaluation、同 Release 绑定、ERROR 无输入、HTML 转义、旧模式回归及无网络/规则求值的报告边界；准确提交 CI 和双语 Pair Gate 成功仅构成工程证据，A1–A8 与 Owner 验收另行记录。
