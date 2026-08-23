# Architecture Freeze — Vehicle System Release Quality Gate

- Architecture Version：0.1.0
- Status：FROZEN
- Scope：MVP 及所有未来扩展
- Date：2026-08-21

## 1. 目的

本文档冻结 Vehicle System Release Quality Gate（VSRQG）不可协商的架构基础。

目的是防止未来的功能需求、实现偏好、供应商变化或 AI 生成的代码无意中改变系统的基础模型。

## 2. 核心问题

交付产品是完整的车辆系统 Release，而不是单个 APK。一个 Release 可以包含：

- Android System Image
- Framework/Platform Component
- 内部开发 APK
- 第三方 APK
- Firmware
- Configuration
- 其他必需 Artifact

因此，平台必须基于 Evidence 回答：

- 这个 Release 的确切定义是什么？
- 哪些 Artifact 属于它？
- 哪些 Issue 与它相关？
- 哪些修复实际已 Included？
- 是否已在真实硬件上测试该 Release？
- 哪些客观 Evidence 支撑结果？
- 为什么允许或阻止该 Release？

## 3. 冻结的架构链路

以下链路在概念层面不可变：

```text
Release Request
    ↓
Release
    ↓
Release Manifest
    ↓
Artifact / Issue / Environment Snapshot
    ↓
Test Orchestrator
    ↓
Test Agent
    ↓
Test Result / Metric / Evidence
    ↓
Traceability Engine
    ↓
Quality Engine
    ↓
PASS / WARNING / BLOCK
    ↓
Release Quality Report
```

实现可以改变，但该链路的职责和信息流必须保持完整。

## 4. 冻结的 Core Contract

以下实体构成 Core Contract：

1. Release
2. Release ID
3. Release Manifest
4. Artifact
5. Issue
6. Commit
7. Build
8. Test Plan
9. Test Case
10. Test Run
11. Test Result
12. Evidence
13. Traceability
14. Quality Rule
15. Quality Result

对这些实体作出结构性变更之前必须提交 ADR。

## 5. 架构模块

系统包含七个概念模块：

### 5.1 Release Manager

负责 Release 生命周期和身份。

### 5.2 Manifest Manager

定义 Release 的确切内容及其完整性。

### 5.3 Source Adapter

对 Jira 和内部 Issue 系统等外部系统进行标准化。

### 5.4 Test Orchestrator

调度和控制真实设备及测试台架上的执行。

### 5.5 Test Agent

在目标设备上运行或控制目标设备，并收集运行时 Evidence。

### 5.6 Traceability Engine

建立 Issue → Commit → Build → Artifact → Release → Test 关系。

### 5.7 Quality Engine

将确定性且版本化的 Quality Rule 应用于 Evidence 和 Traceability 数据。

## 6. 不可协商的原则

### 6.1 Release 是交付单元

APK 是 Artifact，不是 Release。

### 6.2 Manifest 具有权威性

Release 由其 Manifest 定义。

### 6.3 Evidence 是一级数据

没有 Evidence 的 Quality Result 不被视为可信。

### 6.4 Traceability 是强制要求

必须能够区分一个 Issue 的以下状态：

- Fixed
- Included
- Verified

### 6.5 质量决策必须是确定性的

最终 PASS / BLOCK 决策必须能够根据已存储的输入和 Rule 复现。

### 6.6 外部系统使用 Adapter

Jira 和内部 Issue 系统不得将其专有模型泄漏到 Core Contract。

### 6.7 运行时能力使用 Plugin

Crash、ANR、Memory、CPU、FPS、Perfetto 及未来 Collector 是 Test Agent 的能力，而不是 Core Domain 概念。

### 6.8 AI 仅提供咨询

AI 可以分析、总结、分类或建议。在冻结架构中，它不得成为确定性 Release Gate 的权威决策者。

### 6.9 核心变更必须提交 ADR

禁止直接修改 Core Contract。

## 7. 扩展规则

未来功能必须归入以下类别之一：

- Adapter
- Plugin
- Quality Rule
- Report/Presentation
- 非核心实现细节

如果一项功能无法归入任何类别，实施前必须提交 ADR。

## 8. 架构变更政策

如果一项变更修改以下内容，则属于架构变更：

- Core Contract Entity
- 核心职责的归属
- Release Identity
- Manifest Semantics
- Traceability Semantics
- Quality Decision Semantics
- Authoritative Data Source
- Mandatory Information Flow

架构变更必须具备：

1. ADR
2. Impact Analysis
3. Migration Strategy
4. Compatibility Assessment
5. Explicit Approval

## 9. 冻结项与灵活项

### 冻结项

- Core Entity
- Release-centric Model
- Manifest 作为 Release Definition
- Evidence Model
- Traceability Concept
- Deterministic Quality Engine
- Adapter/Plugin Extension Model
- ADR Governance

### 灵活项

- Programming Language
- Database Implementation
- Message Broker
- UI Framework
- CI Provider
- Test Framework
- Device Communication Mechanism
- Storage Implementation
- Deployment Topology

## 10. Release Gate 的完成定义

仅当满足以下条件时，Release Gate 实现才可被接受：

- Release Identity 唯一。
- Manifest 已存储。
- Artifact 可识别且其完整性可验证。
- 相关 Issue 已形成 Snapshot。
- Build/Fix Traceability 可用。
- 真实设备 Test Run 已关联 Release。
- Test Evidence 已持久化。
- Quality Rule 已版本化。
- 最终 Result 可复现。
- 失败原因能够通过 Evidence 解释。

## 11. 冻结声明

本文档是 VSRQG v0.1 的架构章程。

未来开发必须围绕此 Contract 扩展系统，不得为单项功能重新设计该 Contract。
