# 单设备 Smoke 受控恢复验证

本记录覆盖 2026-09-11 Task 7 Step 5 的独立副本恢复、真实 Agent 重启和自有 Backend 停服演练。A、B 的独立补验均退出 0 并报告 `PASS`；原演练 wrapper 的 `FAIL` 原样保留。补验核对同一 Run，不新建 Run，也不把工具误报改写成原执行成功。

## 来源与证据边界

固定产品 Subject：ZH `9c9f97d9ebe5aadc64089530024912620eb2deb0`，EN `b5ed45d4cede1bb7f48f815da838febd647f1f80`。实际执行 clean checkout 为 ZH `9a569258c3d22bdbe50b7286bdcb8740ae02541d`，配对 EN `5ac9a2047d8d0ea7bf5ddfcbb75c1822378eaa0d`；相对产品 Subject 仅 Markdown 变化。运行摘要的代码来源采用实际 checkout，不冒称产品 Subject 就是运行时 HEAD。

使用正式 Backend API、恢复 worker、原版 Agent 与同一 spool；私有 JDI 工具仅暂停、终止本次拥有的进程并计数。计数采用精确方法入口断点，不改变产品协议、SDK 门槛或成功事实来源。所有下列 locator 相对 Runtime Owner / Controller 管理的私有根 `recovery-20260911/`；原始日志、截图、数据库、设备配置及身份材料不入库。生成时间来自报告文件 UTC 时间；固定保留期尚未设定，未来可访问性为 `UNKNOWN`。

| 报告 locator | UTC | SHA-256 |
|---|---|---|
| `cold-copy-manifest.json` | `2026-09-11T07:57:55Z` | `d0ddafe3fe65101094162e7e6b0b829b0a9d9ed57cebb77bde017b892a29a5c7` |
| `restore-report.json` | `2026-09-11T08:02:51Z` | `1c424a79ae7efdad1b48c275cde61fb426f069761e5bf52de88889c0e794662e` |
| `a-supplement/supplement-result.json` | `2026-09-11T08:45:18Z` | `6481d53637d5fea0fb9759d0a9ac4c3dddc940e0d3ad044910ffbe56cc3853ba` |
| `b-supplement/supplement-result.json` | `2026-09-11T08:46:31Z` | `54888e1288cff4dc56aacaa2d66a8a09939ea74f357749c79538b10dd31c465a` |
| `restore-post-exercises-report.json` | `2026-09-11T08:52:00Z` | `1c424a79ae7efdad1b48c275cde61fb426f069761e5bf52de88889c0e794662e` |
| `final-cleanup-check.json` | `2026-09-11T08:52:55Z` | `4f8321a406d7ca3359615b5c5be8e30b7e8acfb937a3b64e5f53f8f7a7203598` |

## 数据库与 Payload 独立副本恢复

原 PostgreSQL 停止后执行 `STOPPED_POSTGRESQL_COLD_COPY`，同时复制数据库与 Payload，共 `1623` 个文件、`71475501` bytes。这是停止状态的物理冷备，不是 `pg_dump` 或在线一致性备份。恢复到新隔离副本，未覆盖原数据；该副本承载后续 A、B 演练。

恢复检查通过正式 API 重新读取原来的 2 个 Run、4 个 Evidence，核对 Result digest、元数据及下载字节校验和；原 normal 仍为 `COMPLETED/PASS/AGENT`，原确定性失败仍为 `COMPLETED/FAIL/AGENT`。精确身份和四份 Payload 摘要见[此前真实设备记录](real-device-smoke-verification.md)。演练前、后的恢复报告均退出 0、报告 `PASS`，且报告 SHA-256 完全相同。

## A：Server 接受后、持久化 ACK 前重启 Agent

Run `run_01a08f99036178f9bd9c7c2b0aaf46f8`；Attempt `01a08f99-0362-707a-9dfb-03230d9fe8f3`；lease `lse_01a08f990364785a9f26a1ff6f748b72`。在 `2026-09-11T08:32:56.496442300Z` 精确暂停于 Result HTTP 成功返回后、receipt 写入前：Server 已 `COMPLETED`，journal 为 `UPLOADED`，receipt 尚不存在。随后终止本次 Agent，再从同一 spool 启动原版 Agent。

首次进程 install/launch 各 `1`，受控终止 exit `1`；重启进程 install/launch 各 `0`，自然 exit `0`，journal 达到 `RESULT_ACKED`。已接受 Result 的 fencing token 为 `1`；终态数据库为 `2`，同 lease、无新 Attempt。lease 到期 `2026-09-11T08:34:18.173798Z`，case deadline `2026-09-11T08:37:47.095918Z`，终态完成 `2026-09-11T08:32:56.378357Z`；本路径不依赖超时恢复重新分配。

补验比较 journal 除 phase 外的全部字段，仅 `evidenceIds` 按生产 Set 语义核对相同成员、数量和无重复；6 个不可变文件的实际字节及摘要前后一致。原 Result 的 JCS digest、持久化 receipt 与正式 Server Result 一致；重放前后正式 Results、input digest、数据库快照一致。Result、result audit、result outbox、terminal outbox 均保持 `1`，无重复安装、Result 或终态事件。

最终 Run/Case 为 `COMPLETED/PASS`，origin `AGENT`。Result digest `sha256:551b2fda7d5c2d277456ffe8c958175583ad044b355e20b179d5edf4148daf80`；input digest `sha256:2eef6fdde8dc9aad43218c03128d5be909c652d05e8798f3f153482c10a7693c`。补验正式重新下载 LOG/PNG 并核对字节、哈希和 PNG 解码。

## B：自有 Backend 停服、租约恢复超时与晚写拒绝

Run `run_01a08f9b091e7de8a73583a702766acc`；Attempt `01a08f9b-091f-7fd4-bbde-fd083b2ef3bb`；lease `lse_01a08f9b092076cbb040ecd74faeddf2`。在 `2026-09-11T08:35:07.665765500Z` 的 `UPLOADED` 阶段、Result PUT 前暂停，`2026-09-11T08:35:07.701508Z` 关闭本次拥有的 Backend context，再放行 Agent，实际得到 `HTTP_IO_ERROR`、exit `1`。首次 install/launch 各 `1`。Backend 于 `2026-09-11T08:35:10.208018500Z` 恢复，正式 worker 按原 deadline 收口。

lease 到期 `2026-09-11T08:36:29.909406Z`；recovery deadline `2026-09-11T08:38:29.909406Z`；实际结束 `2026-09-11T08:38:30.408858Z`，早于 case deadline `2026-09-11T08:39:58.907970Z`。同 lease 的 fencing token 从 `1` 变为 `2`；终态为 `TIMEOUT/TIMEOUT/SERVER`，原因 `RECOVERY_DEADLINE_EXCEEDED`。没有替换 Attempt 或新 lease 的恢复成功主张。

独立补验对同一 spool 发起晚写重放，install/launch 各 `0`，实际收到 `409:LATE_EVENT_CONFLICT`、exit `1`，没有 ACK。补验工具自身 exit `0`、`PASS` 表示拒绝行为符合预期。晚写前后正式 Results 与终态数据库相同：Result `1`、Agent Result `0`、result audit `0`、result outbox `0`、terminal outbox `1`、timeout audit `1`。

Server Result digest `sha256:95ace5d6523ca48e4a209493d83dacc143d173fcf7aa9a1604f5f7dbd8b4d921`；终态 input digest `sha256:2aedffbe355eb4b2c007302e16e63d2721a93d05f5787e5a2ed79187e5203476`。Server Result 的 `evidenceIds` 为空，两项 evidence requirement 为 `FAILED`；已上传的 LOG/PNG 仍可下载并通过字节校验，但不能作为该超时 Result 的成功证据。

## 下载字节摘要

| 路径 | Evidence ID | 类型 | bytes | SHA-256 |
|---|---|---|---|---|
| A | `ev_01a08f992db175e99c377b947d546273` | `LOG` | 195 | `0b6af965a0f8c2047803d8ad2c111df9988d1d5dba7c530f2b978b2bd848f0e7` |
| A | `ev_01a08f992e6473b19c7ae9b642736ef9` | `SCREENSHOT` | 49844 | `3d3c684d82e61dc07d70d213c8ec4afa9ba80664b2aa38fd54ad043f8617975a` |
| B | `ev_01a08f9b2e57722889b390509180ba61` | `LOG` | 195 | `b5803c809be9e5490afa4265fff1f18cc763254ab8ba44374f07fcebdcdb3afe` |
| B | `ev_01a08f9b2f0a7d6cbfb415c1a4c9d6fe` | `SCREENSHOT` | 49291 | `1724ac525d03bf586de497e3a125c8f04e72b8eda64d5eb970cb183a1f4de99b` |

## 原失败与独立补验

A 首轮未到注入点，Run 因 allocation deadline 超时；原始根因仍为 `UNKNOWN`。B 首轮 JDI 全局 MethodEntry 观察导致显著的 TLS 密码运算延迟；合成 PBKDF2 的有界 RED/GREEN 支持改用精确断点，但不能据此倒推 A 首轮唯一根因。原始输出均保留。

A-v3 已完成实际暂停和重启，却因私有工具按数组顺序比较生产 Set 而报 `ACK_JOURNAL_BINDING_CHANGED`。原 wrapper exit `1`、`output-agent-restart-v3/summary.json` 的 `generationStatus=FAILED`、`scenarioOutcome=FAILED` 保留；其中正式 `runStatus=COMPLETED`、`caseStatus=PASS` 也未改写。独立补验按严格 Set 语义核对同一 Run，并保全原摘要四项事实。

B-v2 已产生正式超时结果，但私有工具错误要求执行初期 `input_digest=null` 与终态生成值相等，原 wrapper `FAIL` 保留。独立补验以同一 Run 的既有终态为基准执行晚写检查，不补造 Result，不修改历史失败。两项补验只修正验证边界，不修改产品实现。

## 清理、限制与下一步

`2026-09-11T08:52:55.781930+00:00` 的清理检查为 `PASS`：源数据与备份全部 `1623` 文件、`71475501` bytes 的 SHA-256 未变；本次恢复 PostgreSQL 已停止，端口 `55432`、`58443` 无监听，原及恢复目录无 `postmaster.pid`，本次拥有的 Agent 进程均已退出。

B 是自有 Backend 停服造成的 Agent→Backend 连接中断，不是 USB/ADB 物理断连。物理 ADB 断连、设备重启、在线备份、跨 PostgreSQL 版本恢复、Company Archive、通用故障矩阵均未验证。本轮没有全局网络修改、设备清数据、卸载或重启。单设备受控结果不能扩大为全部 M3 或发布验收通过。

本轮不改变 Quality Gate 的 `NOT_EVALUATED` 或 `verified=false`。完整 Crash/ANR 与断电恢复未验证；P3 和 M2.5 的既有风险继续沿用[此前真实设备记录](real-device-smoke-verification.md)，不借本次结果关闭。

下一步为 Task 7 Step 6 最终整体复核，再由 Owner 对新候选记录 [M3-SMOKE-RECOVERY-001](../governance/acceptance/records/2026-09-11-m3-smoke-recovery-001.md) 作出决定；当前为 `PENDING`。旧验收记录、旧 Subject 和决定历史保持不变。
