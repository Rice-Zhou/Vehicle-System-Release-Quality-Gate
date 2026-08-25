import test from "node:test";
import assert from "node:assert/strict";
import {
  parseAcceptanceRecord,
  validateAcceptanceRecord,
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

test("decision history must end at the declared status", () => {
  const source = validPendingRecord
    .replace("status: PENDING", "status: APPROVE")
    .replace("owner: PENDING", "owner: Project Owner")
    .replace("decisionAt: PENDING", "decisionAt: 2026-08-25T09:00:00Z")
    .replace("## Decision Reason\nPENDING", "## Decision Reason\nEvidence accepted.");
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});
