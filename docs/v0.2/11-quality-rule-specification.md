# 11 — Quality Rule Specification

## 1. Technology Choice

V0.2 uses Git-managed, versioned YAML for Rule metadata and restricted condition trees, publishing them to the database at runtime. Arbitrary scripts and general DSLs are rejected; see [TDR-008](tdr/TDR-008-versioned-yaml-quality-rules.md).

YAML is the authoring format. After parsing, it is normalized to an internal AST and a digest is generated. YAML implicit types, duplicate keys, anchors/aliases, and custom tags are prohibited to prevent parsing ambiguity.

The machine-executable authoring contract is [`schemas/v0.2/quality-rule.schema.json`](../../schemas/v0.2/quality-rule.schema.json). The versioned fact allowlist is [`contracts/facts/v0.2/fact-catalog.json`](../../contracts/facts/v0.2/fact-catalog.json), whose meta-Schema is [`schemas/v0.2/fact-catalog.schema.json`](../../schemas/v0.2/fact-catalog.schema.json). Positive and negative examples are registered in [`contracts/examples/v0.2/validation-cases.json`](../../contracts/examples/v0.2/validation-cases.json). JSON Schema governs structure and restricted operators; M4 Semantic Validator/Golden Tests still accept Fact Catalog Path/type matching and the complete Operator Matrix.

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

Required fields: schemaVersion, ruleId, version, title, scope, condition, onMatch, onNoMatch, and explanation. Rule values must be explicit string/boolean/integer/decimal/null.

## 3. Supported Expressions

MVP operators: `and`, `or`, `not`, `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `in`, `exists`, `count`, `all`, `any`, `consecutive`. Paths must come from the registered Fact Catalog. Reflection, network, files, current time, randomness, and custom code execution are prohibited.

Memory example:

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

`consecutive` uses stable capturedAt, evidenceId ordering. A valid sample whose predicate is false breaks the consecutive sequence; a Missing/Null/type-error sample produces ERROR and must not silently become false.

## 4. Rule Set

Rule Set Version fixes member ruleId+version, applicable projects/platforms, release notes, and digest. One Rule cannot appear with multiple versions in the same Set. Publication flow: Draft → schema validation → semantic validation → golden tests → reviewer approval → PUBLISHED.

PUBLISHED content is immutable. Rollback selects the previous published Rule Set Version or publishes a new version.

## 5. Missing, Empty, Null, and Error Semantics

### 5.1 Value Classification

- **Missing**: the path does not exist in the Fact Object; this differs from an explicit null value.
- **Null**: the path exists with explicit JSON null.
- **Empty**: the path exists as a zero-length Collection/String; this differs from Missing and Null.
- **Type Error**: a value exists but violates the Fact Catalog or operator type.

Required facts are validated while building Quality Input; a Missing required fact makes Evaluation ERROR. A non-required path still follows the table below. Only `exists` may explicitly convert Missing to FALSE.

### 5.2 Per-Operator Semantics

| Operator | Value | Empty | Missing | Null | Type Error |
|---|---|---|---|---|---|
| `eq` | exact comparison within the declared scalar type | Empty String is a legal scalar; Empty Collection is ERROR | ERROR | Null==Null is TRUE; Null versus non-Null is FALSE | ERROR |
| `ne` | deterministic inverse of `eq` | same as `eq` | ERROR | Null!=Null is FALSE; Null versus non-Null is TRUE | ERROR |
| `gt/gte/lt/lte` | compare only same-type ordered scalars declared by Fact Catalog | ERROR | ERROR | ERROR | ERROR |
| `in` | whether a left scalar occurs in an explicit same-type literal list; Null matches only a Null literal | an empty literal list is FALSE | ERROR | TRUE/FALSE according to whether the literal list contains Null | ERROR |
| `exists(path)` | TRUE whenever path exists | TRUE | FALSE | TRUE | value type is not read |
| `exists(path, where)` | TRUE when at least one Collection item has a TRUE predicate | FALSE | FALSE | ERROR | ERROR |
| `count(path)` | Collection length | 0 | ERROR | ERROR | ERROR |
| `count(path, where)` | number of items whose predicate is TRUE | 0 | ERROR | ERROR | ERROR if any item predicate is ERROR |
| `all` | TRUE when every item predicate is TRUE | TRUE (vacuous truth) | ERROR | ERROR | ERROR if any item predicate is ERROR |
| `any` | TRUE when at least one item predicate is TRUE | FALSE | ERROR | ERROR | ERROR if any item predicate is ERROR |
| `consecutive` | N consecutive TRUE values exist in stable order | FALSE | ERROR | ERROR | ERROR if any participating predicate is ERROR |
| `and/or` | evaluate all operands, then apply Boolean composition | not applicable | ERROR | ERROR | ERROR if any operand is ERROR |
| `not` | invert TRUE/FALSE | not applicable | ERROR | ERROR | ERROR if the operand is ERROR |

Boolean and Collection operators do not short-circuit away errors. Even when `or` already has TRUE or `and` already has FALSE, an ERROR in another operand makes the Rule ERROR. The same invalid input therefore cannot vary with operand order.

### 5.3 appliesWhen, Numbers, and Units

- `appliesWhen` defaults to TRUE. FALSE produces NOT_APPLICABLE. ERROR produces Rule ERROR and condition is not evaluated.
- condition produces only TRUE, FALSE, or ERROR. TRUE selects onMatch; FALSE selects onNoMatch; ERROR selects no quality action.
- Integer uses arbitrary precision. Decimal uses fixed-point decimal rather than IEEE-754 binary float. Comparison removes insignificant trailing zeros from canonical form, while digest retains the canonicalized numeric value.
- Fact Catalog defines one canonical unit for every numeric path. Unit conversion occurs in Canonical Facts and records its conversion version. A Rule literal must use the canonical unit; runtime guessing or implicit conversion is forbidden.
- String performs no trim, case, or locale transformation. Fact Catalog explicitly defines any required normalization.
- Unknown operators, unknown paths, duplicate keys, resource-limit violations, and type mismatch are validation/evaluation ERROR. ERROR must never aggregate to PASS.

## 6. Readable, Auditable, and Testable

- Review YAML with Git diff. The database stores source, canonical AST, digest, author, reviewer, commit SHA, and publication time.
- Every Rule has match, no-match, missing, null, empty, and type-error golden cases. A category that cannot apply must be proven by Schema type and recorded.
- Explanation uses a stable code plus parameterized template and must not return only free text.
- Rule-test fixtures reference versioned Fact Snapshots and do not call live systems.

## 7. Security

Rule documents have size, depth, collection-scan, and execution-step limits to prevent resource exhaustion. Only `rule:publish` may publish. An author cannot single-handedly publish a production Rule requiring two-person review. Rules must not contain Secrets or temporary object-storage URLs.

## 8. MVP and Deferred Scope

MVP does not provide a Rule-editor UI, custom functions, scripts, unrestricted regex execution, cross-Release windows, or automatic publication of AI-generated Rules. Future operators require versioned Fact Catalog/Engine and historical replay compatibility.

## 9. Acceptance

- The example Rule passes schema/semantic validation and produces the expected Result.
- Duplicate keys, unknown paths, implicit dates/booleans, anchors, and custom tags are rejected.
- Every published Rule has match/no-match/missing/null/empty/type-error golden tests and a reviewer record.
- Every operator has value/empty/missing/null/type-error Matrix Tests. Boolean error propagation is independent of operand order.
- Selecting an older Rule Set replays the old Result.

Evidence: Rule JSON Schema, Fact Catalog, lint output, golden-test report, publication Audit, and rollback rehearsal.
