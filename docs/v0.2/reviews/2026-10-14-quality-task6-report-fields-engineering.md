# Task 6 报告字段与引用一致性工程记录

- 日期：2026-10-14；性质：报告边界修复与工程验证。
- 依据：[Task 6 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md) 的只读报告补充。Owner 本次授权补齐字段与引用检查、先失败测试再修复、双语提交推送；未授权扩展演示入口、发布规则或变更验收状态。

## 修复行为

此前通用报告校验器允许政策 Case 与固定选择冲突，或 Evidence 属于其他 Run/Attempt、不在已查询的 Test Result 中，规则也可引用输入中不存在的 Evidence。现在这些输入以 `QUALITY_REPORT_INPUT_MISMATCH` 拒绝；固定输入中的 Evidence ID 必须唯一。适用且完成求值的规则引用固定 Evidence 全集，`ERROR` 和 `NOT_APPLICABLE` 规则引用为空，集合顺序不参与关联判断。该检查只核对已有引用，不重新计算质量决定或业务摘要。

关联检查归入现有通用导出边界，CI 夹具不再重复核对 Run/Attempt/Result 成员关系，仍单独核对 Evidence 元数据、摘要、大小、状态与当次完整性观察。没有来源响应时允许生成带明确限制的报告，但政策/选择、Evidence 绑定和规则引用仍必须一致。

双语 HTML 补齐 `resultId`、Evaluation 的 Rule Set 身份、固定选择的 Case/version、Attempt 编号与其他 Attempt 引用，以及 `matchedFacts` 的值、规则解释参数和原错误参数。诊断值使用原 JSON 类型呈现，保留 `INTEGER`/`DECIMAL` 标签和十进制字符串、`false`、`null` 与显式空集合；新增内容经原 HTML 转义路径处理。固定前 `ERROR` 可定位 Rule Set，仍显示 `NOT_EVALUATED`，不制造 Quality Result。

## 验证证据

先新增失败测试并运行，退出码为 1；失败来自缺少预期拒绝及缺少报告字段，而不是环境错误。独立复审发现固定前 ERROR 的早返回会跳过规则 Evidence 校验；补充 `ERROR`/`NOT_APPLICABLE` 非空引用的失败测试后，将该路径纳入同一校验。修复后的目标报告、导出、CI 夹具及旧 M1/M2 回归共 63 项通过。全契约验证通过（7 schemas、22 positive、9 negative、36 operations），验收记录校验与 `git diff --check` 通过。Windows 无法创建符号链接，quality 文件拒绝测试中的该分支未在本机执行，不能以 Node 汇总成功代替该分支证据。

本记录与补丁一起提交；准确 Subject、双语 Pair Gate、远端提交和 M1/M2/M3 结果须按 Git 与 GitHub 的对应提交核对。本记录不提前登记远端 CI 成功。后端生产代码、API/Schema 与数据迁移均未改动；本机未重跑 PostgreSQL 集成测试。

## 范围与下一步

本次不改变 `Proposed / REVIEW_REQUIRED`、Task 5/6 复合条款或 Owner 验收状态。固定 Evidence 摘要不证明 Payload 当前完整；元数据读取会写完整性观察，下载仍需单独授权、目的与审计。静态报告不查询服务或下载 Payload。

当前结果：字段遗漏和引用冲突已有红绿回归证据。Git 状态：以本记录所在中英文提交及远端核对为准。下一步动作：核对该补丁固定提交的双语 Pair Gate 与 M1/M2/M3 CI。前置条件：现有 GitHub 访问能力。验收目标：两条远端分支指向对应补丁提交，Pair Gate 通过，六条固定提交 CI 成功；该工程目标不替代 Owner 验收。
