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

## CI 首轮诊断

首轮 Subject：中文 6af7ce17f6dd8c265add3bb948d56e9bc7c3de58；英文 17800383f824a3d0ed0804acec93f736650af92e。Pair Gate、非 Markdown 一致性通过，双语已推送。中文 M1 [34195927596](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34195927596)、英文 M1 [34195927631](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34195927631) 均 FAILED，各 947 项、1 失败、2 跳过。M2 的 34195927570/34195927617 均 SUCCESS。

中文 Artifact 10044021277 的失败 XML 指向 M1DemoScenario.kt:82 的注册重放；英文日志也确认同一集成用例 DemoHttpFailure。任务 3 为分组报告将注册重放移至 Lock 之后，而 RegisterManifest 在进入幂等执行器前要求 Release 为 DRAFT/REGISTERED。已核对任务 2 原顺序为注册重放在 Lock 前。修复仅恢复演示顺序，并在三次重放全部完成后标记场景 PASS；不放宽生产业务规则。真实集成测试已捕获该回归，必须在修复提交重跑 CI；首轮失败不计为通过。

修复本地 compileDemoKotlin 和 M1DemoReportTest 3/3 通过，退出码 0，实际日志 backend/build/task3-replay-order-local.log；diff 检查通过。真实集成测试仍由 CI 回归，不以模拟协议生成业务 PASS。

## CI 容器兼容性诊断

重放修复 Subject：中文 3e0947381b8bbb763a4495f0e567480fd2a9f389；英文 d768a64e2f732c991c3b627e3a16798d053e0d51。范围复审 APPROVE，Pair Gate 通过。中文 M1 [34196927967](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34196927967)、英文 M1 [34196927800](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34196927800) 的原 M1 候选门禁、M2.4 与 20/20 脚本测试通过，但真实演示在 JVM 启动前返回 DEMO_COMPOSE_START_FAILED；整体 M1 CI 仍 FAILED。M2 的 34196927973/34196927796 均 SUCCESS。失败演示 Artifact：中文 10044498291、英文 10044500316；中文已读取，只有最小 FAILED 报告。

已根据实际 CI 镜像的 [软件清单](https://github.com/actions/runner-images/blob/ubuntu24/20260831.293/images/ubuntu/Ubuntu2404-Readme.md)核实 Docker Compose 2.38.2。该版本 [start 源码](https://github.com/docker/compose/blob/v2.38.2/cmd/compose/start.go)不支持 --wait；[up 源码](https://github.com/docker/compose/blob/v2.38.2/cmd/compose/up.go)支持 --wait 和 --no-recreate。修复使用已创建并检查的容器执行 up --wait --no-recreate，保留本次启动责任及已有运行服务分支；不升级 Compose、不复制健康检查、不重建 volume。原脚本夹具未模拟这个版本限制，因此此前 20/20 不能证明真实启动兼容性。

兼容性回归探针先让旧入口在首次启动时因不支持参数退出 2，探针退出 1；实际日志 backend/build/m1-compose-compatibility-red.log。改用受支持命令后，首次、复用、已有服务、启动失败四项 PASS；实际日志 backend/build/m1-compose-compatibility-green.log。夹具现在拒绝 start --wait，要求 up 同时包含 --wait/--no-recreate，且检查已运行服务不执行 create/start/up。

## 下一步执行计划

当前结果：任务 3 实施与验证进行中。Git 状态：本记录随实施变更版本化。下一步动作：完成独立复审和双语 CI 并记录可复核结果。前置条件：已有 CI 容器环境。验收目标：真实合成场景、重复运行与失败路径的报告对应实施提交；工程验证不代替 Owner 验收。
