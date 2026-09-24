# 最小质量判定 Task 3：纯规则求值工程记录

日期：2026-09-24。依据：[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[规则规范](../11-quality-rule-specification.md)、[政策确认](2026-09-24-quality-task3-policy-review.md)。

## 交付与边界

新增受限 AST、Fact Catalog v2 的局部绑定、纯求值器和聚合器。规则按目录验证路径、类型和局部作用域；求值按累计步数限制执行，不调用网络、文件或当前时间。两条版本化 YAML 分别固定 Smoke Case 状态动作与 required Issue Verified 动作，golden fixture 保存正常、错误及空值边界。规则作者 Schema 中登记两条样例；v1 Catalog、Core Contract、原模块摘要和已发布结果未改动。

受限求值保留 Missing、Empty、Null 和 Type Error 的区别；非 nullable 必需字段为 Null 报 ERROR，集合不可当 scalar 比较，非法 Boolean 谓词不会因空集合或 appliesWhen 为 false 而被隐藏。数值只按目录同类 INTEGER/DECIMAL 精确比较。v2 未声明普通 STRING 或 TIMESTAMP 的通用大小顺序，故有序操作拒绝这些类型；consecutive 专用顺序按 capturedAt 的 Instant 和 evidenceId 固定。未来若需开放通用排序，应版本化目录与 Engine 语义。

## 复核与验证

实现采用 RED→GREEN。独立规范复核发现两项 P1、两项 P2：非 nullable Null 误得 PASS、集合与 Null 的 scalar 比较、预扫描计步、规则 golden 类别不全；修复后逐项复核关闭。独立代码质量复核发现一项 P1：空集合跳过非 Boolean 谓词可误得 PASS；静态结果类型校验及空/非空对照测试修复后复核关闭。两次复核均未代替 Owner 验收。

中文工作区强制重跑 Task 3 目标测试及 assemble：RuleOperatorMatrixTest 17、QualityRuleGoldenTest 3、QualityAggregatorTest 2，合计 22/22 PASS，0 failure、0 error、0 skipped；Gradle 9 个任务实际执行，BUILD SUCCESSFUL。Node 契约测试 7/7 PASS；契约验证 schemas=7、positive=22、negative=9、operations=36。另配对修正规则规范的 Memory 示例：DECIMAL pssMiB 阈值从整数 400 改为 400.0，以符合同类型比较。最终双语 Pair Gate、远端提交和 CI 以固定交付版本另核查，不提前宣称通过。

## 未实施的正式边界

golden 中 testResults 空集合产生的 PASS 只证明纯 any(empty)=false；正式 Rule Set 的 selectedCaseRefs 必须非空，Task 5 还须验证所选 Result 存在且终结，否则 Evaluation ERROR。Evidence 类型声明不是 bytes 完整性证明；Task 5 由 Evidence 模块只读端口核验。RuleOutcome 是领域对象，Task 5 必须显式投影到 Rule Result Schema，含 explanation 结构及 INTEGER/DECIMAL 的有类型诊断值，不能直接 Jackson 序列化。本段没有规则发布、数据库迁移、真实输入、设备操作或质量里程碑验收。

## 下一步执行计划

当前结果：Task 3 的纯求值与两条演示规则已完成本地目标验证，远端状态以交付核查为准。Git 状态：双语配对提交与推送状态由交付命令确认。下一步动作：核对 Task 4 的迁移号、现有 PostgreSQL 集成测试环境及规则发布权限边界。前置条件：Task 3 双语提交与固定提交 CI 核查完成；Task 4 的产品实施须按现有计划另行授权。验收目标：形成可验证的 Task 4 前置核查结论，不发布规则或改动生产数据。
