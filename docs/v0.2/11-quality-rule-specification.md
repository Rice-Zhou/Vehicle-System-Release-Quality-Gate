# 11 — Quality Rule Specification

## 1. Technology Choice

V0.2 uses Git-managed, versioned YAML for Rule metadata and restricted condition trees, publishing them to the database at runtime. Arbitrary scripts and general DSLs are rejected; see [TDR-008](tdr/TDR-008-versioned-yaml-quality-rules.md).

YAML is the authoring format. After parsing, it is normalized to an internal AST and a digest is generated. YAML implicit types, duplicate keys, anchors/aliases, and custom tags are prohibited to prevent parsing ambiguity.

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

`consecutive` uses stable capturedAt, evidenceId ordering. Missing or invalid samples break the consecutive sequence.

## 4. Rule Set

Rule Set Version fixes member ruleId+version, applicable projects/platforms, release notes, and digest. One Rule cannot appear with multiple versions in the same Set. Publication flow: Draft → schema validation → semantic validation → golden tests → reviewer approval → PUBLISHED.

PUBLISHED content is immutable. Rollback selects the previous published Rule Set Version or publishes a new version.

## 5. Missing Values and Error Semantics

- A missing path is not false. If the Rule declares a required Fact, produce ERROR.
- An empty collection differs from a missing collection.
- Units are normalized in Canonical Facts. Rules must not mix undeclared units.
- Type mismatch, unknown operator, unknown path, division by zero, and similar cases fail Rule validation/execution without implicit conversion.
- ERROR must not aggregate to PASS.

## 6. Readable, Auditable, and Testable

- Review YAML with Git diff. The database stores source, canonical AST, digest, author, reviewer, commit SHA, and publication time.
- Every Rule has at least match, no-match, and missing/error golden cases.
- Explanation uses a stable code plus parameterized template and must not return only free text.
- Rule-test fixtures reference versioned Fact Snapshots and do not call live systems.

## 7. Security

Rule documents have size, depth, collection-scan, and execution-step limits to prevent resource exhaustion. Only `rule:publish` may publish. An author cannot single-handedly publish a production Rule requiring two-person review. Rules must not contain Secrets or temporary object-storage URLs.

## 8. MVP and Deferred Scope

MVP does not provide a Rule-editor UI, custom functions, scripts, unrestricted regex execution, cross-Release windows, or automatic publication of AI-generated Rules. Future operators require versioned Fact Catalog/Engine and historical replay compatibility.

## 9. Acceptance

- The example Rule passes schema/semantic validation and produces the expected Result.
- Duplicate keys, unknown paths, implicit dates/booleans, anchors, and custom tags are rejected.
- Every published Rule has three golden-test categories and a reviewer record.
- Selecting an older Rule Set replays the old Result.

Evidence: Rule JSON Schema, Fact Catalog, lint output, golden-test report, publication Audit, and rollback rehearsal.
