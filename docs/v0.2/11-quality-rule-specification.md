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

`consecutive` 按 capturedAt、evidenceId 稳定排序；合法但 predicate 为 false 的样本中断连续序列，Missing/Null/type-error 样本产生 ERROR，不能静默当作 false。

## 4. Rule Set

Rule Set Version 固定成员 ruleId+version、适用项目/平台、发布说明和 digest。同一 Rule 不能在同一 Set 中出现多个版本。发布流程：Draft → schema validation → semantic validation → golden tests → reviewer approval → PUBLISHED。

PUBLISHED 后不可修改；回滚通过重新选择上一已发布 Rule Set Version或发布新版本完成。

## 5. Missing、Empty、Null 与错误语义

### 5.1 值分类

- **Missing**：Fact Object 中不存在该 path；不同于存在且值为 null。
- **Null**：path 存在，值为显式 JSON null。
- **Empty**：path 存在且为长度 0 的 Collection/String；不同于 Missing 或 Null。
- **Type Error**：值存在但不满足 Fact Catalog 或操作符类型。

required fact 在构造 Quality Input 时即校验；Missing required fact 使 Evaluation ERROR。非 required path 仍按下表求值，只有 `exists` 可以把 Missing 显式转换为 FALSE。

### 5.2 逐操作符语义

| Operator | Value | Empty | Missing | Null | Type Error |
|---|---|---|---|---|---|
| `eq` | 同声明 scalar 类型精确比较 | Empty String 是合法 scalar；Empty Collection 为 ERROR | ERROR | Null==Null 为 TRUE；Null 与非 Null 为 FALSE | ERROR |
| `ne` | `eq` 的确定性反值 | 与 `eq` 相同 | ERROR | Null!=Null 为 FALSE；Null 与非 Null 为 TRUE | ERROR |
| `gt/gte/lt/lte` | 仅比较 Fact Catalog 声明的同类 ordered scalar | ERROR | ERROR | ERROR | ERROR |
| `in` | 左 scalar 是否存在于显式同类 literal list；Null 仅可匹配 Null literal | 空 literal list 为 FALSE | ERROR | 按 literal list 是否含 Null 返回 TRUE/FALSE | ERROR |
| `exists(path)` | path 存在即 TRUE | TRUE | FALSE | TRUE | 不读取值类型 |
| `exists(path, where)` | Collection 中至少一项 predicate 为 TRUE | FALSE | FALSE | ERROR | ERROR |
| `count(path)` | Collection 长度 | 0 | ERROR | ERROR | ERROR |
| `count(path, where)` | predicate 为 TRUE 的项数 | 0 | ERROR | ERROR | 任一项 predicate ERROR 则 ERROR |
| `all` | 所有项 predicate 为 TRUE | TRUE（vacuous truth） | ERROR | ERROR | 任一项 predicate ERROR 则 ERROR |
| `any` | 至少一项 predicate 为 TRUE | FALSE | ERROR | ERROR | 任一项 predicate ERROR 则 ERROR |
| `consecutive` | 稳定顺序中存在 N 个连续 TRUE | FALSE | ERROR | ERROR | 任一参与项 predicate ERROR 则 ERROR |
| `and/or` | 全部 operand 都求值后按 Boolean 组合 | 不适用 | ERROR | ERROR | 任一 operand ERROR 则 ERROR |
| `not` | TRUE/FALSE 反转 | 不适用 | ERROR | ERROR | operand ERROR 则 ERROR |

Boolean 和 Collection 操作不使用“短路隐藏错误”：即使 `or` 已有 TRUE 或 `and` 已有 FALSE，其他 operand 的 ERROR 仍使规则 ERROR。这样同一非法输入不会因 operand 顺序得到不同结果。

### 5.3 appliesWhen、数值与单位

- `appliesWhen` 缺省为 TRUE；结果 FALSE 时规则输出 NOT_APPLICABLE；结果 ERROR 时规则输出 ERROR，不执行 condition。
- condition 只能产生 TRUE、FALSE 或 ERROR；TRUE 选择 onMatch，FALSE 选择 onNoMatch，ERROR 不选择任何质量 action。
- Integer 使用任意精度整数；Decimal 使用十进制定点，不使用 IEEE-754 binary float。比较前按规范形式移除无意义尾随零，但 digest 保留规范化数值。
- Fact Catalog 为每个数值 path 定义唯一 canonical unit。单位转换在 Canonical Facts 阶段完成并记录转换版本；规则 literal 必须使用 canonical unit，不允许运行时猜测或隐式换算。
- String 不做 trim、大小写或 locale 转换；需要规范化时由 Fact Catalog 显式定义。
- 未知操作符、未知 path、重复 key、超出资源上限或类型不匹配均为验证/执行 ERROR；ERROR 不得聚合成 PASS。

## 6. 可读、可审计、可测试

- Git diff 审查 YAML；数据库保存原文、规范 AST、digest、作者、reviewer、commit SHA 和发布时间。
- 每条规则至少有 match、no-match、missing、null、empty 和 type-error golden case；不适用的分类必须由 Schema 类型证明并记录。
- explanation 使用稳定 code + 参数化模板；不得只返回自由文本。
- 规则测试 fixture 引用版本化 Fact Snapshot，不调用实时系统。

## 7. 安全

规则文档有大小、深度、集合扫描和执行步数上限，防止资源耗尽。只有 `rule:publish` 可发布；作者不能单人完成需要双人审核的生产规则发布。规则不得包含 Secret 或对象存储临时 URL。

## 8. MVP 与延期

MVP 不提供 UI 规则编辑器、自定义函数、脚本、正则任意执行、跨 Release 窗口或 AI 生成后自动发布。未来扩展操作符必须版本化 Fact Catalog/Engine，并兼容历史重放。

## 9. 验收

- 示例规则能被 schema/semantic validator 接受并产生预期结果。
- 重复 key、未知 path、隐式日期/布尔、anchor、自定义 tag 被拒绝。
- 每个发布规则有 match/no-match/missing/null/empty/type-error golden tests 与 reviewer 记录。
- 每个操作符具备 value/empty/missing/null/type-error Matrix Test；Boolean 错误传播不受 operand 顺序影响。
- 退回旧 Rule Set 可重放旧结果。

证据：Rule JSON Schema、Fact Catalog、lint 输出、golden test 报告、发布审计和回滚演练。
