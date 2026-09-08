# TDR-021 — 最小本地 M1 合成演示

- 日期：2026-09-08；状态：Accepted（任务 1/2/3 实施范围）。Owner 在任务 2 交付后再次指示执行下一步，确认任务 3 单命令入口、样例与结果输出实施；完整演示已完成实际验证，待 Owner 审阅，不宣称 Owner 验收通过。
- 依据：[阶段目标](../reviews/2026-09-08-demonstrable-product-priority.md)、[缺口清单](../reviews/2026-09-08-demonstrable-product-gap-inventory.md)。
- 基线：中文 a2ebf7d079a9e7f5f506bd8e66bb5f27b3493724；英文 af2ab1c4448268cbbd866c412ce46650ae3a92ee。

## 目标与选择

交付可重复的本地流程：启动现有 Backend → 初始化合成项目与身份 → HTTP 创建 Release → 注册含实际文件摘要的 Manifest → 校验 → Lock → 导出。同时执行损坏文件和权限拒绝场景，输出可读结果与脱敏 JSON。只证明 M1 机械闭环，不代表真实车辆 Release 或完整 MVP。

| 方案 | 取舍 |
|---|---|
| 独立 demo source set 启动器，复用 Backend 和 JWT/RBAC | 推荐；无需新服务，实际 HTTP/数据库路径可运行，演示代码不进入生产包 |
| 新建 OIDC 服务和完整前端 | 当前闭环不需要额外环境与 UI；延期 |
| 测试 mock 或预写 VALID/LOCKED | 跳过需要证明的校验与权限路径；不作为交付方案 |

## 文件校验接入

新增 ArtifactPayloadVerifier 接口，输入为按 Manifest 顺序排列的小写 SHA-256 列表；PayloadVerification 复用现有 ValidationStatus、ManifestViolation，并携带 validatorVersion。ValidateManifest.evaluate 先执行既有一致性检查，无结构错误时调用 verifier；结果仍由 RegisterManifest 的同一事务、insertValidation 和既有表持久化。无新 HTTP API、Schema 或 Migration。

普通 Backend 默认 verifier 继续返回 INCOMPLETE、ARTIFACT_CHECKSUM_NOT_VERIFIED 和既有 validator version。演示启动器显式注入本地实现，只在该进程内信任 m1-local-payload/1，不修改全局 allowlist。

本地实现只读启动器指定的合成目录，文件名为摘要；必须实际读取字节重新计算 SHA-256。请求不能提供路径或 URL。拒绝空列表、非法摘要、符号链接、非普通文件、越界、读错、摘要不符，以及超过每文件 1 MiB/每次 16 文件的输入。这些是本地适配器范围，不修改全局 Schema。仅返回固定 violation code 与 Manifest 相对位置，不输出本机路径或原始异常。

验证在注册时完成；validate API 继续读取持久化报告，不改写历史。损坏场景在注册前改变独立工作副本的一字节，并创建新 Release/Idempotency-Key；修复后另建 Release，不能覆盖失败记录。注册后文件变化不反向改变报告，本方案不承诺持续文件监控或管理员不可修改。合成源文件原样保留。

## 身份与运行边界

新增独立 demo source set、m1Demo JavaExec 任务和 com.ricezhou.vsrqg.demo 包，复用 main runtimeClasspath 与同一 VsrqgApplication。生产 bootJar 排除所有 demo 类；测试可依赖 demo output，main 不依赖 demo。

启动器绑定 127.0.0.1 随机端口，固定 PILOT、archive provider NONE，关闭 Issue/Traceability Worker，不加载 Company/真实 Provider 配置。只允许 loopback 的专用 vsrqg_demo 数据库，拒绝其他库名/主机。复用 deploy/dev/compose.yml，采用独立 Compose project vsrqg-m1-demo、端口 55432、独立 volume；不新建服务类型。缺 Docker 或不可用的 JDK 21 时明确退出，不自动安装软件。

启动器用现有 Spring Security/Nimbus 在内存生成一次性 RSA 密钥，签发 10 分钟 JWT；真实 JwtDecoder 检查签名、issuer、audience、exp/nbf。issuer 为 http://localhost/vsrqg-demo，audience 为 vsrqg-m1-demo，不进行远端 discovery。复用 SecurityConfig、JwtPrincipalMapper、JdbcProjectAuthorizer 和 Permission，不允许 permitAll、假 decoder 或复制角色规则。

初始化通过参数化事务仅新增本次运行的 project、principal、project_assignment，包含 RELEASE_MANAGER 和 VIEWER；标识冲突直接失败，不覆盖既有行。Release/Manifest/validation/locked 状态全部经真实 HTTP 生成，不使用测试 seeder。客户端在同一 JVM 内持有 Token；不提供 token endpoint，密钥、Token、密码不写入 Git、日志或结果文件。

现有注册 API 的 422 响应不返回失败版本的 Manifest ID，且没有版本列表 API。损坏场景允许按本次 HTTP 返回的 Release ID 和合成 Project ID 参数化只读查询唯一 REJECTED 版本，仅取得 Lock 请求所需 ID；零条或多条直接失败。所有业务状态变化仍通过真实 HTTP，禁止查询后修正状态、猜测 ID 或创建新生产 API。该查找由启动器注入场景，不成为业务权威。

## 样例与操作生命周期

样例使用标明 SYNTHETIC_DEMO 的 UTF-8 CONFIG 文件，不伪装成可刷写镜像。Manifest 使用创建所得准确 ID、project、buildId 及文件实测摘要，不能复用契约示例占位摘要。

PowerShell 入口为本次子进程设置演示数据库配置、启动 Compose、运行 m1Demo 并透传退出码，不改变用户已有环境。结束时仅停止本次启动的服务，保留 volume 和输出，不自动删数据；已运行的演示服务不擅自停止，连接或口令不匹配即失败。复用服务时必须显式提供匹配的仓库外演示口令，不把新生成口令用于既有 volume。

首次和复用运行均显式提供 VSRQG_DEMO_DATABASE_PASSWORD，脚本不自动生成、输出或保存口令；这使保留 volume 能用同一仓库外口令重用。脚本只接受本机 Docker endpoint。runId、代码提交、固定样例路径和输出目录由入口生成，通过 VSRQG_DEMO_RUN_ID、VSRQG_DEMO_CODE_COMMIT、VSRQG_DEMO_SAMPLE_FILE、VSRQG_DEMO_OUTPUT_DIRECTORY 交给 JVM。报告同时保存 workingTreeDirty，通过 VSRQG_DEMO_WORKING_TREE_DIRTY 传入；未提交修改存在时，代码提交只表示 HEAD，不证明这些修改的字节。JVM 尚未启动时只记录最小 FAILED 元数据；后续由同一 summary 承接实际业务结果，停止服务失败也必须令总结果 FAILED。

每次执行输出 backend/build/demo/m1/<runId>/summary.json 和 manifest.json，内容限 SYNTHETIC_DEMO、代码提交、场景状态、Release/Manifest ID、摘要、错误码和实际 HTTP 状态。不得包含私钥、Token、原始身份或连接信息。启动器结束时关闭自身应用 context，失败非零退出，不重试伪造成功。

## 验证、兼容与回退

必须验证：正确文件→VALID→Lock→导出；注册前一字节损坏不能 Lock；无身份返回 401；VIEWER 即使带写 scope 仍被 RBAC 拒绝；错误签名、过期、错误 audience/issuer 拒绝；重复 key 返回相同结果；历史导出稳定；生产包不含 demo 类；普通 Backend 仍为 INCOMPLETE，Company 控制不受影响。

本地 verifier 单测无需 Docker。真实 HTTP/数据库场景使用 PostgreSQL 17.11 与 JDK 21，不以 MockMvc jwt 注入替代。后端单测默认 60 秒超时，新增目标测试接入现有 CI，保留 M1/M2 回归和双语 Pair Gate。本机无可用 Docker 命令，已验证入口明确失败；完整演示与数据复用已在现有双语 CI 执行通过，详见任务 3 记录。

回退时停用 m1Demo，保留报告和数据库；普通 Backend 使用默认 verifier。无数据库迁移或部署。需要远端文件、真实身份、扩大文件规模或历史重新验证时重新评估；触及冻结语义转 ADR。该文件验证接口不替代 TDR-004 的大型 Evidence 存储。

## 实施与下一步

[实施计划](../../superpowers/plans/2026-09-08-local-m1-demonstration.md)分为文件校验、隔离启动器、单命令流程三项。自检覆盖两项 P0 缺口，未引入预写业务结论、mock JWT、生产演示身份、历史改写或 Company 依赖；这是方案自检，不是独立评审或 Owner 验收。

当前结果：三项任务均完成，独立复审、双语 M1/M2 CI、真实演示和 Artifact 核查通过；详见[任务 3 记录](../../m1/2026-09-08-reproducible-m1-walkthrough.md)及[操作说明](../../m1/demo-runbook.md)。Git 状态：双语实施与结果记录已版本化并推送。下一步动作：由 Owner 审阅 M1 合成演示交付。前置条件：Owner 审阅；本机重跑需要已有容器环境和演示口令，无需 Company 资源。验收目标：明确记录是否满足当前展示目标；不代替 Owner 验收，不自动启动下一里程碑。
