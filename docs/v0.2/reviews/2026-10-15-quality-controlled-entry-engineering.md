# G4 受控配置演示入口工程记录

- 日期：2026-10-15；范围：入口、配置示例、失败测试与运行手册，不是 Owner 或里程碑验收。
- 依据：[证据矩阵 G4](2026-10-15-quality-task5-task6-evidence-matrix-review.md)、[Task 6 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。修改前 Subject：中文 `2d410fba86c7121e6c1d8bdf9e45d1b8c796361f`、英文 `1e99d168af55c8c1f7cee6e88180791fd077a0a9`，不是新增入口的执行 Subject。
- 用户明确没有可用隔离 API 配置。本轮交付可执行入口及自动测试；真实配置执行、完整导航及双语全页/窄屏证据保持未验证，不据此勾选 Task 5/6 或 A1–A8。

## 实现与复用

新增 `scripts/demo/run-quality.ps1`、`scripts/demo/quality-demo.mjs` 与 `demo/quality/config.example.json`，操作步骤见[运行手册](../../demo/quality-evaluation-runbook.md)。四个场景在同一 Release 下分别 POST 新 Test Run，等待正式终态后提交 Evaluation，保存 202 返回的精确 ID；整个历史分页只选择该 ID，不挑选其他成功记录。失败 session 保存幂等 key、已返回 Run/Evaluation ID 和原进度，显式 FAILED，不自动重试、取消或覆盖历史。

直接复用现有 API、导出器与 renderer；从既有导出器提取 findQualityEvaluation 供轮询/导出共用，旧未找到/重复/跨 Release/分页环错误保持。将既有 verifyEvidenceLocator 移到共享导出校验模块，夹具与入口复用原元数据核对和错误码；不新增第二套来源或权限逻辑。现有 Node 编排已满足本段职责，不另建 QualityDemoScenario.kt、Backend 服务或 workflow。

配置严格限定 loopback origin、环境变量名、新输出目录、超时和四场景正式引用；Test Run 请求形状复用 OpenAPI CreateTestRunRequest。Token 仅进入 Authorization header，不保存到配置/报告/诊断；拒绝重定向，响应最多 16 MiB，HTTP 最多 10 秒，每场景最多 600 秒。配置不接受命令、执行器、既有 Run/Evaluation ID 或明文凭据。入口不部署 Backend，不准备来源，不发布规则，不启动 Agent 或真实 Provider，不调用 adb 或 Payload 下载。

四个场景除核对正式 action/error，还核对固定输入中的 Case PASS/FAIL；requiredIssueBlock 要求 Case PASS 且有精确 requiredIssueRefs 对应的 required=true、verified=false 事实。这是演示前提核对，不是第二个规则解释器。报告仍为 UNKNOWN 来源，不将本地配置声明当作真实/合成来源证明。

元数据访问由 verifyEvidenceMetadata 显式控制，与纯报告生成分开。固定引用与当前 VERIFIED 观察另存 evidence-access.json，标记 payloadDownloaded=false；未请求或未固定输入分别记录 NOT_REQUESTED / NOT_PINNED。元数据 GET 的服务端完整性观察副作用和另行下载授权见手册。TDR-026 bytes/事务窗口语义保持不变。

## 失败、修复与验证

先添加入口测试，入口缺失时观察 ERR_MODULE_NOT_FOUND；这是新增功能未实现的 RED，不是既有产品缺陷。首轮入口与旧导出/夹具目标 16/16 通过后，独立复审发现只看 BLOCK action 会混淆确定失败与 required Issue 场景。补入 pass-masquerading-as-fail、fail-masquerading-as-issue、empty-required-issues 三个独立反例，实际出现 3 个 Missing expected rejection，再补固定事实前提核对；随后目标 19/19 通过。

复核还发现限定正常 PASS 必须显式空 requiredIssueRefs。新增 required-issues-in-normal-smoke 反例，先观察 Missing expected rejection，再加入空集合前提核对。最终入口 13 项，连同原目标合计 20/20；不扩大正常 PASS 的既定范围。

入口 13 项覆盖四场景新 Run、精确 ID 历史分页、两类超时、错误决定、四类场景伪装、元数据权限/摘要冲突、重定向、配置拒绝、fresh output、Token 不落盘，以及带空格配置路径的真实 PowerShell 子进程。HTTP 服务为本地模拟，不是正式 Backend。既有正式 PostgreSQL/Job/来源测试继续由 M1 运行，不把两段证明拼为同次 CLI 正式串联。

本地双语运行 acceptance-record、contract 与目标检查；契约统计 schemas=7、positive=22、negative=9、operations=36。Node/PowerShell 语法和 diff 检查通过。现有 contract-validator 链加入入口测试，M1/M2/M3 使用原 workflow，不增加 CI 基础设施。最终固定 Subject、六条 CI、Pair Gate、日志与 artifact 核对以交付回执为准：`D:/development/Vehicle-System-Release-Quality-Gate-evidence/task6-controlled-entry-20261015`。待完成 CI 不提前写为通过。

未修改 Backend/Agent、Core Contract、计划勾选、验收记录或 TDR 状态。V0.2 仍 Draft，TDR-026 仍 Proposed / REVIEW_REQUIRED；无实际规则发布、真机执行、真实 Provider、Company 资源、merge、Tag、发布或部署。未在用户环境创建新 Run；自动测试只操作自己创建的本地模拟服务与临时输出。

## 下一步执行计划

当前结果：G4 入口、配置示例、失败回归和双语运行手册已补齐，真实配置及完整导航/视觉证据仍未验证。Git 状态：分别提交推送、远端 Subject、Pair Gate 与固定提交 CI 以交付回执核实。下一步动作：收到具备既有合法来源和已授权隔离消费者的配置后，按手册执行一次正式 API 四场景串联并留证。前置条件：可用受控配置、对应项目权限、已就绪隔离 Backend/Agent 与既有发布版本；真机和实际规则发布仍需分别授权。验收目标：新 Run、精确 Evaluation/Snapshot/规则版本、四场景原始结果和完整字段报告一致，补齐双语完整页面/窄屏与授权 Evidence 定位访问证据；失败保持可见，Owner 单独验收。
