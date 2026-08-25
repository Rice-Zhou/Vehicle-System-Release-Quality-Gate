# Acceptance Record Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可持续复用的仓库内验收记录机制，并提交首份状态为 `PENDING` 的 M1 Owner Gate 记录。

**Architecture:** Markdown 记录使用 YAML front matter 保存机器可校验元数据，正文保存范围、证据、检查、风险和只追加决策历史。Node.js 校验器复用仓库已锁定的 `yaml` 依赖扫描所有具体记录；M1 单一 Gate 调用测试与校验器，使后续不完整或不合法的验收记录无法进入成功候选。

**Tech Stack:** Markdown、YAML front matter、Node.js 24、`yaml` 2.8.1、Node built-in test runner、PowerShell、GitHub Actions。

---

## 文件结构

- `docs/governance/acceptance/README.md`：长期治理规则、状态机和记录目录。
- `docs/governance/acceptance/template.md`：新验收记录的标准模板；校验器不把模板当作具体记录。
- `docs/governance/acceptance/records/2026-08-25-m1-owner-gate-001.md`：首份 M1 `PENDING` 记录。
- `scripts/acceptance-record-validator.mjs`：front matter 解析、单记录校验和目录扫描入口。
- `scripts/tests/acceptance-record-validator.test.mjs`：状态、字段、SHA、标题和 PENDING 语义的回归测试。
- `package.json`：提供 `test:acceptance` 与 `verify:acceptance` 命令。
- `scripts/m1/verify.ps1`：在候选 Gate 中新增 `acceptance-governance` 检查。

### Task 1: 用失败测试固定验收记录契约

**Files:**
- Create: `scripts/tests/acceptance-record-validator.test.mjs`
- Test: `scripts/tests/acceptance-record-validator.test.mjs`

- [ ] **Step 1: 创建 Node built-in test**

测试必须导入尚不存在的 `parseAcceptanceRecord` 和 `validateAcceptanceRecord`，并定义一个完整的 `PENDING` 记录：

```javascript
import test from "node:test";
import assert from "node:assert/strict";
import {
  parseAcceptanceRecord,
  validateAcceptanceRecord,
} from "../acceptance-record-validator.mjs";

const validPendingRecord = `---
acceptanceId: M1-OWNER-GATE-001
subject: M1 Release Identity and Manifest Authority
subjectCommit: f567e3e366e7cd454d8ccd128dd6a56645b66997
pairedSubjectCommit: 586a89932baa9489d8ac946f0a01f2d0dd332b53
branch: feat/m1-release-manifest
status: PENDING
submittedAt: 2026-08-25T08:45:37Z
owner: PENDING
decisionAt: PENDING
---

# M1 Owner Gate 验收记录

## Scope
Included and excluded scope.

## Evidence
Machine evidence.

## Acceptance Checks
Check results.

## Residual Risks
Known risks.

## Decision Reason
PENDING

## Follow-up Actions
Wait for Owner decision.

## Decision History
| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |
`;

test("accepts a complete pending record", () => {
  const record = parseAcceptanceRecord(validPendingRecord, "record.md");
  assert.deepEqual(validateAcceptanceRecord(record), []);
});

test("rejects a missing required heading", () => {
  const source = validPendingRecord.replace("## Evidence\nMachine evidence.\n\n", "");
  const record = parseAcceptanceRecord(source, "record.md");
  assert.match(validateAcceptanceRecord(record).join("\n"), /Evidence/);
});

test("rejects an invalid subject commit", () => {
  const source = validPendingRecord.replace(
    "f567e3e366e7cd454d8ccd128dd6a56645b66997",
    "not-a-commit",
  );
  const record = parseAcceptanceRecord(source, "record.md");
  assert.match(validateAcceptanceRecord(record).join("\n"), /subjectCommit/);
});

test("pending records cannot prefill owner or decision time", () => {
  const source = validPendingRecord
    .replace("owner: PENDING", "owner: Project Owner")
    .replace("decisionAt: PENDING", "decisionAt: 2026-08-25T09:00:00Z");
  const record = parseAcceptanceRecord(source, "record.md");
  assert.match(validateAcceptanceRecord(record).join("\n"), /PENDING record/);
});

test("different language branches may share an acceptance id", () => {
  const record = parseAcceptanceRecord(validPendingRecord, "record.md");
  assert.equal(record.metadata.acceptanceId, "M1-OWNER-GATE-001");
  assert.equal(record.metadata.pairedSubjectCommit.length, 40);
});

test("rejects an unsupported status", () => {
  const record = parseAcceptanceRecord(
    validPendingRecord.replace("status: PENDING", "status: PASSED"),
    "record.md",
  );
  assert.match(validateAcceptanceRecord(record).join("\n"), /invalid status/);
});

test("rejects a non-UTC submission time", () => {
  const record = parseAcceptanceRecord(
    validPendingRecord.replace("2026-08-25T08:45:37Z", "2026-08-25 08:45"),
    "record.md",
  );
  assert.match(validateAcceptanceRecord(record).join("\n"), /submittedAt/);
});

test("decision history must end at the declared status", () => {
  const source = validPendingRecord
    .replace("status: PENDING", "status: APPROVE")
    .replace("owner: PENDING", "owner: Project Owner")
    .replace("decisionAt: PENDING", "decisionAt: 2026-08-25T09:00:00Z")
    .replace("## Decision Reason\nPENDING", "## Decision Reason\nM1 evidence accepted");
  const record = parseAcceptanceRecord(source, "record.md");
  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `node --test scripts/tests/acceptance-record-validator.test.mjs`

Expected: FAIL，错误明确指出无法找到 `scripts/acceptance-record-validator.mjs`。

- [ ] **Step 3: 提交失败测试**

```powershell
git add scripts/tests/acceptance-record-validator.test.mjs
git commit -m "test(governance): define acceptance record contract"
```

### Task 2: 实现最小验收记录校验器

**Files:**
- Create: `scripts/acceptance-record-validator.mjs`
- Modify: `package.json`
- Test: `scripts/tests/acceptance-record-validator.test.mjs`

- [ ] **Step 1: 实现解析和单记录校验**

实现必须：

```javascript
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

const STATUSES = new Set(["PENDING", "APPROVE", "REJECT", "CONDITIONAL"]);
const REQUIRED_METADATA = [
  "acceptanceId", "subject", "subjectCommit", "pairedSubjectCommit",
  "branch", "status", "submittedAt", "owner", "decisionAt",
];
const REQUIRED_HEADINGS = [
  "Scope", "Evidence", "Acceptance Checks", "Residual Risks",
  "Decision Reason", "Follow-up Actions", "Decision History",
];
const COMMIT = /^[0-9a-f]{40}$/;
const ACCEPTANCE_ID = /^[A-Z0-9]+(?:-[A-Z0-9]+)+$/;
const ISO_UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const TRANSITIONS = new Set([
  "PENDING->APPROVE", "PENDING->REJECT", "PENDING->CONDITIONAL",
  "CONDITIONAL->APPROVE", "CONDITIONAL->REJECT",
]);

function sectionBody(body, heading) {
  const marker = new RegExp(`(?:^|\\n)## ${heading}\\r?\\n`).exec(body);
  if (!marker) return "";
  const remainder = body.slice(marker.index + marker[0].length);
  const nextHeading = remainder.search(/\r?\n## /);
  return (nextHeading < 0 ? remainder : remainder.slice(0, nextHeading)).trim();
}

function decisionStatuses(body) {
  return sectionBody(body, "Decision History")
    .split(/\r?\n/)
    .filter((line) => /^\|/.test(line))
    .slice(2)
    .map((line) => line.split("|").slice(1, -1).map((value) => value.trim())[1])
    .filter(Boolean);
}

export function parseAcceptanceRecord(source, filePath) {
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) throw new Error(`${filePath}: missing YAML front matter`);
  return { filePath, metadata: YAML.parse(match[1]), body: match[2] };
}

export function validateAcceptanceRecord(record) {
  const errors = [];
  const metadata = record.metadata ?? {};
  for (const field of REQUIRED_METADATA) {
    if (metadata[field] === undefined || metadata[field] === null || metadata[field] === "") {
      errors.push(`${record.filePath}: missing metadata ${field}`);
    }
  }
  if (!ACCEPTANCE_ID.test(String(metadata.acceptanceId ?? ""))) {
    errors.push(`${record.filePath}: invalid acceptanceId`);
  }
  for (const field of ["subjectCommit", "pairedSubjectCommit"]) {
    if (metadata[field] !== "N/A" && !COMMIT.test(String(metadata[field] ?? ""))) {
      errors.push(`${record.filePath}: invalid ${field}`);
    }
  }
  if (!STATUSES.has(metadata.status)) errors.push(`${record.filePath}: invalid status`);
  if (!ISO_UTC.test(String(metadata.submittedAt ?? ""))) {
    errors.push(`${record.filePath}: invalid submittedAt`);
  }
  if (metadata.decisionAt !== "PENDING" && !ISO_UTC.test(String(metadata.decisionAt ?? ""))) {
    errors.push(`${record.filePath}: invalid decisionAt`);
  }
  if (metadata.status === "PENDING" &&
      (metadata.owner !== "PENDING" || metadata.decisionAt !== "PENDING")) {
    errors.push(`${record.filePath}: PENDING record must not prefill owner or decisionAt`);
  }
  if (metadata.status !== "PENDING" &&
      (metadata.owner === "PENDING" || metadata.decisionAt === "PENDING" ||
       sectionBody(record.body, "Decision Reason") === "PENDING")) {
    errors.push(`${record.filePath}: decided record must include owner, decisionAt, and reason`);
  }
  for (const heading of REQUIRED_HEADINGS) {
    if (!new RegExp(`^## ${heading}$`, "m").test(record.body)) {
      errors.push(`${record.filePath}: missing heading ${heading}`);
    }
  }
  const history = decisionStatuses(record.body);
  if (history.length === 0 || history[0] !== "PENDING" || history.at(-1) !== metadata.status) {
    errors.push(`${record.filePath}: Decision History must start at PENDING and end at status`);
  }
  for (let index = 1; index < history.length; index += 1) {
    if (history[index] !== history[index - 1] &&
        !TRANSITIONS.has(`${history[index - 1]}->${history[index]}`)) {
      errors.push(`${record.filePath}: invalid Decision History transition`);
    }
  }
  return errors;
}

export function validateDirectory(recordsDirectory) {
  const files = fs.readdirSync(recordsDirectory)
    .filter((name) => name.endsWith(".md"))
    .sort();
  if (files.length === 0) return [`${recordsDirectory}: no acceptance records`];
  const records = [];
  const errors = files.flatMap((name) => {
    const filePath = path.join(recordsDirectory, name);
    try {
      const record = parseAcceptanceRecord(fs.readFileSync(filePath, "utf8"), filePath);
      records.push(record);
      return validateAcceptanceRecord(record);
    } catch (error) {
      return [error.message];
    }
  });
  const ids = records.map((record) => record.metadata.acceptanceId);
  for (const id of new Set(ids)) {
    if (ids.filter((candidate) => candidate === id).length > 1) {
      errors.push(`${recordsDirectory}: duplicate acceptanceId ${id}`);
    }
  }
  return errors;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const errors = validateDirectory(path.join(root, "docs/governance/acceptance/records"));
  if (errors.length) {
    for (const error of errors) console.error(`FAIL ${error}`);
    process.exitCode = 1;
  } else {
    console.log("PASS acceptance-records");
  }
}
```

- [ ] **Step 2: 增加 package scripts**

在 `package.json` 的 `scripts` 中加入：

```json
"test:acceptance": "node --test scripts/tests/acceptance-record-validator.test.mjs",
"verify:acceptance": "node scripts/acceptance-record-validator.mjs"
```

- [ ] **Step 3: 运行目标测试确认 GREEN**

Run: `pnpm run test:acceptance`

Expected: 8 tests PASS。

- [ ] **Step 4: 提交实现**

```powershell
git add package.json scripts/acceptance-record-validator.mjs
git commit -m "feat(governance): validate acceptance records"
```

### Task 3: 建立治理规则和标准模板

**Files:**
- Create: `docs/governance/acceptance/README.md`
- Create: `docs/governance/acceptance/template.md`

- [ ] **Step 1: 编写治理规则**

`README.md` 必须逐项写明：Acceptance ID 唯一性、Subject Commit 与记录提交分离、状态转换、终结决定不可原地改写、Decision History 只追加、证据缺失为 `UNKNOWN`、敏感信息禁止、双语同步、每次更新独立提交、未经授权不合并或建 Tag。

- [ ] **Step 2: 编写标准模板**

模板使用与校验器相同的 YAML front matter 字段和七个二级标题。模板中的示例值明确标记为“复制模板时必须替换”，并包含 Acceptance Checks、Residual Risks、Follow-up Actions 和 Decision History 表格。

- [ ] **Step 3: 人工对照设计规范**

Run:

```powershell
rg -n "Subject Commit|Decision History|UNKNOWN|force-push|PENDING|CONDITIONAL" `
  docs/governance/acceptance/README.md docs/governance/acceptance/template.md
```

Expected: 两个文件均覆盖对应治理术语，没有 `TBD` 或 `TODO`。

- [ ] **Step 4: 提交治理文档**

```powershell
git add docs/governance/acceptance/README.md docs/governance/acceptance/template.md
git commit -m "docs(governance): define acceptance record policy"
```

### Task 4: 创建首份 M1 PENDING 记录

**Files:**
- Create: `docs/governance/acceptance/records/2026-08-25-m1-owner-gate-001.md`
- Test: `scripts/acceptance-record-validator.mjs`

- [ ] **Step 1: 写入固定元数据**

中文记录 front matter 必须精确使用：

```yaml
---
acceptanceId: M1-OWNER-GATE-001
subject: M1 Release Identity and Manifest Authority
subjectCommit: f567e3e366e7cd454d8ccd128dd6a56645b66997
pairedSubjectCommit: 586a89932baa9489d8ac946f0a01f2d0dd332b53
branch: feat/m1-release-manifest
status: PENDING
submittedAt: 2026-08-25T08:45:37Z
owner: PENDING
decisionAt: PENDING
---
```

- [ ] **Step 2: 写入证据和检查结果**

记录必须引用：

- CI Run `32824436148`。
- Artifact `m1-evidence-f567e3e366e7cd454d8ccd128dd6a56645b66997`。
- Artifact digest `sha256:8e4bcde48a31ddb22c5d02f91f6e6237faf59b461c3255d688947634e46dd58e`。
- Paired CI Run `32824447703` 和英文 Artifact。
- M1.0～M1.5 的机器检查结果，以及 Owner 决策检查为 `PENDING`。
- `m1-acceptance-validator/1` 仅为 fixture；生产 validator、OIDC、备份保留和运行责任为残余风险。

Decision History 初始行固定为：

```markdown
| 2026-08-25T08:45:37Z | PENDING | PENDING | 候选已提交 Owner 复核 | PENDING |
```

- [ ] **Step 3: 运行目录校验**

Run: `pnpm run verify:acceptance`

Expected: `PASS acceptance-records`。

- [ ] **Step 4: 提交 M1 记录**

```powershell
git add docs/governance/acceptance/records/2026-08-25-m1-owner-gate-001.md
git commit -m "docs(m1): submit owner gate acceptance record"
```

### Task 5: 将验收治理加入 M1 单一 Gate

**Files:**
- Modify: `scripts/m1/verify.ps1`
- Test: `scripts/m1/verify.ps1`

- [ ] **Step 1: 在 contract Gate 后加入治理 Gate**

加入：

```powershell
Invoke-M1Gate "acceptance-governance" "pnpm run test:acceptance && pnpm run verify:acceptance" {
    & pnpm run test:acceptance
    if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance record tests" $LASTEXITCODE }
    & pnpm run verify:acceptance
    if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance record verification" $LASTEXITCODE }
}
```

不修改最终固定输出 `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery`，但 `evidence.json.gates` 必须新增 `acceptance-governance` 及真实 exit code。

- [ ] **Step 2: 解析 PowerShell 并运行治理测试**

Run:

```powershell
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path scripts/m1/verify.ps1), [ref]$tokens, [ref]$errors
) | Out-Null
if ($errors.Count) { throw ($errors | Out-String) }
pnpm run test:acceptance
pnpm run verify:acceptance
```

Expected: PowerShell 无语法错误，测试和目录校验均 PASS。

- [ ] **Step 3: 提交 Gate 集成**

```powershell
git add scripts/m1/verify.ps1
git commit -m "ci(governance): gate acceptance records"
```

### Task 6: 同步英文分支并完成配对验证

**Files:**
- Create/Modify: 英文分支中与 Task 1～5 相同路径的文件
- Test: 中英文分支全量 Gate

- [ ] **Step 1: 同步非 Markdown 实现**

把中文分支的 validator、tests、`package.json` 和 `verify.ps1` 提交 cherry-pick 到 `feat/m1-release-manifest-en`，保证这些文件逐字节一致。

- [ ] **Step 2: 创建纯英文治理文档和记录**

英文记录使用同一 Acceptance ID，front matter 仅将：

```yaml
subjectCommit: 586a89932baa9489d8ac946f0a01f2d0dd332b53
pairedSubjectCommit: f567e3e366e7cd454d8ccd128dd6a56645b66997
branch: feat/m1-release-manifest-en
```

英文 Evidence 使用 CI Run `32824447703`、Artifact `m1-evidence-586a89932baa9489d8ac946f0a01f2d0dd332b53` 和 digest `sha256:b8cdd9b261e14d51124c916587d1b1dedade9d1fc0eb21e3ca150e197c490fd4`，其 paired reference 使用中文 CI Run `32824436148`。所有说明性 Markdown 正文使用纯英文。

- [ ] **Step 3: 运行语言与字节一致性检查**

Run:

```powershell
rg -n "[\p{Han}]" README.md docs
```

Expected on English branch: exit code 1，没有匹配。

逐文件比较两个 worktree 的所有非 Markdown tracked files SHA-256；Expected: 文件集合和每个 hash 完全一致。

- [ ] **Step 4: 提交英文文档**

```powershell
git add docs/governance/acceptance
git commit -m "docs(governance): add English acceptance records"
```

- [ ] **Step 5: 推送并等待双分支 CI**

```powershell
git push origin feat/m1-release-manifest
git push origin feat/m1-release-manifest-en
```

Expected: 两条 M1 Backend workflow 均 `success`，Artifact 名称分别绑定新的分支 HEAD。

- [ ] **Step 6: 最终审计**

确认：

- 两条 worktree clean，本地 HEAD 等于远端 HEAD。
- M1 记录保持 `PENDING`，Owner、Decision At 和 Decision Reason 未预填。
- CI Artifact 存在且未过期。
- 没有合并 `main`/`release`、没有 Tag、没有 force-push、没有删除 worktree。

完成后保留两个 feature 分支与 worktree，向 Owner 提交记录路径、commit、CI 和下一步决策指令。
