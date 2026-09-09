# M2 合成输入接入实施记录

- 记录日期：2026-09-09；TDR-022 任务 1 工程记录，不是 Owner 验收。
- 依据：[TDR-022](../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md)、[实施计划任务 1](../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md)。
- 实施前基线：中文 b859e4981270fbfdd27eb1c3e67fec2389b54604；英文 7d5d50f04c941ae42ca4b0fb5e82a104ffc2df2b。基线四条 CI 均已核对 SUCCESS。

## 执行依据与范围

Owner 在上述双语方案与“实施任务 1”下一步动作交付后于 2026-09-09 指示执行下一步。原文按 Unicode 转义保留；只记录任务 1 实施授权，不代表任务 2、Owner 验收或部署授权。

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

本次增加 demo 内的 FIXTURE factory、有限合成 Build validator、M2 显式启动选项、Engineer/Service 身份及 Source 配置初始化。单命令 IncludeM2、HTTP 全链和结果报告仍属任务 2；本次不对外提供已完成的 M2 演示命令。

## 实施要点

M2DemoInputs 复用 FixtureIssueSourceAdapter，提供两条 CLOSED/HIGH 合成 Issue，使用已编译的 mappingVersion 和本次启动时间。M2 专用 descriptor 实现公开 IssueSourceDescriptorRegistry 接口，不访问或扩大 main source set 的 internal 可见性。保留原 DefaultIssueSourceRuntimeRegistry、Mapping codec 与生产默认 registry。

M2DemoProvenanceValidator 只匹配设计中的两组 Build/Issue/revision、固定样例字段、当前 payload SHA 和 proofDigest；匹配返回 VALID/LOW 与独立 m2-demo-fixture-provenance/v1，其他输入 INVALID/LOW。它证明输入符合合成 fixture，不证明真实 GitHub Build；不访问样例 locator，不改变 canonicalization 或生产 validator。

M1DemoMain.start 的 includeM2 默认 false；仅显式 M2 启动注入 primary descriptor/validator 和 factory，开启既有 Worker/写入 flags。缺少或非法 payload SHA 明确失败。普通 M1 的 Snapshot 默认启用状态、关闭的 Worker、PILOT/NONE 和 loopback 边界保留。

身份复用真实 RSA JWT/decoder、Principal resolver 和数据库 authorizer。initializeM2 在参数化事务内仅增加 ENGINEER、SERVICE、assignment 与 FIXTURE Source；无凭据引用，不预写业务事实或 Snapshot。未激活 Mapping 的 Source 经现有 runtime 返回 MAPPING_PROFILE_NOT_CONFIGURED，激活后使用权威版本。SERVICE 的 project claim 绑定 project_key，不使用内部 Project ID。

## 本地验证

RED：目标测试在新类型/启动参数尚未实现时，compileTestKotlin 因 unresolved M2DemoInputs、M2DemoProvenanceValidator 和 includeM2 失败，退出码 1；Gradle/JDK 正常。

GREEN：在 backend、JDK 21 执行：

```text
./gradlew test --tests '*M2DemoInputsTest' --tests '*M1DemoPackagingTest' compileDemoKotlin bootJar
./gradlew compileTestKotlin
```

两条命令退出码均为 0。目标测试 11/11 PASS：M2DemoInputsTest 4、M1DemoPackagingTest 7，0 跳过、失败或错误。覆盖匹配/各字段不匹配、JWT claims、默认 M1 与显式 M2 环境、非法 payload SHA、生产 JAR 与组件扫描隔离。

新增真实 PostgreSQL/Spring 用例位于 M1DemoIntegrationTest，复用既有容器，覆盖 M1/M2 Bean 选择、Bootstrap 最小写入、未激活 runtime 拒绝、真实 Mapping activation 后 factory open、SERVICE decoder → resolver → authorizer。已编译；本机无 Docker，未在本机运行，不能据此宣称集成 PASS。

## 评审与远端验证

独立任务复审已通过：Spec compliant、代码质量 Approved，0 Critical/Important/Minor。首轮评审包遗漏工程记录，补入后 finding 关闭；运行代码未因该 finding 改动。绑定实施提交的 CI 结果在取得后追加。当前本地证据不替代 PostgreSQL 17.11/Spring 实际运行结果，也不证明任务 2 全链演示。

## 下一步执行计划

当前结果：任务 1 代码、本地目标测试与集成用例编译完成，独立评审已通过，exact-commit CI 核查待执行。Git 状态：随双语任务 1 实施提交。下一步动作：完成任务 1 的独立评审与双语 CI 核查。前置条件：现有 GitHub CI；无需新增环境。验收目标：任务 1 真实数据库接入测试通过，默认 M1 回归保持成功，并记录实际证据；不替 Owner 验收。
