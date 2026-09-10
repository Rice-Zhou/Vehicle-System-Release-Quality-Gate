# 单设备 Smoke 与 Test Result/Evidence 最小链路设计

## 状态、输入与目标

状态：Accepted（设计及详细规划），依据 [M3-SMOKE-DESIGN-REVIEW-001](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md)；后续已单独获指令完成 Task 1 APK 实施，不表示完整 M3 或产品验收获准。基线为中文 519f88c4fbc667d743f6cb98586c0a998b6f9aff、英文 fd2421b19910196805dcc23ce5c488e43551ed8d。Owner 已确认有可安装、运行测试应用的 Android 设备，并选择项目新增最小演示 APK。真实设备连接与 API Level 尚未核实；后续 Task 1 已验证主机构建工具链，未执行设备命令。

目标：在一台明确指定的设备上，由正式 Run/Attempt 驱动最小 APK 安装/启动检查，将客观 Result、日志和截图通过服务端保存并查询，展示成功与断连/失败的区别。已有 M1/M2/离线报告的验收和 Verified=false 保持不变；本切片不交付完整 Crash/ANR Collector、M4 Quality Engine 或 M5 真实 Release 全链验收。

依据：[原 MVP 计划](../../v0.2/14-mvp-implementation-plan.md)、[测试状态与完成契约](../../v0.2/07-test-architecture.md)、[Agent 协议](../../v0.2/08-test-agent-protocol.md)、[Evidence 设计](../../v0.2/09-evidence-design.md)。技术提案：[TDR-024](../../v0.2/tdr/TDR-024-single-device-smoke-execution.md)、[TDR-025](../../v0.2/tdr/TDR-025-local-demo-evidence-payload.md)。两份 TDR 已按记录限定的演示范围转为 Accepted；后续 Task 1/2 的实施指令与工程验证分别记入对应记录；Task 3 实现与验证已记录；Task 4 工程实现、独立复审及准确提交 CI 已完成，实际证据见[本地 Evidence 工程记录](../../m3/local-evidence-verification.md)，Task 5 工程实现、独立复审及准确提交 CI/Artifact 核对已完成，实际证据见[Event 与结果工程记录](../../m3/attempt-result-verification.md)；Task 6–7 尚未执行。

## 系统分工与交付边界

| 单元 | 职责与代码位置 | 不承担的职责 |
|---|---|---|
| 演示 APK | 新 `demo/android-smoke/` 独立 Java/Android 工程；固定 Activity 显示 Attempt 标记，可用固定负例模式呈现错误标记。 | 网络、身份、Evidence 上传、Run 状态或质量决定。 |
| 主机 Agent | 新 `agent/` 独立 Kotlin/JVM 21 CLI；mTLS 注册/轮询、持久化执行记录、ADB 执行器、LOG/SCREENSHOT Collector、本地 spool 与结果提交。复用现有 Kotlin 版本，不依赖 Spring 服务进程。 | 直接写数据库、创建/篡改 Release/Plan、执行任意命令或判定 Release PASS。 |
| Test Management | 新 Backend `testmanagement` 模块；Device/Agent/Environment、版本化 Plan/Case、Run/Attempt/Command、租约、幂等与终态。 | 重算 M2 Snapshot 或提前实现 Quality Engine。 |
| Evidence | 新 Backend `evidence` 模块；上传会话、流式保存、校验、Metadata、下载授权与对账。 | 将普通目录包装成 WORM、建立 Company 归档或发布真实 Payload 到 GitHub。 |
| 演示入口 | 后续 `scripts/demo/run-m3.ps1` 组装已有 Backend/数据库、测试 APK、显式 Agent/Device 与配置；专用 demo 初始化只发布 Plan/登记允许的身份与设备。 | 直接预写 Run 结果、Evidence AVAILABLE、Traceability Verified 或自动购买/启用资源。 |

APK 和 Agent 是执行工具，Backend 仍是领域事实的权威。不把现有 `shared/.../archive` 当作运行时 Evidence 模块，不复制其中的 Company 流程。已有 M1/M2 命令和输入格式保持兼容；本切片首先提供标准结果 API 与机器可读演示摘要，不顺带扩大离线 HTML 功能。

## Release 身份与测试范围

以 Release + Locked Manifest 驱动 Run，不以 APK 路径当 Release ID。Run 创建事务固定 project、releaseId、manifest revision/digest、Published Plan Version、Device 与 Environment Snapshot。Agent 执行前后都检查设备 boot/session 和系统 build/fingerprint；运行环境不匹配或变化时明确阻止/中断。

首个演示声明 `SYNTHETIC_DEMO` 项目及 APK 安装/启动范围。Manifest 保存本次实际构建 APK 的 checksum、packageName、versionCode、signingCertificateSha256；环境配置作为 CONFIG Artifact 记录其实际字节和 checksum。读取实际设备环境仅用于固定 Environment，不伪造系统镜像字节或宣称车辆所有 Artifact 均已测试。如果未来提供完整目标 Release，则其 Manifest 必须包含真实目标范围且所有 required Artifact 已验证；超出本执行器能力的内容明确拒绝，不能忽略。

复用 M1 demo 的真实文件校验及 Lock 应用路径，为 APK/配置提供真实文件；普通 Backend 默认 INCOMPLETE 策略不放宽。主机 Agent 只接收显式配置的 APK 文件，执行前校验实际 bytes 与服务端固定 Manifest 一致，安装后检查测试包版本/签名和可读取的已安装 APK checksum；无法取得必要身份信息时 BLOCKED，不以安装命令退出 0 代替身份验证。重复构建/签名会生成新 APK digest，必须创建新的 Manifest/Run，不能沿用旧摘要。

## 首个 Case 的可观察行为

固定 `planId=single-device-smoke`，`caseId=apk-launch-smoke`。Plan v1 引用 Case v1，参数 mode=normal；独立 Plan v2 引用 Case v2，参数 mode=assertion-failure，作为明示负例。版本发布后不可修改，不能从 CLI 向运行中的 Case 注入任意 mode。

1. 预检：设备 API Level 至少 26、ADB 明确指向已授权单台设备、boot/session 与 Environment 一致，APK/签名匹配；本 Agent 只宣告 ADB、APK_INSTALL、LOG、SCREENSHOT 能力，不能宣告 Crash/ANR。
2. Agent 持久化 Command/Attempt 与最后阶段，ACK 获取 lease/fencing token 后执行。已有同签名包可替换安装；不同签名、安装受限或无法确认身份时明确停止，不自动卸载、清数据或绕过系统策略。
3. 使用固定 Activity 与已校验 UUID Attempt 标记启动应用。Case v1 页面显示 `VSRQG_SMOKE_READY:<attemptId>`；Case v2 显示 `VSRQG_SMOKE_NOT_READY:<attemptId>`，但断言仍要求 READY，形成确定 FAIL。
4. Agent 同时检查启动结果、前台组件和 UI 层级中的精确本次标记；不只相信 App 日志或截图存在。XML 解析禁止外部实体，输入上限 1 MiB；没有可用 UI 检查能力时 BLOCKED。界面不满足断言为 FAIL，执行工具异常为 ERROR，超时为 TIMEOUT。
5. LOG Collector 仅保留本次执行步骤、脱敏诊断和测试包定向标记；SCREENSHOT Collector 在测试 Activity 前台时保存 PNG。两项均为 required，分别上限 1 MiB / 8 MiB。截图可能包含通知覆盖，实际展示应使用干净设备；未经筛选的原始设备文件不自动提交 GitHub。
6. 创建 Upload Session、上传、Complete 校验后提交一次 Result。Result 包含本次 Evidence ID，服务端检查归属、类型、校验状态及 required 满足情况；不接受错 Run、跨项目或伪造 AVAILABLE。采集/上传失败时 Agent 上报明确 ERROR 及已取得的部分 Evidence，required 失败随结果保留，不能伪装 Case PASS。

ADB 执行只允许 TDR-024 的固定操作；本切片不自动重启/断电设备，不进行任意系统操作。用户的安装/运行许可不扩展成整机清理或系统刷写授权。

## API、身份与持久化

复用现有用户端 Create/Cancel Test Run、Get Results 和 Evidence Metadata/Payload API；复用 Agent 注册、心跳、poll、ACK、Event、Create/Complete Upload、PUT Result 的既有字段、完整版本前缀、permission 和幂等要求。

新增 `GET /agent-api/v1/attempts/{attemptId}/context`，仅当前已分配 Agent 可读，permission 为既有 `agent:execute`，GET 无 Idempotency-Key 要求。返回服务端固定的 Release/Manifest 摘要、APK 身份、Plan/Case 参数和 Environment/Device 引用，不能包含凭据、本机路径或原始设备序列号。它解决现有 Command Envelope 没有完整执行上下文的问题，不向协议 1.0 的严格 Command Payload 偷塞额外字段。

按 TDR-025 新增 mTLS `PUT /agent-api/v1/evidence/uploads/{id}/payload`，复用 `agent:evidence:write`；流式二进制、无 Idempotency-Key，使用已绑定 Session + bytes digest 实现重传语义。上传 Session 绑定当前 Agent、Attempt、项目、类型、size/checksum，5 分钟过期且不能越过 Attempt 有效租约/终态。两条新增端点必须同步 OpenAPI、Agent 表和精确 Method/Path 契约测试。

Agent 路由使用独立有序 SecurityFilterChain，基于受信 mTLS 证书与持久化身份绑定鉴权；没有证书不能退回用户 JWT，用户路由也不能因 Agent 证书获得用户权限。开发证书在仓库外生成/保存，不需要部署外部身份服务；信任、有效期、项目/Agent 撤销及请求审计必须测试。不能只读取可伪造 Header 建立身份。

按既有 ER 增量迁移 Device、Agent、Capability、Environment Snapshot、Plan/Case Version、Run、Attempt、Result、Command/Event、Evidence/Upload Session；不另建通用任务平台或第二权限表。复用现有 principal/project_assignment 和 Audit/Job 能力。Run+Manifest+Environment+Audit/Outbox、Attempt 终态+Result+Outbox、Evidence Metadata+Audit/Outbox 分别在单事务内写入；Evidence 的跨 Release/Run 关联由既有设计的 composite FK 与事务检查保证。

## 幂等、时间与恢复

首个 Plan 顺序执行一个 required Case，最大 Attempt 数为 1，不自动重试安装。心跳 20 秒、poll 等待 20 秒、lease 90 秒；只有当前 Agent 的匹配心跳可按 Server 时间续租。分配等待上限 60 秒，Case/Command 上限 300 秒，Run 上限 600 秒，RECOVERY_PENDING 的恢复窗口为 120 秒且不越过 Run deadline。这些是固定 Plan/lease policy，不依赖设备墙钟。

Agent 先持久化接收状态再 ACK；执行动作前后持久化阶段。ACK 丢包重复 ACK 返回同一租约；重启后只在同一有效租约、同一 boot/session 且阶段可确认时继续报告/上传。安装或启动是否执行不确定时不自动重放，进入 RECOVERY_PENDING 并在恢复窗口到期后明确 ERROR/TIMEOUT。过期租约不能恢复业务写入，只可提交受控诊断；新执行须新 Run/Attempt，不覆盖原事实。

Event 以 commandId/sequence 幂等；Result 使用既有 PUT attemptId，不同 digest 冲突。Result digest 由 JCS 对已验证请求中除 resultDigest 外的字段（含按升序去重规范化的 evidenceIds）计算 SHA-256，客户端与服务端使用同一规则；完整返回事实保留以便复核。终态后相同摘要返回旧确认，不同摘要/非法序列进入冲突诊断，不能追加新的有效 Evidence 改变已封闭 Run。

Run COMPLETED 仍遵守原完成契约，可含 FAIL 或明确 required Evidence 失败；不等于 Release PASS。取消/Run deadline 必须先 fence 活动 Attempt、写对应终态 Result，再封闭 Run，不能遗留 UPLOADING/RECOVERY_PENDING。Case 状态与最终质量决定始终分离。

## 验证与实施分解

| 工作单元（详细步骤在设计通过后编制） | 独立验证出口 |
|---|---|
| 最小 APK 与构建 | assemble/lint、非法标记拒绝、normal/负例 UI；固定 APK 与签名摘要，实际设备支持范围明确。 |
| Backend Run/Agent/租约 | 上下文端点契约、事务/状态机、未 Lock 拒绝、能力选择、独占 Device、mTLS 与跨项目隔离、重复/过期/取消与恢复测试。 |
| Evidence 保存与查询 | 上传端点契约、流式限制、重复/冲突、链接/路径拒绝、跨 Run 拒绝、DB/文件失败对账、权限下载和恢复复验。 |
| 主机 Agent 与 Collector | ADB 参数白名单、真实进程超时、持久化阶段、旧标记/重启/断连、上传重传与 Result 幂等；协议夹具不伪称设备测试。 |
| 单设备串联交付 | 实际正常 Case 与确定 FAIL，显式断连/Agent 重启；查询 Run→Result→两份 Evidence，原字节 checksum 重算；独立审查与 Owner 验收记录。 |

后端单测默认 60 秒超时；真实设备 Case 300 秒属于业务期限，不能为规避失败无限等待。CI 可使用受控 ADB 替身验证协议/进程边界，并构建 APK；只有实际设备执行才形成真实设备证据。设备断电、完整 Crash/ANR 和所有 M3 出口若未做，必须明确未覆盖，不将此切片称为 M3 完成。

原设计轮次没有运行新增构建、API、ADB 或数据库迁移，原设计批准仅覆盖设计与规划。后续 Task 1 构建见[构建验证](../../m3/minimal-apk-build-verification.md)，Task 2 的 V12 迁移、Agent 注册与 mTLS 检查见[身份与注册验证](../../m3/agent-identity-registration-verification.md)。Task 3 已获单独实施指令，实际状态见[Run 与租约验证](../../m3/run-lease-verification.md)。ADB 和真实设备行为尚未执行；文档检查不能替代运行时证据。

## 下一步执行计划

当前实施进度、Git 状态、唯一下一步与验收目标见[Event 与结果工程记录](../../m3/attempt-result-verification.md)。原设计 Subject 与批准范围不变；实际测试以各 Task 工程记录为准。
