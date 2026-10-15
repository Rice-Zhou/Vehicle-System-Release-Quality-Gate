# Task 5/6 复合条款与 A1–A8 证据归并核查

日期：2026-10-15。只读核查产品代码、测试及 CI；本轮只新增双语工程记录。本文中的“已覆盖”均指限定工程证据，不是条款勾选、A1–A8 PASS、Owner 或里程碑验收。

依据：[范围与 A1–A8](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-scope-draft.md)、[技术设计](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md)、[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。保留权威文档中的历史结论；本记录补充当前 Subject 上的证据归并，不修改其状态。

## 1. 固定 Subject 与 CI 来源

复用 `git worktree list` 中现有 `m2-issue-traceability-design` 与 `m2-issue-traceability-design-en`，核查开始时均干净。以下为产品/测试 Subject；本记录后续文档提交不是这些历史 CI 的 Subject。本轮重新读取 GitHub Actions 的 head_sha、结论和 artifact 归属，不仅依赖旧工程记录。

| Subject | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| ZH `5969533c858db3ae5e69672986e67e7cfd3a67f4` | [38708682189](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708682189) | [38708682048](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708682048) | [38708682058](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708682058) |
| EN `7cb4864478594a248a6887eef17d1cd0564e28cc` | [38708524659](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708524659) | [38708524630](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708524630) | [38708524697](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708524697) |

六条均为 `completed/success`。实现检查均针对上述树；之前的来源补证可查 [Task 5 正式来源记录](2026-10-08-quality-task5-engineering.md)、[恢复记录](2026-10-12-quality-task5-persisted-replay-engineering.md)、[same-APK 记录](2026-10-12-quality-a1-same-apk-release-binding-engineering.md)、[四场景记录](2026-10-12-quality-task6-four-scenarios-engineering.md)、[字段记录](2026-10-14-quality-task6-report-fields-engineering.md)、[封存边界](2026-10-15-quality-sealing-boundary-tests-engineering.md)与[事务直接测试](2026-10-15-quality-transaction-window-tests-engineering.md)。历史记录中的“待补”按本矩阵核对，不能将不同夹具的证据拼成一次执行。

| Artifact | 本地 ZIP SHA-256 |
| --- | --- |
| ZH M1 `11960212098` | `c6bdc702e63a1229a06cb1692850da648f72c549f52715137d4331bcdf18297c` |
| EN M1 `11959708429` | `f04d09fef8df22c0a748e6e742705bc067c308e12b3c2fee204e77a8b6646c97` |

GitHub artifact 的 workflow_run.head_sha 与 Subject 一致，均未过期。已下载 ZIP 位于 `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-transaction-window-20261015/tx-zh-evidence.zip` 与同目录 `tx-en-evidence.zip`；本轮重新计算以上摘要并读取其 `evidence/<Subject>/full-test-results/TEST-com.ricezhou.vsrqg.quality.*.xml`。`scripts/m1/verify.ps1` 在 `clean test bootJar` 后归档完整结果，后续 smoke 不覆盖该份 XML。以下每类两边数量相同，合计每边 17 类、92 个测试，均 failures=0/errors=0/skipped=0。

| Test class | 每个 Subject 的 tests |
| --- | --- |
| `QualityAggregatorTest` | 2 |
| `QualityCanonicalEncoderTest` | 8 |
| `QualityEvaluationDecisionIntegrationTest` | 1 |
| `QualityEvaluationEvidenceIntegrationTest` | 1 |
| `QualityEvaluationIntegrationTest` | 18 |
| `QualityEvaluationRecoveryIntegrationTest` | 1 |
| `QualityEvaluationWorkerTest` | 1 |
| `QualityEvidenceReaderTest` | 2 |
| `QualityFormalSourceTest` | 8 |
| `QualityInputBindingTest` | 1 |
| `QualityPermissionTest` | 2 |
| `QualityReplayTest` | 4 |
| `QualityRuleGoldenTest` | 3 |
| `RuleOperatorMatrixTest` | 17 |
| `RulePublicationGateTest` | 2 |
| `RulePublicationIntegrationTest` | 10 |
| `StrictRuleYamlTest` | 11 |

测试类位于 `backend/src/test/kotlin/com/ricezhou/vsrqg/quality/`。下表代码名默认位于 `backend/src/main/kotlin/com/ricezhou/vsrqg/quality/`；Evidence 使用 `evidence/application/ReadQualityEvidence.kt` 和 `evidence/adapter/ControlledPayloadStore.kt`。完整 XML 支持下面的具体测试范围，不把类级绿色等同于所有条款验收。

## 2. Task 5 复合条款拆分

| ID | 子条款 | 固定 Subject 的代码与证据 | 限制或缺口 |
| --- | --- | --- | --- |
| 5.1 | 否定来源绑定 | `FormalQualitySourceReader.read`; `QualityFormalSourceTest`, `QualityInputBindingTest`, `QualityEvaluationEvidenceIntegrationTest`。真实双 Release 共享同一 APK，混入另一 Release 的 Run 后 ERROR，既有来源与结果保持原样。 | 计划仅此项已勾选；跨项目、Manifest 摘要、缺失 Result/Case/required refs 等还有端口 mock 证据，不统称全部正式数据库反例。 |
| 5.2a | 首次短事务与事务外 bytes | `QualityEvaluationWorker.runNext` → `FormalQualitySourceReader.read` → `ReadQualityEvidence.verify`。正式上传集成测试观察真实 JDBC：首次 read-only REPEATABLE_READ 已提交，再校验两个 Payload；bytes 阶段无事务、同步或 ConnectionHolder。 | 直接证明执行边界；没有给“短”设耗时 SLO，不从共享 CI 推导性能保证。 |
| 5.2b | 封存事务与窗口 | `JdbcQualityEvaluationRepository.seal` 重读来源、比较 inputDigest 与完整 Evidence 项，并 `lockClaim`。正式集成观察 recheck 与实际 Snapshot INSERT 共用可写 REPEATABLE_READ、与首次读取不同的 ConnectionHolder，提交后无遗留事务。 | 来源/metadata 变化拒绝用 mock reader；测试显式调用 `fail`。尚无该 seal 拒绝经完整 Worker 自动落库的直接数据库证据。 |
| 5.2c | ERROR 与 Payload 窗口 | 正式缺失 Payload 经 Worker 生成可查询 ERROR；损坏 bytes 的直接校验拒绝已有证据。窗口特征测试先验 bytes，再外部改文件、metadata 不变，seal 仍成功。 | 窗口测试末尾 `TEST_WINDOW_PROBE` 是显式夹具终结，不是自动完整性诊断；损坏 bytes 经完整 Worker 自动 ERROR 尚缺同等级数据库补证。保持 TDR-026：seal 不重读 bytes；后续完整性检查才诊断当前文件。 |
| 5.3a | 纯求值与原子落库 | `QualityDecisionRunner.evaluate`; `complete` 原子保存 Rule Results、Quality Result、Evaluation/Job 终态、Audit/Outbox。`QualityEvaluationIntegrationTest` 用非法结果与真实 outbox trigger 故障证明回滚；重试后各记录恰好一份。 | 这是实际 repository/DB 事务证据，来源端口为 mock；不等同于所有 Worker 故障注入组合。 |
| 5.3b | 重领、到期、重复与唯一性 | 同类测试覆盖旧 claim 封存/完成拒绝、重领耗尽、同幂等键冲突；299s 可写，300s/301s 无重领亦拒绝 seal/complete。V17 unique/immutable 约束保留历史。 | 到期测试直接传时间且确认 Job 未被重领；不声称过期 Worker 会越过 fencing 自行写 ERROR。 |
| 5.4 | 选择与精确版本 | 正式 Published Plan Case、单 Run/Case/Attempt、resultDigest、selectionPolicyVersion、ruleSetDigest 与 catalog/encoding/Engine 进入 Snapshot；`QualityFormalSourceTest` 拒绝多个 Attempt。`QualityDecisionRunner` 校验受支持版本。 | `otherAttempts=[]` 只满足当前 V0.2 限制，不是多 Attempt 历史支持。历史版本测试直接覆盖未知 Engine；catalog/encoding 各自未知值仍缺专门回归。 |
| 5.5a | 正式来源三次新 JVM | `QualityEvaluationEvidenceIntegrationTest` 从数据库读取正式来源生成的 Input Snapshot 与保存的规则 AST，在三个新 JVM 重放，与已保存 Quality Result 摘要比较。 | 只重放隔离夹具的一条规则；不声称四个场景、完整规则集或实际已发布规则都做了冷重放。 |
| 5.5b | 时间/请求隔离与当前完整性 | `QualityEvaluationRecoveryIntegrationTest` 两份相同固定输入使用不同 requestId/createdAt/Snapshot ID，inputDigest/resultDigest 相同。删除恢复副本 Payload 后当前完整性失败，历史重放仍一致。 | 该恢复夹具的来源 reader 为 mock，Snapshot 为合成构造；不能与上一行拼接成“同一正式来源 Evaluation 已独立恢复”。 |
| 5.6 | 测试与独立恢复 | 固定 M1 完整测试 XML 覆盖 unit/DB/permissions/replay。恢复测试实际 `pg_dump`/`pg_restore` 新 DB，并复制独立 Payload 目录，从恢复库读取 Snapshot/AST 后三次新 JVM 比对。 | 不触碰原 demo DB；真实演示数据恢复及正式来源与恢复串联仍缺证据。本机无 Docker，本轮未重跑 PostgreSQL，使用已核实固定 CI。 |

Evidence 的正常 API 与 V14 `guard_local_evidence` 禁止终态 AVAILABLE 的 metadata/state 改写。上表 mock 差异是防御性来源重读测试，不是合法 API 竞态的证明；后续不得关闭触发器、篡改生产历史来制造“正常可变来源”。TDR-026 仍为 Proposed / REVIEW_REQUIRED；不增强为 WORM 或文件系统/数据库原子性承诺。

## 3. Task 6 复合条款拆分

| ID | 子条款 | 固定 Subject 的代码与证据 | 限制或缺口 |
| --- | --- | --- | --- |
| 6.1 | 四场景与非法输入 | 正式 HTTP/Job 测试及 `quality-fixture-ci.mjs` 已覆盖 normalSmoke→PASS、deterministicFail→BLOCK、requiredIssueBlock→BLOCK、missingEvidence→ERROR；Node 测试覆盖转义、跨 Release、旧 m1/m2 回归。 | 四例均 SYNTHETIC_FIXTURE；规则是隔离库夹具，不是实际规则发布或设备输入。 |
| 6.2a | 固定结果与逐字段投影 | `export-quality-report.mjs` 遍历分页仅选 exact evaluationId；`quality-report-export.mjs` 校验来源关联、唯一 Evidence、Case/Attempt/Result 与规则引用集合；`quality-report.mjs` 显示结果 ID、固定来源/版本、有类型诊断值及原始 ERROR。 | Traceability GET 无 Issue digest，保留 Input 中值而不独立复验。ERROR 无 input 时跳过来源 GET，显示未固定/未确定；与 pinned empty 区分。 |
| 6.2b | 定位与权限 | 正式夹具对同一 Evaluation 的 Traceability/Test/Evidence metadata GET 比对定位；静态报告只展示 Evidence ID/type/runId/attemptId/digest/sizeBytes，不预取 Payload、不存 Token、不宣称授权。 | metadata GET 会写完整性观察；Payload 下载需另行授权、purpose 与 audit。当前没有该报告完整设备导航与下载证据。规则旁 Evidence 为固定输入全集，不是逐规则因果证据。 |
| 6.2c | 正式演示编排 | 隔离测试通过现有正式 API 建立新 Run 与来源，保存历史；已有独立导出器、Schema、纯 renderer 可复用。 | 计划中的 `QualityDemoScenario.kt`、`scripts/demo/run-quality.ps1`、`docs/demo/quality-evaluation-runbook.md` 尚不存在。通用受控配置入口及运行手册未交付；测试夹具不是用户可执行演示入口。 |
| 6.3 | CI 能力 | 现有 `.github/workflows/m1-backend.yml` 运行 contracts、`clean test bootJar`（含 full Operator Matrix、故障、三次重放）、双语夹具报告与 artifacts；M2/M3 为相同 Subject 的附加回归。 | 没有 `quality-evaluation.yml` 不等于缺少上述 CI 能力，也不要求重复建 workflow。共享 CI 性能不是独立基准，M3 workflow 成功不证明本轮真机运行。 |
| 6.4 | 设备前提 | 保留历史设备记录；本轮没有设备运行、真实 Provider、Company 资源或实际规则发布。 | 新增设备阶段仍 UNKNOWN；需要当次授权、配置、设备状态及新 Run/output/spool。旧截图不能代替新执行。 |
| 6.5 | 工程复核与单独验收 | 本记录归并 A1–A8、固定 Subject、CI/XML、实现边界与剩余顺序；用户已确认错误页面措辞，但不据此扩大视觉结论。 | 中英文全页/窄屏完整视觉证据仍不齐。Task 5/6 复合条款不整体勾选，A1–A8 不自行 PASS，Owner 独立验收。 |

报告测试位于 `scripts/tests/quality-report.test.mjs`、`quality-report-contract.test.mjs`、`quality-report-export.test.mjs`、`quality-fixture-ci.test.mjs`；严格 Schema 为 `schemas/v0.2/quality-report-export.schema.json`，quality 导出上限为 16 MiB，旧 m1/m2 仍为每文件 1 MiB。现有独立导出函数通过注入 fetchPage/fetchTraceability/fetchTestRun 使用正式查询；尚无完整受控配置 CLI/演示编排，二者不可混称。

## 4. A1–A8 证据矩阵

| ID | 范围草案要求 | 工程证据 | 剩余限制；不作验收结论 |
| --- | --- | --- | --- |
| A1 | 同 Release 引用/version/digest；拒绝跨项目/Release且历史不变 | 5.1、5.2、6.2；真实 same-APK 反例 + 来源端口矩阵。 | 未覆盖全部正式来源冲突的端到端组合；不由单一反例推定全部 A1。 |
| A2 | 正常规则 PASS、失败 BLOCK、全规则聚合及 Case/Attempt/log/screenshot 导航 | 6.1 四例；`QualityRuleGoldenTest`/`QualityAggregatorTest` 动作表与优先级；正式 GET 定位。 | normalSmoke 明确 empty requiredIssueRefs；Case PASS 不覆盖 requiredIssue BLOCK。Payload/设备完整导航未验证。 |
| A3 | required Issue 未 Verified 则 BLOCK，Smoke 不改历史 Verified | 正式 requiredIssueBlock 夹具 + 两条版本化 YAML golden；来源 required policy 从 Rule Set 引用投影。 | 隔离夹具，不代表真实 Issue 验证或实际规则发布；保持政策与发布审查边界。 |
| A4 | 缺失/损坏/摘要冲突/无适用规则不 PASS，保留 ERROR 与原因 | 5.1–5.3、`QualityReplayTest`/`QualityAggregatorTest`，正式 missingEvidence/missingPayload；6.2 无 input ERROR 不造结果。 | seal 来源变化自动错误落库链与 catalog/encoding 独立拒绝测试待补；bytes 窗口按 TDR，不列为自动 seal 检出。 |
| A5 | Missing/Empty/Null/UNKNOWN；required ERROR、optional uncovered | `RuleOperatorMatrixTest`、`QualityRuleGoldenTest`；正式 Snapshot 保留 Crash/ANR/Memory 为 uncovered；双语报告区分 unpinned 与 empty。 | 未实现这些 Collector；无事件数或真实采集完整性证明。不能把 uncovered 解释为零事件。 |
| A6 | 全 Operator Matrix、置换、golden、非法 YAML、三次摘要一致 | 17 operator + 11 YAML + 8 encoding + 3 golden；5.4–5.6 的固定输入与新 JVM 证据。 | 测试次数不是规范验收；正式来源重放仅一规则，恢复为另一夹具，缺失版本分支还有补证。 |
| A7 | 发布不可变、历史、项目/权限、重复/失败恢复 | `RulePublicationIntegrationTest`/`QualityPermissionTest` + V17 与5.3/5.6；真实 rollback、reclaim、无重领到期、独立 DB+Payload 恢复。 | 实际规则发布未执行；多 Attempt 不是当前能力；正式来源到独立恢复的同一 Evaluation 证据缺口保留。 |
| A8 | 只读正式结果、来源/uncovered/原始失败、区分合成与设备、Owner 单独验收 | 6.1–6.5；固定 ID 导出/纯 HTML/无 Token；2026-10-15 用户措辞确认。 | 仅部分截图与用户文本确认；无新双语全页视觉证据、受控演示入口或本轮设备 Evidence。Owner 未验收。 |

A1–A8 是范围草案的验收要求，设计中的扩展检查通过 Task 5/6 行关联。这里不新增另一套验收标准。原始范围草案“未执行”及计划中的历史进度文字不改写；当前事实以绑定 Subject 的工程证据补充。

## 5. 剩余顺序与证据计划

| 顺序 | 动作与可核验证据 |
| --- | --- |
| G1 | 先补 `QualityEvaluationWorker` 遇到 seal 来源重读冲突时的自动错误落库测试：复用真实 repository/DB，明确标注故障注入 reader 的位置，不由测试直接 `fail`；断言原错误码、无 Snapshot/Rule Result/Quality Result、Job/Audit/Outbox 的实际终态及不切换来源。先证明测试确实触达 seal 拒绝；另用正式上传夹具补首次校验前损坏 bytes 经 Worker 自动 ERROR 的证据。只有真实行为失败才修复，工具不可用不算 RED。 |
| G2 | 补未知 catalog/encoding 的独立拒绝回归，使用其余字段合法的固定 Snapshot/规则，并精确断言 `QUALITY_VERSION_UNSUPPORTED`，避免 empty rules 或 digest 错误造成假阳性。保持拒绝缺失解释器，不做历史解释器扩展。 |
| G3 | 按验收所需范围补同一正式来源 Evaluation 的独立 DB+Payload 恢复链：复用现有正式上传夹具和恢复工具，恢复库查询其 Snapshot/全部选中规则 AST，与该条已保存 Result 在三个新 JVM 比较；将当前完整性与历史 digest 分开记录。当前两条独立证明仍有效，此项是串联补证，不是已发现产品错误。 |
| G4 | Task 5 证据补齐后，再按另次授权补受控配置演示入口/运行手册，复用现有 API、导出器、renderer 与 M1；不新增重复服务、权限或 workflow。保留新 Run、固定 Evaluation ID、四场景与逐字段报告。随后补双语完整页面/窄屏与授权定位访问证据。 |
| G5 | 实际规则发布、设备执行及 Owner 验收分别等待相应授权/审查；TDR-026 与规则政策边界先按治理评审。设备阶段绑定新 Subject、Manifest/Snapshot/规则/Engine 版本、新 Run/output/spool，缺失仍 UNKNOWN。多 Attempt 扩展先另审 TDR-024/TDR-026，不纳入上述补证。 |

G1–G4 是后续实施顺序建议，本轮不实施。新增测试可全部通过，此时只提交补证，不人为制造生产修复。每次有意义变更继续双语分别提交、推送、Pair Gate 与 exact-head M1/M2/M3；不得把测试夹具发布状态表述为实际规则发布。

## 6. 本轮验证与交付边界

本轮重新核对基线六条 CI、artifact 归属与本地 ZIP 摘要，逐类读取完整 XML；检查源代码/测试及缺失入口文件。文档提交前运行 acceptance/contract 验证与 `git diff --check`，提交后运行双语 Pair Gate，再推送并核实新文档提交的六条 CI；这些交付结果在本次会话回执给出，不能提前作为本记录自身的 CI 结论。产品代码、计划勾选、TDR 状态和验收记录保持不变。

当前结果：Task 5/6 复合条款及 A1–A8 已按固定 Subject 归并，明确既有覆盖、夹具限制与 G1–G5 顺序，不作验收。Git 状态：新增双语记录，最终提交/推送/CI 以交付回执核实。下一步动作：另次授权后执行 G1 的 Worker seal 冲突自动错误落库补证。前置条件：后续测试实施授权及可用 PostgreSQL/CI；真实设备/规则发布仍需分别授权。验收目标：测试经过真实 seal 拒绝，Worker 自动保存可查询原始 ERROR、无虚构结果、不替换来源，双语 Pair Gate 与固定提交 CI 通过；不等于 Owner 验收。
