# Changelog

本文件记录 VSRQG 可评审、可追溯的架构版本。只有目标明确且可独立审查的变更才形成提交或版本标签。

## 0.1.0 — Architecture Baseline — 2026-08-21

- 冻结 Release-centric 核心架构与 Core Contract。
- 确立 Release Manifest 的权威地位。
- 确立 Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 与 ADR 治理机制。
- 提供初始 Release Manifest JSON Schema 和 V0.2 演进边界。

## Version governance

- V0.1 冻结架构只允许通过获批 ADR 修改。
- V0.2 设计在评审通过前使用 Draft 标识，不得标记为 Design Freeze。
- 每次提交只包含一个可说明、可审查的逻辑变更。
- 发布标签只指向已完成对应评审的提交。
