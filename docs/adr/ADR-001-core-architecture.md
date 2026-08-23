# ADR-001 — Release-centric Quality Gate 架构

- Status：Accepted
- Date：2026-08-21

## 背景

交付产品是包含多个内部与第三方 Artifact 的完整汽车 Android 系统。传统 APK-centric 测试无法提供充分的 Release 身份、Traceability 或可审计的质量决策。

## 决策

采用 Release-centric 架构，并强制遵循以下链路：

Release → Manifest → Artifact/Issue/Environment → Test Run → Evidence → Traceability → Quality Engine → Quality Result。

外部系统使用 Adapter。运行时 Collector 使用 Plugin。质量决策必须是确定性的并由 Rule 驱动。

## 后果

正面影响：

- 完整系统的 Release 身份
- 可审计的 Evidence
- 可扩展性
- 可复现的决策
- 相互独立的外部系统集成

负面影响：

- 需要先进行数据建模
- 需要与现有 Build/Issue 系统集成
- 需要真实设备基础设施

## 回退

由于本决策定义了 Core Contract，回退必须通过 ADR。
