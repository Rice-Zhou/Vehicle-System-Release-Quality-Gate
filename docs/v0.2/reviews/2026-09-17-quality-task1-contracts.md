# 最小质量判定 Task 1：来源绑定与兼容性记录

- 日期：2026-09-17；范围：机器契约与契约测试，不含质量运行服务、规则发布、数据库迁移或真机执行。
- 依据：[实施计划 Task 1](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[设计](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。
- 本任务中 Owner 在明确的 Task 1 下一步之后回复“执行下一步”。据此落实该段实施，不代录 Owner 验收 APPROVE；TDR 正式状态与旧验收记录保留。

## 来源绑定核查

| 目标事实 | 当前实际来源 | 本段边界与后续要求 |
|---|---|---|
| sourceRef.version | Manifest revision、Issue snapshotVersion、Traceability version | 指固定源修订号，不是 Release 的乐观锁 version；摘要保持 sha256: 前缀。 |
| Issue source/severity | IssueSnapshotCandidate.sourceId 与 SnapshotObservation.sourceIssueId/severity | source 来自 Snapshot 头部，不从标题或外部原始 DTO 推断。tombstone 项不作为当前选中 Issue；绑定精确 Snapshot。 |
| fixed/included/verified | TraceabilitySnapshotIssueView | 保留历史 verified=false，不由 Smoke PASS 改写。 |
| required | 已发布 Rule Set 的 requiredIssueRefs | 现有 Issue Snapshot 无此字段；显式政策引用，不新增第二 Issue 权威。正式发布和检查引用归属在后续任务实现。 |
| confidence | Traceability 的 HIGH/MEDIUM/LOW/UNKNOWN 等级 | v2 保留等级；不把 UNKNOWN 填成零，不造概率。 |
| gaps | TraceabilitySnapshotGapView 的 diagnosticCode、breakEntityType/breakEntityId 等源字段 | v1 的概念化 gapType/sourceRef/targetRef 不得用虚构内容填充；v2 使用可绑定字段和稳定顺序，原 gap 不消失。 |
| Case / Attempt / Result | TestRunRepository 的终态快照、attempts/results，Published Case 关联与 RunCompletion | 原实现没有独立 Result ID 或已持久化的最终 Resolution ID。结果由 attemptId + resultDigest 标识；Task 5 按当前单 Case/Attempt 约束显式选择，不虚构标识或“最新成功”。 |
| Evidence | EvidenceSession 的绑定、metadata、state、expected；PayloadStore.verify | ResolveAttemptEvidence 是执行端口，返回 availableIds/failedRequiredTypes，且要求事务；不是完整只读质量数据接口。后续在 Evidence 模块补只读端口，复用唯一字节核验，不调用 seal 来查询。 |
| Crash/ANR | 本切片没有 Collector 数据 | Missing/UNKNOWN 不能改为空数组或零；Schema 不能证明“未发生”。 |

核查源文件为 backend 的 IssueSnapshotModels.kt、TraceabilityVerificationRepository.kt、TestRunRepository.kt、JdbcTestRunRepository.kt、TestRunLifecycle.kt、SubmitAttemptResult.kt、ResolveAttemptEvidence.kt 与 Evidence 的 AttemptEvidence.kt；源目录和职责沿用现有模块。本段只交付绑定契约与说明，不新增运行读取端口。

## 设计衔接修正

既有 agent-protocol 已允许 TIMEOUT，服务端也产生 TIMEOUT Test Result；取消 Run 时 Case 结果为 BLOCKED，不是 CANCELLED。本段事实目录保留 TIMEOUT，与 Run/Attempt 的状态枚举区分。它属于已存在的非 PASS 终态，后续 Smoke 规则按非 PASS 阻断意图覆盖它；若 required Evidence 缺失则 Evaluation ERROR 优先，不能为了 BLOCK 绕过完整性检查。

“正式 Resolution”在设计中指依据固定 Published Case、终态 Attempt/Result 作出的选择，不代表当前已有独立 Resolution 实体或 ID。Task 5 必须记录选择策略和实际引用，不新增假的历史记录。以上修正对齐已交付来源，不改变 Core Contract 或 Issue Verified 语义。

诊断数值沿用 TDR-026 及探针修订的有类型数组：整数使用 `["INTEGER","1"]`，小数使用 `["DECIMAL","1.23"]`，不把数字悄悄变成普通字符串或 double。matchedFact.value 与 explanation.parameters 使用该表示；完整规范树编码和资源限制仍由 Task 2 验证。Evaluation ERROR 可保留已封闭 inputSnapshot 和独立 ruleResults（包括 ERROR），但不生成 qualityResult；源输入尚未固定时允许只记录失败原因。

## API 与目录兼容性

v1 Fact Catalog、其 Schema 及 compatibility-baseline.json 必须原字节保留。v2 使用独立文件与版本，新增枚举与局部集合绑定；v1 不自动升级，旧规则必须显式选用匹配目录。

质量路由此前只有机器声明、没有 Backend 运行实现。本段补全严格请求及响应；新增 required 字段、单 Run 上限及具体响应结构属于这些草案质量契约的有意收紧，不声称对旧质量请求完全兼容。现有已实现 Release/Manifest/Issue/Traceability/Agent/Evidence 路由保持原行为。基线脚本只检查操作、权限、幂等和 requestBody 引用，不能据其 PASS 宣称所有结构兼容；基线文件不重写以掩盖差异。

运行跨 project/Release 的引用归属、真实 bytes checksum、同一事务快照、发布审核权限仍由后续服务验证。JSON Schema 与静态语义检查只证明结构和引用声明一致，不能证明数据库或设备事实真实，不能把 A1–A8 全部标为通过。

## 验证与交付记录

- RED：首次 4 项测试全部因新契约缺失/旧引用失败；复审新增的 ERROR 诊断与精确数值测试也先失败，再修正。
- GREEN：双语工作区均执行 node --test scripts/tests/quality-contract.test.mjs（由全量验证器再次执行），7/7 PASS；node scripts/contract-validator.mjs 为 schemas=7、positive=20、negative=9、operations=36。
- 后端回归：JUnit 默认超时 60 秒，M2ApiContractTest 10 项、ManifestContractTest 9 项，全部通过，0 skipped；Gradle BUILD SUCCESSFUL。未执行数据库集成、质量引擎或设备验收。
- 原 v1 Catalog/Schema 与 compatibility-baseline 相对实施前提交 Git diff 为空；新测试另固定 v1 内容摘要并只容许 checkout 换行差异。
- A1 的引用结构、A3 的 required/verified 布尔结构、A4 的 ERROR 无 qualityResult、A5 的显式未覆盖声明已作结构检查；跨项目拒绝、规则动作、真实 Evidence 损坏与未采集数据运行行为尚未验收。
- 独立复审指出两项 P2（ERROR 无逐条诊断、数字诊断无法表达）；修复与正反例随本提交保留，独立复审确认两项均关闭，无其他 Task 1 阻断问题。
- 本记录不是 Owner APPROVE。最终双语提交、Pair Gate、推送与远端 CI 以本次交付核查记录和 GitHub 对应提交为准，不提前宣称远端成功。

## 下一步执行计划

当前结果：Task 1 绑定和兼容边界已明确，工程结果以本记录后续验证段及固定提交为准。Git 状态：双语配对提交，实际版本由 Git history 定位。下一步动作：Task 1 工程复审通过后实施 Task 2 的严格解析与精确编码。前置条件：Task 1 验证通过及下一步实施授权，TDR/Owner 验收状态不代改。验收目标：真实拒绝器与全类型 golden tests 通过，保持目录、规则和历史解释一致。
