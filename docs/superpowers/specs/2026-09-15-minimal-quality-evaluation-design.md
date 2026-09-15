# 最小质量判定技术设计

- 日期：2026-09-15；状态：DRAFT / REVIEW_REQUIRED。
- Owner 在范围草案后回复“执行下一步”，据此继续设计推荐切片；不登记新的实施或里程碑 APPROVE。范围基线：ZH `47478f8` / EN `ea83c31`。
- 依据：[范围与 A1–A8](2026-09-15-minimal-quality-evaluation-scope-draft.md)、[Engine](../../v0.2/10-quality-engine-design.md)、[规则语义](../../v0.2/11-quality-rule-specification.md)、[技术决定](../../v0.2/tdr/TDR-026-minimal-quality-evaluation.md)。

## 1. 结构与唯一权威

在现有 Backend 增加 quality 模块，分为 domain 的纯求值器、application 的输入构建/发布/评估/查询、adapter 的 JDBC/HTTP/作业与 YAML 边界。模块之间通过应用读取端口提供不可变版本，不让 Quality 修改 Issue、Traceability 或 Test Result。复用现有 PostgreSQL、鉴权、Audit/Outbox、Job 和 Evidence 存储，不增加网络服务或报告侧求值器。

数据链为：同 Release 的正式引用 → 固定输入 → 规范事实 → 已发布规则 → Rule Results/Quality Result → 只读报告。客户端只指定引用；本地 summary、截图文字、CI 场景 PASS 不能作为质量输入权威。

## 2. 正式输入绑定与演示准备

复用既有请求的 ruleSet、testRunIds、traceabilitySnapshotId。服务端从 Traceability Snapshot 获取精确 Issue Snapshot 与 Manifest 引用，逐一核对 Release、project、版本、状态与已存摘要。Test Run 必须终结且绑定同一 Manifest；选择每个已发布 Case 的正式最终 Resolution 所指 Attempt，记录选择策略版本及所有其他 Attempt 的历史引用。禁止选择“最新成功”或由客户端指定摘要来绕过失败。

首个切片一次只接受一个显式 Run；多 Run 请求明确拒绝并记录容量原因，不合并同 Case 的相互矛盾结果。重评新 Run 时创建新的 Evaluation，不覆盖旧结果。当前单 Case 限制保持。

新演示由正式 API 为同一个 Release 依次创建/锁定 Manifest、同步合成 Issue、创建 Issue Snapshot、导入与真实 APK checksum 对应且标记合成来源的 Build/Edge、生成 Traceability Snapshot，然后执行 Smoke。现有启动代码中抽取可复用的准备步骤，不复制另一套身份、注册或 Manifest 校验。正常与确定失败用独立新 Run；历史 M2/M3 文件不拼接、不修改、不补写虚构关联。本轮不执行设备操作。

Evidence 模块提供只读核验端口，复用其唯一 Payload 解析与 checksum 逻辑，不调用会封闭 Session 的执行端口来做查询。每个所选 Result 的 Evidence 检查项目、Run/Attempt、类型、状态、大小、实际 bytes checksum；规则必需类型在求值前核验，即使 appliesWhen 为 false 也不得隐藏缺失。Quality 保存核验引用与结果，不复制原 Payload。

## 3. 事实目录衔接（版本化提案）

现有事实目录是版本 1，实施的 Traceability 使用 HIGH/MEDIUM/LOW/UNKNOWN；Issue Snapshot 没有 required，目录也没有明确的 Test/Issue item 局部别名。禁止假设这些已经兼容。拟发布目录版本 2，保留版本 1 原文件与解释，不偷偷重写旧 digest。

| 事实 | 唯一来源与映射 |
|---|---|
| Release / artifacts | Locked Manifest 的精确版本，保留数组语义顺序。 |
| Issue fixed/included/verified | 精确 Traceability Issue Result；verified 原值保留 false。severity/source 来自所引用 Issue Snapshot。缺项报错。 |
| Issue required | 规则集发布内容中的显式 requiredIssueRefs；引用用 source + sourceIssueId，禁止按标题或严重性猜测。数组必须显式提交，可显式为空；不存在于选定 Snapshot 的引用报错。该规则集限适用项目，变更集合须发布新版本。 |
| Traceability gaps | 保留原 gap code、Issue 与断边引用；不把 M2.5 的 Evidence gap 因存在普通 Smoke 而删除。 |
| Traceability confidence | 版本 2 使用新路径 traceability.minimumConfidenceLevel，类型为有界 STRING 枚举，取原有最低等级；UNKNOWN 保留 UNKNOWN。不得换算 0、0.5 或 1。版本 1 的 required DECIMAL minimumConfidence 不在版本 2 中复用；两版不互转。 |
| Test Results | 正式 Resolution 所选结果，包含 caseId/version、attemptNo/status 与引用；按目录稳定排序。 |
| Crash/ANR/Memory | 未采集不构造路径，展示 UNKNOWN；不能填空数组或零。 |

版本 2 增加有类型的集合局部绑定：在 testResults 的 where 中 item.status 绑定其 status，在 issues 中 item.required/item.verified 绑定同一项；不得在其他集合使用。Fact path 验证基于当前集合环境，不用无类型反射。增加上述 source/标识/排序字段的目录声明与测试，禁止仅 Schema 正则允许就接受未知 path。

现有 Case 错误是可用事实，不等于求值器错误。拟定规则 SMOKE_CASE_OUTCOME：PASS 不阻断；FAIL、BLOCKED、SKIPPED、ERROR 均 BLOCK，说明保存原 Case 状态；无所选结果、非终结或未知状态是 Evaluation ERROR。REQUIRED_ISSUE_VERIFIED：存在 required=true 且 verified=false 的 Issue 则 BLOCK，否则 PASS。两条规则没有 appliesWhen 隐藏分支，前者要求 LOG/SCREENSHOT。动作是供审核的具体规则政策，不在本轮发布。

规则集显式声明 selectedCaseRefs，输入必须覆盖且不得为空，防止 all 对空集合的真值产生空跑 PASS。这是所选演示规则的输入要求，不改变 all 的标准语义。输入中的所有选定 Case 均应属于规则集范围；不静默过滤。

本版本 2 仅补全实现衔接，不改变 Core Entity、Traceability 状态或聚合语义。评审必须确认目录类型变更与 required 策略归属；若判断触及冻结语义，先提交 ADR，不能凭本设计直接实施。旧目录仍可用于既有契约测试；运行服务拒绝其不具备绑定能力的评估请求，不降级成版本 2。

## 4. 发布、执行与恢复

沿用已有 createRuleSet / publishRuleSet / requestQualityEvaluation / getQualityResults 路由及权限、幂等要求。规则集请求显式增加 catalogVersion、engineVersion、requiredIssueRefs、selectedCaseRefs 及项目适用信息；响应补全 Evaluation、Snapshot、Rule Result 和分页历史的机器 Schema。以上通过独立契约变更与兼容测试交付，本轮不修改 OpenAPI。已有幂等 key 同内容返回原资源，不同内容冲突。

YAML 解析只产生有界节点树，在绑定对象前拒绝重复 key、alias/anchor/tag、多文档、隐式日期及不受支持的 scalar；规范 AST 与 Fact 类型检查后执行所有操作符的 golden/matrix 测试，才允许审核发布。复用 TDR-008 的作者/审核者权限边界，生产双人审核不因演示绕过。解析器依赖必须在实施计划的前置验证中确认节点级限制可配置并锁定版本；不手写通用 YAML 解析器。

作业复用 PostgreSQL claim/lease/fencing。请求短事务保存 Evaluation 与 Job；worker 在一致性读事务中固定所有不可变源引用与内容，事务外通过 Evidence 端口验证 bytes，再以 fencing 校验和源状态复核事务写入输入快照。源状态变化导致 ERROR，不换成新输入。纯求值在事务外运行；提交 Rule Results、Quality Result、Audit 与 Job 终态在一个事务内完成。唯一键约束每 Evaluation 只可提交一次；过期 worker 不得提交。

持久化按职责分为规则版本/规则集版本、Evaluation/Job 关联、Quality Input Snapshot、Rule Results/Quality Result。原文、规范 AST、版本、摘要、审核信息和源引用均保存；发布规则与已封闭输入/结果采用既有不可更新/删除保护模式。DB Job 的可变状态不混入业务结果内容。提交失败回滚且允许重新领取；达到重试上限记录明确错误，不输出伪造 Quality Result。

Evaluation ERROR 独立于 PASS/WARNING/BLOCK，不生成可用最终质量 action。查询包含失败 Evaluation，不能只列成功结果；旧结果仍按原输入可见，不作为当前评估结果。Override 保持未实施，不暴露假成功接口。

## 5. 确定性、摘要与边界

保留原模块摘要算法，Quality 只校验已声明覆盖范围，不能声称检测原摘要未覆盖字段的任意篡改。新的 Quality Snapshot 摘要覆盖其消费的全部规范事实、原引用/digest、选择策略、目录/规则/Engine 版本。它是本模块输入身份，不替代原模块摘要权威。

规范数值使用任意精度整数与十进制定点，禁止转换为 double。规范编码必须单独版本化并测试大整数、小数尾零、负零与 Unicode；不得直接复用会丢失精度的普通 JCS number 路径。顺序沿事实目录；结果 digest 包含全部规则结果、matched facts、解释参数、Evidence refs、输入和执行版本，不含 Evaluation ID、耗时、时间戳或 AI 文本。

重放读取保存的快照与精确规则/执行版本，重新验证输入摘要，不重新选择实时 Issue/Run 或外部 Provider。若请求当前 Evidence 完整性证明，另行复核存储；历史决策重放成功不证明 Payload 当前仍可用。缺旧解释器版本时 ERROR，不自动换新引擎。

初始工程上限：每规则 YAML 64 KiB、深度 32、AST 节点 4096；每规则集 32 规则、输入规范字节 4 MiB、累计求值步数 100000；Issue/Edge 沿用 20/2000；首片一个 Run/Case。上限计入 Engine 配置版本，超过则明确失败。它们是可测资源预算，不是 Company 性能承诺。规则异常不短路隐藏，聚合继续遵守既有规范。

## 6. A1–A8 实施验证映射

| 范围 | 设计出口与验证 |
|---|---|
| A1 | 输入读取端口、Snapshot 绑定、项目/Manifest/Issue 版本冲突及重复 Case 拒绝；包含两个 Release 使用同一 APK 的反例。 |
| A2 | 两条版本化 YAML 的状态表、正常/FAIL 正式结果与导航；正常 Smoke 可因 required Issue 未 Verified 得到总 BLOCK。 |
| A3 | requiredIssueRefs 发布审核与精确来源；Verified 不回写；不存在的 required 引用 ERROR。 |
| A4 | 缺失/损坏/状态变化、空 selectedCaseRefs、无规则、无适用规则、旧引擎缺失全部不 PASS。 |
| A5 | Missing/Empty/Null/UNKNOWN 区分；必需 Evidence 先验检查与报告未覆盖范围。 |
| A6 | 全 Operator Matrix、operand 置换、资源限制、数值编码和三次冷启动重放；版本 1 拒绝误绑定，版本 2 的 item 路径越界拒绝。 |
| A7 | Job 重领/晚写/事务回滚、幂等冲突、发布后不可变、跨项目权限和审核记录；恢复 DB+Payload 后重放独立记录。 |
| A8 | 查询正式结果后只读呈现，导航到同 Release 的证据与追溯；真实/合成来源、原失败和未覆盖项可见。 |

实施顺序为契约衔接 → 规则发布/纯求值 → 输入快照/作业/API → 串联与报告 → 故障/重放验收。每段先对应测试再实现；后端单测默认 60 秒超时。本设计尚无运行验收结论，现有 Smoke Owner 批准不转移为本切片批准。

## 7. 评审与下一步

必须审阅：版本 2 置信等级路径与局部绑定、required Issue 的规则集来源、Case 状态动作表、异步快照提交边界及解析/编码前置验证。尚未选定可用解析器版本是实施计划的显式前置验证，不得宣称已完成实现可行性测试。

当前结果：技术设计与 TDR 候选已形成。Git 状态：双语文档提交，版本由 Git history 定位。下一步动作：对设计完成评审并据评审结论制定实施计划。前置条件：设计中的契约衔接和规则政策被接受；若涉及冻结变更须先获 ADR 批准。验收目标：计划逐项覆盖 A1–A8，固定解析与编码验证及实施/测试顺序；本轮不执行产品代码、设备操作或规则发布。
