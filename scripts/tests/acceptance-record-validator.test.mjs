import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  parseAcceptanceRecord,
  validateAcceptanceRecord,
  validateDirectory,
} from "../acceptance-record-validator.mjs";

const subjectCommit = "a".repeat(40);
const pairedSubjectCommit = "b".repeat(40);
const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);
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

const initialHistoryRow =
  "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | PENDING |";

function withHistoryRows(source, rows) {
  return source.replace(initialHistoryRow, rows.join("\n"));
}

function decidedRecord({
  status = "APPROVE",
  owner = "Project Owner",
  decisionAt = "2026-08-25T09:00:00Z",
  rows = [
    initialHistoryRow,
    `| 2026-08-25T09:00:00Z | APPROVE | Project Owner | Accepted | ${subjectCommit} |`,
  ],
} = {}) {
  return withHistoryRows(
    validPendingRecord
      .replace("status: PENDING", `status: ${status}`)
      .replace("owner: PENDING", `owner: ${owner}`)
      .replace("decisionAt: PENDING", `decisionAt: ${decisionAt}`)
      .replace("## Decision Reason\nPENDING", "## Decision Reason\nEvidence accepted."),
    rows,
  );
}

test("accepts a complete pending record", () => {
  const record = parseAcceptanceRecord(validPendingRecord, "record.md");

  assert.deepEqual(validateAcceptanceRecord(record), []);
});

test("rejects the unmodified acceptance template as placeholder data", () => {
  const templatePath = path.join(
    repositoryRoot,
    "docs",
    "governance",
    "acceptance",
    "template.md",
  );
  const record = parseAcceptanceRecord(fs.readFileSync(templatePath, "utf8"), templatePath);
  const errors = validateAcceptanceRecord(record);
  const output = errors.join("\n");

  assert.notEqual(errors.length, 0);
  assert.match(output, /acceptanceId/);
  assert.match(output, /subjectCommit/);
  assert.match(output, /pairedSubjectCommit/);
  assert.match(output, /submittedAt/);
});

test("decided records reject an inline-code PENDING decision reason from the template", () => {
  const templatePath = path.join(
    repositoryRoot,
    "docs",
    "governance",
    "acceptance",
    "template.md",
  );
  const source = fs
    .readFileSync(templatePath, "utf8")
    .replaceAll("REPLACE_ACCEPTANCE_ID", "M1-OWNER-GATE-002")
    .replaceAll("REPLACE_SUBJECT_COMMIT", subjectCommit)
    .replaceAll("REPLACE_PAIRED_SUBJECT_COMMIT", pairedSubjectCommit)
    .replaceAll("REPLACE_SUBJECT", "Example Release Acceptance")
    .replaceAll("REPLACE_BRANCH", "feat/example-release")
    .replaceAll("REPLACE_SUBMITTED_AT_UTC", "2026-08-25T08:45:37Z")
    .replace("status: PENDING #", "status: APPROVE #")
    .replace("owner: PENDING #", "owner: Project Owner #")
    .replace("decisionAt: PENDING #", "decisionAt: 2026-08-25T09:00:00Z #")
    .replace(
      /(## Decision Reason\r?\n\r?\n)`PENDING`[\s\S]*?(?=\r?\n## Follow-up Actions)/,
      "$1`PENDING`\n",
    )
    .replace(
      /^\| 2026-08-25T08:45:37Z \| PENDING \| PENDING \| .* \| PENDING \|$/m,
      initialHistoryRow +
        "\n" +
        `| 2026-08-25T09:00:00Z | APPROVE | Project Owner | Accepted | ${subjectCommit} |`,
    );
  const record = parseAcceptanceRecord(source, templatePath);

  assert.match(
    validateAcceptanceRecord(record).join("\n"),
    /decided record must provide a Decision Reason/,
  );
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

test("rejects a calendar-invalid UTC instant", () => {
  for (const invalidTimestamp of [
    "2026-02-29T00:00:00Z",
    "2026-13-99T25:61:61Z",
  ]) {
    const record = parseAcceptanceRecord(
      validPendingRecord.replace(
        "submittedAt: 2026-08-25T08:45:37Z",
        `submittedAt: ${invalidTimestamp}`,
      ),
      "record.md",
    );

    assert.match(validateAcceptanceRecord(record).join("\n"), /submittedAt/);
  }
});

test("rejects a calendar-invalid decisionAt instant", () => {
  for (const invalidTimestamp of [
    "2026-02-29T00:00:00Z",
    "2026-13-99T25:61:61Z",
  ]) {
    const record = parseAcceptanceRecord(
      decidedRecord({ decisionAt: invalidTimestamp }),
      "record.md",
    );

    assert.match(validateAcceptanceRecord(record).join("\n"), /decisionAt/);
  }
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

test("Decision History rejects empty data cells", () => {
  const emptyCells = {
    At: "| | PENDING | PENDING | Submitted | PENDING |",
    Owner: "| 2026-08-25T08:45:37Z | PENDING | | Submitted | PENDING |",
    Reason: "| 2026-08-25T08:45:37Z | PENDING | PENDING | | PENDING |",
    Commit: "| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | |",
  };

  for (const [field, row] of Object.entries(emptyCells)) {
    const record = parseAcceptanceRecord(
      validPendingRecord.replace(initialHistoryRow, row),
      `${field.toLowerCase()}-record.md`,
    );

    assert.match(
      validateAcceptanceRecord(record).join("\n"),
      new RegExp(`Decision History.*${field}`),
    );
  }
});

test("Decision History At must be a real UTC instant", () => {
  const record = parseAcceptanceRecord(
    validPendingRecord.replace(
      initialHistoryRow,
      "| 2026-02-29T08:45:37Z | PENDING | PENDING | Submitted | PENDING |",
    ),
    "record.md",
  );

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History.*At/);
});

test("Decision History first row must match submission metadata", () => {
  const invalidRows = [
    "| 2026-08-25T08:45:36Z | PENDING | PENDING | Submitted | PENDING |",
    "| 2026-08-25T08:45:37Z | PENDING | Project Owner | Submitted | PENDING |",
    `| 2026-08-25T08:45:37Z | PENDING | PENDING | Submitted | ${subjectCommit} |`,
    "| 2026-08-25T08:45:37Z | PENDING | PENDING | PENDING | PENDING |",
  ];

  for (const row of invalidRows) {
    const record = parseAcceptanceRecord(
      validPendingRecord.replace(initialHistoryRow, row),
      "record.md",
    );

    assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History first row/);
  }
});

test("Decision History later rows require a lowercase parent record SHA", () => {
  for (const invalidCommit of ["PENDING", "N/A", "A".repeat(40), "a".repeat(39)]) {
    const source = decidedRecord({
      rows: [
        initialHistoryRow,
        `| 2026-08-25T09:00:00Z | APPROVE | Project Owner | Accepted | ${invalidCommit} |`,
      ],
    });
    const record = parseAcceptanceRecord(source, "record.md");

    assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History.*Commit/);
  }
});

test("Decision History Owner must match the row status", () => {
  const invalidRows = [
    [
      initialHistoryRow,
      `| 2026-08-25T09:00:00Z | PENDING | Project Owner | Correction | ${subjectCommit} |`,
    ],
    [
      initialHistoryRow,
      `| 2026-08-25T09:00:00Z | APPROVE | PENDING | Accepted | ${subjectCommit} |`,
    ],
  ];

  for (const rows of invalidRows) {
    const source = rows[1].includes("| APPROVE |")
      ? decidedRecord({ rows })
      : withHistoryRows(validPendingRecord, rows);
    const record = parseAcceptanceRecord(source, "record.md");

    assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History.*Owner/);
  }
});

test("Decision History At values must be strictly increasing", () => {
  for (const laterAt of ["2026-08-25T08:45:37Z", "2026-08-25T08:45:36Z"]) {
    const record = parseAcceptanceRecord(
      withHistoryRows(validPendingRecord, [
        initialHistoryRow,
        `| ${laterAt} | PENDING | PENDING | Correction | ${subjectCommit} |`,
      ]),
      "record.md",
    );

    assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History.*increasing/);
  }
});

test("decided metadata matches the first arrival at its current status", () => {
  const rows = [
    initialHistoryRow,
    `| 2026-08-25T09:00:00Z | CONDITIONAL | Conditional Owner | Conditions set | ${"c".repeat(40)} |`,
    `| 2026-08-25T10:00:00Z | APPROVE | Final Owner | Accepted | ${"d".repeat(40)} |`,
    `| 2026-08-25T11:00:00Z | APPROVE | Correction Owner | Facts corrected | ${"e".repeat(40)} |`,
  ];
  const validSource = decidedRecord({
    owner: "Final Owner",
    decisionAt: "2026-08-25T10:00:00Z",
    rows,
  });
  const validRecord = parseAcceptanceRecord(validSource, "valid-record.md");

  assert.deepEqual(validateAcceptanceRecord(validRecord), []);

  const wrongDecisionAt = parseAcceptanceRecord(
    validSource.replace(
      "decisionAt: 2026-08-25T10:00:00Z",
      "decisionAt: 2026-08-25T11:00:00Z",
    ),
    "wrong-decision-at.md",
  );
  const wrongOwner = parseAcceptanceRecord(
    validSource.replace("owner: Final Owner", "owner: Correction Owner"),
    "wrong-owner.md",
  );

  assert.match(validateAcceptanceRecord(wrongDecisionAt).join("\n"), /decisionAt.*first/);
  assert.match(validateAcceptanceRecord(wrongOwner).join("\n"), /owner.*first/);
});

test("Decision History correction Reason cannot be PENDING", () => {
  const record = parseAcceptanceRecord(
    withHistoryRows(validPendingRecord, [
      initialHistoryRow,
      `| 2026-08-25T09:00:00Z | PENDING | PENDING | PENDING | ${subjectCommit} |`,
    ]),
    "record.md",
  );

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History.*Reason/);
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

test("Decision History rejects an internal blank line", () => {
  const source = validPendingRecord.replace(
    "|---|---|---|---|---|\n| 2026-08-25T08:45:37Z",
    "|---|---|---|---|---|\n   \n| 2026-08-25T08:45:37Z",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});

test("Decision History accepts an escaped pipe in a cell", () => {
  const source = validPendingRecord.replace("| Submitted |", "| A \\| B |");
  const record = parseAcceptanceRecord(source, "record.md");

  assert.deepEqual(validateAcceptanceRecord(record), []);
});

test("Decision History treats a pipe after an even backslash run as a delimiter", () => {
  const source = validPendingRecord.replace(
    "| Submitted | PENDING |",
    "| A\\\\| PENDING |",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.deepEqual(validateAcceptanceRecord(record), []);
});

test("Decision History rejects an escaped terminal pipe without a closing delimiter", () => {
  const source = validPendingRecord.replace(
    "| Submitted | PENDING |",
    "| Submitted | PENDING \\|",
  );
  const record = parseAcceptanceRecord(source, "record.md");

  assert.match(validateAcceptanceRecord(record).join("\n"), /Decision History/);
});
