# Evidence Archive 身份扩展任务 2 验证记录

## 授权与范围

Project Owner 本轮原始指令为“授权执行下一步”，指向任务 1 交付中唯一的下一步任务 2。原文 Unicode 转义如下。本记录保存实施验证，不构成 Owner 对完整工具或 Company 归档的验收。

```text
\u6388\u6743\u6267\u884c\u4e0b\u4e00\u6b65
```

依据：[TDR-019](../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md)、[实施计划](../superpowers/plans/2026-09-07-m2-5-evidence-archive-identity-implementation.md)与[任务 1 记录](2026-09-07-evidence-archive-identity-task1.md)。
实施基线：4d71742de325fcebfb416b07c135939dd0774afb；配对基线：71bac31f5c1ab4ccc4e52779abe4f699bd689b4a。

## 修改与边界

- Runner 在 facade/provider 工作前调用唯一 profile resolver，报告传递工作包版本，继续核对 receipt acceptanceId。
- Recovery 按 beginOutput → 完整解析 descriptor → 保存本 invocation 上下文 → 读取 archive report 顺序执行。解析前诊断为 v2/null；解析后失败保留已验证版本/ID，不复制不可信 archive 身份。
- 未绑定报告的 ID 与身份字段输出 literal JSON null；已绑定报告保留既有 nullableIdentity 对象格式。canonical 算法、完成 marker、异常分类、清理和 Error 传播未改变。
- OperationMain 从报告取得显式 schemaVersion；PASS 要求有效 profile、两份 Artifact、无错误，安全摘要 JSON 字段保持原样。三个受影响 JUnit 类均设置 @Timeout(60)。
- 既有 Recovery Fixture 参数化版本/ID，descriptor、报告和 receipt 共用身份；使用实际 descriptor bytes 计算摘要，覆盖两个恢复入口的 v2 成功、版本/摘要错配、解析前失败及解析后损坏/无法读取 archive。
- 原 M1 descriptor、历史样本、M2.5 清单和 ZIP 未修改。任务 3 正式 descriptor 与 JVM→Node 新样本未创建；未运行真实 Provider/Company 归档。

## 验证证据

| 阶段 | 实际结果 |
|---|---|
| Runner RED | exit 1；期望版本 2，实际 1 |
| Recovery RED | exit 1；未绑定诊断期望版本 2，实际 1 |
| Summary RED | exit 1；编译暴露缺少显式 schemaVersion 字段 |
| 最终修复覆盖 | Recovery/OperationMain 测试 exit 0，42s |
| 最终归档回归与构建 | 224 项，219 通过、5 环境跳过，0 failure/error；assemble 成功，52s |
| 最后断言修复 | 覆盖测试 1/1，通过，19s；无生产修改 |
| Node 离线回归 | 82/82，通过，无跳过 |
| 独立复审 | 首轮 NEEDS_FIXES；最终 Spec APPROVE / Quality APPROVE，无遗留发现 |

命令：`./backend/gradlew.bat -p backend test --tests '*EvidenceArchive*Test' assemble --no-daemon`；`node --test scripts/tests/evidence-archive-evidence.test.mjs`。

本地证据位于忽略的 build 目录：`backend/build/task2-red.log`、`backend/build/task2-recovery-red.log`、`backend/build/task2-summary-red.log`、`backend/build/task2-review-fix-full-green.log`、`backend/build/task2-node-regression.log`；最终 XML 为 `backend/build/test-results/test/TEST-*EvidenceArchive*Test.xml`。本表保留版本化摘要；提交后四条 exact-head CI 在最终交付列出。

首次复审发现原 v2 测试仅复制 Verified 对象、文件失败矩阵未覆盖 v2；父任务另确认缺少 OperationMainTest 超时。已改为实际 descriptor 摘要和两个入口的行为验证，并补齐超时。早期 `task2-partial-green.log` 实为失败，暴露 null 身份仍序列化为对象；已修复。修复覆盖日志 `task2-review-fix-red.log` 实为通过，仅是历史命名，未当作 RED 证据。

第二轮复核发现无下载断言检查了未参与操作的 Fixture，已改为实际使用的 m25.gateway 并通过覆盖测试与复核；证据为 `backend/build/task2-review-round2-green.log`。

5 项跳过来自 POSIX 权限、符号链接权限及本地文件系统身份条件；不能推断这些测试通过。既有 Gradle 弃用警告保留，本次未升级构建工具。

## 限制与下一步执行计划

任务 2 是中间交付，正式固定输入与完整 JVM→Node 证据仍待任务 3。原创建 P95 1467/1477 ms 未达 1000 ms 参考目标；canonical 不覆盖非主路径全部字段；原 Artifact 最早 2026-10-07 到期，本地保全不等于不可变归档。上述限制未关闭。

当前结果：任务 1、2 已完成，实施修复与独立复审通过。Git 状态：以本文所在双语提交及远端为准。下一步动作：执行任务 3。前置条件：Owner 明确授权任务 3；Company 写入与独立恢复仍另行授权。验收目标：正式固定 descriptor、真实 JVM→Node 端到端证据、M1 兼容与双语 CI；不代表实际归档完成。
