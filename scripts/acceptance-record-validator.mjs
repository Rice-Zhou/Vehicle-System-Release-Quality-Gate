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
const ACCEPTANCE_ID_PATTERN = /^[A-Z0-9]+(?:-[A-Z0-9]+)+$/;
const UTC_TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const ALLOWED_TRANSITIONS = {
  PENDING: new Set(["APPROVE", "REJECT", "CONDITIONAL"]),
  CONDITIONAL: new Set(["APPROVE", "REJECT"]),
};

function hasValue(value) {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

function isUtcTimestamp(value) {
  return typeof value === "string" && UTC_TIMESTAMP_PATTERN.test(value);
}

export function sectionBody(body, heading) {
  const escapedHeading = heading.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = body.match(
    new RegExp(`^##[ \\t]+${escapedHeading}[ \\t]*\\r?\\n([\\s\\S]*?)(?=^##[ \\t]+|(?![\\s\\S]))`, "m"),
  );
  return match ? match[1].trim() : null;
}

export function decisionStatuses(body) {
  const history = sectionBody(body, "Decision History");
  if (!history) {
    return [];
  }

  const rows = history
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.startsWith("|") && line.endsWith("|"))
    .map((line) => line.slice(1, -1).split("|").map((cell) => cell.trim()));
  if (rows.length < 2) {
    return [];
  }

  const statusIndex = rows[0].findIndex((cell) => cell.toLowerCase() === "status");
  if (statusIndex === -1) {
    return [];
  }

  return rows.slice(2).map((row) => row[statusIndex]).filter(hasValue);
}

export function parseAcceptanceRecord(source, filePath) {
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) {
    throw new Error(`${filePath}: missing YAML front matter`);
  }

  const metadata = YAML.parse(match[1]);
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
    if (sectionBody(body, "Decision Reason") === "PENDING") {
      addError("decided record must provide a Decision Reason");
    }
  }

  for (const heading of REQUIRED_HEADINGS) {
    if (sectionBody(body, heading) === null) {
      addError(`missing required heading: ${heading}`);
    }
  }

  const statuses = decisionStatuses(body);
  if (statuses.length === 0) {
    addError("Decision History must contain at least one status");
  } else {
    if (statuses[0] !== "PENDING") {
      addError("Decision History must start with PENDING");
    }
    if (statuses.at(-1) !== metadata.status) {
      addError("Decision History final status must match declared status");
    }
    for (let index = 1; index < statuses.length; index += 1) {
      const previous = statuses[index - 1];
      const current = statuses[index];
      if (current !== previous && !ALLOWED_TRANSITIONS[previous]?.has(current)) {
        addError(`Decision History contains invalid transition: ${previous} -> ${current}`);
      }
    }
  }

  return errors;
}

export function validateDirectory(recordsDirectory) {
  if (!fs.existsSync(recordsDirectory)) {
    return [`${recordsDirectory}: no acceptance records`];
  }

  const files = fs.readdirSync(recordsDirectory)
    .filter((name) => name.endsWith(".md"))
    .sort((left, right) => left.localeCompare(right));
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
