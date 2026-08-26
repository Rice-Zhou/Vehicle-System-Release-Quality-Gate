# TDR-011 — Pilot / Company 部署 Profile 与归档能力

- 状态：Accepted
- 日期：2026-08-26
- 批准：Project Owner 对书面 Pilot/Company 设计的批准
- 批准日期：2026-08-26
- 授权回执定位符：中文计划提交 `7cb0adcc20491f3d18bbc53144f2166101942dd4`，英文配对计划提交 `7f28cb4f50aed94c4c320b4a83022c4591550610`
- 授权回执声明：上述 Git 提交是记录遵循 Owner 直接指令开展工作的不可变定位符；它们未签名，不授权 merge、tag 或 release
- 已接受残余风险：`PILOT` 没有经验证的公司归档，不能声明长期 `PASS`
- 范围：V0.2 部署就绪度、归档操作与验收证据解释
- 相关决定：[TDR-004](TDR-004-s3-compatible-evidence-storage.md)、[TDR-010](TDR-010-containerized-vm-deployment.md)

## 问题与需求

课题阶段不能以公司对象存储、身份或运维资源已经可用为前提，但公司部署又必须证明加密、私有访问、版本、保留和不可变控制确实生效。单一布尔配置无法区分目标意图和外部事实，容易把未配置或仅本地暂存的状态误报为长期归档成功。

本决定解决如何用同一套业务实现支持 `PILOT` 和 `COMPANY`，同时保持真实能力报告、确定性验收语义和公司环境的失败关闭行为。Profile 只影响部署就绪度、归档操作和验收证据解释，不改变 V0.1 Core Contract、冻结架构、Release-centric 信息流或 Quality Result 的业务语义。

## 决策与理由

采用 `PILOT` 和 `COMPANY` 两个部署 Profile，并由一个 Archive Port 连接 `NONE`、`FILESYSTEM_STAGING` 和 `S3_COMPATIBLE` Adapter。两种 Profile 共享配置契约、能力评估、归档命令、回执和验收路径，不建立平行业务实现。

归档工作流、摘要校验、加密、私有访问、保留策略和不可变性这六个目标控制布尔值默认 `true`。这些值只表达要求，不能表达实际外部状态。只读 Capability 只能由主动 probe 产生；调用方和配置文件不能直接填写 `UNCONFIGURED`、`LOCAL_PILOT`、`EXTERNAL_UNVERIFIED` 或 `EXTERNAL_VERIFIED`。

本 MVP 采用单次使用的新鲜探针语义。每次 readiness 评估都重新 probe；每条归档命令都在执行前立即重新 probe。报告绑定当前 Profile、Provider、策略与配置快照及 `checkedAt`，只回答该次评估，不能被缓存或复用为后续授权。任何配置或 Profile 变化，以及任何 probe、上传、回读或回执失败，都会使该报告失效。所有 Provider 调用使用有界连接与读取超时；超时按 probe 或操作失败处理，不延长旧报告的有效期。

```text
Profile + Policy
    -> Provider Probe
    -> Capability Report
    -> Readiness and Archive Operation
    -> Archive Receipt
```

`FILESYSTEM_STAGING` 只证明显式本地根目录可写且摘要可复算，因此只能产生 `LOCAL_PILOT` 和非长期回执，绝不能产生长期归档 `PASS`。只有 `S3_COMPATIBLE` 的连接、写入、回读摘要、加密、私有访问、版本、保留和不可变控制全部验证通过，Capability 才能成为 `EXTERNAL_VERIFIED`。

不可变性 `PASS` 使用中立于 Provider 的判定：payload 与 Archive Receipt 都受不可变控制覆盖；实际生效的保留期不短于策略要求；运行时身份无法覆盖、删除或绕过保留；回执的 `immutabilityControl` 记录实际锁定模式或经批准的等价控制。仅证明 bucket 已启用 Object Lock 不足以通过，必须验证上述对象级范围、有效保留期和运行时身份限制。

`PILOT` 允许在外部能力缺失时启动并如实报告未配置或降级状态，但归档失败不得伪装为成功。`COMPANY` 的 READY 不变量是 `archive.enabled=true` 且 Capability 为 `EXTERNAL_VERIFIED`；任一条件不成立都为 NOT_READY。尤其是 `archive.enabled=false` 时，即使 Provider 可验证，readiness 仍为 NOT_READY，归档操作及依赖归档的批准路径仍 fail closed。liveness 独立于外部对象存储，外部故障不应触发存活探针失败或进程重启循环。

AWS SDK for Java v2 由 `software.amazon.awssdk:bom:2.54.4` 管理版本，只选择 `software.amazon.awssdk:s3` 和 `software.amazon.awssdk:url-connection-client`。不引入完整 SDK、Transfer Manager 或第二套对象存储客户端。凭据使用 `DefaultCredentialsProvider` 和默认凭据链，只能来自受控环境注入、工作负载身份或凭据配置；Git 与 YAML 永远不得保存 access key、secret key、token 或临时签名地址。

## 治理关系

本决定不替换 [TDR-004](TDR-004-s3-compatible-evidence-storage.md)。后者继续规定 S3-compatible 长期 Evidence Payload Storage、内容摘要、inventory 与保源迁移；本决定只规定何时该能力可被如实声明和用于部署就绪度。

本决定也不替换 [TDR-010](TDR-010-containerized-vm-deployment.md)。后者继续规定不可变容器、受控虚拟机或小型平台以及外置状态；本决定只增加这些部署中的 Profile、探针和归档失败关闭约束。

本决定不自动改变 `M1-OWNER-GATE-001`。本地 ZIP、transfer manifest 和 filesystem staging 仍不是公司长期不可变归档，现有 `CONDITIONAL` 状态保持不变。任何 Owner 验收状态转换都需要独立、明确的授权和验收记录，不能由 Profile 或 Capability 自动推导。

## 未选方案

- 实际外部状态默认 `true`：会在未探测连接、权限和控制时伪造成功，破坏 Evidence-first、审计性和确定性。
- Pilot 的每个控制均禁用：会让课题路径与公司路径分叉，把安全和归档集成风险推迟到切换前，无法持续验证目标契约。
- Pilot 与 Company 使用独立业务实现：会产生两套归档、验收和 Quality Engine 语义，增加漂移、重复测试与切换风险；Profile 与 Adapter 已足以表达环境差异。

## V0.2 / V0.3 影响

V0.2 增加部署 Profile、归档策略、主动 Capability Report、readiness 集成、filesystem staging、S3-compatible Adapter 和可复核 Archive Receipt。这些是 Adapter 与非核心实现细节，不新增 Core Evidence Entity，不修改 Manifest authority、Traceability 或 Quality Engine。

V0.3 可根据实测 SLO、对象量、归档成本或公司平台要求提取受控 Evidence Gateway、增加分层或跨区域存储、扩展身份集成及编排平台；Profile、Archive Port、Capability 与回执语义保持兼容，除非通过新的 TDR 或 ADR 明确变更。

## 迁移与回滚

初始使用 `PILOT` 加 `NONE`；需要演示传输和摘要流程时可切换到 `FILESYSTEM_STAGING`，但验收仍不得记录长期归档成功。取得公司资源后，先配置 `S3_COMPATIBLE`，生成源对象 inventory，逐对象复制并比对数量、大小和 SHA-256，完成回读、控制与回执验证后再切换到 `COMPANY`。

迁移在验证切流完成前绝不删除源对象。切流后仍保留来源到目标的映射、inventory 和摘要证据，并按批准的保留策略处理旧对象。回滚可恢复上一应用镜像和配置；仅为非生产研发恢复可将 `COMPANY` 切回 `PILOT`，但不得删除外部对象、覆盖成功回执、改写失败检查或把 staging 提升为长期归档。

## 测试矩阵

| 场景 | 预期 Capability / 健康状态 | 归档与验收结果 |
|---|---|---|
| `PILOT` + `NONE` | `UNCONFIGURED`；liveness 正常 | 操作明确失败，无回执，无长期 `PASS` |
| `PILOT` + `FILESYSTEM_STAGING` 且 probe 成功 | `LOCAL_PILOT`；liveness 正常 | 可产生非长期回执，不得产生长期 `PASS` |
| `PILOT` + `S3_COMPATIBLE` 且任一控制失败 | `EXTERNAL_UNVERIFIED`；报告降级 | fail closed，无成功回执 |
| `PILOT` + `S3_COMPATIBLE` 且全部控制成功 | `EXTERNAL_VERIFIED` | 回读摘要一致且回执完整时才可声明长期归档 |
| `COMPANY` + `archive.enabled=false` | readiness NOT_READY；liveness 正常 | 归档和依赖归档的批准路径 fail closed，即使 Provider 可验证 |
| `COMPANY` + `archive.enabled=true` + 非 `EXTERNAL_VERIFIED` | readiness NOT_READY；liveness 正常 | 归档和依赖归档的批准路径 fail closed |
| `COMPANY` + `archive.enabled=true` + `EXTERNAL_VERIFIED` | readiness READY；liveness 正常 | 仅依据真实、可复核回执解释验收结果 |
| 每次 readiness 评估或归档命令 | 有界超时内重新 probe；报告绑定当前快照和 `checkedAt` | 旧报告不得复用为授权 |
| 配置或 Profile 变化 | 当前报告立即失效 | 使用新快照重新 probe |
| probe、上传、回读摘要或回执写入失败 | 当前报告失效且不提升 Capability | 保留源对象和已上传对象，不产生成功回执 |
| payload 或回执未全部受保护、有效保留期不足或运行时身份可绕过 | `EXTERNAL_UNVERIFIED` | 不可变性不得为 `PASS`，回执不得声明长期归档成功 |

测试还必须验证默认的六个目标控制值、路径规范化、摘要不一致、重复执行、现有目标内容冲突、日志与错误无凭据、S3 控制矩阵、Provider 超时、单次报告不可复用、实际锁定模式或等价控制被记录，以及归档 Capability 只参与 readiness 而不影响 liveness。

## 部署

开发和课题环境以 `PILOT` 启动，可显式选择 `NONE` 或 `FILESYSTEM_STAGING`。filesystem 根目录必须是受控绝对路径，并标明仅用于 staging。公司环境使用 `S3_COMPATIBLE`，在切换 `COMPANY` 前验证 bucket 可达、最小权限、加密、私有访问、版本、payload 与回执的不可变覆盖、有效保留期、运行时身份限制、写入、回读摘要与 Archive Receipt。

容器继续遵循 TDR-010 外置配置和无本地持久状态原则。凭据不进入镜像、Git 或 YAML。归档 Capability 只参与 readiness，不参与 liveness；其他 readiness 检查仍然保留。部署完成后以当前配置重新 probe，并保存不含 secret 的 Capability Report 作为部署证据，但不把该报告复用为归档授权。

## 故障恢复

配置格式错误应直接暴露具体属性和原因。Provider 不可达、超时、无权限或控制无法证明时保留真实失败项并保持 `EXTERNAL_UNVERIFIED`，使当前报告失效，不得切换 Provider、延长超时后沿用旧状态或静默降级。上传失败不写成功回执；回读摘要不一致时保留 expected/actual digest 并失败关闭；回执写入失败时保留源对象和已上传 payload 供对账与重试。

如果 payload 与回执未同时受保护、有效保留期不足、实际锁定模式不明或运行时身份能够覆盖、删除或绕过保留，则不可变性检查失败，当前报告失效。恢复不得降低保留期、改用旁路身份或把仅 bucket 级开关改写为对象级成功；应保留对象并修复控制，随后验证 payload 与回执的实际状态。

恢复先修复配置、身份、网络或存储控制，再用有界超时重新 probe 和重放幂等归档命令。使用 bucket inventory、稳定 locator、Archive Receipt 和 SHA-256 对账；任何恢复步骤都不得删除唯一副本或覆盖冲突内容。

## 重新评估条件

出现以下任一情况时重新评估本决定：公司禁止 S3 API 或默认凭据链；强制平台无法提供所需探针或 Object Lock/WORM 等价控制；实测可用性、性能、容量或成本不能满足保留策略；filesystem staging 被要求承担长期归档；Profile 数量或环境差异无法继续由同一 Archive Port 和 Adapter 安全表达；V0.3 需要改变 Capability、回执或 Core Contract 语义。涉及 Core Contract 或冻结架构的变更必须另行提交 ADR。
