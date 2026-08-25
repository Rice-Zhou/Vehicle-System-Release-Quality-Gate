# Acceptance Record Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a reusable repository-native acceptance-record mechanism and submit the first M1 Owner Gate record with `PENDING` status.

**Architecture:** Markdown records use YAML front matter for machine-verifiable metadata and body sections for scope, evidence, checks, risks, and append-only decision history. A Node.js validator reuses the pinned `yaml` dependency and scans every concrete record. The single M1 gate runs both its tests and directory validation so incomplete or invalid records cannot enter a successful candidate.

**Tech Stack:** Markdown, YAML front matter, Node.js 24, `yaml` 2.8.1, Node built-in test runner, PowerShell, and GitHub Actions.

---

## File Structure

- `docs/governance/acceptance/README.md`: long-term governance rules, state machine, and record catalog.
- `docs/governance/acceptance/template.md`: standard new-record template; the validator does not treat it as a concrete record.
- `docs/governance/acceptance/records/2026-08-25-m1-owner-gate-001.md`: first M1 `PENDING` record.
- `scripts/acceptance-record-validator.mjs`: front-matter parsing, record validation, and directory entry point.
- `scripts/tests/acceptance-record-validator.test.mjs`: regression tests for statuses, fields, SHAs, headings, and PENDING semantics.
- `package.json`: `test:acceptance` and `verify:acceptance` commands.
- `scripts/m1/verify.ps1`: new `acceptance-governance` check in the candidate gate.

### Task 1: Lock the Acceptance Record Contract with a Failing Test

**Files:**
- Create: `scripts/tests/acceptance-record-validator.test.mjs`
- Test: `scripts/tests/acceptance-record-validator.test.mjs`

- [ ] **Step 1: Create the Node built-in test**

The test imports the not-yet-existing `parseAcceptanceRecord` and `validateAcceptanceRecord` functions and defines one complete `PENDING` record:

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
subjectCommit: 586a89932baa9489d8ac946f0a01f2d0dd332b53
pairedSubjectCommit: f567e3e366e7cd454d8ccd128dd6a56645b66997
branch: feat/m1-release-manifest-en
status: PENDING
submittedAt: 2026-08-25T08:45:37Z
owner: PENDING
decisionAt: PENDING
---

# M1 Owner Gate Acceptance Record

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
    "586a89932baa9489d8ac946f0a01f2d0dd332b53",
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

- [ ] **Step 2: Run the test and verify RED**

Run: `node --test scripts/tests/acceptance-record-validator.test.mjs`

Expected: FAIL because `scripts/acceptance-record-validator.mjs` does not exist.

- [ ] **Step 3: Commit the failing test**

```powershell
git add scripts/tests/acceptance-record-validator.test.mjs
git commit -m "test(governance): define acceptance record contract"
```

### Task 2: Implement the Minimal Acceptance Record Validator

**Files:**
- Create: `scripts/acceptance-record-validator.mjs`
- Modify: `package.json`
- Test: `scripts/tests/acceptance-record-validator.test.mjs`

- [ ] **Step 1: Implement parsing and single-record validation**

Implement:

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

- [ ] **Step 2: Add package scripts**

Add to `package.json` scripts:

```json
"test:acceptance": "node --test scripts/tests/acceptance-record-validator.test.mjs",
"verify:acceptance": "node scripts/acceptance-record-validator.mjs"
```

- [ ] **Step 3: Run the target test and verify GREEN**

Run: `pnpm run test:acceptance`

Expected: 8 tests PASS.

- [ ] **Step 4: Commit the implementation**

```powershell
git add package.json scripts/acceptance-record-validator.mjs
git commit -m "feat(governance): validate acceptance records"
```

### Task 3: Establish Governance Rules and the Standard Template

**Files:**
- Create: `docs/governance/acceptance/README.md`
- Create: `docs/governance/acceptance/template.md`

- [ ] **Step 1: Write the governance rules**

`README.md` must explicitly cover Acceptance ID uniqueness, separation of Subject Commit and record commit, status transitions, terminal decisions, append-only Decision History, missing evidence as `UNKNOWN`, sensitive-information exclusion, bilingual synchronization, meaningful commits, and the prohibition on unauthorized merge or tag creation.

- [ ] **Step 2: Write the standard template**

Use the validator's YAML front-matter fields and seven required level-two headings. Mark example values as mandatory replacements when copying the template. Include Acceptance Checks, Residual Risks, Follow-up Actions, and Decision History tables.

- [ ] **Step 3: Inspect against the approved design**

Run:

```powershell
rg -n "Subject Commit|Decision History|UNKNOWN|force-push|PENDING|CONDITIONAL" `
  docs/governance/acceptance/README.md docs/governance/acceptance/template.md
```

Expected: both documents cover the governance terms and contain no `TBD` or `TODO`.

- [ ] **Step 4: Commit the governance documentation**

```powershell
git add docs/governance/acceptance/README.md docs/governance/acceptance/template.md
git commit -m "docs(governance): define acceptance record policy"
```

### Task 4: Create the First M1 PENDING Record

**Files:**
- Create: `docs/governance/acceptance/records/2026-08-25-m1-owner-gate-001.md`
- Test: `scripts/acceptance-record-validator.mjs`

- [ ] **Step 1: Write the fixed metadata**

The English record front matter must be:

```yaml
---
acceptanceId: M1-OWNER-GATE-001
subject: M1 Release Identity and Manifest Authority
subjectCommit: 586a89932baa9489d8ac946f0a01f2d0dd332b53
pairedSubjectCommit: f567e3e366e7cd454d8ccd128dd6a56645b66997
branch: feat/m1-release-manifest-en
status: PENDING
submittedAt: 2026-08-25T08:45:37Z
owner: PENDING
decisionAt: PENDING
---
```

- [ ] **Step 2: Write evidence and check results**

The record must reference:

- CI Run `32824447703`.
- Artifact `m1-evidence-586a89932baa9489d8ac946f0a01f2d0dd332b53`.
- Artifact digest `sha256:b8cdd9b261e14d51124c916587d1b1dedade9d1fc0eb21e3ca150e197c490fd4`.
- Paired CI Run `32824436148` and the Chinese artifact.
- Machine results for M1.0 through M1.5, while Owner decision checks remain `PENDING`.
- `m1-acceptance-validator/1` as a fixture only; production validator, OIDC, backup retention, and operational ownership as residual risks.

The initial Decision History row is:

```markdown
| 2026-08-25T08:45:37Z | PENDING | PENDING | Candidate submitted for Owner review | PENDING |
```

- [ ] **Step 3: Run directory validation**

Run: `pnpm run verify:acceptance`

Expected: `PASS acceptance-records`.

- [ ] **Step 4: Commit the M1 record**

```powershell
git add docs/governance/acceptance/records/2026-08-25-m1-owner-gate-001.md
git commit -m "docs(m1): submit owner gate acceptance record"
```

### Task 5: Add Acceptance Governance to the Single M1 Gate

**Files:**
- Modify: `scripts/m1/verify.ps1`
- Test: `scripts/m1/verify.ps1`

- [ ] **Step 1: Add the governance gate after the contract gate**

Add:

```powershell
Invoke-M1Gate "acceptance-governance" "pnpm run test:acceptance && pnpm run verify:acceptance" {
    & pnpm run test:acceptance
    if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance record tests" $LASTEXITCODE }
    & pnpm run verify:acceptance
    if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance record verification" $LASTEXITCODE }
}
```

Do not change the fixed final output `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery`; `evidence.json.gates` must additionally contain `acceptance-governance` and its actual exit code.

- [ ] **Step 2: Parse PowerShell and run governance tests**

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

Expected: no PowerShell syntax errors; tests and directory verification PASS.

- [ ] **Step 3: Commit gate integration**

```powershell
git add scripts/m1/verify.ps1
git commit -m "ci(governance): gate acceptance records"
```

### Task 6: Synchronize the Chinese Branch and Complete Paired Verification

**Files:**
- Create/Modify: the same Task 1 through Task 5 paths on the Chinese branch
- Test: full gates on both language branches

- [ ] **Step 1: Synchronize non-Markdown implementation**

Cherry-pick validator, tests, `package.json`, and `verify.ps1` changes between the feature branches so those files remain byte-identical.

- [ ] **Step 2: Create Chinese governance documents and record**

The Chinese record uses the same Acceptance ID and changes only:

```yaml
subjectCommit: f567e3e366e7cd454d8ccd128dd6a56645b66997
pairedSubjectCommit: 586a89932baa9489d8ac946f0a01f2d0dd332b53
branch: feat/m1-release-manifest
```

Chinese Evidence uses CI Run `32824436148`, Artifact `m1-evidence-f567e3e366e7cd454d8ccd128dd6a56645b66997`, and digest `sha256:8e4bcde48a31ddb22c5d02f91f6e6237faf59b461c3255d688947634e46dd58e`. Explanatory Markdown uses Chinese.

- [ ] **Step 3: Run language and byte-parity checks**

Run on the English branch:

```powershell
rg -n "[\p{Han}]" README.md docs
```

Expected: exit code 1 with no matches.

Compare SHA-256 for every tracked non-Markdown file in both worktrees. Expected: identical file sets and hashes.

- [ ] **Step 4: Commit the Chinese documentation**

```powershell
git add docs/governance/acceptance
git commit -m "docs(governance): add Chinese acceptance records"
```

- [ ] **Step 5: Push and wait for both CI runs**

```powershell
git push origin feat/m1-release-manifest
git push origin feat/m1-release-manifest-en
```

Expected: both M1 Backend workflows report `success`, with artifact names bound to each new branch HEAD.

- [ ] **Step 6: Perform the final audit**

Confirm:

- Both worktrees are clean and local HEAD equals remote HEAD.
- The M1 record remains `PENDING`; Owner, Decision At, and Decision Reason are not prefilled.
- CI artifacts exist and are not expired.
- No `main`/`release` merge, tag, force-push, or worktree deletion occurred.

Keep both feature branches and worktrees, then provide the Owner with record paths, commits, CI runs, and the next decision instruction.
