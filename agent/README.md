# 单设备 Smoke 主机 Agent

此独立 Kotlin/JVM 21 CLI 消费正式 Agent HTTPS 协议，以明确指定的 ADB serial 执行一个 `apk-launch-smoke` Case。它不创建 Release、Plan、Quality Result，也不修改 Issue Verified。主机测试通过不代表真实设备或完整 M3 通过。

## 构建与启动

使用已有 JDK 21；Wrapper 与 Backend 固定为 Gradle 8.14.4。Kotlin 版本导入 Backend version catalog，Jackson/JUnit 版本使用同一 Spring Boot BOM。无需启动 Spring 或数据库即可测试 Agent。

```powershell
$env:JAVA_HOME='<JDK21_ABSOLUTE_DIRECTORY>'
.\gradlew.bat clean test build
.\gradlew.bat installDist
.\build\install\vsrqg-agent\bin\vsrqg-agent.bat --server=https://<backend-host>:8443 --tls-config=C:/controlled/agent-tls.json --device=<registered-device-id> --adb-config=C:/controlled/agent-adb.json --apk=C:/controlled/smoke.apk --spool=C:/controlled/spool
```

Linux/macOS 构建使用 `./gradlew clean test build`，发行目录下使用 `bin/vsrqg-agent`。配置文件中的路径仍须为该主机的绝对路径。此任务只在 Windows/JDK 21 验证，其他主机需单独验证。

CLI 只接受六个必需的 `--name=value` 参数；未知参数、重复参数、空值、非 HTTPS、缺配置/文件以及配置或输出路径中的符号链接/Windows junction 均拒绝。错误只输出稳定 code 或异常类型，不打印凭据内容、serial、命令输出或 Payload 路径。

`agent-adb.json` 的结构如下。所有值均为占位符，文件必须存放在仓库外：

```json
{
  "serial": "EXPLICIT_USB_SERIAL",
  "adbExecutable": "C:/Android/platform-tools/adb.exe",
  "aaptExecutable": "C:/Android/build-tools/34.0.0/aapt.exe",
  "apksignerJar": "C:/Android/build-tools/34.0.0/lib/apksigner.jar"
}
```

只接受显式 USB/模拟器 serial（字母、数字、点、下划线、连字符）；不枚举设备、不选择首台、不接受 IP/port 或传输别名。`device` 必须是服务端预登记且绑定该 serial 的 Device 引用。Agent 不推断这项管理员配置关系。ADB 与 Build Tools 34.0.0 由受控主机预先提供；Agent 不安装工具链。签名命令使用当前 Java 直接运行 JAR，不执行 `apksigner.bat`。

`agent-tls.json` 只引用 PKCS12 与口令文件：

```json
{
  "keyStore": "C:/controlled/agent-identity.p12",
  "trustStore": "C:/controlled/backend-trust.p12",
  "keyStorePasswordFile": "C:/controlled/identity-password.txt",
  "trustStorePasswordFile": "C:/controlled/trust-password.txt"
}
```

身份仓库必须恰有一个私钥项；使用主机账户权限隔离以上文件。口令不作为 CLI 值传入，不写入日志。mTLS 由服务端验证，用户 JWT 不参与 Agent 调用；不跟随重定向，只允许仓库 OpenAPI 中的固定同源端点。

## 单实例与持久化

设备锁放在运行账户的 `~/.vsrqg-agent-locks`，文件名为 serial 的 SHA-256，不暴露原始 serial。它覆盖同一服务账户、同一 serial 的跨 spool/跨进程启动；spool 另有独占 journal 锁。

**部署前提：同一设备仅由一个受控服务账户运行本 Agent，且使用唯一规范 serial。** 账户目录锁不能保证跨操作系统账户互斥，也不能识别两个 selector 别名是否指向同一设备。此演示不提供设备池或跨主机协调锁。

每个 Attempt 保存 Command、Context、journal、待确认 Event、观察结果、Evidence bytes、上传会话和原始 Result。Journal 使用临时文件、文件 `force(true)` 与原子替换；不支持原子替换时明确失败。Windows JDK 不支持目录 fsync，因此不声称掉电时目录元数据绝对持久；丢失或损坏记录会阻止执行，不能当作新任务重装。

spool 必须置于仓库及公开目录之外，并由服务账户控制。可用空间低于 778 MiB 时心跳报告 DEGRADED 并停止新工作；这为三份上限 256 MiB 的 APK 快照和有界 Evidence 留出空间，是资源保护，不是质量阈值。本实现保留所有 spool 文件，包括已确认 Result；没有自动清理、保留期或磁盘扩容功能。

## 执行与恢复

- 心跳独立每 20 秒运行；poll 等待 20 秒，租约 90 秒，Case 最长 300 秒。有效期根据 Server 时间与本地主机单调经过时间计算，不能越过 Command deadline；服务端继续约束 Run deadline。
- 预检先固定读取 `ro.build.version.sdk`，只接受整数 API Level ≥ 26；缺失、非法值或过低版本在安装意图之前拒绝。Context 固定 APK bytes checksum、签名、versionCode、boot/build/fingerprint。安装使用已验证的 spool 快照；固定已有包查询 `pm path com.ricezhou.vsrqg.smoke` 仅在退出码 1 且 stdout/stderr 完全为空时确认未安装并继续。成功查询必须无 stderr 且给出唯一路径；其他非零、错误诊断或未知输出明确停止。已有包须能读取唯一 base APK 并确认同签名。split、未知签名/版本、不可读 APK 明确停止，不卸载、清数据或自动降级。
- 安装/启动前持久化意图；恢复 `INSTALL_INTENT` / `LAUNCH_INTENT` 时仅报告恢复等待，不重复动作。`INSTALLED` 只继续尚无启动意图的阶段；`ACKED` 已确认 STARTED 时不再新增 STARTED。未知 Event 响应只重放已持久化的同一请求与 sequence。
- 安装前、UI 观察后、采集结束及上传后固化 Result 之前复核当前 boot/build/fingerprint。任何变化都保留 spool 并停止；只有最后一次环境复核通过才写入可重放的 Result，避免复核失败后留下 PASS。
- 前台组件和本次 UUID 的 READY 行共同支撑 UI 断言。负例 mode 仍要求 READY，因此产生确定 FAIL；XML 禁止外部实体且上限 1 MiB。uiautomator 仅写本次 UUID 路径，之后只清理这个完整路径。
- LOG 只读取测试包 PID，保留本次固定标记与主机步骤 code，不保存整机日志；SCREENSHOT 只在测试 Activity 前台时读取 binary PNG。两项分别上限 1 MiB / 8 MiB；PNG 解码另有 16M 像素资源上限，Collector 不包含质量决策。
- 所有子进程并发读取有界 stdout/stderr，超限、非零退出、超时和租约失效均可见；只有上述固定已有包查询会识别明确的不存在出口，不打印原始诊断；只终止当前拥有的进程，从不执行 `adb kill-server`。
- `OBSERVED` 恢复只继续已有 bytes 上传；Session/Complete 回执确认后记录 Evidence ID。服务端明确的内容、完整性或已拒绝 Session 错误使用窄白名单识别：有界 Problem JSON 必须匹配 HTTP status 和当前固定请求路径，只保存稳定 code；先持久化原始拒绝原因，再核对有效租约，以 ERROR 和已确认的部分 Evidence IDs 提交 Result。未确认文件继续保留；重启不会再次上传已记录的永久失败。503、未知响应/错误、临时 I/O、权限拒绝及失效租约不会转成可写 ERROR 结果。Result 以共享 JCS 规则生成并原样持久化，`localFile` / `fileName` 不进入 wire 或 digest。
- 重启时可以先发送已持久化的完全相同 Result PUT，确认服务端已存在的幂等回执；这不获取新的可写租约。成功须核对原摘要和全部请求字段；409/租约失效/绑定冲突后保留 spool，仅诊断，不创 Evidence、不重新执行。`RESULT_ACKED` 无动作。

常见稳定失败 code：`CLI_REQUIRED_ARGUMENTS`、`SYMLINK_DENIED`、`DEVICE_LOCKED`、`JOURNAL_CORRUPT`、`DEVICE_API_LEVEL_UNSUPPORTED`、`DEVICE_API_LEVEL_INVALID`、`ENVIRONMENT_IDENTITY_CHANGED`、`APK_SIGNATURE_CONFLICT`、`APK_BASE_UNAVAILABLE`、`SMOKE_ASSERTION_FAILED`、`PROCESS_TIMEOUT`、`PROCESS_OUTPUT_LIMIT`、`LEASE_LOST`、`RECOVERY_WAIT_FOR_DEADLINE`、`HTTP_STATUS_409`、`SPOOL_INTEGRITY_ERROR`。故障退出后先检查服务端 Attempt/Run 与受控 spool；不要删除意图文件来强制重跑。新执行由新 Run/Attempt 驱动。

## 验证边界

测试直接导入仓库 Schema、OpenAPI 和 Backend canonical golden JSON；没有第二套 wire Schema。测试包括实际受限子 JVM、跨进程设备锁、Windows junction、localhost JVM HTTPS/mTLS、重定向/上传重试、Result 响应未知后的精确重放、长安装期间独立心跳及完整 Smoke 协议流程。

完整 Smoke 流程的设备端口是明确测试替身；没有枚举、安装、启动或读取真实 Android 设备。真实 ADB、真实 APK 在设备上的行为及 Task 7 串联均未验证。普通 Backend 的 M1 文件校验与默认 INCOMPLETE 行为未改动。
