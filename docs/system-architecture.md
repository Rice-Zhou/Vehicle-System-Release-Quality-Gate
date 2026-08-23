# 系统架构

## 1. 逻辑架构

```text
                        Release Manager
                              |
                       Release Manifest
                              |
          +-------------------+-------------------+
          |                   |                   |
       Artifact            Issue              Environment
       Sources             Sources             / Devices
          |                   |                   |
          +-------------------+-------------------+
                              |
                     Test Orchestrator
                              |
                         Test Agent
                              |
                  +-----------+-----------+
                  |           |           |
               Results      Metrics     Evidence
                  |           |           |
                  +-----------+-----------+
                              |
                     Traceability Engine
                              |
                       Quality Engine
                              |
                    PASS / WARNING / BLOCK
                              |
                       Quality Report
```

## 2. 模块边界

### Release Manager

职责：

- 创建 Release
- 分配 Release ID
- 管理生命周期
- 引用 Manifest

不负责：

- 执行 Test
- 解析 Jira 专有模型
- 决定单项 Test 的实现细节

### Manifest Manager

职责：

- 存储 Manifest
- 验证 Artifact 完整性
- 验证 Artifact Identity/Integrity

### Source Adapter

职责：

- 连接外部系统
- 标准化数据
- 保留 Source Reference

### Test Orchestrator

职责：

- 选择 Device
- 部署 Release
- 执行 Test Plan
- 收集 Test Run State

### Test Agent

职责：

- 执行设备侧 Action
- 收集运行时数据
- 打包 Evidence

### Traceability Engine

职责：

- 构建并验证 Issue、Commit、Build、Artifact、Release 和 Test 之间的关系

### Quality Engine

职责：

- 加载 Rule Version
- 评估 Fact/Evidence
- 产生确定性 Result
- 解释失败原因

## 3. 数据流

正常的 Release 验证流程如下：

```text
1. Create Release
2. Build/collect Manifest
3. Validate artifacts
4. Snapshot relevant issues
5. Resolve build/fix traceability
6. Allocate real device
7. Deploy Release
8. Execute Test Plan
9. Collect Evidence
10. Evaluate Traceability
11. Evaluate Quality Rules
12. Generate Quality Result
13. Generate Release Report
```

## 4. 部署原则

初始实现应优先采用 Modular Monolith 或少量 Service，避免过早拆分 Microservice。

架构必须允许未来提取组件，同时不改变 Core Contract Semantics。

## 5. 存储

推荐的初始模式：

- PostgreSQL 用于结构化 Domain Data
- Object Storage 用于 Log、Trace、Screenshot、Dump 和大型 Evidence
- Git/CI/Build System 作为外部 Source System

## 6. 通信

系统应围绕 Core Contract Object 暴露稳定 API。

内部实现初期可以使用同步 API，后续可以使用 Event-driven Processing。

## 7. 安全边界

外部系统、Device 和 User 均为不可信边界。

Credential 和 Token 必须存储在 Source Code 与 Release Manifest Data 之外。

## 8. 故障隔离

一个 Adapter 或 Test Plugin 的失败不得破坏 Release Identity 或既有历史 Evidence。

## 9. 版本管理

以下内容必须具有明确版本：

- Manifest Schema
- Test Plan
- Test Case
- Quality Rule
- API Contract
- Agent Protocol

必须能够使用生成历史 Release Result 时所采用的版本解释该 Result。
