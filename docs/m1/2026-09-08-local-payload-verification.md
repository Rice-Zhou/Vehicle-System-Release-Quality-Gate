# 本地文件校验实施记录

- 记录日期：2026-09-08；仅为任务 1 工程记录，不是 Owner 验收。
- 设计与范围：[TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md)、[实施计划任务 1](../superpowers/plans/2026-09-08-local-m1-demonstration.md)。
- 实施前基线：中文 4876d8a1a3cb75fa0f1f6236fe3965b2ac64acd1；英文 a4a3ff1c457b66bd9ef77125b39f69b0d635c108。

## 执行依据

Owner 在方案交付后给出的下一步执行指令如下。原始中文按 Unicode 转义保存；该指令确认任务 1 实施，不等于完整演示验收，也不扩展为任务 2/3 或 Company 建设授权。

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

## 改动与兼容

ArtifactPayloadVerifier 是应用接口；普通 Backend 默认配置保留原 INCOMPLETE、ARTIFACT_CHECKSUM_NOT_VERIFIED、消息和 validator version。ValidateManifest 只在既有一致性检查通过后调用接口，报告仍沿既有注册事务持久化。LocalArtifactPayloadVerifier 是需要显式构造的普通类，不自动成为生产 Bean。

本地实现按摘要名实际读取文件，每文件最多 1 MiB、每次最多 16 个，使用 m1-local-payload/1。匹配为 VALID，缺失或不可读为 INCOMPLETE，非法摘要、链接、非普通文件、超限或摘要不符为 FAILED；混合情况 FAILED 优先，violation 保留输入位置。返回内容不含本机路径或原始异常。

没有修改 API、Schema、Migration、Lock 信任名单或历史 validate 读取逻辑；没有新增依赖、启动器、环境或真实 Provider。注册后的文件变化不回写历史，本实现不提供持续监控或管理员不可修改保证。

## 验证记录

RED：新增目标测试在缺少接口、本地实现和配置时编译失败，退出码 1；随后实现并执行 GREEN。本地使用已有 JDK 21，执行下述命令退出码 0：33 项测试中 30 PASS、3 SKIPPED、0 失败，bootJar 成功。ArchitectureTest 6 项、ArtifactPayloadEvaluationTest 2 项、ArtifactPayloadVerifierTest 13 项（3 跳过）、ManifestContractTest 9 项、ManifestIdempotencyScopeTest 1 项、ManifestSemanticValidationTest 2 项；XML 位于 backend/build/test-results/test。契约检查与验收记录校验通过。Windows 文件权限与符号链接能力不足时明确记录 SKIPPED，不计入 PASS；PostgreSQL 集成测试需 Linux CI 容器环境。

```text
./backend/gradlew.bat -p backend test --tests '*ArtifactPayload*Test' --tests '*ManifestSemanticValidationTest' --tests '*ManifestContractTest' --tests '*ManifestIdempotencyScopeTest' --tests '*ArchitectureTest' bootJar --console=plain
```

独立只读复审结论：APPROVE，无需修复的重要问题；检查了任务范围、依赖方向、默认兼容性、本地校验分支及测试 XML。复审没有重新执行构建，数据库与 Linux 特定测试仍以 CI 为准。

本提交沿用 M1 的 clean test bootJar 和 M2 门禁，新增单元测试自动进入现有 CI。远端结果必须以本次双语固定提交的实际 workflow 结果为准；尚未完成的运行不得记为通过。

## 下一步执行计划

当前结果：任务 1 实施、本地检查与独立复审完成，远端回归待 CI。Git 状态：本记录随双语实现提交。下一步动作：任务 1 检查完成后执行任务 2 的隔离演示启动器。前置条件：下一步执行指令；真实数据库场景需要已有容器环境。验收目标：真实 HTTP/JWT 完成合成 M1 正反例，生产包排除演示类；本记录不替代 Owner 验收。
