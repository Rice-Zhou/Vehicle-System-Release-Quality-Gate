# 单设备 Smoke 串联运行手册

本入口用于 [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md) 与 [TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md) 的 `SYNTHETIC_DEMO`。它经正式 API 创建 Release、校验并 Lock APK/CONFIG Manifest、创建 Run、启动主机 Agent，再查询 Result 和下载两份 Evidence 复算 SHA-256。`Run COMPLETED` 或场景通过不表示 Release PASS、Issue Verified、完整 M3 或 Company 完成。

## 运行前提

使用已配置的 JDK 21、PowerShell 7、PostgreSQL、Android SDK 和明确授权的单台设备。APK 构建基线见 [APK README](../../demo/android-smoke/README.md)，Agent 配置与恢复限制见 [Agent README](../../agent/README.md)。本入口不安装 SDK、数据库、服务，不枚举或自动选择设备，不卸载、清数据、刷机或重启设备。

首次设备操作前由操作者核实唯一明确的 ADB serial、授权状态、API Level ≥26，并确认允许测试包安装、启动、定向日志、UI 层级读取和截图。只允许 `com.ricezhou.vsrqg.smoke/.SmokeActivity`，保留原始设备身份在受控配置文件中。截图前应清理个人通知覆盖；真实截图、配置、证书、spool 不自动进入 GitHub。

`START` 仅连接 loopback 的 `vsrqg_demo` 数据库，启动绑定 loopback 的隔离 HTTPS Backend；它关闭本次启动的应用及 Agent 子进程，保留数据库、Payload、spool、APK 和结果目录。`EXISTING` 只使用既有受控演示服务及其既有用户 JWT，绝不初始化或关闭该服务；服务器必须已经启用演示文件验证、Smoke、Agent 注册及 Evidence，并预登记对应项目、身份、Device、Published Plan。普通 Backend 默认 `INCOMPLETE` 策略不变。

## 严格配置

所有 JSON 拒绝未知字段、重复字段和错误类型。主配置、身份、设备、环境、TLS 配置与密码文件放在仓库和公开目录之外，使用绝对、规范、无符号链接或 Windows junction 的路径。凭据内容不放入 JSON，只引用受控文件。`outputRoot` 必须尚不存在；`payloadRoot`、`spool`、`outputRoot` 不能互相包含。APK 每文件上限 1 MiB，沿用 M1 文件验证器。

主配置示例（占位符必须替换为已准备的文件）：

```json
{
  "server": { "origin": "https://localhost:8443", "lifecycle": "START" },
  "identityConfig": "D:/controlled/m3/identity.json",
  "apk": "D:/controlled/m3-input/smoke.apk",
  "deviceConfig": "D:/controlled/m3/device.json",
  "payloadRoot": "D:/controlled/m3-payload",
  "spool": "D:/controlled/m3-spool",
  "outputRoot": "D:/controlled/m3-output-normal",
  "planVersion": 1
}
```

`server.origin` 只接受无账号、query、fragment 或业务路径的 loopback HTTPS origin，显式端口 1–65535；`lifecycle` 只接受 `START` / `EXISTING`。没有 fixture 模式字段；正常入口固定使用真实 Agent 主类。

`START` 的 identity 文件：

```json
{
  "databaseConfig": "D:/controlled/m3/database.json",
  "serverTlsConfig": "D:/controlled/m3/server-tls.json",
  "agentTlsConfig": "D:/controlled/m3/agent-tls.json"
}
```

`database.json` 只接受下面三个字段。URL 必须为 `jdbc:postgresql://localhost:PORT/vsrqg_demo` 或等价的 loopback 地址，不允许连接参数；用户名和密码从受控文本文件读取。

```json
{
  "url": "jdbc:postgresql://127.0.0.1:55432/vsrqg_demo",
  "usernameFile": "D:/controlled/m3/db-user.txt",
  "passwordFile": "D:/controlled/m3/db-password.txt"
}
```

两份 TLS 配置使用与 Agent 相同的四字段结构，分别引用服务端和 Agent 的 PKCS12 身份。Agent keystore 必须只有一个私钥条目，服务端证书必须匹配 origin 主机名，信任链必须有效。服务端 TLS 使用 `want` 让用户 JWT 路由可不带客户端证书；独立 Agent SecurityFilterChain 仍强制可信 mTLS，JWT 不能替代它。

```json
{
  "keyStore": "D:/controlled/m3/agent.p12",
  "trustStore": "D:/controlled/m3/trust.p12",
  "keyStorePasswordFile": "D:/controlled/m3/key-password.txt",
  "trustStorePasswordFile": "D:/controlled/m3/trust-password.txt"
}
```

`EXISTING` 的 identity 文件仅接受：

```json
{
  "projectKey": "existing-synthetic-project",
  "userTokenFile": "D:/controlled/m3/user-jwt.txt",
  "agentTlsConfig": "D:/controlled/m3/agent-tls.json"
}
```

用户 JWT 要属于该项目的既有 USER，并具有 `release:create release:read manifest:write manifest:lock test:execute test:read evidence:read` scope 与原有权限目录允许的角色；不能用 Agent SERVICE 身份代替。令牌有效期需覆盖整个场景。后端拒绝任何不匹配的身份、项目、设备或 Plan。

`device.json`：

```json
{
  "agentId": "agt_explicit_demo",
  "deviceId": "dev_explicit_demo",
  "adbConfig": "D:/controlled/m3/agent-adb.json",
  "environmentConfig": "D:/controlled/m3/environment.json",
  "versionCode": 1,
  "signingCertificateSha256": "REPLACE_WITH_64_LOWERCASE_HEX_CHARACTERS"
}
```

`agent-adb.json` 只接受 `serial`、`adbExecutable`、`aaptExecutable`、`apksignerJar`，结构与 [Agent README](../../agent/README.md) 一致。`environment.json` 只接受 `bootSessionId`、`buildId`、`buildFingerprint`，使用操作者从选定设备读取的准确数据；文件的准确 bytes 被作为 CONFIG 注册并固定。APK 版本与签名摘要从同次 APK 的 aapt/apksigner 输出读取，Agent 执行前还会独立核对本地 APK 与设备安装后的身份。

`payloadRoot/artifacts` 用于 content-addressed APK/CONFIG 输入；相同 hash 的现存文件必须相同，绝不覆盖冲突 bytes。`payloadRoot/evidence` 必须预先存在，且符合服务账户独占权限：POSIX 为 `0700`，Windows ACL 只允许当前服务账户、SYSTEM 和 Administrators。普通共享目录会明确失败；入口不会悄悄放宽或改写既有目录权限。

## 执行与检查

```powershell
$env:JAVA_HOME='<JDK21_DIRECTORY>'
./scripts/demo/run-m3.ps1 -Config D:/controlled/m3/config.json
if ($LASTEXITCODE -ne 0) { throw 'Smoke scenario failed; inspect fixed codes and summary' }
```

包装层先以同一 Kotlin 配置解析器做只读校验，然后构建 Agent distribution 并调用独立 Backend `m3Demo` entry。配置缺失或无效返回非零与固定 `CONFIG_INVALID`，不生成结果报告。工具链阻止配置校验时明确 `CONFIG_CHECK_FAILED`，也不伪造场景报告。配置已验证后出现构建或子进程失败会保留失败摘要；在实际执行模式尚未建立时 `executionMode` 为 null。正常运行的模式来自执行主类，不从配置推测。

Plan 固定为 `single-device-smoke`；Case 固定为 `apk-launch-smoke`。`planVersion=1` 使用 `normal`，要求原始 Case PASS；`planVersion=2` 使用 `assertion-failure`，要求原始 Case FAIL，而 `scenarioOutcome=PASS`。第二次运行使用新的 outputRoot 和新的 Run；禁止改写 Published Plan 或旧 Manifest 摘要来换 mode。

首次 `START` 创建 synthetic 项目及分离的 USER/SERVICE 身份。重复 `START` 只能复用完全匹配的 synthetic Project/Agent/Device/证书绑定，以及原 Published v1/v2 定义；仅为本次进程内 JWT decoder 新建同项目的 scoped USER 会话。它不重绑定、更新身份或修改 Plan，不比较 lastHeartbeat 等动态观察。部分初始化、非演示数据、撤销或不匹配的绑定明确失败。既有 Run/Result/Evidence 保留，新环境通过新 Run 与准确 CONFIG bytes 固定。

成功输出只有 `summary.json`、`log.txt`、`screenshot.png`。summary 保留来源 commit/dirty、模式、Release/Manifest 摘要、Run/Attempt、Case/Result 原状态及 digest、两份 Evidence ID/size/checksum 和下载复算值。Result API 未暴露独立数据库 Result ID，报告以 Attempt ID 与正式 resultDigest 引用 Result，不伪造 ID。`generationStatus` 与 `caseStatus` 分开，始终保留 `releaseQuality=NOT_EVALUATED` 与 `verified=false`。

场景先检查注册返回的 Agent，并由正式 Create Run API 校验项目、Device、Locked Manifest 与 Published Plan。Create Run 同步创建 QUEUED Attempt；协调器从正式 Results 读取其 UUID，使用可选 `--until-attempt-acked=<UUID>` 启动有限 Agent。Agent 自身负责 poll→Context→持久化 journal→ACK；协调器不能在尚未 dispatch 时提前查询 Context。结束时从用户 API 查询 Run→Result→Evidence，申请各自下载 grant，限制下载大小，重新计算每份 SHA-256，并再次读取同一 Run 结果确认历史投影稳定。随后最多等 30 秒，让 Agent 校验并持久化本次目标的 Result 回执为 RESULT_ACKED，并自然退出零；等待超时、非零退出或未完成夹具断言均不能生成成功摘要。旧 Attempt 重放完成不等于本次目标完成；原六参数 Agent 仍持续运行。summary 不包含原始序列号、账号、JWT、私钥、download grant URL 或本机绝对路径。

进程输出有界，超时只终止本次拥有的进程树；包装层总子进程期限 900 秒、HTTP 30 秒、实际 Run 观察最多 610 秒。Backend 固定业务期限仍为分配 60 秒、Case/Command 300 秒、Run 600 秒；心跳 20 秒、poll 20 秒、lease 90 秒、恢复窗口 120 秒。异常会尝试经用户 API 取消本次 Run，取消失败单独记录。不会删除旧输出、Payload、spool，或卸载 App。

## CI 与恢复覆盖

[独立 M3 workflow](../../.github/workflows/m3-smoke.yml) 先显式预检既有 SDK/JDK，再构建同次 APK 与 Agent。`m3IntegrationTest` 是 opt-in source set，普通 Backend `test` 不需要 Android SDK 或 Agent。受控夹具只替换 `SmokeDevice`，使用正式 AgentLoop、AgentClient、真实 mTLS Backend、PostgreSQL 与上传/下载 bytes；它不模拟生产 Controller、不直接 seed Result 或 AVAILABLE。

```powershell
./scripts/tests/m3-demo.tests.ps1
./agent/gradlew.bat -p agent --no-daemon test build installDist m3FixtureClasspath
# 按 workflow 准备同次 APK、签名摘要、commit/dirty 和 fixture classpath 环境变量后：
./backend/gradlew.bat -p backend --no-daemon m3IntegrationTest
```

`m3IntegrationTest` 每次实际执行，禁用 up-to-date 与 build-cache 复用；独立 invocation 目录不覆盖旧材料。本地入口自动生成 UUID，`backend/build/m3/current-invocation.txt` 指向当前 UUID。CI 显式生成测试专用 `VSRQG_M3_INVOCATION`，复用同 UUID 会失败；这不是产品配置。只上传 `backend/build/m3/fixtures/<UUID>/plan-{1,2,2-reuse}/{summary.json,log.txt,screenshot.png}` 中本次生成的文件。

CI 产物名为 `m3-ci-fixture-<commit>`：包括 APK 与实测身份摘要、XML、`plan-1`/`plan-2` 的 summary/LOG/PNG。只上传显式枚举的受控文件，不包含临时身份/TLS/数据库配置。`CI_FIXTURE` 绝不计作 `REAL_DEVICE`。v1 覆盖本次启动服务，以及相同身份再次 START 执行 v2；`plan-2-reuse` 保留重复启动输出。回归检查不匹配 Agent 绑定拒绝且 USER 数量及旧 Result digest 不变；独立 v2 覆盖已有服务执行后仍存活；所有 DB/SDK 缺失都失败，不静默跳过。

该整链 CI 未注入设备断连、Agent 重启或备份恢复；summary 的 `notCovered` 保留这些项目，以及完整 Crash、ANR、断电、Company 和完整 M3。Agent 既有恢复单测只证明组件行为。实际恢复演练需在获准的既有演示服务上，使用明确拥有的独立 Agent 进程、同一 spool 与正式 Run API；保存注入前状态、准确时序、租约与 fencing、恢复 bytes、终态和未重复安装的证据。不能在不确定安装阶段自动重放动作，不自动断电或重启设备。

备份恢复必须由操作者另行保存并恢复同一时点的数据库与 Payload 副本，核对清单及每份 bytes 的 SHA-256；单纯复制或读取当前目录不算恢复验证。设备、数据库或恢复资源不可用时相应项目写 `UNKNOWN`，不能用这份工程手册或 CI 夹具替代真实交付证据。
