# Task 6 未固定输入提示修复记录

- 日期：2026-10-15；性质：只读报告投影修复，不是 Owner 验收。
- 依据：[Task 6 字段映射](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md) 与前一[字段补齐记录](2026-10-14-quality-task6-report-fields-engineering.md)。Owner 在人工截图复核发现问题后授权执行下一步修复，继续沿用双语提交、推送和 CI 验证边界。

## 根因与修改

人工提供的双语 ERROR 截图显示，固定前 Evaluation 的原始错误与输入未固定提示正确，但“未覆盖事实”使用“无”。渲染器在不存在 `inputSnapshot` 时复用了空集合的提示；Evidence 区也存在同类分支。这会把未确定的固定输入范围表示成空集合。

现在先按固定输入是否存在区分：没有 `inputSnapshot` 时，未覆盖事实和 Evidence 引用各自显示输入尚未固定、内容尚未确定；存在输入且集合为空时，明确显示“无”，英文为“None”；非空集合继续投影原始记录。判断依据是输入存在性，不是 `COMPLETED` 或 `ERROR` 状态。没有重算质量决定、构造来源数据或改变导出契约。

## 验证与限制

先写双语负例，分别检查未覆盖事实与 Evidence 区，确认旧实现缺少未确定提示而失败；固定输入为空集合的对照还揭示原未覆盖区只显示空表头。修复后目标报告、导出、CI 夹具及旧报告回归共 73 项通过。测试覆盖固定前 ERROR、固定后 ERROR、COMPLETED 的实际空集合，并确认输入对象不被修改。全契约校验通过（7 schemas、22 positive、9 negative、36 operations），验收记录校验与 `git diff --check` 通过。

没有修改 Backend、API/Schema、规则、存储、权限或演示入口；本机不重复执行 PostgreSQL 集成测试，受影响的正式来源夹具兼容性以准确提交的 M1 CI 为准。Windows 无法创建符号链接，quality 文件拒绝测试的该分支未在本机执行。浏览器策略仍阻止代理打开本地 HTML，既有用户截图证明的是旧报告；本补丁不能据旧截图宣称新文案已完成视觉核查。

## 下一步与治理

本记录随补丁成对提交；准确 Subject、远端提交、Pair Gate 与 M1/M2/M3 结果按 Git 和 GitHub 对应提交核对，不提前登记远端成功。TDR-026 保持 `Proposed / REVIEW_REQUIRED`，Task 5/6 复合条款与 Owner 验收状态不变。固定集合为空仅描述已记录输入，不表示真实设备零异常或 Payload 当前完整。

当前结果：已区分未固定输入与实际空集合，具备红绿回归证据。Git 状态：以本记录所在中英文提交与远端核对为准。下一步动作：核查固定提交 CI 生成的双语 ERROR 报告修正提示。前置条件：CI 完成；实际视觉核查继续由用户在允许的浏览器环境执行。验收目标：固定提交 CI 与 Pair Gate 成功；新报告在无输入时明确未确定，存在输入的空集合仍显示为空，Owner 验收独立进行。
