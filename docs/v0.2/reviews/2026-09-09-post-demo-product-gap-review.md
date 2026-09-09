# M1/M2 演示验收后的产品缺口复核

## 基线与范围

- 中文基线：290e26c2df520d10434ab1ac062160d0cccbf629；英文基线：dbec8542b3a5525b1e3e22769a7aa8854c8975ef。两工作区开始时干净，GitHub 远端分支与基线相同。
- 本次根据 Owner“执行下一步”复核已有实现并提出一个下一工作包；不启动 M3/M4，不修改代码、Schema、冻结架构或既有验收决定。
- 依据：[路线图](../../roadmap.md)、[MVP 计划](../14-mvp-implementation-plan.md)、[领域模型](../01-domain-model.md)、[数据库设计](../02-database-design.md)、[Agent 协议](../08-test-agent-protocol.md)、[Evidence 设计](../09-evidence-design.md)。下文替代旧缺口清单中的当前状态，原核查内容保留为历史。

## 当前结论

M1 启动与 Manifest 演示、M2 追溯串联、离线结果呈现均已获 Owner 接受。继续扩展页面不是当前最短产品路径。按原 MVP 依赖顺序，下一处尚未交付的能力是目标 Release 在设备上执行测试，生成可关联、可复验的 Test Result/Evidence；之后才有 M4 确定性质量决定所需的运行事实。

当前可以展示 Release/Manifest、Fixed/Included、路径与 Gap，不能宣称完整 MVP、真实 Release 已测试或 Issue 已 Verified。真实设备执行一次普通 Smoke 也不能自动证明特定 Issue 已 Verified。

| 能力 | 可验证现状 | 剩余缺口 |
|---|---|---|
| M1 演示 | [TDR-021 验收](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md)为 APPROVE；Subject 917f0c7 / 4a05ce5；已有实际合成文件校验、Lock、导出与身份/权限路径。 | 展示缺口关闭；合成验收不等于真实系统镜像部署或真实 Provider 接入完成。 |
| M2 串联 | [TDR-022 验收](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md)为 APPROVE；Subject 8d5354d / db98f89；已有 Issue/Build 合成输入、Worker、A/B Snapshot 与历史。 | 展示缺口关闭；仍为 Verified=false。 |
| 离线展示 | [TDR-023 验收](../../governance/acceptance/records/2026-09-09-tdr-023-demo-report-review-001.md)为 APPROVE；Subject c5400fd / 1fc37d4；[生成器](../../../scripts/demo/render-report.mjs)和样例已交付，正常/失败报告有实际验证。 | P2 展示缺口关闭；它呈现源事实，不是 Quality Engine。 |
| M3 设备测试与 Evidence | 主源码模块为 access、issue、manifest、release、shared、traceability；Controller 扫描未发现 Device/Agent/Test Run/Evidence 运行端点，受跟踪数据库迁移为 V1–V11，未发现对应运行表。Agent OpenAPI、Schema 和示例属于契约材料。 | Device/Agent、Run/Attempt/Result、Collector、运行时 Evidence 链路及设备故障恢复尚未交付。 |
| M4 确定性质量决定 | 未发现 Quality Engine/Quality Evaluation 的主源码模块或 Controller；[规则规范](../11-quality-rule-specification.md)及 YAML 示例已经存在。 | Fact/Input Snapshot、规则运行、Quality Result、重放与最终报告仍未交付；现有 HTML 不能替代。 |
| M5 完整运行验收 | 已有局部 CI、备份/恢复及验收材料，但缺少 M3/M4 运行链。 | 原计划要求的一个真实 Release 全链验收包尚不成立；局部工程通过不可推导整体完成。 |

核查范围是两条固定分支的受跟踪主源码、迁移、契约与验收记录；“未发现”不推断其他机器或未提交内容不存在。已有 shared 下 Evidence Archive 工具用于资料归档，不等于实现了 Test Run 的 Evidence 上传与业务关联。

## 唯一下一工作包建议

**编制“单设备、单 Smoke Case 的 Test Run → Result/Evidence 最小纵向链路”设计与实施计划。** 这是 M3 的首个候选切片，当前交付物是可执行设计，不是直接实现整个 M3。复用已接受领域/协议与现有 Backend、数据库、身份和 Job 能力，必要技术选择写 TDR；不重新定义 Core Contract。

后续设计应把一个版本化 Plan/Case、一个 Device/Agent、一次 Run/Attempt、一个客观测试结果及 required LOG/SCREENSHOT 作为范围候选。具体 Case 与必需 Evidence 由真实可用设备和 Owner 的展示目标确定，不能用假数据填补设备事实。Crash/ANR Collector 和完整 M3 故障出口仍在原里程碑范围，首个切片不得宣称已经完成它们。

| 设计必须回答的问题 | 最小可验收结果 |
|---|---|
| 测的是哪个 Release | 明确设备运行版本如何对应 Locked Manifest、固定 Plan/Case Version 与 Environment Snapshot；不匹配不能以成功 Smoke 冒充该 Release 验证。保留 Orchestrator 的部署职责，不默认刷机或执行设备写操作。 |
| 谁执行以及如何报告 | 复用 Agent 注册、心跳、pull/ACK/Event/Result 契约及独立身份；说明最小实现顺序、持久化与 API 复用。ADB 若适用只是执行机制，不替代 Agent 协议。 |
| 成功和失败如何可见 | 正常结果、设备不可达、执行超时、重复提交/冲突、重启或旧租约写入分别有观察点与测试方法；保持 Attempt/终态/恢复不变量，不把任务结束等同 PASS。 |
| 数据能否对应和复验 | Test Result/Evidence 关联同一 Run/Release，保留原 Payload、size/checksum、Collector Version；required Evidence 缺失或损坏不得伪装 AVAILABLE 或验收成功。普通 Smoke 不改写 M2.5 Verified=false。 |
| 如何按业余时间落地 | 给出依赖有序的最小任务和测试，区分 CI 协议/夹具检查与真实设备验收；未验证项可见，不要求先建设设备池、在线管理平台或完整 M4。 |

## 设备与存储边界

本轮未连接、扫描或操作任何设备，也未核实可用设备、控制主机、允许的命令/部署方式及目标系统版本。设计阶段先确认这些最少输入；目前不能承诺真实设备运行结果。没有设备时可设计与测试协议，但只能记录夹具证据，不替代真实设备出口。本轮不要求采购或准备 Company 资源。

继续按 Owner 方向使用 GitHub 保存合适的项目材料，不建议 AWS、Object Lock 或 Company 归档。现有 [TDR-020](../tdr/TDR-020-git-project-evidence-preservation.md)只落实项目材料保存；[TDR-004](../tdr/TDR-004-s3-compatible-evidence-storage.md)和 Agent 协议仍描述运行时 Payload 存储及直传。下一设计必须结合首个切片的实际体量、敏感性与本地演示需求，说明最小存储实现及与现有技术契约的关系；如需简化既有选择，显式重新评估 TDR，不能默默把 GitHub Artifact 当运行时 Evidence API，也不能据旧文档直接要求搭建云环境。此轮没有作出新存储选型。

不扩展设备池、并行调度、通用刷机平台、Memory Stretch、趋势 UI、真实外部系统写回或最终质量判定；这些不是该设计包的前置条件。已知 M2.5 性能参考差距、canonical 覆盖限制、历史 CI Artifact 到期及既有测试限制继续以原验收记录为准。

## 验证与下一步执行计划

本轮复核三份 APPROVE 的 Subject/Scope 与实际入口，扫描主源码 Controller、模块和全部迁移，对照领域、API、Agent/Evidence 设计与原 MVP 出口。没有重新运行设备、数据库或 HTTP 演示，没有把文档复核当作产品验收。交付时执行双语契约/验收记录校验、diff review 与 Pair Gate；只提交上述复核与状态导航。

当前结果：已区分关闭的 M1/M2 展示缺口与尚未交付的 M3/M4/M5 能力，提出一个 M3 最小切片设计包。Git 状态：本记录按双语治理版本化，推送以远端核对为准。下一步动作：编制单设备单 Smoke Case 的 Test Run→Result/Evidence 设计与实施计划。前置条件：设计执行指令；设计期间确认设备及允许操作，真实运行前具备相应资源与授权。验收目标：固定最小范围、既有契约映射、必要 TDR、失败验证与任务顺序，可进入实施审阅；不将设计通过视为 M3 验收。
