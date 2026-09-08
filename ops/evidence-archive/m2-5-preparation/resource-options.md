# Company 归档资源路径对比

- 状态：DRAFT；仅选型研究，未指定资源或作出技术决定。
- 核查日期：2026-09-08。
- 代码基线：中文 b9936d070ee93a0194b3a3ba0cdaa48f09091b97；英文 39471d90719ca61788f30f07eeaa4f96782d4849。
- Owner 授权：先编制选型对比，暂不指定资源。
- 适用范围：现有 M2.5 Evidence Archive 工作包；不重新定义冻结架构或扩展运行时能力。

## 结论与条件

建议优先评估托管 AWS S3 原生路径，前提是公司允许该服务及拟定区域、预算和访问路径。此建议来自当前代码已有原生身份验证、而自定义 endpoint 尚缺生产 attestor 的实施差异，不代表成本报价、资源选定或 Company 验收。

若公司要求内网自管，或能够提供已有且由 Platform 维护的 S3-compatible 服务，则评估该服务的具体产品与版本；先解决身份验证适配，再证明完整归档/独立恢复链。当前没有资源且运维责任尚未落实，不建议仅为这两份 Evidence 从零搭建存储集群。该工程判断以六个月业余实施尺度为依据，供 Owner 评审。

## 现有实现约束

依据 [TDR-004](../../../docs/v0.2/tdr/TDR-004-s3-compatible-evidence-storage.md)、[TDR-012](../../../docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md)及[运行手册](../../../docs/m1/evidence-archive-runbook.md)，两条路径均须保持私有访问、加密、版本化、实际 COMPLIANCE 保护和 retain-until、仓库外身份、独立恢复、精确版本回读及 fail-closed。

[ArchiveConfiguration](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt) 中，endpoint 为空时使用同一凭据链的 AwsStsIdentityAttestor；自定义 endpoint 只有恰好一个获批 ProviderIdentityAttestor 才可使用，否则选择 MissingProviderIdentityAttestor。[S3Gateway](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt) 对缺失 attestor 明确返回 IDENTITY_UNAVAILABLE。当前生产代码未发现自定义 endpoint 的具体 attestor 实现，不能将兼容 S3 解释为配置后即可上线。

## 两条路径对比

| 维度 | 托管 AWS S3 原生路径 | 公司自管 S3-compatible 存储 |
|---|---|---|
| 当前资源 | 无；公司账户/区域/网络与责任人未确定 | 无；产品/版本/集群与责任人未确定 |
| 代码适配 | 已有 AWS S3 client 与 STS 身份路径；仍需实际配置验证 | 已有 S3 endpoint 接口；需要获批的身份 attestor 与对应测试/技术决定 |
| 不可变性 | 官方 Object Lock 能力可作为候选依据；仍需既有实际探针 | 产品具体版本能力、策略及实际探针均待验证，不能只查 bucket 开关 |
| 独立身份 | 由公司建立两个受控身份，使用既有 Provider attestation | 平台需提供可验证身份来源并证明与 S3 操作身份绑定，不接受配置自报 |
| 运维责任 | 云方运营存储服务；公司仍负责权限、配置、费用、审计与恢复验证 | 公司承担存储容量、故障处理、补丁升级、监控、备份/恢复与身份服务维护 |
| 费用 | 存储、请求、传输/恢复及所选管理服务，按区域与用量核算 | 设备/托管、冗余容量、网络、电力、支持及运维人力，并计入 attestor 适配 |
| 首轮工作 | 明确公司边界与责任方、配置目标/双身份、获授权后运行既有链路 | 先固定产品/版本/运营方，再设计适配并做兼容验证，最后运行既有链路 |
| 主要不确定性 | 公司使用许可、区域/数据驻留要求、预算、访问路径均未给出 | 全部资源与具体产品未给出；兼容性和持续运维能力均未证实 |
| 适用情形 | 公司允许托管服务，希望减少自建基础设施工作 | 公司要求内网，且有明确平台团队或已有合格存储服务 |

## 官方资料与证据边界

AWS 文档说明 Object Lock 依赖版本化并保护特定对象版本；COMPLIANCE 保留期不能缩短。新版本或 delete marker 不等于被保护版本被删除，因此必须沿用本项目精确 versionId 恢复。这里是产品文档事实，不是本项目实测 PASS。[AWS Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)

AWS 的 GetCallerIdentity 返回调用凭据对应的身份信息，与现有原生路径吻合；它不代替应用授权和独立身份要求。[AWS STS API](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html)

作为自管路径的协议资料示例，Ceph 文档列出 GetCallerIdentity 及对象 retention API。所查 latest 页面明确标注开发版本，不能据此认定某个生产版本满足本项目全部要求，也不能推定本项目已具备 Ceph attestor。本文没有选定 Ceph、MinIO 或其他具体产品。[Ceph STS](https://docs.ceph.com/en/latest/radosgw/STS/)、[Ceph Object Operations](https://docs.ceph.com/en/latest/radosgw/s3/objectops/)

## 成本比较方法

当前缺少区域、日增量、保留期、对象数量、恢复频率及公司报价，不能给出可信月费或声称任一路径更便宜。原 ZIP 仅 1756 / 1753 bytes，是此次摘要 Evidence 的大小，不能外推未来日志、trace 或 dump 容量。

- 托管路径：保留版本的实际存储用量 × 区域/类别单价，加写入、读取、HEAD/控制检查、恢复流量、所选加密密钥/审计/监控及支持费用。按实际选用项目报价，不假定所有附加服务均启用。
- 自管路径：设备或托管摊销、冗余与预留容量、网络、电力、支持、人力，加首次部署及身份适配成本。现有集群也应计入增量运维成本。
- 日增量与保留期仅用于估算稳定存储规模；另计 receipt、日控制对象、保留历史版本和必要恢复副本，不假定 content-addressing 消除全部版本增长。
- 当前链路要求精确版本可回读；生产代码未发现冷归档 restore/wait 流程，不能只按最低冷存储价格估算并默认可运行。

AWS 官方价格页区分存储、请求/检索、传输和可选管理等费用。实际报价时应固定区域、币种、日期和配置；本文不引用未经这些条件限定的金额。[AWS S3 Pricing](https://aws.amazon.com/s3/pricing/)

## 评审所需输入与退出条件

| 输入 | 提供角色 | 用途 |
|---|---|---|
| 是否允许托管 AWS、允许的区域/数据驻留及网络边界 | Project Owner / Security | 排除不允许的资源路径，不由实施者推定 |
| 日增量、对象数、保留期限、恢复频率与预算上限 | Project Owner / Release Engineer | 按同等保留和恢复要求计算成本 |
| 可承担持续运维的团队；是否已有可复用对象存储 | Platform | 判断自管路径是否现实 |
| 具体服务或产品版本、身份来源及受控配置定位 | Platform / Security | 形成可验证的技术候选 |

Owner 评审方向后，实施者依据实际候选形成 TDR，记录 HOW、适配范围、测试和回退；关键技术选择未经记录不进入实施。真实探针、资源创建和 Company 归档仍必须另获相应授权。所有实际前置条件继续由[准备包](README.md)的现有表管理，本文不创建第二套准入权威。

## 下一步执行计划

当前结果：完成两条资源路径的文档与代码对比，结论为附条件建议；未指定资源。Git 状态：本次双语研究文档提交，原实施与验收 Subject 不变。下一步动作：Owner 评审是否允许以托管 AWS S3 作为优先技术候选，或要求内网自管。前置条件：提供使用边界与负责团队；预算/容量缺失继续明确保留。验收目标：形成明确资源方向及约束，随后据实际候选编制 TDR；不代表批准采购、创建资源、启用 Company、merge、Tag、发布、部署或下一里程碑。
