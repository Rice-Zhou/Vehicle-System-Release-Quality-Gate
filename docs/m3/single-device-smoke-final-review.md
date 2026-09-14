# 单设备 Smoke 最终复核

## 范围与来源

本记录汇总 Task 7 Step 6 的 Tasks 1–7 整体工程复审与候选证据核对，候选为 [M3-SMOKE-FINAL-OWNER-GATE-001](../governance/acceptance/records/2026-09-14-m3-smoke-final-owner-gate-001.md)，Owner 状态为 `PENDING`。原设计批准与各历史记录不代替本次验收；不改变冻结权威链、`NOT_EVALUATED` 或 `verified=false`。

| 来源层级 | ZH | EN |
|---|---|---|
| 整体复审设计基线 | `0856fc38cad004c153c9a9c3f8ddd115f9be4af6` | 同一设计范围，以配对产品核对 |
| 固定产品 Subject | `9c9f97d9ebe5aadc64089530024912620eb2deb0` | `b5ed45d4cede1bb7f48f815da838febd647f1f80` |
| 恢复实际 checkout | `9a569258c3d22bdbe50b7286bdcb8740ae02541d` | `5ac9a2047d8d0ea7bf5ddfcbb75c1822378eaa0d` |
| 本轮开始时记录 HEAD | `d5ad3917b8c3855876c9e0e33fb160d521c99778` | `3cf5ed6cf0a636effb39a2c29d3ad6de74fbcf5f` |

恢复 checkout 与记录 HEAD 相对产品仅有 Markdown 变化；产品配对的 `467` 个 non-Markdown blob/mode 一致。本记录承载 commit 由 Git history 定位，不冒充产品或恢复执行 HEAD。

## Tasks 1–7 覆盖矩阵

| Task | 复核范围 | 历史工程与验收定位 |
|---|---|---|
| 1 | 最小 APK、输入与构建身份 | [APK 验证](minimal-apk-build-verification.md) |
| 2 | Agent 身份、注册、机器契约 | [身份验证](agent-identity-registration-verification.md) |
| 3 | Run/Attempt、调度、租约与固定 Context | [租约验证](run-lease-verification.md) |
| 4 | 有界 Payload、权限、Complete、下载与恢复 | [Evidence 验证](local-evidence-verification.md) |
| 5 | Event、Result digest、幂等、终态与事务 | [结果验证](attempt-result-verification.md) |
| 6 | 主机 Agent、ADB、LOG/SCREENSHOT、spool 与 ACK | [主机验证](host-agent-verification.md) |
| 7 | 正式 API 串联、CI、真机、恢复与最终候选 | [串联](single-device-smoke-verification.md)、[运行前提](local-runtime-verification.md)、[真机](real-device-smoke-verification.md)、[恢复](smoke-recovery-verification.md) |

各链接保留各轮 Subject、验收 ID、失败、测试计数与范围，不把历次执行相加为本轮测试。整体复审范围为上述设计基线至固定产品完成的完整变更，不限于最终截图修复。

## 准确 CI 与 Artifact

以下均为 `2026-09-10` 产品执行证据；`2026-09-14` 只读重核确认六条运行 `completed/success`，实时 metadata 的 size/digest 与既有记录一致、`expired=false`。本轮不据此声称新测试、新真机执行或重新下载。ZIP bytes/SHA-256/到期时间的精确表见[真机记录](real-device-smoke-verification.md)。

| Subject | 工作流 | CI Run | 选定 Artifact |
|---|---|---|---|
| ZH 产品 | M1 | [34479550025](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479550025) | [10153442303](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153442303) |
| ZH 产品 | M2 | [34479549785](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549785) | [10153328623](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153328623) |
| ZH 产品 | M3 | [34479549893](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549893) | [10153212746](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153212746) |
| EN 产品 | M1 | [34479549737](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549737) | [10153435188](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153435188) |
| EN 产品 | M2 | [34479549739](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549739) | [10153339613](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153339613) |
| EN 产品 | M3 | [34479549735](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549735) | [10153193975](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153193975) |

先前记录 HEAD 的六条 CI 亦为 `completed/success`：ZH M1/M2/M3 为 `34582569454` / `34582569526` / `34582569457`；EN 为 `34582569515` / `34582569499` / `34582569489`。它们绑定上表记录 HEAD，不是本新记录的 CI，也不替代产品 Artifact。

每份产品 M1 为 `1138` tests、`0` failures/errors、`3` Windows ACL skips，`135` retainedReports；两份列名 JAR 在 ZIP 外，未独立核对。每份 M2 为 `12/12` checks PASS。每份 M3 为 `128` tests、`0` failures/errors、`1` Windows junction skip，lint 保留 `3` warnings；该 junction 在历史本机 Agent `100/0/0/0` 中执行，不声称本轮重跑 Windows 测试。CI_FIXTURE 的原始 PASS/FAIL/FAIL 与 REAL_DEVICE 分开，CI APK `7645` bytes 与真机 APK `7677` bytes 不混用。

## 真实与恢复证据

`2026-09-10` 正常与确定 FAIL 真机 Run 均 `COMPLETED`、origin `AGENT`、wrapper exit `0`、scenario `PASS`，Case 分别为 `PASS` / `FAIL`。四份 LOG/PNG 的 ID、实际大小/摘要以及原三次失败、绑定修复 I1 CLOSED 的证据见[真机记录](real-device-smoke-verification.md)。

`2026-09-11` 的 `STOPPED_POSTGRESQL_COLD_COPY` 为 `1623` files / `71475501` bytes，恢复至隔离副本，原 `2` Run / `4` Evidence 的演练前后读取与下载校验 PASS。A 同 Run 重启后 `RESULT_ACKED`、install/launch 各 `0`，没有重复 Result/audit/outbox。B 为 Backend 停服后的 `TIMEOUT/TIMEOUT/SERVER`、`RECOVERY_DEADLINE_EXCEEDED`；晚写 `409:LATE_EVENT_CONFLICT`、Agent exit `1`、无 ACK，补验工具 exit `0` / `PASS`。B 已上传 LOG/PNG 可核对，但不属于其 SERVER Result 的空 Evidence 引用集合。

A/B 原 wrapper `FAIL` 原样保留；同 Run 独立补验 `PASS` 不改写 A 的 `generationStatus=FAILED` / `scenarioOutcome=FAILED` 或 B 的原失败。A 首轮根因仍 `UNKNOWN`。精确 Run/Attempt、时序、摘要和清理证据见[恢复记录](smoke-recovery-verification.md)。本轮只读核对既有材料，未启动设备、Agent、Backend 或数据库服务。

## 本轮独立结论与材料定位

整体工程复审结论：`APPROVE_FINAL`，Controller 已读取独立报告并确认。无新增 Critical、Important 或 blocking 发现，既有 P3 保持延期。这是工程处置，不是 Owner 决定；候选校验的完成状态独立记录。

本轮留存 ZIP 与真实 Payload 独立复算结论：`PASS`。六份 ZIP 的 size/SHA-256、CRC/路径、内部报告与 CI Payload 复核通过；正常/确定 FAIL 的离线 Result JCS/receipt/journal 与四份 Payload 核对通过。恢复报告、A 六份不可变文件、A/B 留存 Payload 和源/备份各 `1623` files / `71475501` bytes 重算一致。未重新执行恢复或晚写；A 的 JCS/运行行为由哈希未变的历史补验支持，CI ZIP 仅有 Result 引用，不声称重算完整 CI Result JCS。

私有材料由 Runtime Owner / Controller 管理，当前复核根为 `runtime-20260910-85ebd6c0/final-review-20260914/`：`live-ci.json`、`live-artifact-metadata.json`、`source-binding.json`。工程报告定位于忽略目录 `.superpowers/sdd/2026-09-09-single-device-smoke-implementation/final-review-20260914/whole-implementation-review.md`；独立字节核对为同目录 `evidence-reconciliation.md`。报告本体、ZIP、数据库、凭据、序列号与原始 Payload 不入库；固定保留期与未来可访问性为 `UNKNOWN`。

| 报告 | Generated At | SHA-256 |
|---|---|---|
| `whole-implementation-review.md` | `2026-09-14T01:39:40Z` | `5e0015cf83cd34132612745c70af849cfb7ac79647a4adb5aa524567e9eb5f3a` |
| `evidence-reconciliation.md` | `2026-09-14T01:43:03Z` | `87bf97d900af883065ca917cc5ad78db6bd69fea541b6ef6ab108b424ef56e23` |

## 保留风险与决定边界

P3 首因诊断覆盖保持 `OPEN/NON-BLOCKING/EXPLICITLY DEFERRED`。物理 USB/ADB 断连、完整 Crash/ANR 与断电恢复为 `UNKNOWN`；Backend 连接中断不替代物理断连，冷备不证明在线或跨 PostgreSQL 版本恢复。单账户/规范 serial 锁、Windows 持久化与 spool 容量边界保留；本地 PostgreSQL Windows ZIP 的 `NotSigned` 与本地摘要验证限制见[运行记录](local-runtime-verification.md)。

M2 start P95 为 ZH `1273 ms` / EN `1433 ms`，历史 `1340 ms` / `1650 ms` 亦未达 `1000 ms` 参考目标，仅满足共享 CI `30000 ms` 硬上限。canonical 非主路径字段覆盖有限；当前六份产品 Artifact 最早 `2026-10-10T13:01:50Z` 到期，历史最早 `2026-10-07` 约束保留。过期或无法访问时相应证据转为 `UNKNOWN`。APK/JDK 既有 warnings 保留。

本候选不等于完整 M3、车辆 Release Quality Gate、Company 或 Owner 验收通过。Owner 明确决定前不 merge、Tag、release 或 deploy；不新增 TDR，不修改旧验收历史。

## 下一步执行计划

当前结果：最终双语候选材料、独立工程复审与证据核对完成，Owner `PENDING`。Git 状态：本记录与产品分离，承载 commit 由 Git history 定位。下一步动作：Owner 仅审阅新 ID `M3-SMOKE-FINAL-OWNER-GATE-001` 并明确决定。前置条件：固定来源、独立报告与受控证据可访问。验收目标：Owner 对本新 ID 的决定、理由、时间和身份被明确记录，不借用旧设计批准。
