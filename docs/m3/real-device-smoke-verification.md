# 单设备真机 Smoke 验证

## 范围与固定 Subject

2026-09-10，Owner 在明确下一步后授权执行单台车机正常与确定 FAIL 两次 Smoke。首次操作前已核对指定设备 ADB 授权、API 34、boot/build/fingerprint 与配置一致；允许操作仅限既定测试包。沿用 [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)、[TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md)和原包装层/生产 Agent，不 seed Result 或 Evidence，不改变冻结权威链。

最终产品 Subject：ZH `9c9f97d9ebe5aadc64089530024912620eb2deb0`，EN `b5ed45d4cede1bb7f48f815da838febd647f1f80`。两次实际执行均使用准确 ZH Subject、clean 工作区；配对已原子推送并核对远端，467 个 committed non-Markdown blobs 一致。本机 70 份既有 CRLF/LF 差异经核对不改变 Git blob/语义，未顺带重写。

本记录与产品提交分离。新 [M3-SMOKE-REAL-DEVICE-001](../governance/acceptance/records/2026-09-10-m3-smoke-real-device-001.md) 为 PENDING；旧 [M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001](../governance/acceptance/records/2026-09-10-m3-smoke-implementation-owner-gate-001.md) 保留原 Subject `dbd59a48ba9c7dc9279588e046182dbf97ab22ef` / `bc1f62637ac9f9357912abf85961a65cb0035852` 与 PENDING，不借用旧设计批准或改写其历史检查。

## 本次真实结果

两次均为 SYNTHETIC_DEMO / REAL_DEVICE、Run COMPLETED、AGENT 原始 Result、wrapper exit 0、generationStatus SUCCEEDED、scenarioOutcome PASS。第二次场景正确保留 Case FAIL。均为 releaseQuality NOT_EVALUATED、verified=false。

| 模式 / Plan、Case version | Run | Attempt | Case | Result digest |
|---|---|---|---|---|
| normal / v1 | `run_01a08b64329e7dd28165bd706c00fb18` | `01a08b64-32a0-7bc4-a9d5-d6bd47161898` | PASS | `sha256:61ec5d4f832a2b5664361a21854b68350ab83ded2a25b6144e3164956129862e` |
| assertion-failure / v2 | `run_01a08b65dfdd71f388e0536be8b6e330` | `01a08b65-dfdf-7162-bfde-5dc96bf2f059` | FAIL | `sha256:862950c8b59ae9c898432252bbd4e2e778cc53cda7375cddf098f0e4d194a249` |

四份 Evidence 经正式 API 下载、实际 bytes 大小/SHA-256 复核；只读数据库确认均 AVAILABLE。以下 SHA-256 同时匹配 payloadChecksum 与 downloadSha256。

| 模式 | 类型 | Evidence ID | bytes | SHA-256 |
|---|---|---|---|---|
| normal | LOG | `ev_01a08b64562e728c8a1181c66bf4f5c9` | 192 | `7dec0d76f6ee4acf165939f57daa6c6dd9115755998ba141618b71235eb3b436` |
| normal | SCREENSHOT | `ev_01a08b6457487df1ac90f59ffb32a620` | 50541 | `00f3fdc2fae93b38b371f66d4c84567a25d02a2a61d03df9c300ec20a1e191bd` |
| assertion-failure | LOG | `ev_01a08b66027772dda3eea8e73286246c` | 191 | `48584b62e7a51d1c883529b5aaadfb2031adb7bf06e27e505c83e17b8ff17d08` |
| assertion-failure | SCREENSHOT | `ev_01a08b66031a7eb8974e2d8d6b8662a9` | 50783 | `e430d4d1f699bc8176614164f0e1bb894e62c378905b5a0fe586d8224fd23170` |

| 摘要定位 | 原始 summary.json SHA-256 |
|---|---|
| `output-normal-retry-3/summary.json` | `924995d9b24853f5423d804c94e38689f8419953f5c43b69057d763c0a17ed65` |
| `output-fail/summary.json` | `583124fcc7e822f2ba091259dafa7d1304e0911b5328567233b83c15070250df` |

两次 APK SHA-256 均为 `282187c056abe33b6f2b7629896d32a27d6244318990d6ec909e7136a4377ea6`，CONFIG SHA-256 均为 `6b3772119d8e2bec6133a07141e0004f040c1a76b1e0e53da1c0c76b4bd137a6`。Manifest 与 Release 关联保留在固定摘要中；测试 APK 不代表完整车辆 Release。

## 历史失败与修复

所有原报告、Run、Result、spool 与 bytes 保留；下表定位均相对于受控 runtime 集合，摘要失败没有被后续成功覆盖。

| 输出目录 | 当时 ZH Subject | Run | Attempt | summary.json SHA-256 |
|---|---|---|---|---|
| `output-normal` | `b67c1fe475d8403abe6079bdd8361411be348568` | `run_01a08b3a93237ef382ab4d135f4965ad` | `01a08b3a-9324-7770-98ba-f421d71379ce` | `ed7b0e8d231481e585e14838eac060d81dd51c42997a76ff3bac83082e5ca05b` |
| `output-normal-retry-1` | `97e8a7ac1c179c86a4b1f113052ba460219a8f3f` | `run_01a08b43c58172328a8960f60a6377c8` | `01a08b43-c583-7369-9635-5b5107fe96a5` | `724e6f613a7e74e1c3cfe320f3543774fe4a4b931dfff826b57f9850d2a17e65` |
| `output-normal-retry-2` | `07994a20f111dc1250d0e11bc4a0d5103e16367e` | `run_01a08b5079d87c7a826a8117f18c9f5b` | `01a08b50-79d9-76d8-ab2d-6f58e3553eb8` | `76de20a8225476081d84d8c10c84c9e71c8ee88d837edba12c5789477b7ecca6` |

1. 首次 Windows `libs.resolve("*")` 在启动 Agent 前抛 InvalidPathException，报告 SCENARIO_FAILED。协调器取消后 DB 为 Run/Attempt CANCELLED、SERVER/BLOCKED Result、0 Evidence；不能因摘要缺 Result 引用而声称无服务端 Result。改用 JVM classpath 字符串；修复 ZH `97e8a7ac1c179c86a4b1f113052ba460219a8f3f` / EN `9bda7b52d3ff801347bc592f6d43ac88b342e024`。
2. retry-1 为 COMPLETED / 原始 ERROR，reason SCREENSHOT_FOREGROUND_REQUIRED，仅 LOG，journal RESULT_ACKED；摘要 EVIDENCE_COUNT_INVALID。实际前台记录的 `topResumedActivity=` / `ResumedActivity:` 未被识别。精确支持实测格式并保留组件/user/task 边界；修复 ZH `07994a20f111dc1250d0e11bc4a0d5103e16367e` / EN `7413d1a6db6bfdf5fcf2832ff0fdf7d45d43e754`。
3. retry-2 为 COMPLETED / 原始 ERROR，reason PNG_INVALID，仅 LOG，journal RESULT_ACKED；摘要 EVIDENCE_COUNT_INVALID。设备 screencap stdout 混入 347-byte 诊断前缀。唯一截图路径改为本次 UUID 临时 PNG → binary cat → 精确清理，保留 PNG 校验而非扫描/剥离前缀或 fallback；修复 ZH `aa54839ff44522934d8867f0eda35a7162756c90` / EN `1655c09420dfe64b4f7392ed3b953b2f9baa5c5d`。探针 PNG 只用于诊断，不计正式 Evidence。
4. 独立 quality review 在上述截图改动发现 Important I1：SDK 早期失败后截图可能空绑定或沿用旧 Attempt。真机未继续执行，先以最终 Subject 修复唯一绑定入口，在设备操作前清空旧值并严格绑定当前 Context，未绑定明确 AgentFailure。范围重审 APPROVED，I1 CLOSED，无新 Critical/Important/Minor；原 NEEDS FIXES 报告保留。P3 首因覆盖仍 deferred。

## 分轮自动验证与独立方法

以下计数属于各次命令，不累加；GREEN 均为 0 failures/errors/skips。完整命令及 XML/HTML 保留在忽略的 SDD 目录 `.superpowers/sdd/2026-09-09-single-device-smoke-implementation/`。

| 修复轮次 / 子目录 | RED | 定向 GREEN | 完整或配对 GREEN |
|---|---|---|---|
| Windows / windows-launch-fix | 1 / 1 failure | Backend M3 19 | ZH 19、EN 19；实际 JVM 加载含空格目录内两个 JAR |
| 前台 / foreground-format-fix | 9 / 3 failures | Agent 9 | ZH 87、EN 87；test/build/installDist |
| PNG / screenshot-stream-fix | 15 / 7 failures | Agent 15 | ZH 95、EN 95；test/build/installDist |
| I1 / screenshot-stream-fix/binding-fix | 11 / 5 failures | Agent 24 | ZH 100、EN 100；test/build/installDist |

Controller 的独立 VerifyRealEvidence 对两次运行均 PASS：比对准确 Subject/Case/Run，重算保留 Result 请求的 JCS 摘要，与摘要、receipt 和 durable journal 核对，确认 receipt 绑定字段一致且本次 Attempt 为 RESULT_ACKED；同时重算两个实际 LOG/PNG 的 size/SHA-256 并解码 PNG。最终只读 DB 核对 AGENT PASS/FAIL 及四项 AVAILABLE，不写入或修正结果。

受控 locator 集合为 `runtime-20260910-85ebd6c0`，访问责任人为本地 Runtime Owner / Controller，根目录及权限见[本地运行记录](local-runtime-verification.md)。其中 `output-normal-retry-3/summary.json`、`output-fail/summary.json`、`logs/normal-independent-verification.txt`、`logs/fail-independent-verification.txt`、`logs/failed-attempts-index.json`、`logs/final-real-runs-db.txt`、`logs/final-runtime-stopped.txt` 支持本记录。原始设备配置、凭据、日志和截图不入库；需要复核原始 bytes 时由访问责任人提供受控访问。

最终 owned PostgreSQL 与 Backend 均已停止，55432/58443 无监听。全部数据、spool、APK 和历史输出保留；无卸载、清数据、设备重启、断连/Agent 重启注入或数据库/Payload 恢复。

## 准确 CI 与保留限制

准确最终配对 Subject 的两条 M3 CI 均 SUCCESS，Controller 已独立下载核对选定 Artifact 的 ZIP size/SHA-256、CRC/路径、APK bytes/摘要、三个不同 CI_FIXTURE Run 的原始 PASS/FAIL/FAIL 及各自 LOG/PNG 摘要、JUnit 与 lint。每份共 128 tests、0 failures/errors、1 skipped；Linux 跳过 Windows junction 专用测试，该测试已在本机 Agent 100/0/0/0 中执行。每份 lint 保留 3 项既有 warning。

| 分支 | M3 CI Run | Artifact | ZIP bytes | ZIP SHA-256 | expiresAt |
|---|---|---|---|---|---|
| ZH | [34479549893](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549893) | [10153212746](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153212746) | 28842 | `352d5f5ac6f8011e288ad47758757e3934ecefdfdf0d9e2bc975b348f8dafa5d` | 2026-10-10T13:02:19Z |
| EN | [34479549735](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549735) | [10153193975](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153193975) | 28789 | `b287cff01bdd00bd1309ae83157ea32ee87e20a995385870b944ce05a4d2530a` | 2026-10-10T13:01:50Z |

受控核验摘要与 metadata 定位：同一 runtime 集合下 `ci-real-subject/m3-zh.summary.json` / `m3-en.summary.json` 及对应 `.metadata.json`。CI APK 各 7645 bytes（ZH SHA-256 `497a6dac984766038d6327d5ed4ed19178bafafb27d5ea6e4a9bf3b46beecad8`；EN `44a9dd88229c0b5d3efeb29573de950594ea5cc32c57c3a93b2d166e9567ba4a`），与真实设备使用的 7677-byte APK 分开记录；CI_FIXTURE 不作为 REAL_DEVICE。

M1 两条准确 CI 均 completed/success，Controller 已独立核验两份选定 m1-evidence Artifact 的 ZIP/摘要、exact Subject 和每份 135 项 retainedReports 的 bytes/hash。每份 1138 tests、0 failures/errors、3 skipped：Linux 跳过 3 项 Windows ACL 测试，不声称本轮 Windows 已运行它们。两份列名 JAR（`vsrqg-backend-0.1.0-SNAPSHOT-plain.jar`、`vsrqg-backend-0.1.0-SNAPSHOT.jar`）位于 Artifact 外，未独立复算，不计入已核验 bytes。受控材料为 `ci-real-subject/m1zh.metadata.json` / `m1zh.summary.json` 与 `m1en` 对应文件。

| 分支 | M1 CI Run | Artifact | ZIP bytes | ZIP SHA-256 | expiresAt |
|---|---|---|---|---|---|
| ZH | [34479550025](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479550025) | [10153442303](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153442303) | 275050 | `a31f1473bdaea741726234998eec9f7efeee8cf7c9f11cb2909cc4bee63db336` | 2026-10-10T13:07:54Z |
| EN | [34479549737](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549737) | [10153435188](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153435188) | 275353 | `7ff449f939b2155c55153f4bd2fed423f44ad99150b486d85b3e8f9245fe4e79` | 2026-10-10T13:07:44Z |

准确最终配对 Subject 的 M1/M2/M3 六条 CI 与六份选定 Artifact 均独立核验通过。各项范围、跳过项和未纳入 Artifact 的材料边界分别记录，不扩大为全部产物或 Company 验收。


M2 两条准确 CI 与所选 Artifact 独立核验 PASS：ZIP/sidecar、exact Subject、性能文件、恢复与 replayDigest 一致，各 12/12 checks PASS（不是将各项 tests 字段累加）。受控材料为 `ci-real-subject/m2zh.metadata.json` / `m2zh.summary.json` 与 `m2en` 对应文件。当前 start P95 为 ZH 1273 ms / EN 1433 ms，仍高于 1000 ms 参考目标，仅满足共享 CI 30000 ms 硬上限，不代表 Company 性能达标。M2 恢复报告不能证明 M3 数据库+Payload 恢复。

| 分支 | M2 CI Run | Artifact | ZIP bytes | ZIP SHA-256 | expiresAt |
|---|---|---|---|---|---|
| ZH | [34479549785](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549785) | [10153328623](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153328623) | 1755 | `10e35f42085d27baf221e3ef8b1f9c0ed5138bb31529af19416bb8c472587df4` | 2026-10-10T13:05:10Z |
| EN | [34479549739](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549739) | [10153339613](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153339613) | 1758 | `45e024ca457125962df783bc9816ef0697506a05f4bca51eee906427e6bf4922` | 2026-10-10T13:05:26Z |

真实连接中断、Agent 重启及数据库+Payload 配套恢复仍 UNKNOWN；普通数据库停止/启动不证明故障恢复。完整 Crash/ANR、断电、M3、车辆 Release Quality Gate、Company 与 Owner 验收未交付。单账户/唯一规范 serial 锁、Windows 持久化和 spool 保留边界见[主机记录](host-agent-verification.md)。

既有 P3 OPEN/NON-BLOCKING/EXPLICITLY DEFERRED；APK OldTargetApi、MissingApplicationIcon、SetTextI18n 及 JDK21/Java8 source-target warnings 保留。M2.5 Run P95 的历史 ZH 1340 ms / EN 1650 ms 未达 1000 ms 参考目标，canonical 非主路径覆盖有限，最早 2026-10-07 历史 Artifact 到期约束保留；当前 CI 到期单独核验，不用历史日期替代。

## 唯一下一步

当前结果：正常/确定 FAIL 真机链路和独立 bytes/回执核对完成，进程已停止，Owner 状态 PENDING。Git 状态：本记录为独立文档收尾，最终承载提交由 Git history 定位；产品固定 Subject 如上。

上文结果与未执行状态保留为 2026-09-10 的历史事实。Task 7 Step 5 的实际证据现见[恢复验证](smoke-recovery-verification.md)，其中保留验证脚本失败与独立补验。当前唯一下一步：Task 7 Step 6 独立工程复审及候选证据包核对。前置条件：固定 Subject 与受控证据可访问。验收目标：形成发现处置报告及准确双语 CI/Artifact/真机证据映射；Owner 决定独立保持 PENDING。
