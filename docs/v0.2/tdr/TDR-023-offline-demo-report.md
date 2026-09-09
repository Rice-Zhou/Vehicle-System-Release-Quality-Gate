# TDR-023 — M1/M2 离线只读演示报告

- 日期：2026-09-09；状态：Accepted（任务 1 实施范围）。Owner 在方案 8b1dbc3 / 78bf739 交付后回复“执行下一步”，授权按该方案实施；不代表最终 Owner 验收。
- 固定方案基线：中文 79dc3637c5bae9a6f282c7d5fc41327661659ab1；英文 af835a5040f66f7b6849f69a1679d89593bb7f5d。
- 依据：[验收后缺口清单](../reviews/2026-09-08-demonstrable-product-gap-inventory.md)、[M1 验收](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md)、[M2 验收](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md)。

## 目标与选择

把同次已有 JSON 结果整理为可在浏览器直接打开的只读报告，解释 Release 内容、两条 Issue 的 Fixed/Included/Verified、A/B 路径与 Gap、历史状态和演示失败。报告只呈现已记录事实，不执行 Gate、不查询服务、不重新计算图或 canonical digest。

| 方案 | 取舍 |
|---|---|
| 现有 Node 工具生成单文件静态 HTML | 推荐；用 Node 内置文件 API 和 node:test，无 npm 新依赖，打开报告无需 Node、数据库或网络 |
| 扩展 Kotlin demo 直接生成 HTML | 会把离线展示与后端构建/JVM 生命周期耦合，处理已有 ZIP 结果不便 |
| 新建在线前端与 API | 当前只读结果已有数据，新增服务、身份与部署超出此工作包 |

生成器使用现有 Node 环境（本机已核查 v20.14.0，现有 CI 配置为 Node 24）；不升级项目依赖或安装运行时。实现仅依赖这两个环境已有的 node:fs/promises、node:path 和 node:test 等内置能力，分别验证本地与 CI。界面采用内联 CSS、语义化标题和表格、原生 details；不包含脚本、外部字体、CDN、fetch、嵌入原始 JSON 或可执行输入。

V0.1 冻结语义、生产 API、数据库、鉴权、DemoReport/M2DemoReport 输出格式、run-m1.ps1 生命周期均不修改。当前报告不是最终 Release Quality Report；M3/M4、真实 Provider、Company、merge、Tag、发布及部署不在范围内。

## 输入、范围与失败

新增 scripts/demo/render-report.mjs 命令入口和 scripts/demo/demo-report.mjs 纯呈现模块。必填参数为 --run-dir、--output、--scope m1|m2、--language zh|en；不自动选择“最新”目录、不自动探测 M2 模式，参数缺失、重复或未知直接拒绝。--run-dir 指向一份明确的已有运行目录或解压目录，读取固定文件名 summary.json、manifest.json、m2-summary.json；m1 模式不读取 m2-summary.json，页面明确标注 M2 不在本次呈现范围。

每个读取文件最多 1 MiB，UTF-8 JSON 根为 object；拒绝非法编码、损坏 JSON、非普通文件和符号链接。此上限仅服务当前两个合成 Issue 的报告工具，不修改业务限制。渲染只采用下表白名单字段，未知扩展字段不显示；不 dump 输入对象、异常正文或绝对路径。

| 输入 | 呈现字段与一致性 |
|---|---|
| summary.json（必须） | classification=SYNTHETIC_DEMO；status 为 PASS/FAILED；UUID runId；codeCommit 为 40 位小写十六进制或失败时的 null；workingTreeDirty 为 boolean 或失败时的 null；scenarioStatuses、httpStatuses、errorCodes、apiErrorCodes，以及 Release/Manifest 标识、contentDigest、payloadSha256。FAILED 的启动前最小记录允许业务字段缺失，显示“未提供”，不补写 NOT_RUN 或 PASS。 |
| manifest.json | 仅呈现 releaseId、manifestVersion、vehicle/platform/systemVersion/buildId 和 Artifact 的 artifactId/type/name/version/required/checksum。project、source、target 不用于展示。文件是注册输入；Lock/导出结果只引用 summary 的场景状态。与 summary 的 releaseId、单一样例 Artifact checksum/payloadSha256 一致；不把 JSON 文件字节摘要等同 canonical digest。 |
| m2-summary.json | classification=SYNTHETIC_DEMO、proofKind=SYNTHETIC_FIXTURE；status、runId、codeCommit、workingTreeDirty；场景/HTTP 状态、固定错误码；releaseId/manifestId、syncRunId、issueSnapshotId、verificationRunIds、traceabilitySnapshotIds、contentDigests、issues.A/B 和 history。与 summary 的运行/代码/dirty 标识严格相同；两边已有的 Release/Manifest ID 必须一致。 |

summary=PASS 要求六项既有 M1 场景字段齐全、标识/摘要/Manifest 存在且类型有效；场景状态与 summary.status 分开展示，不重新生成一个 M1 判定。m2=PASS 要求十项既有场景、A/B 标识与两条 Issue 的展示字段齐全。场景字段名沿用现有报告；状态只接受 PASS、FAILED、NOT_RUN、RUNNING，HTTP 状态为整数 100–599，标识/摘要/错误码沿用现有报告格式。Issue 布尔值必须为 boolean，Verified 必须为 false；路径/Gap 只接受现有四种 edgeType 和五种 diagnosticCode。验证输入格式和关联，不再实现 TraceabilityVerifier 或完整业务场景断言。

FAILED 报告允许缺少尚未产生的业务字段、Manifest 或 M2；提供了的字段仍按上述类型与关联检查，失败原因按固定 errorCodes 显示。m2 模式且 summary=PASS 时，缺 Manifest 或 m2-summary.json 是输入不完整错误；summary=FAILED 时缺失显示“未产生”，不推定是 Worker 失败。有效 m2=FAILED、NOT_RUN/RUNNING 场景须原样显示；不得仅凭 M1 PASS 显示整个串联成功。m1 模式忽略 M2 文件不是对该文件的验证。

读取/格式/关联错误返回非零并且不生成 HTML；固定诊断码区分 REPORT_ARGUMENT_INVALID、REPORT_INPUT_INVALID、REPORT_INPUT_MISMATCH、REPORT_OUTPUT_FAILED。有效失败记录可以正常渲染，命令退出 0 只表示 REPORT_RENDERED，页面仍显示 FAILED；这一命令不是验收或演示运行 gate。输出文件必须尚不存在，父目录由调用者准备；不能覆盖输入或旧报告。全部输入读完、校验及渲染完成后以独占创建写入；写入失败时仅清理本次新建的不完整输出，不删除既有文件。

## 页面与双语

页面顺序：合成演示与来源标识；M1/M2 各自的原始结果及缺失提示；Release/Manifest 和 Artifact 表；A/B 两条 Issue 的状态、路径和 Gap；历史检查；场景/HTTP 状态和固定错误码明细。单独说明 negative scenario PASS 表示按预期拒绝，不是 Release PASS。history.snapshotABytesStable 是既有运行检查结果，不是报告工具重新验证历史。

所有动态文本经过同一个 HTML 文本转义函数处理 &、<、>、双引号和单引号；动态内容不进入 href、style、脚本或事件属性。使用固定模板与有限 zh/en 文案字典；枚举、ID 和错误码保持原值。长 ID 换行，表格在窄屏可滚动，不能只靠颜色表示状态。meta CSP 禁止脚本、网络和对象，仅允许本页内联样式。CSP 是辅助边界，不能代替正确转义。

同一份非 Markdown 源码和样例在两条分支字节相同；--language 决定输出语言，不根据 Git 分支隐式切换。输出 report.zh.html 与 report.en.html 分别生成，技术事实一致；不引入前端构建链。报告保留输入 runId/codeCommit/workingTreeDirty，不能用生成器当前 HEAD 替换源代码提交；dirty=true 或 codeCommit=null 的限制可见。

## 样例、验证与交付

复用已批准 M2 CI 演示 Artifact 10087409439 中同一成功 IncludeM2 运行的三个 JSON，原 ZIP SHA-256 为 1490d7979dcac9815bdbaadeaed832be1a3c401ff905f5a385e63dfceb1d65b4。从已有本地副本读取或从验收记录定位下载；核对 ZIP 摘要、成员位于同一 runId、源 codeCommit=8d5354dcf21ae7b506b27f56eae4d044b9beb895 及脱敏边界后，原样保存至 demo/report/sample/。用该目录 README 记录来源、原成员路径和三个文件摘要；不建立新归档服务或第二份业务摘要权威。若原样例不可取，不伪造 CI 来源，应报告缺失并使用明确标注的测试夹具开展实现验证。

测试使用真实样例加内存变体，覆盖中文/英文、M1-only、正常 M2、最小 FAILED、部分 FAILED、缺失/损坏、混合 runId/commit/dirty/Release/Manifest、错误类型、超限、符号链接、输出已存在、特殊字符安全显示及源文件不变。测试断言展示值来自输入，不能只检查 HTML 文件存在或几个标题。

现有 M1 workflow 运行 node:test，并在既有 demo 生命周期检查之后，对每个已产生的 summary 目录显式选择 m1 或 m2 范围，生成两种语言报告；选择规则为是否存在 m2-summary.json，仅限 CI 的已知样例目录。生成失败令该步骤失败，原运行失败状态仍可读。沿用原 Artifact 上传，在 JSON 旁增加 report.*.html，保持原 30 天保留；不以生成成功覆盖 demo gate 的结果，不修改 M2 workflow 或新增 pipeline。

用实际浏览器检查中文/英文正常和失败报告、长 ID 与窄屏表格，并核对无网络依赖、无脚本及原字段对应；保存渲染检查记录。若浏览器不可用，明确记录视觉验证未完成，不用文本测试代替。双语契约/验收记录校验、Pair Gate、非 Markdown 一致性与 diff review 必须通过；代码实施和最终 Owner 验收另行记录。

## 回退与重新评估

停止使用 render-report 命令即可回退；保留原 JSON、已生成报告及数据库。需要实时查询、真实业务数据、在线权限、更多 Issue 规模、最终质量决策或更改输入报告格式时重新评估 TDR；触及冻结语义转 ADR。原 M1/M2 合成范围、Verified=false、Artifact 到期和既有性能/摘要覆盖限制不因增加 HTML 改变。

## 实施与下一步

[实施计划](../../superpowers/plans/2026-09-09-offline-demo-report.md)以一个可独立验收工作包覆盖生成器、样例、测试、CI 和手册。方案自检覆盖缺口清单的五项完成条件；当前实施验证见下方状态，最终 Owner 验收另行记录。

当前结果：TDR-023 任务 1 的生成器、样例、测试、CI 接入和操作说明已完成本地验证；独立任务审查三项问题已修复并复审通过。Git 状态：本次实施按双语治理版本化，推送以远端核对为准。下一步动作：核对固定实施提交的双语 CI/Artifact 并整理 Owner 审阅记录。前置条件：最终审查及实际 CI 结果；无需 Company 资源或新环境。验收目标：准确提交的 HTML 与源 JSON 逐项一致，自动化与实际渲染证据齐全，提交 Owner 决定。
