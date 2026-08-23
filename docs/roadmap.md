# 路线图

## Phase 0——架构冻结

目标：建立基础。

交付物：

- Core Contract
- Architecture Freeze
- Project Constitution
- Manifest Schema
- ADR 机制
- 仓库结构

## Phase 1——MVP

目标：使用一台真实设备证明完整端到端链路。

范围：

1. 创建 Release
2. 注册 Manifest
3. 校验 Artifact checksum
4. Jira Adapter
5. 基础内部 Issue Adapter
6. Git/Build Traceability
7. 一个测试台架
8. Test Agent
9. Smoke Test
10. Crash 采集
11. ANR 采集
12. Evidence 存储
13. 基础 Quality Rule
14. Release Report

成功条件：

一个真实 Release 能够从创建开始，经 Evidence 支撑的确定性判定，最终得到 PASS/BLOCK。

## Phase 2——运行化

增加：

- Device Pool
- 并行执行
- Retry Policy
- 测试调度
- 更丰富的 Dashboard
- Memory/CPU/FPS 指标
- 回归对比
- 通知
- CI 集成

## Phase 3——企业治理

增加：

- 多车型项目
- 基于角色的访问控制
- 审批流程
- 质量趋势分析
- 跨 Release 分析
- 供应商/第三方 Artifact 治理

## Phase 4——智能辅助

将 AI 作为咨询层加入：

- Issue 聚类
- 根因建议
- 失败摘要
- 相似失败检索
- Release 风险解释
- 测试选择建议

除非未来 ADR 明确变更本政策，否则 AI 必须始终位于确定性最终 Quality Gate 决策路径之外。

## MVP 非目标

- 对车辆所有功能进行完全自主测试
- AI 驱动的发布决策
- 为微服务化而拆分微服务
- 取代 Jira/内部 Issue 系统
- 取代现有 CI/Build 系统
