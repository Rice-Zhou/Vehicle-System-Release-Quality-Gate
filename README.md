# Vehicle System Release Quality Gate

> Vehicle System Release Quality Gate（VSRQG）是面向 Android 车载主机的发布质量治理平台。

## 项目目的

本平台针对完整的车辆系统 Release 建立确定性、可追溯、可审计的 Quality Gate，覆盖系统镜像、内部开发 APK、第三方 APK、固件和配置。

核心目标：

1. 建立可信的 Release 内容定义。
2. 在真实设备上验证 Release。
3. 收集 Crash、ANR、Memory 和测试结果等客观运行时 Evidence。
4. 追溯 Issue → Commit → Build → Artifact → Release → Test Run。
5. 将 Evidence 转换为确定性的 PASS / WARNING / BLOCK 决策。
6. 在不改变核心架构的前提下，保持未来集成与能力的可扩展性。

## 架构原则

核心架构由 `docs/00-architecture-freeze.md` 冻结。

新能力必须以 Adapter、Plugin、Rule 或 Extension 实现。对 Core Contract 的修改必须提交 Architecture Decision Record（ADR）。

## 初始范围

MVP 聚焦于：

- Release Manifest
- Artifact 完整性
- Issue Adapter
- Git/Build Traceability
- 一个真实设备测试台架
- Smoke Test
- Crash 和 ANR 采集
- Deterministic Quality Rule
- Release Quality Report

## 仓库结构

```text
docs/
  00-architecture-freeze.md
  project-constitution.md
  system-architecture.md
  core-contract.md
  roadmap.md
  ai-development-guide.md
  adr/
schemas/
```

## 状态

Architecture Version：`0.1.0`

Status：**FROZEN FOR MVP DESIGN**
