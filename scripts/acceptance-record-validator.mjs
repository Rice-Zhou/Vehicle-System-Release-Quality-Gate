import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

const STATUSES = new Set(["PENDING", "APPROVE", "REJECT", "CONDITIONAL"]);
const REQUIRED_METADATA = [
  "acceptanceId",
  "subject",
  "subjectCommit",
  "pairedSubjectCommit",
  "branch",
  "status",
  "submittedAt",
  "owner",
  "decisionAt",
];
const REQUIRED_HEADINGS = [
  "Scope",
  "Evidence",
  "Acceptance Checks",
  "Residual Risks",
  "Decision Reason",
  "Follow-up Actions",
  "Decision History",
];
const COMMIT_PATTERN = /^(?:[0-9a-f]{40}|N\/A)$/;
const HISTORY_COMMIT_PATTERN = /^[0-9a-f]{40}$/;
const ACCEPTANCE_ID_PATTERN = /^[A-Z0-9]+(?:-[A-Z0-9]+)+$/;
const UTC_TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const DECISION_HISTORY_COLUMNS = ["At", "Status", "Owner", "Reason", "Commit"];
const MARKDOWN_SEPARATOR_PATTERN = /^:?-{3,}:?$/;
const ALLOWED_TRANSITIONS = {
  PENDING: new Set(["APPROVE", "REJECT", "CONDITIONAL"]),
  CONDITIONAL: new Set(["APPROVE", "REJECT"]),
};

function hasValue(value) {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

function isUtcTimestamp(value) {
  if (typeof value !== "string" || !UTC_TIMESTAMP_PATTERN.test(value)) {
    return false;
  }

  const instant = new Date(value);
  return (
    !Number.isNaN(instant.getTime()) &&
    instant.toISOString().replace(".000Z", "Z") === value
  );
}

export function sectionBody(body, heading) {
  const escapedHeading = heading.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = body.match(
    new RegExp(`^##[ \\t]+${escapedHeading}[ \\t]*\\r?\\n([\\s\\S]*?)(?=^##[ \\t]+|(?![\\s\\S]))`, "m"),
  );
  return match ? match[1].trim() : null;
}

function tokenizeMarkdownTableRow(line) {
  const cells = [];
  let cell = "";
  let backslashCount = 0;
  let hasOpeningDelimiter = false;
  let hasClosingDelimiter = false;

  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (character === "\\") {
      cell += character;
      backslashCount += 1;
    } else if (character === "|" && backslashCount % 2 === 0) {
      if (!hasOpeningDelimiter) {
        if (index !== 0) {
          return null;
        }
        hasOpeningDelimiter = true;
      } else {
        cells.push(cell.trim());
      }
      cell = "";
      backslashCount = 0;
      hasClosingDelimiter = index === line.length - 1;
    } else {
      cell += character;
      backslashCount = 0;
    }
  }

  return hasOpeningDelimiter && hasClosingDelimiter ? cells : null;
}

function parseDecisionHistory(body) {
  const history = sectionBody(body, "Decision History");
  if (!history) {
    return { valid: false, rows: [], statuses: [], structureErrors: ["is missing"] };
  }

  const lines = history
    .split(/\r?\n/)
    .map((line) => line.trim());
  if (lines.some((line) => !hasValue(line))) {
    return {
      valid: false,
      rows: [],
      statuses: [],
      structureErrors: ["contains an internal blank line"],
    };
  }

  const rows = lines.map(tokenizeMarkdownTableRow);
  if (rows.some((row) => row === null)) {
    return {
      valid: false,
      rows: [],
      statuses: [],
      structureErrors: ["contains a malformed table row"],
    };
  }
  if (
    rows.length < 2 ||
    !DECISION_HISTORY_COLUMNS.every((column, index) => rows[0][index] === column) ||
    rows[0].length !== DECISION_HISTORY_COLUMNS.length ||
    rows[1].length !== DECISION_HISTORY_COLUMNS.length ||
    !rows[1].every((cell) => MARKDOWN_SEPARATOR_PATTERN.test(cell))
  ) {
    return {
      valid: false,
      rows: [],
      statuses: [],
      structureErrors: ["has an invalid header or separator"],
    };
  }

  const dataRows = rows.slice(2);
  if (dataRows.some((row) => row.length !== DECISION_HISTORY_COLUMNS.length)) {
    return {
      valid: false,
      rows: [],
      statuses: [],
      structureErrors: ["contains a data row with an invalid column count"],
    };
  }

  const parsedRows = dataRows.map((row) =>
    Object.fromEntries(DECISION_HISTORY_COLUMNS.map((column, index) => [column, row[index]])),
  );
  return {
    valid: true,
    rows: parsedRows,
    statuses: parsedRows.map((row) => row.Status),
    structureErrors: [],
  };
}

export function decisionStatuses(body) {
  return parseDecisionHistory(body).statuses;
}

export function parseAcceptanceRecord(source, filePath) {
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) {
    throw new Error(`${filePath}: missing YAML front matter`);
  }

  let metadata;
  try {
    metadata = YAML.parse(match[1]);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`${filePath}: invalid YAML front matter: ${detail}`, { cause: error });
  }
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
    throw new Error(`${filePath}: YAML front matter must be a mapping`);
  }

  return { filePath, metadata, body: match[2] };
}

export function validateAcceptanceRecord(record) {
  const { filePath, body } = record;
  const metadata = record.metadata ?? {};
  const errors = [];
  const addError = (message) => errors.push(`${filePath}: ${message}`);

  for (const key of REQUIRED_METADATA) {
    if (!hasValue(metadata[key])) {
      addError(`missing required metadata: ${key}`);
    }
  }

  if (hasValue(metadata.acceptanceId) && !ACCEPTANCE_ID_PATTERN.test(metadata.acceptanceId)) {
    addError("invalid acceptanceId");
  }
  for (const key of ["subjectCommit", "pairedSubjectCommit"]) {
    if (hasValue(metadata[key]) && !COMMIT_PATTERN.test(metadata[key])) {
      addError(`invalid ${key}`);
    }
  }
  if (hasValue(metadata.status) && !STATUSES.has(metadata.status)) {
    addError(`invalid status: ${metadata.status}`);
  }
  if (hasValue(metadata.submittedAt) && !isUtcTimestamp(metadata.submittedAt)) {
    addError("invalid submittedAt; expected YYYY-MM-DDTHH:mm:ssZ");
  }
  if (hasValue(metadata.decisionAt) && metadata.decisionAt !== "PENDING" && !isUtcTimestamp(metadata.decisionAt)) {
    addError("invalid decisionAt; expected PENDING or YYYY-MM-DDTHH:mm:ssZ");
  }

  if (metadata.status === "PENDING") {
    if (metadata.owner !== "PENDING" || metadata.decisionAt !== "PENDING") {
      addError("PENDING record must keep owner and decisionAt as PENDING");
    }
  } else if (STATUSES.has(metadata.status)) {
    if (metadata.owner === "PENDING" || metadata.decisionAt === "PENDING") {
      addError("decided record must set owner and decisionAt");
    }
    const decisionReason = sectionBody(body, "Decision Reason");
    if (!hasValue(decisionReason) || decisionReason === "PENDING") {
      addError("decided record must provide a Decision Reason");
    }
  }

  for (const heading of REQUIRED_HEADINGS) {
    if (sectionBody(body, heading) === null) {
      addError(`missing required heading: ${heading}`);
    }
  }

  const history = parseDecisionHistory(body);
  const { rows, statuses } = history;
  if (!history.valid) {
    for (const structureError of history.structureErrors) {
      addError(`Decision History ${structureError}`);
    }
  } else if (statuses.length === 0) {
    addError("Decision History must contain at least one status");
  } else {
    rows.forEach((row, index) => {
      const rowNumber = index + 1;
      for (const field of DECISION_HISTORY_COLUMNS) {
        if (!hasValue(row[field])) {
          addError(`Decision History row ${rowNumber} ${field} must not be empty`);
        }
      }
      if (hasValue(row.At) && !isUtcTimestamp(row.At)) {
        addError(`Decision History row ${rowNumber} At must be a real UTC instant`);
      }
      if (hasValue(row.Reason) && row.Reason === "PENDING") {
        addError(`Decision History row ${rowNumber} Reason must not be PENDING`);
      }
    });

    const firstRow = rows[0];
    if (firstRow.At !== metadata.submittedAt) {
      addError("Decision History first row At must equal metadata submittedAt");
    }
    if (statuses[0] !== "PENDING") {
      addError("Decision History must start with PENDING");
    }
    if (firstRow.Owner !== "PENDING") {
      addError("Decision History first row Owner must be PENDING");
    }
    if (firstRow.Commit !== "PENDING") {
      addError("Decision History first row Commit must be PENDING");
    }
    if (!hasValue(firstRow.Reason) || firstRow.Reason === "PENDING") {
      addError("Decision History first row Reason must be non-empty and not PENDING");
    }
    if (statuses.at(-1) !== metadata.status) {
      addError("Decision History final status must match declared status");
    }
    for (let index = 1; index < statuses.length; index += 1) {
      const previous = statuses[index - 1];
      const current = statuses[index];
      const row = rows[index];
      const previousRow = rows[index - 1];
      if (hasValue(row.Commit) && !HISTORY_COMMIT_PATTERN.test(row.Commit)) {
        addError(`Decision History row ${index + 1} Commit must be a 40-character lowercase SHA`);
      }
      if (current === "PENDING" && row.Owner !== "PENDING") {
        addError(`Decision History row ${index + 1} Owner must be PENDING for PENDING status`);
      } else if (hasValue(current) && current !== "PENDING" && row.Owner === "PENDING") {
        addError(`Decision History row ${index + 1} Owner must not be PENDING for ${current} status`);
      }
      if (
        isUtcTimestamp(previousRow.At) &&
        isUtcTimestamp(row.At) &&
        Date.parse(row.At) <= Date.parse(previousRow.At)
      ) {
        addError("Decision History At values must be strictly increasing");
      }
      if (current !== previous && !ALLOWED_TRANSITIONS[previous]?.has(current)) {
        addError(`Decision History contains invalid transition: ${previous} -> ${current}`);
      }
    }

    if (metadata.status !== "PENDING" && STATUSES.has(metadata.status)) {
      const decisionRow = rows.find((row) => row.Status === metadata.status);
      if (decisionRow) {
        if (metadata.decisionAt !== decisionRow.At) {
          addError(`decisionAt must match Decision History first arrival at ${metadata.status}`);
        }
        if (metadata.owner !== decisionRow.Owner) {
          addError(`owner must match Decision History first arrival at ${metadata.status}`);
        }
      }
    }
  }

  return errors;
}

export function validateDirectory(recordsDirectory) {
  let names;
  try {
    names = fs.readdirSync(recordsDirectory);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    return [`${recordsDirectory}: unable to read acceptance records directory: ${detail}`];
  }

  const files = names
    .filter((name) => name.endsWith(".md"))
    .sort();
  if (files.length === 0) {
    return [`${recordsDirectory}: no acceptance records`];
  }

  const errors = [];
  const acceptanceIds = new Map();
  for (const name of files) {
    const filePath = path.join(recordsDirectory, name);
    try {
      const record = parseAcceptanceRecord(fs.readFileSync(filePath, "utf8"), filePath);
      errors.push(...validateAcceptanceRecord(record));

      if (hasValue(record.metadata.acceptanceId)) {
        const existing = acceptanceIds.get(record.metadata.acceptanceId);
        if (existing) {
          errors.push(`${filePath}: duplicate acceptanceId ${record.metadata.acceptanceId} (also in ${existing})`);
        } else {
          acceptanceIds.set(record.metadata.acceptanceId, filePath);
        }
      }
    } catch (error) {
      errors.push(error instanceof Error ? error.message : String(error));
    }
  }

  return errors;
}

const currentFilePath = fileURLToPath(import.meta.url);
if (process.argv[1] && path.resolve(process.argv[1]) === currentFilePath) {
  const repositoryRoot = path.dirname(path.dirname(currentFilePath));
  const recordsDirectory = path.join(repositoryRoot, "docs", "governance", "acceptance", "records");
  const errors = validateDirectory(recordsDirectory);
  if (errors.length > 0) {
    for (const error of errors) {
      console.error(`FAIL ${error}`);
    }
    process.exitCode = 1;
  } else {
    console.log("PASS acceptance-records");
  }
}
