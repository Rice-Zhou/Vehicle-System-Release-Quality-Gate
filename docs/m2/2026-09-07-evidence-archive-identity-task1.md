# Evidence Archive 身份扩展 — 任务 1 验证记录

## 授权与范围

Project Owner 本轮明确指令为“执行任务1”，授权执行已接受 TDR-019 对应计划的任务 1；原文 Unicode 转义如下。本记录是实施验证记录，不是 Owner 对工具实现或 Company 归档的新验收决定。

```text
\u6267\u884c\u4efb\u52a1\u0031
```

依据: [TDR-019](../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md)、[Implementation Plan](../superpowers/plans/2026-09-07-m2-5-evidence-archive-identity-implementation.md).
实施基线: f9f311bb2ce87fea7d2639f98505d179661901ad.

## 修改与边界

- 新增唯一内部 profile，精确接受 `1 / V0-2-EVIDENCE-ARCHIVE-001` 和 `2 / M2-5-EVIDENCE-ARCHIVE-001`。Parsed/Verified 模型显式携带 Int schemaVersion，无默认值；旧 M1 构造点明确传入 1。
- 三个 Schema 共用 work-package 的 identity 定义；Node 严格注册依赖、校验三份文档的版本/ID/摘要绑定，成功摘要来自已验证输入。
- 未绑定 v2 FAIL 只允许 JSON null ID/身份、空 Artifact 和合法错误/cleanup；已绑定报告保留旧 nullableIdentity 对象格式。禁止 null PASS、IN_PROGRESS 最终报告及格式互换。
- RecoveryVerifier 生产文件仅增加一个构造参数；Runner/Recovery 两个测试类同步显式版本及 60 秒单测超时，未推进任务 2 的行为修改。
- 原 M1 descriptor、M2.5 保全清单、ZIP 和历史 fixture 未修改。没有创建正式 M2.5 descriptor 或新 producer fixture，没有运行 M2.5 JVM/Company 归档。

## 验证证据

| 阶段 | 命令或证据 | 实际结果 |
|---|---|---|
| compiler RED | `backend/build/task1-schema-field-red.log` | exit 1；schemaVersion 未定义 |
| runtime RED | `backend/build/task1-fixed-id-red.xml` | 1 项失败，明确 `DESCRIPTOR_INVALID:workPackageId` |
| 扩展矩阵 RED | `backend/build/task1-matrix-red.xml` | 62 项、3 项失败 |
| Node RED | `backend/build/task1-node-red.log` | 82 项、77 通过、5 失败 |
| Node GREEN | `node --test scripts/tests/evidence-archive-evidence.test.mjs` | exit 0；82/82，原 47 项保留 |
| SourceVerifier GREEN | `./backend/gradlew.bat -p backend test --tests '*EvidenceArchiveSourceVerifierTest'` | exit 0；62/62，无跳过 |
| 归档回归与构建 | `./backend/gradlew.bat -p backend test --tests '*EvidenceArchive*Test' assemble` | exit 0；217 项，212 执行通过、5 环境跳过，0 failure/error |
| 现有治理与 Contract | `node scripts/acceptance-record-validator.mjs`、`node scripts/contract-validator.mjs` | `PASS`；`schemas=4 positive=12 negative=5 operations=34` |
| 独立只读复审 | 完整 diff、TDR/计划、实际 RED/GREEN 日志及 XML | 规格 APPROVE；代码质量 APPROVE；无遗留 findings |

RED/GREEN 日志位于忽略的本地 build 目录，本表保留版本化结果摘要。运行时 RED 前已完成显式字段迁移，失败明确来自旧固定 ID。首次误命名的 task1-compiler-red.log 实为基线成功，未作为 RED 证据使用。Node 实现中一次局部变量引用错误被 17 项正向测试揭示，已修正为已解析的 descriptorInput.value，再完整复验至 82/82；未降低断言或吞掉失败。

5 项跳过来自 POSIX 权限不可用、符号链接权限及本地文件系统父目录身份条件；新增 SourceVerifier 测试全部执行。远端 exact-head M1/M2 CI 用于补充平台验证，不能以本地 212 项通过推断跳过项通过。提交后四条 CI 的真实结论在本任务最终交付中列出，不引用前一轮代替。

## 限制与下一步执行计划

任务 1 是中间集成状态。输入 parser 和离线校验支持 v2，不代表 Runner、恢复报告或 CLI 摘要已完成 v2 身份贯穿；这些仍是任务 2，正式 descriptor 与 JVM→Node 端到端证据仍是任务 3。未启用真实 Provider、Company、merge、Tag、发布、部署或下一里程碑。

原实施创建 P95 1467/1477 ms 未达 1000 ms 参考目标，canonical 不覆盖非主路径全部字段；原 Artifact 最早 2026-10-07 到期，本地保全不等于不可变归档。这些限制未被本任务关闭。

当前结果：任务 1 实施与独立复审完成。Git 状态：以本记录所在双语提交及远端为准。下一步动作：执行计划任务 2。前置条件：Owner 明确授权任务 2；Company 写入仍另行授权。验收目标：真实 Runner/Recovery/CLI 身份贯穿、解析前后失败区分、M1 canonical 兼容及对应 red/green、双语提交和 CI。
