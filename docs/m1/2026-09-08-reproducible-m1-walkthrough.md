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

## 最终实施提交与证据核查

兼容性修复范围复审 APPROVE；Pair Gate 与非 Markdown 一致性通过。最终四条 CI 均已完成 SUCCESS：

| Branch | Implementation Subject Commit | M1 | M2 |
|---|---|---|---|
| Chinese | 917f0c74b297cfb74e2e6dc73714de81057309cd | [34198426316](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426316) SUCCESS | [34198426303](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426303) SUCCESS |
| English | 4a05ce5b3f88df1db233610d486d4619730267ed | [34198426318](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426318) SUCCESS | [34198426370](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426370) SUCCESS |

下载并读取中文 M1 Artifact 10045129918、英文 10045102918 的 full-test-results XML：各 947 项、945 PASS、2 SKIPPED、0 失败/错误。跳过仅为既有 EvidenceArchiveDirectoryAccessReaderTest 的两项 Windows ACL 用例；M1DemoIntegrationTest 1/1、M1DemoPackagingTest 4/4、M1DemoReportTest 3/3 均通过且无跳过。双语脚本矩阵 20/20 通过。

实际读取演示 Artifact：中文 10045128467、英文 10045102163。每份含三次 PASS 和一次预期 FAILED，全部绑定对应最终实施 Subject 且 workingTreeDirty=false；正常结果六场景全部 PASS，实际 HTTP 状态覆盖 200/201/401/403/409/422，重放记录为 [201,201,200]。导出的 Manifest 与报告中的 Release ID、payloadSha256 一致，报告仅包含约定字段。错误口令的报告包含 DEMO_STARTUP_FAILED/DEMO_PROCESS_FAILED、全部 NOT_RUN 和空 HTTP 记录，不伪造业务成功。

| Case | Chinese runId | English runId |
|---|---|---|
| Fresh PASS | 945c3d3f-b781-4660-87a5-fa44f154579c | ff1f2261-ee08-4308-93b6-8868ced1df1d |
| Reuse PASS | af764fe7-6ed8-4b33-92e4-3271638810f0 | 2dd3c2f5-355d-4ffd-8653-44885f644eb4 |
| Existing service PASS | 8aec51d4-bb05-406f-aa64-1ac0bd6d5107 | a1536676-b45f-4e04-a6e1-5a1920965b80 |
| Wrong password FAILED | 66189ba5-2fba-433b-adc8-9f8a6c8ae4b3 | 2e193f41-5574-46be-b1f8-2c854232ddf4 |

已读取双语 CI 的最终生命周期 PASS 日志：首次与停止后复用运行完成后无本项目运行容器；原先运行的服务在正常/错误口令执行后保持运行；volume 创建时间及源样例摘要不变。上述检查只证明同一 CI 作业内的数据保留；本地操作说明提供长期复用方式，不将临时 runner 视作长期存储。

本轮演示和 M1 Artifact 于 2026-10-08 UTC 到期。结果记录和实施代码已在 GitHub 版本化；Artifact 是有期限的运行证据，不新增 Company 归档资源或不可变存储前置条件。后续文档提交不是新的实施 Subject。任务 3 六项步骤关闭，完整 TDR-021 三项实现完成；仍仅为合成 M1 演示，不代表完整 MVP、真实车辆或 Owner 验收。

## 下一步执行计划

当前结果：M1 合成演示实施、验证及 Owner 验收完成，见[验收记录](../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md)。Git 状态：双语验收及导航记录已版本化并推送。下一步动作：编制复用既有 M2 Issue/Build/Traceability 能力的最小合成串联演示方案。前置条件：下一步执行指令；无需 Company 资源。验收目标：方案对应既有 P1 缺口，包含完整链与缺边链、明确 Verified=false，实施前确认范围。
