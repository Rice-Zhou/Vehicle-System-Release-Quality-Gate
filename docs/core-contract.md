# Core Contract

## 1. Release

表示一个特定的系统交付候选版本。

必需的概念字段：

- releaseId
- project
- vehicle/platform
- systemVersion
- buildId
- manifestId
- status
- createdAt

## 2. Release Manifest

以权威方式描述 Release 内容。

它包含：

- Manifest version
- Release identity
- System Artifact
- APK Artifact
- Firmware/Configuration Artifact
- Integrity metadata

## 3. Artifact

表示一个具体的可交付组件。

示例：

- APK
- 适用时的 AAB
- System Image
- Vendor Image
- Firmware
- Configuration Package

重要字段：

- artifactId
- type
- name
- version
- buildId
- source
- checksum

## 4. Issue

表示来自外部来源的质量或需求事项。

重要字段：

- source
- sourceIssueId
- title
- severity
- status
- fixVersion
- component
- snapshot metadata

## 5. Commit

表示一次源代码变更。

重要字段：

- repository
- commitId
- branch
- author
- timestamp

## 6. Build

表示可复现的 Build 输出。

重要字段：

- buildId
- pipeline
- source revision
- branch
- build timestamp
- artifacts

## 7. Test Plan

具名的 Test Case 集合。

## 8. Test Case

可重复执行的验证过程。

它必须具有稳定的 caseId 和 version。

## 9. Test Run

针对一个 Release 和 Environment 实际执行一次 Test Plan。

## 10. Test Result

一个 Test Run 中一个 Test Case 的结果。

可用的高层状态：

- PASS
- FAIL
- BLOCKED
- SKIPPED
- ERROR

## 11. Evidence

与 Test Run 或 Test Result 关联的一份不可变或内容寻址的证明。

示例：

- Log
- Screenshot
- ANR Trace
- Tombstone
- Perfetto Trace
- Memory Dump
- Metric Sample

## 12. Traceability

Traceability 记录如下关系：

```text
Issue → Commit
Commit → Build
Build → Artifact
Artifact → Release
Release → Test Run
Test Run → Test Result
Test Result → Evidence
```

## 13. Fixed / Included / Verified

一个 Issue 的状态含义如下：

### Fixed

存在有效的源代码变更。

### Included

能够证明该修复已包含在目标 Release 中。

### Verified

已针对目标 Release 测试该修复，且满足其验证标准。

只有 Verified 的 Issue 才满足 Release 验证要求。

## 14. Quality Rule

对 Release Fact 进行评估的、版本化的确定性表达式。

一条 Rule 必须定义：

- ruleId
- version
- scope
- condition
- severity/action
- explanation

## 15. Quality Result

使用一组 Quality Rule 评估 Release 后产生的不可变结果。

它应保留：

- Release ID
- Rule Set Version
- Evaluation Timestamp
- Evaluated Facts
- Rule Outcomes
- Final Status
- Failure Explanations
