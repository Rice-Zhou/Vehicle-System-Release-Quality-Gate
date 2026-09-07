# M2.5 Owner 状态 Gate 修复记录

## 范围与根因

中文状态提交 `2d62500fa3e5601449c67bd9702541b696945949` 的 M2 Run `34087115638`，以及英文状态提交 `ce56d460f830ef6575a65725e4d571f7509555b6` 的 M2 Run `34087115597` 均为 11/12，通过其余检查后，仅 acceptance 以 `OWNER_DECISION_NOT_PENDING` 失败。两侧 M1 Runs `34087115618` / `34087115616` 成功。

原因是 M2 Gate 在通用验收校验器之后，再次通过正则强制 status、owner、decisionAt 为 PENDING，拒绝了治理规范允许的 Owner 决定。本修复复用现有治理权威，不引入新技术选择，不修改 TDR-018 或冻结架构。

## 修改与边界

删除第二套 PENDING 状态判断；状态、Owner、时间和历史转换仍由 `scripts/acceptance-record-validator.mjs` 统一校验。保留 M2.5 目标记录必须存在的检查，缺失时返回 `ACCEPTANCE_RECORD_MISSING`。其他记录不能代替目标记录。

编排测试使用真实 Node 校验器和已安装的 YAML 依赖，仅隔离重型外部测试命令。Windows 包装器使用独立退出码传递，Linux 使用 exec。M2 workflow 在候选 Gate 前执行该回归。

机器 Gate PASS 不等于 Owner APPROVE；REJECT 与 CONDITIONAL 的治理效力不变。原始实施 Subjects、Owner 决定、历史 Evidence、性能限制、canonical 覆盖边界和归档义务不变。本修复不授权 Company、真实 Provider、下一里程碑、merge、Tag、发布、部署或 Pilot 启用。

## 验证记录

- RED：真实校验器接受合法 APPROVE 后，原 Gate 仍返回 `OWNER_DECISION_NOT_PENDING`，准确复现 CI 失败。
- GREEN：`scripts/tests/m2-5-verify-gates.tests.ps1` 完整通过；覆盖四种合法状态、非法状态、未决 Owner/时间、非法历史起点，以及其他合法记录存在时目标记录缺失。
- 测试开发中 Windows 包装器曾吞掉 Node 非零退出码，非法状态断言准确失败；修正退出码传递后完整复验通过，未放宽生产校验。
- 既有验收校验器 37/37 tests 通过；实际验收记录校验通过；Contract `schemas=4 positive=12 negative=5 operations=34` 通过。
- 独立只读评审 `APPROVE`，无遗留 findings。
- CI 验收条件：修复提交推送后，双语 exact-head M1/M2 成功，并实际执行新编排回归。本记录不将本地 stub 测试当作 PostgreSQL 或 Linux CI Evidence。

## 下一步执行计划

当前结果：Gate 修复与本地回归完成。Git 状态：以本文件所在提交及远端分支为准。下一步动作：核实修复提交的双语 exact-head CI。前置条件：推送并等待运行完成。验收目标：四条成功 Runs、新编排回归通过、M2 12/12 PASS；Evidence 归档仍需独立授权。
