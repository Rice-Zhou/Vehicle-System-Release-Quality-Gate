# 可重复 M1 演示实施记录

- 日期：2026-09-08；任务 3 工程记录，不是 Owner 验收。
- 依据：[TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md)、[实施计划](../superpowers/plans/2026-09-08-local-m1-demonstration.md)。
- 实施前基线：中文 634073ed629a15fbff60d46e4342021f1c0358bf；英文 4ab3336636f3528bc5ed3c203818ec40809551f4。

## 执行依据与范围

Owner 在任务 2 交付及任务 3 下一步计划之后指示执行下一步。原始中文按 Unicode 转义记录；此指令授权任务 3 实施，不代表演示验收或 Company 资源建设。

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

本轮交付固定合成文件、单命令 PowerShell 入口、安全结果报告、脚本生命周期测试、已有 CI 内的真实运行及[使用说明](demo-runbook.md)。复用任务 1 的文件校验、任务 2 的隔离启动器和现有 Compose；不新增服务、依赖或生产 API。

## 实施边界

首次运行和复用已有 volume 都显式提供 VSRQG_DEMO_DATABASE_PASSWORD，不自动生成、保存或打印用户口令。仅使用本地 Docker endpoint、独立 vsrqg-m1-demo 项目和 vsrqg_demo 数据库。仅停止本次启动的容器，不删除 volume 或既有报告；外部已运行服务保持运行。

结果绑定本次 runId、代码提交及 workingTreeDirty。真实 HTTP 场景生成业务 ID、摘要、状态和错误代码；预期拒绝可以是场景 PASS，任一非预期失败及停止失败必须整体 FAILED 并非零退出。报告不保存连接信息、Token、密钥、原始身份或完整异常。

## 验证记录

脚本和报告测试先确认新增入口/类型缺失导致 RED。最终受限 inspect 版本脚本矩阵 19/19 PASS、退出码 0，涵盖依赖、远端 endpoint 拒绝、项目冲突、口令/数据库/子进程/启动/停止失败、报告缺失/错配及三种成功生命周期。运行输出已由工具记录，未保存独立完整日志文件。

本地 JDK 21 目标测试退出码 0，BUILD SUCCESSFUL：M1DemoReportTest 3/3、M1DemoPackagingTest 4/4、ArtifactPayloadVerifierTest 10 PASS/3 SKIPPED，共 20 项、17 PASS、3 SKIPPED、0 失败/错误。跳过仅为既有 Windows 符号链接/POSIX 权限能力测试。报告测试用真实本地 HTTP 503 验证意外响应不能 PASS，且不将响应正文或 Token 写入报告。compileTestKotlin 与 bootJar 同时成功。

```text
./backend/gradlew.bat -p backend test --tests '*M1DemoReportTest' --tests '*M1DemoPackagingTest' --tests '*ArtifactPayloadVerifierTest' compileTestKotlin bootJar
```

本机实际入口运行退出码 1、DEMO_DOCKER_UNAVAILABLE；summary 为 FAILED，runId 为 93f5c322-06da-4622-8bf9-0cd225eec1d0，绑定实施前 HEAD 且 workingTreeDirty=true，不计为成功演示。契约与验收记录校验通过，远端结果仍待完成，未运行不计为 PASS。

最后针对确切场景名称补充两项探针：已有服务正常运行及替换一个场景名称后拒绝，2/2 PASS、退出码 0；实际日志 backend/build/m1-report-key-probe.log。完整脚本矩阵已加入该负例，共 20 项，在 CI 执行。协调者重新执行上述 Gradle 命令退出码 0（目标为 UP-TO-DATE），日志 backend/build/m1-demo-task3-local.log，并读取 JUnit XML 核对上述数量。

完整 TDR-021 三项任务的独立只读复审 APPROVE，无 Critical/Important/Minor 发现；检查生产隔离、真实鉴权与业务路径、安全输出、错误退出和容器责任。复审未执行 Docker 或核对本轮远端 Artifact，不代替后续实际 CI 或 Owner 验收。

本机缺少容器运行时，不安装额外环境。真实 PostgreSQL/HTTP 及四次生命周期运行使用已有 GitHub CI：首次运行、保留 volume 后复用、已有服务继续运行、错误口令失败。CI 同时核查固定样例未修改、报告与真实导出一致、运行标识独立及 volume 保留。

## 下一步执行计划

当前结果：任务 3 实施与验证进行中。Git 状态：本记录随实施变更版本化。下一步动作：完成独立复审和双语 CI 并记录可复核结果。前置条件：已有 CI 容器环境。验收目标：真实合成场景、重复运行与失败路径的报告对应实施提交；工程验证不代替 Owner 验收。
