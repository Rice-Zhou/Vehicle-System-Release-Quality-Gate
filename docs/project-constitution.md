# 项目章程

## 1. 使命

为完整的汽车 Android 系统 Release 构建公司级发布质量治理平台。

本系统不只是自动化测试框架，也不只是 Dashboard。

它是 Release 身份、运行时 Evidence、Issue 闭环和发布决策的 System of Record。

## 2. 核心价值

平台必须提升四类可信度：

1. 版本可信——交付的具体内容是什么？
2. 运行可信——真实硬件上发生了什么？
3. Issue 可信——重要修复是否确实 Included 并完成 Verified？
4. 决策可信——为什么允许或阻止该 Release？

## 3. 工程原则

### 原则 A——Evidence 优先于意见

每项重要质量主张都应有机器可读的 Evidence 支撑。

### 原则 B——可复现性优先于便利性

相同输入和 Rule 版本应产生相同 Quality Result。

### 原则 C——显式优先于隐式

重要关系必须用数据表示，不能只根据命名约定推断。

### 原则 D——扩展优先于变更

修改核心之前，应优先通过 Adapter、Plugin 或 Rule 增加新能力。

### 原则 E——真实硬件不可忽略

对于 Release 级运行时主张，在适用场景中以真实设备 Evidence 为权威依据。

### 原则 F——可审计性

评审者必须能够从 Release 决策反向追溯至导致该决策的 Evidence。

## 4. 反模式

除非 ADR 获批，否则禁止：

- 将一个 APK 视为完整 Release。
- 让 Jira 成为 Core Domain 的依赖。
- 让 AI 成为最终 Release 决策者。
- 只存储聚合指标而不保留原始 Evidence。
- 仅因 Commit 存在便声明 Issue 已 Verified。
- 将 Quality Rule 硬编码进业务逻辑。
- 在外部 Adapter 与 Quality Engine 之间引入直接依赖。
- 为解决单个项目特有问题而修改 Core Contract。

## 5. 变更治理

任何架构变更都必须有 ADR。

只要保持冻结架构不变，常规实施变更无需 ADR。

## 6. 平台自身质量

平台自身必须：

- 可观测
- 可测试
- 有版本管理
- 在实际可行时向后兼容
- 有文档
- 可独立部署
