# 受控配置质量判定演示运行手册

## 1. 用途与边界

入口 `scripts/demo/run-quality.ps1` 复用现有正式 API，依次为四个场景创建新 Test Run，等待终态，提交质量判定并保存 202 返回的精确 Evaluation ID，再生成双语只读报告。它不求值规则，不准备或发布规则，不创建 Manifest/Issue/Traceability，不启动 Agent 或真实 Provider，不启动/部署 Backend，不下载 Payload，也不替 Owner 验收。

依据：[Task 6 计划](../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../v0.2/tdr/TDR-026-minimal-quality-evaluation.md)。V0.2 为 Draft，TDR-026 为 Proposed / REVIEW_REQUIRED。本手册是 HOW，不授予规则发布、设备执行或 Company 资源权限。

本轮没有可用隔离 API 配置。入口已用本地模拟 HTTP 服务测试编排，既有正式来源夹具仍由 M1 验证；这两段不能拼成“该 CLI 已在真实配置完成端到端执行”。实际配置运行、双语全页/窄屏及完整 Evidence 导航证据仍未验证。

## 2. 运行前准备

- 使用已获授权的隔离环境，准备已锁定 Manifest 的同一 Release、精确 Traceability Snapshot、已发布规则集和 Test Plan。所有正式绑定由 Backend 校验；不要改数据库、关闭约束或拼接旧演示文件以制造结果。
- 隔离 Agent/受控夹具需已就绪，能消费新 Run。入口只提交并等待，不启动 Agent、不执行 adb。没有已授权消费者会超时；实际设备阶段须另行授权，不能仅因地址是 loopback 就认定安全或已获设备授权。
- 身份需相应项目成员权限及 scopes：`test:execute`、`test:read`、`quality:evaluate`、`quality:read`、`traceability:read`；启用元数据核对时另需 `evidence:read` 与 Evidence 路由。沿用服务端 scope + 项目角色门禁，不新增权限旁路。
- 安装项目锁定的 Node 依赖并准备 PowerShell 7、Node。入口不安装依赖。TLS 使用系统/Node 信任链；如需私有 CA，按环境设置 `NODE_EXTRA_CA_CERTS`，不得关闭证书校验。
- 预先创建输出目录的父目录，输出目录本身必须不存在，路径必须绝对且现有父目录不得为符号链接。每次运行用新目录；入口不覆盖历史文件。

| 场景 | 必需的既有隔离来源 | 期望记录 |
| --- | --- | --- |
| smokePass | normal Smoke Plan；规则的 requiredIssueRefs 显式为空 | COMPLETED / PASS |
| smokeFail | 已发布的确定失败 Smoke Plan 与相符规则 Case 版本 | COMPLETED / BLOCK |
| requiredIssueBlock | Case PASS；规则显式要求未 Verified 的 Issue | COMPLETED / BLOCK |
| missingEvidence | 合法终态输入，但缺少质量规则必需的 Evidence 类型 | ERROR / QUALITY_REQUIRED_EVIDENCE_MISSING |

missingEvidence 需要隔离环境已有可产生该输入的合法 Plan/夹具；不能损坏已上传文件、伪造终态或放松规则来凑结果。示例中的 Test Plan version 3 只是待替换配置，不表示仓库已发布该版本。缺少任一前置来源时先准备受审查的环境，不删除场景或改写期望值。

入口还核对固定 Snapshot 的单 Case 事实：smokeFail 必须 FAIL，其余完成场景必须 PASS；smokePass 的 requiredIssueRefs 必须为空；requiredIssueBlock 必须有精确 requiredIssueRefs 对应的 required=true、verified=false 事实。不因两个场景都是 BLOCK 就允许互换，不重跑规则或宣称逐规则因果定位。

## 3. 配置与调用

复制 `demo/quality/config.example.json` 到受控位置，替换全部示例 ID、版本、vehicle、origin 和 outputRoot。四个场景必须指向同一 Release；可以引用各自精确的 Traceability/规则版本。配置严格拒绝未知字段，不接受 Token 明文、既有 runId、evaluationId、任意命令或执行器路径。

| 字段 | 约束 |
| --- | --- |
| origin | 仅 http/https 的 127.0.0.1 或 [::1] 根地址；无用户名、密码、路径、查询或 fragment；不跟随重定向 |
| tokenEnvironment | 只保存环境变量名称；凭据由当前进程环境注入，不写入配置、输出或命令行 |
| outputRoot | 新的绝对路径；父目录存在；已有输出显式拒绝 |
| pollIntervalMs | 整数，10–5000 |
| timeoutSeconds | 每场景整数，1–600；HTTP 单请求最多 10 秒，响应最多 16 MiB |
| verifyEvidenceMetadata | true 时单独访问固定 Evidence ID 并核对引用；false 时记 NOT_REQUESTED |
| scenarios | 必须恰好包含上表四个场景；releaseId、ruleSet、traceabilitySnapshotId、testPlan、deviceSelector 使用正式引用 |

通过安全的本地凭据流程为 `VSRQG_QUALITY_DEMO_TOKEN` 注入 Token，然后执行：

```powershell
./scripts/demo/run-quality.ps1 -Config D:/controlled/quality/config.json
```

成功退出码为 0，输出 QUALITY_DEMO_REPORTS_READY。非零退出保留明确错误码；不要把进程成功当作 Release 发布授权。示例中的绝对路径需按本机修改。

## 4. 输出与失败处理

根目录 `session.json` 保存四个场景的 Run/Evaluation ID、请求幂等 key 和进度；先保存 key，再发 POST，返回后立即保存 ID。正常状态为 RUNNING → SUCCEEDED，场景为 STARTING → RUN_CREATED → EVALUATION_QUEUED → EXPORTED。失败时根状态为 FAILED，并保存 errorCode；已创建 Run 或已排队 Evaluation 不会被自动取消、删除、改选或重试。请求结果不明时保留 key，由操作者通过既有 API 查询/幂等重放核对，不能直接重跑入口并声称是原任务。

每场景输出 `quality-report-export.json`、`report.zh.html`、`report.en.html`。报告来源标记固定 UNKNOWN，因为配置声明本身不证明输入来自真实设备或合成夹具。质量 action 与错误原样来自正式 Evaluation，入口只核对四个预期，不重新推算。

开启元数据核对时另写 `evidence-access.json`：固定 evaluationId、检查时间、精确 Evidence ID 与 VERIFIED 观察，并标记 payloadDownloaded=false。这会调用 Evidence 元数据 GET，服务端可能记录完整性观察；它与纯 renderer 分开，不证明下载已授权或当前 Payload 永久完整。session 的 evidenceAccess 为 VERIFIED、NOT_REQUESTED、NOT_PINNED 或失败前的 PENDING；输入尚未固定的 ERROR 不查询缺失的 Traceability/Test/Evidence，不把未知写成空集合。

若超时、HTTP 401/403、引用冲突、错误决定或报告生成失败，读取 session 中的固定 ID 定位；保留失败，不切换 latest/成功历史。若某个场景已经导出而后续失败，根状态仍为 FAILED；局部 HTML 不代表四场景全部成功。Payload 下载需另行授权、purpose 与 audit，本入口不调用下载路由。

TDR-026 仍适用：首次 bytes 验证在事务外，seal 复核来源/元数据/fencing；历史摘要不证明当前文件完整。只读报告不会改变 Evaluation ERROR 或验收状态。

## 5. 自动验证与证据清单

```powershell
node --test scripts/tests/quality-demo.test.mjs scripts/tests/quality-report-export.test.mjs scripts/tests/quality-fixture-ci.test.mjs
node scripts/contract-validator.mjs
```

入口测试包括本地模拟 API 的四场景、历史分页精确 ID、失败进度、重定向、元数据权限/摘要冲突、配置拒绝、fresh output 和带空格配置路径的真实 PowerShell 调用。现有 M1 契约链执行这些测试，同时保留正式 PostgreSQL/来源夹具及报告回归；不新建重复 workflow。

有配置后，应另存本次 Subject、配置非敏感引用、session、四场景导出与摘要；核对新 Run、同一 Release、精确 Snapshot/规则版本、Evaluation ID、原始 ERROR 与逐字段投影。随后采集中英文完整页面、窄屏可读性和授权定位访问证据。未执行项保持 UNKNOWN，不用旧截图替代，也不整体勾选 Task 5/6 或把 A1–A8 自行改成 PASS。
