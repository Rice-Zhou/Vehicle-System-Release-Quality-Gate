import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  parseAcceptanceRecord,
  validateAcceptanceRecord,
  validateDirectory,
} from "../acceptance-record-validator.mjs";

const subjectCommit = "a".repeat(40);
const pairedSubjectCommit = "b".repeat(40);
const validPendingRecord = `---
acceptanceId: M1-OWNER-GATE-001
subject: Example Release Acceptance
subjectCommit: ${subjectCommit}
pairedSubjectCommit: ${pairedSubjectCommit}
branch: feat/example-release
status: PENDING
submittedAt: 2026-08-25T08:45:37Z
owner: PENDING
decisionAt: PENDING
---

# Example Acceptance Record

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

test("missing metadata returns validation errors instead of throwing", () => {
  const errors = validateAcceptanceRecord({ filePath: "record.md", body: "" });

  assert.match(errors.join("\n"), /acceptanceId/);
  assert.match(errors.join("\n"), /decisionAt/);
});

test("front matter closing delimiter must be followed by a newline", () => {
  assert.throws(
    () => parseAcceptanceRecord("---\nacceptanceId: M1-OWNER-GATE-001\n---", "record.md"),
    /missing YAML front matter/,
  );
});

test("rejects a missing required heading", () => {
  const source = validPendingRecord.replace(
    "## Evidence\nMachine evidence.\n\n",
    "",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Evidence/);
});

test("rejects an invalid subject commit", () => {
  const source = validPendingRecord.replace(subjectCommit, "not-a-commit");
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /subjectCommit/);
});

test("pending records cannot prefill owner or decision time", () => {
  const ownerRecord = parseAcceptanceRecord(
    validPendingRecord.replace("owner: PENDING", "owner: Project Owner"),
    "owner-record.md",
  );
  const decisionAtRecord = parseAcceptanceRecord(
    validPendingRecord.replace("decisionAt: PENDING", "decisionAt: 2026-08-25T09:00:00Z"),
    "decision-at-record.md",
  );

  assert.match(validateAcceptanceRecord(ownerRecord).join("\n"), /PENDING record/);
  assert.match(validateAcceptanceRecord(decisionAtRecord).join("\n"), /PENDING record/);
});

test("language branches may share an acceptance id for pairing", () => {
  const primaryRecord = parseAcceptanceRecord(validPendingRecord, "record.md");
  const pairedSource = validPendingRecord
    .replace(`subjectCommit: ${subjectCommit}`, `subjectCommit: ${pairedSubjectCommit}`)
    .replace(
      `pairedSubjectCommit: ${pairedSubjectCommit}`,
      `pairedSubjectCommit: ${subjectCommit}`,
    )
    .replace("branch: feat/example-release", "branch: feat/example-release-en");
  const pairedRecord = parseAcceptanceRecord(pairedSource, "paired-record.md");

  assert.equal(primaryRecord.metadata.acceptanceId, pairedRecord.metadata.acceptanceId);
  assert.equal(primaryRecord.metadata.pairedSubjectCommit, pairedRecord.metadata.subjectCommit);
  assert.equal(pairedRecord.metadata.pairedSubjectCommit, primaryRecord.metadata.subjectCommit);
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

test("UTC validation checks format without calendar semantics", () => {
  const record = parseAcceptanceRecord(
    validPendingRecord.replace("2026-08-25T08:45:37Z", "2026-02-29T00:00:00Z"),
    "record.md",
  );

  assert.deepEqual(validateAcceptanceRecord(record), []);
});

test("decision history must end at the declared status", () => {
  const source = validPendingRecord
    .replace("status: PENDING", "status: APPROVE")
    .replace("owner: PENDING", "owner: Project Owner")
    .replace("decisionAt: PENDING", "decisionAt: 2026-08-25T09:00:00Z")
    .replace("## Decision Reason\nPENDING", "## Decision Reason\nEvidence accepted.");
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});

test("decided records reject a blank decision reason", () => {
  const source = validPendingRecord
    .replace("status: PENDING", "status: APPROVE")
    .replace("owner: PENDING", "owner: Project Owner")
    .replace("decisionAt: PENDING", "decisionAt: 2026-08-25T09:00:00Z")
    .replace("## Decision Reason\nPENDING", "## Decision Reason\n   ")
    .replace(
      "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |",
      "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |\n" +
        "| 2026-08-25T09:00:00Z | APPROVE | Project Owner | Accepted | " +
        `${subjectCommit} |`,
    );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(
    validateAcceptanceRecord(record).join("\n"),
    /decided record must provide a Decision Reason/,
  );
});

test("Decision History rejects a malformed header", () => {
  const source = validPendingRecord.replace(
    "| At | Status | Owner | Reason | Commit |",
    "| At | Status | Owner | Reason |",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});

test("Decision History rejects a malformed separator", () => {
  const source = validPendingRecord.replace(
    "|---|---|---|---|---|",
    "|---|---|not-a-separator|---|---|",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});

test("Decision History rejects a data row with the wrong column count", () => {
  const source = validPendingRecord.replace(
    "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |",
    "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted |",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});

test("Decision History rejects a data row with an empty status", () => {
  const source = validPendingRecord.replace(
    "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |",
    "| 2026-08-25T08:45:00Z | | PENDING | Correction | PENDING |\n" +
      "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});

test("wraps invalid YAML front matter with file context and cause", () => {
  assert.throws(
    () => parseAcceptanceRecord("---\nacceptanceId: [broken\n---\n", "broken.md"),
    (error) => {
      assert.match(error.message, /^broken\.md: invalid YAML front matter: /);
      assert.ok(error.cause instanceof Error);
      return true;
    },
  );
});

test("validateDirectory returns a readable error for a missing directory", (t) => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "vsrqg-acceptance-"));
  t.after(() => fs.rmSync(temporaryDirectory, { recursive: true, force: true }));
  const missingDirectory = path.join(temporaryDirectory, "missing");

  const errors = validateDirectory(missingDirectory);

  assert.match(errors.join("\n"), /unable to read acceptance records directory/);
  assert.match(errors.join("\n"), new RegExp(missingDirectory.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
});

test("validateDirectory returns a readable error when the path is a file", (t) => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "vsrqg-acceptance-"));
  t.after(() => fs.rmSync(temporaryDirectory, { recursive: true, force: true }));
  const filePath = path.join(temporaryDirectory, "records.md");
  fs.writeFileSync(filePath, "not a directory", "utf8");

  const errors = validateDirectory(filePath);

  assert.match(errors.join("\n"), /unable to read acceptance records directory/);
  assert.match(errors.join("\n"), new RegExp(filePath.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
});

test("validateDirectory uses deterministic code-point filename order", (t) => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "vsrqg-acceptance-"));
  t.after(() => fs.rmSync(temporaryDirectory, { recursive: true, force: true }));
  fs.writeFileSync(path.join(temporaryDirectory, "Z.md"), validPendingRecord, "utf8");
  fs.writeFileSync(path.join(temporaryDirectory, "a.md"), validPendingRecord, "utf8");

  const duplicateError = validateDirectory(temporaryDirectory).find((error) =>
    error.includes("duplicate acceptanceId"),
  );

  assert.ok(duplicateError.startsWith(path.join(temporaryDirectory, "a.md")));
  assert.match(duplicateError, /also in .*Z\.md/);
});
