# Agent 身份与注册 — Task 2 工程验证

## 范围与实施指令

2026-09-09，Owner 在明确的 Task 2 下一步后回复下列原文，按上下文解释为“执行下一步”。本轮仅实施 Agent 身份、注册与上下文机器契约，不执行 Task 3–7、设备操作或 Company。本记录属于工程验证，不代替 Owner 验收，不设新的组件验收门槛，不授权 merge、Tag、发布或部署。

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

依据：[实施计划](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[设计](../superpowers/specs/2026-09-09-single-device-smoke-design.md)、[TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)及 [TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md)。

## 实施 Subject 与行为

- 中文 Subject：[729812348756ee20e897136b0a89177f0b0ac22a](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/729812348756ee20e897136b0a89177f0b0ac22a)。
- 英文 Subject：[269a6307f7685c035db89cb8e967a3fde6a615c4](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/269a6307f7685c035db89cb8e967a3fde6a615c4)。
- 上述 Subject 包含初始实现、并发与请求体读取修复及既有 CI 测试适配；本记录的后续提交不替换实施 Subject。
- V12 固定 Agent、SERVICE principal、项目、Device 与证书指纹绑定；注册复用现有权限、幂等存储及事务内 Audit，每次 replay 前重新鉴权。
- 独立 X509 与用户 JWT 链不能互换凭证。注册默认关闭；开发证书配置见 [Agent Protocol](../v0.2/08-test-agent-protocol.md)。未启用真实 Provider，也未登记真实设备身份。
- Context Schema 严格限制必填及未知字段。Context GET 与 Payload PUT 本轮仅声明机器契约，运行实现分别属于 Task 3、Task 4；普通和敏感 Evidence 按敏感度授权，HIGH 控制保留。

## 已执行检查

本地使用既有 JDK 21.0.7+6、Kotlin 2.2.21、Spring Boot 3.5.16、Gradle 8.14.4。测试证书由 JDK keytool 临时生成，口令与私钥不入库。独立 HTTPS 测试使用真实 TLS 和生产安全链，但注册应用及用户 decoder 为测试替身；数据库绑定必须由 PostgreSQL 集成测试另行验证。

| 检查 | 结果 | 证据与边界 |
|---|---|---|
| TDD RED | 已观察 | 新权限缺失时断言失败；新增 Context 路径未登记时契约检查失败。Docker 初始化失败不算行为 RED。 |
| 本地受影响测试 | 47/47 PASS | 权限 7、架构 6、默认上下文 2、API 10、池预算 4、安全链 9、真实 HTTPS 5、注册应用 4；0 failure/error/skipped，包含编译与 bootJar。共享 SSL 初始化调整后，另定向复验 HTTPS/池预算 9/9 PASS。 |
| TLS 证书与入口 | PASS | 强制发送指定证书，未受信/过期证书必须出现 SSLException；用户 Bearer 与证书不能互换入口。 |
| 契约与验收记录校验 | PASS | 双语 schemas=5、positive=13、negative=6、operations=36；Context 另逐项拒绝缺失和未知字段。 |
| PostgreSQL 本地 | 环境阻断 | 14 项因本机无 Docker 初始化失败，未记录为通过；需以下准确提交 CI。 |
| 独立任务与最终工程复审 | PASS | 共享锁升级死锁、完整聚合后才检查请求体上限两项已修复并复审；覆盖受控并发、读取字节上限及 HTTPS 定长/chunked 413。CI 测试适配另经限定复核，无未解决关键发现。 |
| 双语 Pair Gate | PASS | 准确实施提交的结构、EnglishOnly、链接及非 Markdown 一致性通过。 |

## 准确提交 CI

已核对下列运行的 head SHA 与实施 Subject 一致，四条 CI 均为 SUCCESS。两语 M1 完整测试 XML 各为 992 tests、0 failure/error、2 skipped；跳过的是 Linux 上不适用的既有 Windows ACL 测试。新增 PostgreSQL 身份 8、并发注册 2、数据库与 TLS 4，共 14/14 PASS，无跳过。两语 M2 均为 12/12 PASS，摘要的 exactCommit、sidecar 摘要及恢复结果已核对。

| 分支 | M1 | M2 |
|---|---|---|
| 中文 | [34339324545](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324545) | [34339324487](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324487) |
| 英文 | [34339324583](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324583) | [34339324521](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324521) |

以下原始 ZIP 已下载，大小及 SHA-256 与 GitHub Artifact 元数据匹配；测试 XML 保留于 ZIP 中。本记录版本化保留验证摘要，原始 Artifact 仍有期限，不声称永久托管或管理员不可修改。

| Artifact | bytes | SHA-256 | expiresAt UTC |
|---|---:|---|---|
| 中文 M1 10099430400 | 225150 | 51f06ea7fc92ded5920af5107be410383d50b9f414e66a0c57a37e33f3045318 | 2026-10-09T10:27:22Z |
| 英文 M1 10099425590 | 225154 | 76d2b439ee400008b3c03a70e2c3785c368b04da24707325401a3fc14074287c | 2026-10-09T10:27:13Z |
| 中文 M2 10099285527 | 1756 | 4ff206b0ea63939b354926daa9a99fabbb0f9792b52e83c67b5542552a7fa192 | 2026-10-09T10:23:17Z |
| 英文 M2 10099316363 | 1760 | ad263489eae7dca490892954679ce51d8644d38c0ab5f244b30254d70add76ca | 2026-10-09T10:24:09Z |

首次中间提交的 [M1 CI](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34337578605) 曾有 10 项失败，涉及无数据库测试缺少新适配器替身、API 集合、迁移/恢复版本及 TLS 测试动态配置。修复保留 34 项兼容基线，只允许两条新增 Agent 路径；当前升级/恢复验证 V12，历史 V10→V11 专测固定 target 11，共享连接池预算检查未放宽。中间提交的局部通过未代替以上最终 CI。

## 已知限制

OpenJDK CDS instrumentation 及 Schema 加载使用 ObjectMapper URL overload 的 deprecation 提示保留，没有通过抑制配置掩盖输出。测试证书生成失败附诊断日志路径。本轮不产生设备安装/UI、Run、Result、Evidence Payload 或 Release PASS 证据，不代表完整 M3 或 Company 验收；既有性能、canonical 摘要覆盖范围及历史 Artifact 保留限制不因此关闭。

本轮 M2 回归的创建 Run P95 为中文 1089 ms、英文 1323 ms，仍未达到 1000 ms 参考目标，仅通过共享 CI 硬上限。M2 证据继续使用既有固定的 migrationVersion=V11 字段，不能据此推断最新数据库版本；本轮当前 V12 由 M1 迁移和恢复测试验证。

## 下一步执行计划

当前结果：Task 2 工程实现、独立复审及双语准确提交 CI 完成，未代做 Owner 验收。Git 状态：上述实施 Subject 已推送并核对远端，记录提交独立。下一步动作：执行 Task 3，实现 Run、Attempt、调度与租约。前置条件：Task 3 实施指令；沿用已接受设计，不需要 Company 资源。验收目标：Locked Manifest/Plan 绑定、设备独占、并发领取/ACK、租约到期和取消历史及上下文隔离的真实 PostgreSQL 测试通过，双语提交可核对。
