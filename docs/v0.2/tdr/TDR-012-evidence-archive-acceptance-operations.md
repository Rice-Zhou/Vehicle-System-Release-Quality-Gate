# TDR-012 — Evidence Archive 验收运维操作

- 状态：Accepted
- 日期：2026-08-27
- 决策依据：已批准的 Evidence Archive 验收工作包设计与实施计划
- 范围：`V0-2-EVIDENCE-ARCHIVE-001` 的受控运维入口、输入描述符与机器 Evidence
- 相关决定：[TDR-002](TDR-002-kotlin-spring-boot.md)、[TDR-004](TDR-004-s3-compatible-evidence-storage.md)、[TDR-009](TDR-009-oidc-and-service-identities.md)、[TDR-011](TDR-011-pilot-company-deployment-profiles.md)

## 为什么选择该技术

选择 `narrow JVM operation + canonical JSON Evidence + two invocations`。归档入口复用现有 Kotlin/JVM Archive facade、Capability probe、Provider-attested identity 和 Adapter，只装配归档所需的窄依赖；它不启动 Web 管理面，也不建立新的持久化协调层。JVM 路径可以直接复用已经测试的 Archive 契约，避免把摘要、回执、不可变性或身份判断复制到另一种运行时。

每次工作包需要两个显式、相互分离的 invocation：第一个 `archive` invocation 使用归档身份验证固定源并执行 create-only 归档，输出 canonical JSON archive Evidence；第二个 `verify` invocation 使用独立验证身份，按精确版本恢复 payload 与 receipt 并输出 canonical JSON recovery Evidence。canonical JSON 使字段、排序和摘要可确定复算，也使后续机器 Gate 能交叉核验两次调用，而不依赖进程日志或人工转述。

固定工作包描述符只记录 Artifact ID、run ID、commit、纯文件名、size 和 SHA-256。源目录、恢复目录和输出位置只作为 invocation 的运行时参数存在，凭据仅来自仓库外的 external identity chain；工作包、Git、canonical JSON Evidence 和日志均不得保存 credential、token、原始主体或临时签名 URL。

## 解决什么问题

本决定把两个 Pilot ZIP 和保全清单固化为无秘密、机器可验证的输入契约，并为未来真实 Company Provider 归档规定可复现的操作边界。执行前可以拒绝摘要、大小、commit、分类或文件名不符合契约的输入；执行后可以用独立 invocation 证明精确对象版本、回读摘要、保护控制和恢复结果，且失败不会被转换为长期归档成功。

这是操作层决定，不改变 Archive 核心、Archive Port、Capability 语义、Provider Adapter、Archive Receipt 或 Evidence 一级实体，也不改变 Release-centric、Manifest authority、Traceability 或 Deterministic Quality Engine。该工作包不是 acceptance record，不关闭 `V0-2-PILOT-COMPANY-002` 的任何条件，也不改变 `M1-OWNER-GATE-001`。

## 为什么不选其他方案

- REST 管理端点：会扩大远程攻击面并引入新的鉴权、幂等、生命周期和长期运维契约；一次性受控操作不需要常驻管理 API。
- AWS CLI 脚本：会绕开现有 Archive facade 和 Provider-neutral 契约，复制摘要、回执与控制判断，并把实现绑定到单一 Provider。
- 数据库队列表：当前只有固定的两项输入和人工授权的受控执行，不需要新的调度状态、迁移、清理或恢复协调数据源。
- 新微服务：会增加部署、身份、网络、观测和故障面，却不提供当前规模所需的独立扩展收益。

## 对 V0.2 的影响

V0.2 增加一个无 Web、无数据库依赖的窄 JVM 运维入口、一个版本化工作包 schema、固定描述符，以及 archive/recovery 两份 canonical JSON Evidence 契约。实现仍调用唯一 Archive facade；工作包不包含本地路径、Provider 配置或凭据。真实外部写入、独立恢复验证和后续 acceptance record 创建仍分别需要明确的独立授权，本决定本身不授权任何 Company 写入、merge、Tag、release 或 production deployment。

## 对 V0.3 的影响

V0.3 可以在实测操作量或 SLO 证明需要时，把同一窄 operation 挂接到受控作业平台或 Evidence Gateway。迁移必须保持工作包版本、canonical JSON、精确版本引用、双 invocation、独立身份和 fail-closed 语义；不得把队列、数据库或远程 API 提升为新的 Archive 权威来源。若需要改变 Archive Receipt、Capability 或 Core Contract，必须另行走 TDR/ADR 治理。

## 迁移

初始状态只提交 schema 与固定工作包，不执行 Provider 操作。取得 Company Provider、批准的 retention policy、`accessOwner`、归档身份、独立验证身份和执行授权后，先在当前源上复算 size 与 SHA-256，再用 `archive` invocation create-only 写入并按精确版本回读；随后切换到独立验证身份运行 `verify` invocation，在独立恢复位置复算原摘要和保护状态。

迁移始终保留 Pilot 源和既有对象版本。重试必须产生新的 execution ID 和 Evidence；已成功写入且通过严格冲突复核的 exact object ref 可以复用，只有真实新写入或切换 Provider 时才产生新的 locator/version。该重试语义不改变现有 Archive key 或唯一 Archive facade。任何重试均不得覆盖旧 Evidence、修改固定摘要、删除唯一副本，或把 `FILESYSTEM_STAGING` 解释为 Company 长期不可变归档。

## 测试

工作包 schema 测试使用 Node.js 与现有 AJV 依赖按 JSON Schema 2020-12 编译，验证有效固定描述符通过，并拒绝未知字段、任何未声明的 path/root/credential 字段、本地绝对路径、错误 SHA-256、非正整数 size、重复 Artifact ID、错误 Pilot 分类、关闭条件 B、非两个 Artifact、不安全纯文件名和非 40 位小写 commit。

后续 operation 测试覆盖源 size/摘要不一致、fresh Capability 与实际 identity 绑定、create-only 冲突、payload/receipt 精确版本回读、canonical JSON 稳定性、双身份隔离、不可变性和 retention 控制、独立恢复、禁止字段扫描及故障退出码。测试必须针对真实实现契约，不以 mock 调用次数替代行为证明。

## 部署

operation 随现有 JVM 应用构建产物交付，以单次进程方式在受控运维环境运行，不暴露监听端口，不部署新服务、数据库表或队列。工作包从只读仓库内容加载；源根、Evidence 输出和独立恢复位置由受控执行环境显式注入。Provider、region、bucket、prefix 和 identity policy 遵循既有外置配置，所有凭据只由 external identity chain 提供。

每次 deployment 前验证工作包 schema、构建版本、当前 Provider Capability、实际运行身份与最小权限。归档与验证 invocation 使用不同的受控身份和输出；日志只记录 secret-free fingerprint、稳定对象引用、摘要与明确失败，不记录本地绝对路径、原始 principal 或临时 URL。

## 失败恢复

schema、固定 size、摘要、commit 或 Pilot manifest 校验失败时，在任何外部写入前停止并暴露具体错误。Capability、identity attestation、加密、私有访问、versioning、retention 或 immutability 无法证明时 fail closed，不产生成功 Archive Receipt，也不降级到 Pilot 成功。网络和超时错误保持失败或不确定语义，不能当作预期拒绝或成功。

部分上传成功时保留已提交精确版本引用、源文件和真实错误，修复同一 Provider 后以新 execution ID 重试；不得覆盖或删除来源。payload 或 receipt 回读、摘要、保护状态或独立恢复失败时，保留对应 canonical JSON 失败 Evidence，禁止创建 acceptance record。疑似凭据泄露时立即停止并交由外部安全流程撤销与替换，仓库只保留不含秘密的处置证明。

## 重新评估条件

只有当受控操作规模需要调度平台、公司禁止 JVM operation 或 external identity chain、Provider 无法提供精确版本和不可变控制，或 V0.3 需要改变既有 Archive/Core Contract 语义时重新评估。本工作包执行成功也不能自动改变任何 Owner 决定；状态转换仍需独立授权。
