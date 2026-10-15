# 报告文案确认与输入封存证据核查

- 日期：2026-10-15；性质：用户反馈归档与只读工程核查，不是 Owner 验收。
- 依据：[提示修复记录](2026-10-15-quality-task6-unpinned-presentation-engineering.md)、[Task 5/6 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。本次不修改生产代码、测试、规则、演示入口或验收状态。

## 用户确认与来源

用户确认页面显示未固定／未确定，并保持 ERROR，要求执行下一步。此反馈登记为 `USER_REPORTED_CONTENT_CONFIRMATION`。按紧邻的交付上下文关联以下重新渲染的历史合成报告；用户没有另给 URL、语言、视口或新截图，不能将其扩大为完整双语页面视觉验收，也不是代理浏览器核查。

| 定位 | 固定值 |
| --- | --- |
| 中文修复提交 | `7be5d55fcfd40a343be67e8c1181a2c26fe159e8` |
| 英文修复提交 | `b8e2c5a78f07d4b4b3f4a409cc6de8b907f6c235` |
| 来源夹具提交／Artifact | `de578230bd8bbef124ce090697c6e76fda124873` / `11867684721` |
| Evaluation | `qev_01a1386ede8e703a9915c8ad9dd595bf` |
| 导出 SHA-256 | `658dd2b55813c112abcc96455258aac42571f649a52bd393792a1c215c97615d` |
| 中文 HTML SHA-256 | `19777c0b36a0bce858bb1ac2849432ca5c5865434c60cd735ab6e0f76e525b81` |
| 英文 HTML SHA-256 | `3cb40864604f1b42d14d6a7270e251d670f8c84a4739c60ba73a779cc05f91ff` |
| 用户反馈回执 SHA-256 | `9c54a5a4924952959ab3d5cf7e3ce8599e36f4272808e035c90f630f0a1e6eff` |

文件保存在本机证据目录 `task6-unpinned-7be5d55`，回执为 `user-confirmation-20261015.json`。本次重算上述三个报告文件摘要，并核对远端两条分支仍指向修复提交。原导出保持 ERROR、NOT_EVALUATED 与 QUALITY_REQUIRED_EVIDENCE_MISSING；没有执行新 Evaluation，没有 Input Snapshot 或 Quality Result。保留原来源记录和旧截图，不将历史的待核查记录改写为当时已通过。

前轮交付回执记录 Pair Gate 通过，以及准确修复提交的六项 CI 成功：中文 [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697359009)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697359080)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697359019)；英文 [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697372090)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697372117)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697372100)。本次首次在线复读超时；此处明确引用前轮已核实的交付证据，不伪称本次已重新取得六份响应。本记录自身的提交与 CI 应另行核对。

## Task 5 事务边界映射

代码和测试 Subject 为上表修复提交的树。下列测试存在于默认 Backend test 源集；[M1 工作流](../../../.github/workflows/m1-backend.yml)经 [candidate gate](../../../scripts/m1/verify.ps1)执行 clean test bootJar 并归档测试结果。前轮 M1 成功是该批测试的聚合证据，本次没有重新运行 PostgreSQL，也没有独立下载并检查逐测试 XML，不能据此报告逐用例的新运行次数。

| 条款 | 可定位实现与已有测试 | 证据边界／缺口 |
| --- | --- | --- |
| 短事务固定来源 | [FormalQualitySourceReader.read](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/quality/application/QualitySourceReader.kt) 第 40–41 行为只读 REPEATABLE_READ；Worker 第 36–38 行依次读取、验 bytes、封存 | 事务注解及调用顺序可检查；尚无直接断言 bytes 验证时没有活动事务或事务持锁时长的专项测试。 |
| Evidence bytes 在事务外验证 | [ReadQualityEvidence.verify](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/evidence/application/ReadQualityEvidence.kt) 第 51–68 行核对会话、绑定、状态、metadataDigest 并调用 PayloadStore；Worker 本身没有事务注解 | [正式 Evidence 串联测试](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationEvidenceIntegrationTest.kt) 删除 Payload 后经 Worker 查询到完整性 ERROR；不证明持续完整。 |
| 封存时复核来源与元数据 | [JdbcQualityEvaluationRepository.seal](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/quality/adapter/JdbcQualityEvaluationRepository.kt) 第 123–156 行先锁 claim，再重新 read，比对 inputDigest 与完整 evidence 对象，随后写 Snapshot | [仓储集成测试](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationIntegrationTest.kt) 第 140 行用模拟读取器改变摘要，证明拒绝封存并可查询 ERROR；未直接覆盖摘要不变而仅 Evidence metadataDigest 改变的比较分支。 |
| 旧 worker 不得封存或完成 | 同仓储 lockClaim 第 312–328 行校验 RUNNING、attempt_count、started_at 与 300 秒 lease，并锁 Job/Evaluation；seal、complete、fail、retry 共用此处 | 仓储集成测试第 252、272、291 行覆盖重领后 fail、seal、complete 拒绝；未直接覆盖恰好到期、尚无新 worker 重领的边界。 |
| 完成事务原子性 | 同仓储 complete 第 158–224 行在一个事务内写 Rule Results、Quality Result、Evaluation/Job 终态、Audit 与 Outbox | 仓储集成测试第 169 行制造结果约束失败；第 193 行制造 Outbox 失败并断言结果、规则、Audit、Outbox 均回滚，重领后只完成一次。来源读取器在该测试类中为模拟实现。 |
| 失败可查询且不换源 | 同仓储 fail、terminalFailure、row；Worker 将来源错误写入该 Evaluation，不创建另一份请求替换 | 仓储集成测试第 126、140、252 行覆盖来源失败／源变化／重试耗尽；Worker 单测只证明调用 fail，不能单独证明数据库可查询。 |
| 校验后的外部文件变化 | 正式 Evidence 串联测试第 440–462 行在 bytes 核验后改文件，二次核验失败，但 seal 可成功；恢复文件后由夹具主动 fail | 与 TDR-026 窗口一致；该 ERROR 不是 Worker 自动捕获窗口变化，不得将封存成功解释为封存时再次验 bytes。 |

## Task 6 未关闭范围

字段、同 Evaluation 引用一致性、四类隔离结果、双语只读投影和此次提示修复已有工程证据。计划列出的独立串联入口文件尚不存在；当前 CI 复用既有 M1 工作流生成报告，不因计划曾列出一个新工作流文件名而新增平行基础设施。新入口不在本次授权范围。

真实规则发布、真机输入、完整页面视觉覆盖与 Owner 对 A1–A8 的判断仍独立保留。未固定输入的未知状态不能写成空集合；固定输入的真实空集合仍由现有测试验证。Task 5/6 的复合条款继续未勾选，TDR-026 仍为 Proposed / REVIEW_REQUIRED。

## 可执行顺序与证据计划

1. 首先补齐封存边界的目标测试：摘要不变而 Evidence metadataDigest 变化必须拒绝封存；lease 在 300 秒恰好到期、尚未重领时，旧 claim 的 seal/complete 必须拒绝。复用现有仓储集成测试，断言原 Snapshot、结果、Job attempt 与终态没有被旧 claim 改写。此为证据补齐，现有实现可能直接通过，不能将预期通过的新增测试伪称修复前红灯。
2. 如出现真实行为失败，保留失败输出后仅修复公共来源比较或 lockClaim，不新增另一套 gate；无失败则仅提交测试。目标单测默认 60 秒超时；无 Docker 时只报告编译结果，数据库行为由隔离 CI 证明。
3. 对通过用例保留准确双语 Subject、M1 测试 XML、零失败且零跳过的目标用例、Pair Gate 和六项 CI；另行记录 bytes 校验事务状态的直接证据缺口，不将其隐含关闭。先目标测试，再受影响 build，不操作真实规则或设备。

当前结果：用户确认的新提示已按来源归档，Task 5 封存边界实现和现有测试已逐项定位；未实施新行为。Git 状态：以包含本记录的双语提交及远端核对为准。下一步动作：补齐 Evidence 元数据变化与未重领 lease 到期的封存边界目标测试。前置条件：隔离 PostgreSQL／可执行这些测试的 CI；不需要真实 Provider 或设备。验收目标：固定提交的目标用例证明拒绝旧来源和过期 claim，且没有写入额外 Snapshot／结果；工程证据与 Owner 验收保持分开。
