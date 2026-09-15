# 最小质量判定工作包：范围与验收草案

- 状态：DRAFT / OWNER_SCOPE_PENDING。
- 日期：2026-09-15。来源：Owner 授权形成范围与验收草案；不代表实施、规则发布或里程碑验收授权。
- 核查基线：ZH `93d0e5b` / EN `61e846f`。Smoke 产品 Subject 仍为 ZH `9c9f97d` / EN `b5ed45d`。

## 1. 目标与依据

让评审者沿同一 Release，从已存储事实看到版本化规则的判定、原因和 Evidence，并重放同一决定。目标是可展示的最小纵向闭环，不是完整 M4 或车辆发布许可。

沿用[冻结架构](../../00-architecture-freeze.md)、[Engine 设计](../../v0.2/10-quality-engine-design.md)、[规则规范](../../v0.2/11-quality-rule-specification.md)、[TDR-008](../../v0.2/tdr/TDR-008-versioned-yaml-quality-rules.md)及[MVP 计划](../../v0.2/14-mvp-implementation-plan.md)。本草案只提出 WHAT / BOUNDARY / ACCEPTANCE 供 Owner 决定，不重新定义上述权威。

## 2. 现状及首项依赖

M1/M2 演示、离线报告与限定 Smoke 已存在；[Smoke 最终复审](../../m3/single-device-smoke-final-review.md)保留其验收与风险。现有报告生成器只接受 M1/M2；主源码没有 Quality Evaluation/Result 运行模块。OpenAPI 已声明质量请求与查询，但声明不等于实现。

Smoke 串联入口创建 Release、Manifest 与 Run，未创建对应 Issue/Traceability Snapshot。历史 M2 与 Smoke 输出不能通过文件名、APK 相同或人工拼接推断为同一 Release。首项交付必须建立并验证同一 Release 的正式输入引用；旧运行与失败记录不改写。演示输入仍标记 SYNTHETIC_DEMO，真实 Smoke Evidence 与合成 Issue/Build 来源分别可辨。

Fact Catalog 要求 Issue、Traceability 和 Test Result 等事实。完整、显式的空 Issue Snapshot 与缺失 Snapshot 不同；不能为了 PASS 自动补空数组、默认置信度或把 Verified 改为 true。普通 Smoke 不证明某个 Issue 的验证标准已满足。

## 3. 路径比较与推荐

| 路径 | 代价与取舍 |
|---|---|
| 推荐：在现有 Backend 内实现最小质量闭环 | 复用数据库、身份、正式 Result/Evidence 与版本化规则；需先完成同 Release 输入绑定。保留一个决定权威。 |
| 先补齐全部 M3 采集与物理恢复 | 满足更完整的原计划顺序，但本次看不到质量决定；Crash/ANR 与设备断电需要额外设备许可和验证。 |
| 在离线报告中直接判定 | 页面容易展示，但形成另一决定实现并绕开输入快照；不采用。 |

推荐仅是待 Owner 确认的阶段排序：允许在完整 M3 尚未验收时设计、随后按单独授权实施限定质量切片。它不关闭 M3 缺口，也不改变原 MVP 完成定义。

## 4. 拟纳入范围

1. 从正式存储选择同 Release 的 Locked Manifest、Issue/Traceability Snapshot、终结 Test Result 和完整 Evidence，形成可重放的 Quality Input Snapshot。选 Run/Attempt 的策略必须明确、可追溯，不自动挑选成功重试覆盖失败。
2. 沿用版本化 Fact Catalog、严格 YAML 与受限 AST；规则发布保留原文、版本、digest、审核与权限。首组规则拟覆盖所选 Smoke Case 的完成结果和 required Issue 未 Verified 的阻断；具体 YAML、适用范围与动作表需 Owner 确认后发布。
3. 所选 Smoke Case 的 FAIL 拟映射 BLOCK；PASS 只表示该规则不阻断。其他规则仍可能 BLOCK/WARNING/ERROR；未完成、缺失或损坏输入不能以 Case PASS 掩盖。Case 的 ERROR 与 Evaluation ERROR 不混用，完整状态映射在技术设计中给出。
4. 保存 Rule Results、Quality Result、解释参数、事实/Evidence 引用和版本；通过既有质量 API 契约查询，报告只投影正式结果，不执行规则。
5. 相同 Input/Rule/Engine/Canonicalization 版本至少三次重放，比较规范结果 digest；标识与时间戳不同不得影响决定。
6. 验证项目隔离、评估/读取/发布权限、重复请求、事务失败及历史不可覆盖。仅使用现有本地运行环境和 CI，不引入新服务。

规则语义继续遵守 `BLOCK > WARNING > PASS`。任何 required 输入缺失、规则异常、无适用 Gate 规则或 Evidence 完整性错误，均为 Evaluation ERROR；Release 保持 NOT_EVALUATED，旧结果可见但不作为当前结果。

## 5. 明确不纳入及未采集数据

本工作包不实施 Crash/ANR Collector、物理断连/断电演练、设备池、Memory、在线规则编辑器、完整 Dashboard、Override 或 Company/真实 Provider。完整 M3/M4/M5 的这些既有要求仍保留，不能用本切片验收替代。

Crash/ANR 未采集保留 Missing/UNKNOWN，不转换为零事件。限定规则不消费这些可选事实时，报告仍展示未覆盖范围；规则声明其 Evidence 为必需而材料缺失时必须 ERROR。不得通过 appliesWhen 隐藏必需 Evidence 缺失。任何 PASS 仅说明该已发布规则集对该输入通过，不表示无 Crash/ANR 或整车质量达标。

## 6. 验收矩阵（拟定，尚未执行）

| 编号 | 场景 | 必需结果与证据 |
|---|---|---|
| A1 | 同 Release 绑定 | 全部输入引用、版本与 digest 可定位；混入其他 Release/项目输入被拒绝，既有数据不变。 |
| A2 | 正常与确定 FAIL | 完整输入下正常规则结果 PASS、失败规则结果 BLOCK；总结果按全部规则聚合，能导航到 Case、Attempt、日志与截图。 |
| A3 | Issue 状态 | required Issue 未 Verified 产生 BLOCK；Smoke PASS 不自动改变历史 Verified。 |
| A4 | 缺失与损坏 | 缺 Snapshot/Result/required Evidence、摘要冲突、无适用规则均不能得到 PASS；保留 ERROR 和原因。 |
| A5 | 未采集事实 | 未采集 Crash/ANR 与显式空集合区分；必需时 ERROR，不必需时报告未覆盖，不能宣称零异常。 |
| A6 | 规则与重放 | 规范要求的完整 Operator Matrix、operand 顺序置换、golden cases、非法 YAML 拒绝，以及三次相同结果 digest；不能只测首组规则使用的操作符就宣称 Engine 验收通过。 |
| A7 | 历史与权限 | 发布后版本不可变；新评估不覆盖历史；跨项目与未授权调用被拒绝，重复请求/失败恢复有记录。 |
| A8 | 展示与范围 | 正式结果只读呈现，来源、未覆盖项和原失败可见；CI 合成材料不替代真机证据，Owner 单独验收本切片。 |

受控真实运行须另行检查设备授权与配置。CI 先证明协议、规则与故障路径；需要新真机输入时，使用新的运行和输出目录，保留历史证据。每条验收记录绑定实际实施 Subject、输入/规则版本和可访问证据，当前矩阵无 PASS 结论。

## 7. 实施前需完成的设计与 Owner 决定

Owner 需确认：推荐的阶段排序、两类初始规则意图及动作、展示范围和 A1–A8 验收边界。确认不等于规则发布、实施验收、merge、Tag、发布或部署。

技术设计须提交 TDR，落实既有 JVM/数据库内执行方式、同 Release 输入构建、事实绑定、持久化/API、确定性与资源上限。特别核对 Fact Catalog 的 `testResults[].status`、Issue 字段和 evaluator-local path 的绑定；未登记路径不得先使用后补规范。若需契约补全，明确版本与兼容性；若触及冻结语义，走 ADR。当前不选择新依赖，不新增平行摘要或权限机制。

当前结果：范围、依赖与验收草案已形成，待 Owner 范围确认。Git 状态：草案按双语版本提交，提交由 Git history 定位。下一步动作：依据 Owner 对本草案的确认形成技术设计与 TDR。前置条件：Owner 明确接受或调整本草案的阶段排序、规则意图与验收边界。验收目标：技术设计逐项映射 A1–A8，并解决同 Release 与事实绑定问题；尚不执行产品实施。
