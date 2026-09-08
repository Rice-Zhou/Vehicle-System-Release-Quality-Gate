# 隔离演示启动器实施记录

- 记录日期：2026-09-08；任务 2 工程记录，不是 Owner 验收。
- 依据：[TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md)、[实施计划任务 2](../superpowers/plans/2026-09-08-local-m1-demonstration.md)。
- 实施前基线：中文 9c7dc5e54ed4cd517a4c65b7a2bb0066d3e6a6f7；英文 759f058d6cf381429cc8c6fe3dbf2a587856e7fe。基线双语 M1/M2 四条 CI 已核对 SUCCESS。

## 执行依据与范围

Owner 在任务 1 交付及明确的任务 2 下一步计划之后再次给出以下指令，授权隔离演示启动器与真实鉴权实施。原始中文按 Unicode 转义保存；不将其解释为完整演示验收、任务 3 实施或 Company 建设授权。

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

本轮只增加独立 demo source set、启动器、临时身份、最小身份表初始化、真实 HTTP 场景及测试。单命令脚本、固定样例和持久化结果格式由任务 3 交付。复用现有依赖与 PostgreSQL 测试环境，不安装新软件，不搭建 Company 资源。

## 实施约束

启动器复用 VsrqgApplication、SecurityConfig、JwtPrincipalMapper、JdbcProjectAuthorizer 和 Permission。演示 Bean 仅显式注入；普通应用扫描不得自动加载演示配置。生产 bootJar 不含 demo 类。

仅绑定 loopback 随机端口，固定 PILOT、NONE、本地 verifier 与当前进程信任版本；关闭 Issue/Traceability Worker。固定边界不能被环境中的 Company/真实 Provider 配置覆盖。数据库限定 loopback 的 vsrqg_demo，身份表初始化使用参数化事务和每次新标识，不预写任何 Release/Manifest/Validation/Lock 状态。

临时 JWT 在内存签名，真实 decoder 检查签名、issuer、audience 和时间约束；VIEWER 即使带写 scope 仍必须被既有 RBAC 拒绝。不得输出密钥、Token、密码或连接信息。损坏文件在注册前改变独立工作副本，历史报告和导出不得改写。

现有注册 API 的 422 响应不返回失败版本的 Manifest ID，且没有版本列表 API。损坏场景允许按本次 HTTP 返回的 Release ID 和合成 Project ID 参数化只读查询唯一 REJECTED 版本，仅取得 Lock 请求所需 ID；零条或多条直接失败。所有业务状态变化仍通过真实 HTTP，禁止查询后修正状态、猜测 ID 或创建新生产 API。该查找由启动器注入场景，不成为业务权威。

## 验证记录

RED：新增 M1DemoIntegrationTest 和 M1DemoPackagingTest 在新入口尚不存在时，compileTestKotlin 因未解析的新类失败，Gradle 明确 BUILD FAILED。原 PowerShell 包装在打印日志后返回 0，不能将其当作测试通过；后续命令已显式透传退出码。原日志为 backend/build/m1-demo-red.log。

本地 GREEN：使用已有 JDK 21 执行下述命令，退出码 0；M1DemoPackagingTest 4/4、ArchitectureTest 6/6、ArtifactPayloadVerifierTest 10 PASS/3 SKIPPED，共 23 项、20 PASS、3 SKIPPED、0 失败。跳过项为既有两个 Windows 符号链接能力测试和一个 POSIX 权限测试。compileTestKotlin（含真实 HTTP 集成测试）与 bootJar 成功；打包测试直接枚举生产 JAR，确认没有 demo 类，并验证普通组件扫描不加载演示配置。

```text
./backend/gradlew.bat -p backend test --tests '*M1DemoPackagingTest' --tests '*ArtifactPayloadVerifierTest' --tests '*ArchitectureTest' compileTestKotlin bootJar
```

GREEN 日志位于 backend/build/m1-demo-local.log，JUnit XML 位于 backend/build/test-results/test。缺少演示数据库输入时运行 m1Demo，实际退出码 1，输出固定 DEMO_INPUT_INVALID；日志为 backend/build/m1-demo-input-failure.log。启动器只读取 VSRQG_DEMO_DATABASE_URL、VSRQG_DEMO_DATABASE_USERNAME、VSRQG_DEMO_DATABASE_PASSWORD 三项显式输入，不接收命令行参数；当前尚无任务 3 的单命令脚本。

本机没有可用容器命令，真实 PostgreSQL/HTTP 测试必须在已有 CI 容器环境执行；新增测试不配置容器不可用时自动跳过。未运行不计为 PASS。

独立只读复审：APPROVE，未发现阻断问题；核对了 source set/bootJar 隔离、无演示组件扫描、真实 JWT、本地 verifier、配置隔离、初始化和 HTTP/只读定位路径。复审未重跑构建，真实 HTTP/数据库仍待 CI。契约与验收记录校验通过。

## CI 首轮诊断

首轮实施 Subject：中文 ee9260d676a104d440f62d0cd67e63329e6fc67b，英文 e4bbcfa48d8a83ca03525beac8f4bd93ff804fab。中文 M1 [34191581247](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34191581247) 和英文 M1 [34191581034](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34191581034) 均 FAILED；M2 的 34191581281/34191581026 均 SUCCESS。M1 报告 944 项、1 失败、2 跳过，失败为 M1DemoIntegrationTest 构造 DemoDatabase 时的 DEMO_DATABASE_INVALID，尚未进入 HTTP 场景。

根因已从现有 Testcontainers PostgreSQL 1.21.4 字节码核实：configure 自动加入 loggerLevel=OFF，getJdbcUrl 将该参数拼入 URL。演示入口有意禁止全部 JDBC 参数。修正测试夹具，按容器的实际 host、mapped port、database name 构造无参数 URL，并增加 loggerLevel 参数拒绝用例；不放宽入口、不丢弃未知参数、不改变业务实现。修复后必须重新执行双语 CI，首轮失败不能计为最终通过。

修复本地检查：M1DemoPackagingTest 4/4 与 compileTestKotlin 通过，退出码 0；日志为 backend/build/m1-demo-ci-fixture-fix.log。范围化独立复审 APPROVE，确认使用真实容器端点且不放宽入口边界。修复后的远端结果见下一节。

## 最终实施提交与远端结果

| 分支 | 最终实施 Subject Commit | M1 | M2 |
|---|---|---|---|
| Chinese | 68611b2f4e58c2eb7be8f03520207570c2296aae | [34192532742](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34192532742) SUCCESS | [34192532667](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34192532667) SUCCESS |
| English | 77e904f0feb44747ff69b60a0e164786606e6f42 | [34192532629](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34192532629) SUCCESS | [34192532641](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34192532641) SUCCESS |

读取中文 Artifact 10042865886、英文 Artifact 10042856006 的 full-test-results XML：各 944 项、942 PASS、2 SKIPPED、0 失败/错误；跳过仅为既有 EvidenceArchiveDirectoryAccessReaderTest 的两项 Windows ACL 测试。M1DemoIntegrationTest 1/1、M1DemoPackagingTest 4/4 均通过、0 跳过；Manifest 注册 7/7 和 Lock 并发 4/4 也全部通过。

集成用例通过真实 TCP HTTP、签名 JWT 和 PostgreSQL 执行六个场景（正常文件 Lock/导出、损坏拒绝、无身份拒绝、VIEWER 写入拒绝、幂等重放、历史稳定），并实际拒绝错误签名、过期、错误 issuer/audience 和未生效 JWT。生产 JAR 排除 demo 类，普通组件扫描不激活演示配置。已核对双语 Pair Gate 和非 Markdown 一致性。

最终 Subject 包含测试夹具修复；后续结果文档提交不是新的实施 Subject。任务 2 六项步骤关闭，任务 3 未实施，Owner 验收状态不变。以上为合成 M1 机械流程证明，不是完整 MVP、真实设备验证或 Company Ready。

## 下一步执行计划

当前结果：任务 2 实施、独立复审、双语 CI 与测试报告核查完成。Git 状态：本记录随双语实施提交。下一步动作：执行任务 3 的单命令入口与结果输出。前置条件：下一步执行指令；完整运行需要已有容器环境。验收目标：一条命令执行真实合成场景、输出脱敏结果、失败非零退出并保留数据；不代替 Owner 验收。
