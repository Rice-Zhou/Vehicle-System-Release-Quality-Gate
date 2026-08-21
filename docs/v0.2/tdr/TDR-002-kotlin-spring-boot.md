# TDR-002 — Kotlin/JVM and Spring Boot

- 状态：Proposed for V0.2 Review
- 范围：Backend 实施栈

## 问题与需求

系统需要强类型领域模型、事务、REST/OIDC、PostgreSQL、后台任务、验证、可观测性和成熟测试生态；Agent 位于 Android/JVM 环境。一个主要开发者需要高生产率和公司内可维护性。

## 决策与理由

Backend 推荐 Kotlin/JVM + Spring Boot 3，运行在当前受支持 LTS JDK。Kotlin 的空安全、sealed 类型和不可变模型适合状态/契约表达；Spring 提供事务、Web、Security、Validation、Actuator 与广泛集成，减少自建基础设施。Agent 使用 Kotlin，但 Server/Agent 只共享协议 schema，不共享领域内部类。

## 未选方案

- Java：同样可行且生态成熟，但在不可变领域模型和协议类型上更冗长；若团队 Java 能力显著更强可替换。
- Go：部署轻，但复杂事务、规则模型和企业认证需更多约束，且与 Android Agent 语言分离。
- Node.js/TypeScript：API 快，但长周期企业事务和 JVM/Android 协同优势较弱。

## V0.2 / V0.3 影响

V0.2 提升交付速度，代价是 JVM 内存高于 Go。V0.3 可保持 Backend 或按稳定 OpenAPI/事件抽离其他语言服务，不影响契约。

## 迁移与回滚

所有外部契约由 OpenAPI、DB migration 和 Agent protocol 固定；替换语言时实现同一契约并通过 contract/replay tests。发布失败回滚上一镜像，不回退不可逆迁移。

## 测试、部署与恢复

单元/属性/集成/容器化 PostgreSQL/契约/端到端测试；构建不可变 JVM 容器。进程故障重启，状态由 PostgreSQL/Object Storage 恢复。

## 重新评估条件

公司明确不支持 JVM、实测资源无法满足部署限制、或维护团队技术栈发生实质变化。
