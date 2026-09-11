# 本地 PostgreSQL / Backend 演示环境验证

## 范围与授权

Owner 在上一轮明确“在 D 盘准备最小、隔离的本地 PostgreSQL/Backend 演示环境”及其前置条件后回复“执行下一步”，授权本次本地依赖准备。沿用 TDR-003、TDR-024/025；不启用 Company、真实 Provider 或系统服务，不代表 M3 Owner 验收。

使用中文源码 d1ac4a0e1645b1344404d5cc117d99e3c206eda9，配对英文 5d9169c57b21eb2e78941808f63df7a7371d2486；其中固定实施 Subject 仍为 dbd59a48ba9c7dc9279588e046182dbf97ab22ef / bc1f62637ac9f9357912abf85961a65cb0035852。本轮没有修改产品代码、Migration 或设备配置原始身份。后续承载本记录的提交独立版本化。

## 实际环境

- 日期：2026-09-10；Windows 当前账户，已有 JDK 21 与 PowerShell 7。
- 仓库外目录：D:/VSRQG-local-smoke/runtime-20260910-85ebd6c0。根目录禁用继承，只有当前账户的一条显式 FullControl 规则；私钥、密码、设备原始信息与数据库文件不入库。
- PostgreSQL 17.11 与仓库 compose 基线一致。使用[PostgreSQL 官方 Windows 页面](https://www.postgresql.org/download/windows/)指向的 [EDB ZIP 页面](https://www.enterprisedb.com/download-postgresql-binaries)，下载定位 https://sbp.enterprisedb.com/getfile.jsp?fileid=1260491。
- ZIP 为 341325378 bytes，SHA-256 为 4b8db0930c38f6ef845db919551dedda3b6b845aeb0927b3d79a6e8e9e4537cf。全部 ZIP CRC 与成员路径检查通过，只解压 bin/lib/share。此 SHA 为本机实测摘要，未声称与独立发布方摘要比较；postgres.exe Authenticode 为 NotSigned。
- 数据库只监听 127.0.0.1:55432，数据库名 vsrqg_demo，SCRAM-SHA-256；独立应用角色没有 SUPERUSER/CREATEDB/CREATEROLE。随机密码位于受控文件，配置只引用文件路径；没有 trust 认证、系统服务或全局 PATH 修改。
- Backend 使用 https://localhost:58443、PILOT、Provider NONE 与原隔离演示环境。服务端/Agent 使用本地演示 PKCS12 和独立证书信任库，90 天有效期；未修改操作系统信任库，未跳过 TLS 证书或主机名验证。
- 正常/失败主配置、身份/数据库/TLS 引用和 payload/evidence 已准备；引用上一轮私有 device.json 与 APK。未重新读取设备或执行 ADB；设备环境必须在真正执行前复核。

## 检查与证据

本地 RuntimeHealth.java 仅调用现有 M3Config.read、M3DemoBootstrap.start，使用真实 HTTPS 请求检查 health/liveness/readiness 与未认证业务 API，再关闭 Spring context；未调用 initialize、M3DemoMain 或 Agent。通过仓库外 Gradle init script 导出原 demo runtimeClasspath，原 demoClasses 均为 UP-TO-DATE，导出任务成功；该小型探针不是新的产品启动入口或协议夹具。

| 检查 | 结果 | 实测依据 |
|---|---|---|
| PostgreSQL 版本、连接与角色限制 | PASS | 实际 --version 为 17.11；psql 连接 vsrqg_demo，三项管理权限均 false。 |
| Flyway 迁移 | PASS | 15 条成功，按 installed_rank 的最后版本为 15。没有新增或改写 Migration。 |
| 首轮 HTTPS 健康 | PASS | health、liveness、readiness 均 HTTP 200 / UP；业务 API 未认证为 401。 |
| Backend 正常关闭 | PASS | context 关闭后 58443 不再监听。 |
| 数据库停止/启动 | PASS | pg_ctl 有界等待成功；停止期间 55432 与 58443 均无监听，重新启动成功。 |
| 重启后的迁移状态 | PASS | 迁移 version/checksum/success 与四项实体计数输出逐字节摘要相同。 |
| 第二轮 HTTPS 健康 | PASS | 再次三项 HTTP 200 / UP 与业务 API 401，Backend 正常关闭。 |
| 无演示业务执行 | PASS | release_record、test_run、test_result、agent 均为 0；未创建 Run、安装 APK 或采集设备 Evidence。 |
| 最终状态 | PASS | PostgreSQL 与 Backend 均停止，55432/58443 无监听；数据、配置、日志保留。 |
| 真机正常/FAIL、断连/Agent 重启、数据库/Payload 恢复 | UNKNOWN | 本轮未执行；数据库普通重启不等于备份恢复。 |

仓库外 logs 保存实际日志；以下为 SHA-256，供定位同一材料：

| 材料 | SHA-256 |
|---|---|
| RuntimeHealth.java | f23c695d0f577d492b816c42511649920aa6ca3c57283190bcdd2d5034b339cb |
| postgres.ps1 | 0ec1acef743bc121c1c654c748e7b8e236fc1c9585bd9640437700e074fff274 |
| backend-health-1.log / backend-health-2.log | ae1e1364f0d3b8e0a5f9d265ea116495b9fba8f6ab3c08beccecc24716d7b81e |
| db-before-restart.txt / db-after-restart.txt | 79042344d2d0e7aa6cfeada7e1cce0cca8b2c49df7af9a8bd0d99296c5cbf0a0 |

## 实测问题与保留限制

首次直接调用 pg_ctl 时，日志已显示服务启动，PowerShell 仍因长生命周期子进程继承输出句柄而等待。单独停止本次数据库后调用才返回，后续建库明确连接失败。改为 Start-Process 的 Hidden 模式、分离输出文件，并仅对 pg_ctl 使用有界 Process.WaitForExit；重新启动、建库、停止和第二次启动均通过。保留初次失败日志，没有重做 initdb 或删除数据库。诊断查询曾误用 release 表名导致只读查询失败；按实际 Migration 改为 release_record 后计数验证通过。

本轮只解决本地运行前提，未增加 Docker/Testcontainers；上一轮本地 m3IntegrationTest 因 Docker 缺失失败的事实保留。未重新运行六份实施 Artifact 或将普通重启称为故障恢复。既有 P3、性能、canonical 与到期限制继续见[串联工程记录](single-device-smoke-verification.md)。新 Owner Gate 仍为 PENDING。

## 操作入口与下一步

使用 PowerShell 7，在上述私有目录执行 postgres.ps1 -Action Start / Status / Stop。脚本只指向自己的 data 目录，Start 遇到端口已占用明确失败；无服务注册和自动启动。RuntimeHealth.java 与编译 classpath、受控配置保留，实际正常/失败演示仍使用仓库原 scripts/demo/run-m3.ps1 及 config-normal.json / config-fail.json，不能以健康探针替代它。不要重跑一次性的 prepare-config.ps1 或 initialize-postgres.ps1。

当前结果：本地数据库与 Backend 两轮健康、正常停止/启动和迁移状态保留验证完成；进程已停止。Git 状态：本记录按双语独立提交推送，提交版本由 Git history 定位。

后续正常与确定 FAIL 运行见[真机验证](real-device-smoke-verification.md)。上文结果与未执行状态保留为 2026-09-10 的历史事实。Task 7 Step 5 的实际证据现见[恢复验证](smoke-recovery-verification.md)，其中保留验证脚本失败与独立补验。当前唯一下一步：Task 7 Step 6 独立工程复审及候选证据包核对。前置条件：固定 Subject 与受控证据可访问。验收目标：形成发现处置报告及准确双语 CI/Artifact/真机证据映射；Owner 决定独立保持 PENDING。
