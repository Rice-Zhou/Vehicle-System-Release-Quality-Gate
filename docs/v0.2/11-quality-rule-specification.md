# 11 — Quality Rule Specification

## 1. 技术选择

V0.2 使用 Git 管理、版本化 YAML 表达规则元数据和受限条件树；运行时发布到数据库。拒绝任意脚本/通用 DSL，理由见 [TDR-008](tdr/TDR-008-versioned-yaml-quality-rules.md)。

YAML 是作者格式；解析后规范化为内部 AST 并生成 digest。YAML 隐式类型、重复 key、anchor/alias 和自定义 tag 均禁止，避免解析歧义。

## 2. Rule Model

```yaml
schemaVersion: "1.0"
ruleId: CRITICAL_ANR
version: 1
title: Critical application must not have ANR
scope: RELEASE
appliesWhen:
  op: exists
  path: evidence.anrs
  where:
    op: eq
    path: item.applicationCriticality
    value: CRITICAL
condition:
  op: gt
  left:
    op: count
    path: evidence.anrs
    where:
      op: eq
      path: item.applicationCriticality
      value: CRITICAL
  right: 0
onMatch: BLOCK
onNoMatch: PASS
explanation:
  code: CRITICAL_ANR_DETECTED
  template: "Detected {count} critical ANR occurrence(s)"
evidenceRequirements:
  - ANR
```

Required 字段：schemaVersion、ruleId、version、title、scope、condition、onMatch、onNoMatch、explanation。规则值只能是显式 string/boolean/integer/decimal/null。

## 3. 受支持表达式

MVP 操作符：`and`、`or`、`not`、`eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`in`、`exists`、`count`、`all`、`any`、`consecutive`。路径必须来自注册的 Fact Catalog；不允许反射、网络、文件、当前时间、随机数或自定义代码执行。

Memory 示例：

```yaml
condition:
  op: consecutive
  path: evidence.memory.samples
  count: 3
  where:
    op: and
    operands:
      - {op: eq, path: item.package, value: com.example.critical}
      - {op: gt, path: item.pssMiB, value: 400}
onMatch: BLOCK
```

`consecutive` 按 capturedAt、evidenceId 稳定排序；缺失/无效样本中断连续序列。

## 4. Rule Set

Rule Set Version 固定成员 ruleId+version、适用项目/平台、发布说明和 digest。同一 Rule 不能在同一 Set 中出现多个版本。发布流程：Draft → schema validation → semantic validation → golden tests → reviewer approval → PUBLISHED。

PUBLISHED 后不可修改；回滚通过重新选择上一已发布 Rule Set Version或发布新版本完成。

## 5. 缺失值与错误语义

- 缺失路径不是 false；若规则声明 required fact，产生 ERROR。
- 空集合与缺失集合不同。
- 单位在 Canonical Facts 阶段统一；规则禁止混用未声明单位。
- 类型不匹配、未知操作符、未知路径或除零等均使规则验证/执行失败，不做隐式转换。
- ERROR 不得聚合成 PASS。

## 6. 可读、可审计、可测试

- Git diff 审查 YAML；数据库保存原文、规范 AST、digest、作者、reviewer、commit SHA 和发布时间。
- 每条规则至少有 match、no-match、missing/error 三类 golden case。
- explanation 使用稳定 code + 参数化模板；不得只返回自由文本。
- 规则测试 fixture 引用版本化 Fact Snapshot，不调用实时系统。

## 7. 安全

规则文档有大小、深度、集合扫描和执行步数上限，防止资源耗尽。只有 `rule:publish` 可发布；作者不能单人完成需要双人审核的生产规则发布。规则不得包含 Secret 或对象存储临时 URL。

## 8. MVP 与延期

MVP 不提供 UI 规则编辑器、自定义函数、脚本、正则任意执行、跨 Release 窗口或 AI 生成后自动发布。未来扩展操作符必须版本化 Fact Catalog/Engine，并兼容历史重放。

## 9. 验收

- 示例规则能被 schema/semantic validator 接受并产生预期结果。
- 重复 key、未知 path、隐式日期/布尔、anchor、自定义 tag 被拒绝。
- 每个发布规则有三类 golden tests 与 reviewer 记录。
- 退回旧 Rule Set 可重放旧结果。

证据：Rule JSON Schema、Fact Catalog、lint 输出、golden test 报告、发布审计和回滚演练。
