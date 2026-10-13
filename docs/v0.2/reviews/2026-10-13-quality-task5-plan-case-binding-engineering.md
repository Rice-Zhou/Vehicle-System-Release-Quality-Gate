# Task 5 Published Plan Case 绑定工程记录

- 日期：2026-10-13；性质：工程验证，不是 Owner 或里程碑验收。
- 依据：[Task 5 实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[Task 1 来源绑定记录](2026-09-17-quality-task1-contracts.md)。

## 实现与边界

正式来源读取器从终态 Test Run 投影取得 Plan ID/版本，再调用现有 Test Management `TestRunRepository.plan()` 读取该版本的 Published Case。规则集 `selectedCaseRefs`、Published Case 定义和所选 Result 的 Case ID/版本必须一致；Plan 不可用、字段缺失或不一致时返回 `QUALITY_CASE_SELECTION_MISMATCH`。该读取端口沿用 Test Management 的发布约束和不可变版本，不复制 SQL 或创建第二个 Case 权威。

读取器信任 Test Management 按 Run ID 返回的终态投影及其中的 Plan 引用；本次没有改写 Test Run Snapshot 或引入独立 Resolution 实体。当前 V0.2 仍限单 Run、单 Case、单 Attempt，`otherAttempts=[]` 不代表多 Attempt 历史已支持。

## 验证

先新增 Case ID/版本不匹配反例，目标单测按预期失败；补校验后，匹配路径、两种不匹配和 Published Plan 不可用路径在中英文 `QualityFormalSourceTest` 各 8/8 通过。隔离数据库串联测试的 Test Management 模拟端口补入 Published Plan 返回值，未改变正式规则发布状态。本机无 Docker，数据库串联结果以固定提交 CI 为准。

| 实施提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `652280f` | [38334709073](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334709073) | [38334708978](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334708978) | [38334708975](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334708975) |
| 英文 `260180e` | [38334914408](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334914408) | [38334914391](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334914391) | [38334914406](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334914406) |

上述六项固定提交 CI 均为 success；M1 的 `Run M1 candidate gate` 覆盖后端 `clean test bootJar`。TDR-026 仍为 Proposed / REVIEW_REQUIRED；未发布真实规则、执行真实 Provider／设备、合并、打 Tag 或变更验收状态。
