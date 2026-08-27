import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { createHash } from "node:crypto";
import { TextDecoder } from "node:util";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import canonicalize from "canonicalize";

const MAX_INPUT_BYTES = 1024 * 1024;
const REQUIRED_ARTIFACT_COUNT = 2;
const WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001";
const SHA256 = /^[0-9a-f]{64}$/;
const PORTABLE_FILE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const WINDOWS_RESERVED_NAME = /^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/i;
const FORBIDDEN_MARKER = /(?:credential|password|secret|token|private[\s_-]*key)/i;
const RAW_PRINCIPAL = /(?:arn:|\b(?:account|subject|session[\s_-]*name|user[\s_-]*id|principal)\b\s*[:=])/i;
const QUERY_CREDENTIAL = /[?&](?:x-amz-|signature=|credential=|security-token=|access[_-]?token=)/i;
const HTTP_URL = /https?:\/\//i;
const URL_USER_INFO = /^[a-z][a-z0-9+.-]*:\/\/[^/?#]*@/i;
const WINDOWS_ABSOLUTE_PATH = /^[A-Za-z]:[\\/]/;
const UNC_PATH = /^(?:\\\\|\/\/)[^/\\]+[/\\][^/\\]+/;
const POSIX_ABSOLUTE_PATH = /^\/(?!\/)/;
const UTF8 = new TextDecoder("utf-8", { fatal: true });

const currentFilePath = fileURLToPath(import.meta.url);
const repositoryRoot = path.resolve(path.dirname(currentFilePath), "../..");
const schemaDirectory = path.join(repositoryRoot, "ops", "evidence-archive", "schemas");
const ajv = new Ajv2020({ allErrors: true, strict: true });
const validators = Object.fromEntries(
  [
    ["descriptor", "work-package.schema.json"],
    ["archiveReport", "archive-execution.schema.json"],
    ["recoveryReport", "recovery-verification.schema.json"],
  ].map(([name, fileName]) => [
    name,
    ajv.compile(JSON.parse(fs.readFileSync(path.join(schemaDirectory, fileName), "utf8"))),
  ]),
);

export class EvidenceVerificationError extends Error {
  constructor(code) {
    super(code);
    this.name = "EvidenceVerificationError";
    this.code = code;
  }
}

function fail(code) {
  throw new EvidenceVerificationError(code);
}

function rawBuffer(value) {
  if (!Buffer.isBuffer(value) && !(value instanceof Uint8Array)) fail("MALFORMED_JSON");
  const bytes = Buffer.from(value);
  if (bytes.length === 0 || bytes.length > MAX_INPUT_BYTES) fail("MALFORMED_JSON");
  return bytes;
}

function decodeUtf8(bytes) {
  try {
    return UTF8.decode(bytes);
  } catch {
    fail("MALFORMED_JSON");
  }
}

// JSON.parse validates syntax. This scanner only tracks object keys so duplicate members
// cannot be silently collapsed before schema and cross-document validation.
function rejectDuplicateKeys(source) {
  let index = 0;

  const whitespace = () => {
    while (/[\t\n\r ]/.test(source[index] ?? "")) index += 1;
  };

  const stringToken = () => {
    if (source[index] !== '"') fail("MALFORMED_JSON");
    const start = index;
    index += 1;
    while (index < source.length) {
      const character = source[index];
      if (character === '"') {
        index += 1;
        try {
          return JSON.parse(source.slice(start, index));
        } catch {
          fail("MALFORMED_JSON");
        }
      }
      if (character === "\\") {
        index += 2;
      } else {
        index += 1;
      }
    }
    fail("MALFORMED_JSON");
  };

  const value = () => {
    whitespace();
    if (source[index] === "{") {
      object();
    } else if (source[index] === "[") {
      array();
    } else if (source[index] === '"') {
      stringToken();
    } else {
      const start = index;
      while (index < source.length && !/[\s,}\]]/.test(source[index])) index += 1;
      if (start === index) fail("MALFORMED_JSON");
    }
  };

  const object = () => {
    index += 1;
    whitespace();
    const keys = new Set();
    if (source[index] === "}") {
      index += 1;
      return;
    }
    while (index < source.length) {
      const key = stringToken();
      if (keys.has(key)) fail("MALFORMED_JSON");
      keys.add(key);
      whitespace();
      if (source[index] !== ":") fail("MALFORMED_JSON");
      index += 1;
      value();
      whitespace();
      if (source[index] === "}") {
        index += 1;
        return;
      }
      if (source[index] !== ",") fail("MALFORMED_JSON");
      index += 1;
      whitespace();
    }
    fail("MALFORMED_JSON");
  };

  const array = () => {
    index += 1;
    whitespace();
    if (source[index] === "]") {
      index += 1;
      return;
    }
    while (index < source.length) {
      value();
      whitespace();
      if (source[index] === "]") {
        index += 1;
        return;
      }
      if (source[index] !== ",") fail("MALFORMED_JSON");
      index += 1;
    }
    fail("MALFORMED_JSON");
  };

  whitespace();
  value();
  whitespace();
  if (index !== source.length) fail("MALFORMED_JSON");
}

function parseStrict(bytesValue, requireCanonical) {
  const bytes = rawBuffer(bytesValue);
  const source = decodeUtf8(bytes);
  rejectDuplicateKeys(source);
  let value;
  try {
    value = JSON.parse(source);
  } catch {
    fail("MALFORMED_JSON");
  }
  if (value === null || typeof value !== "object" || Array.isArray(value)) fail("SCHEMA_INVALID");
  if (requireCanonical) {
    const canonical = canonicalize(value);
    if (canonical === undefined || !bytes.equals(Buffer.from(canonical, "utf8"))) {
      fail("NON_CANONICAL_JSON");
    }
  }
  scanForbiddenValues(value);
  return { bytes, value };
}

function scanForbiddenValues(value) {
  if (typeof value === "string") {
    if (
      FORBIDDEN_MARKER.test(value) ||
      RAW_PRINCIPAL.test(value) ||
      QUERY_CREDENTIAL.test(value) ||
      HTTP_URL.test(value) ||
      URL_USER_INFO.test(value) ||
      WINDOWS_ABSOLUTE_PATH.test(value) ||
      UNC_PATH.test(value) ||
      POSIX_ABSOLUTE_PATH.test(value)
    ) {
      fail("FORBIDDEN_VALUE");
    }
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) scanForbiddenValues(item);
    return;
  }
  if (value && typeof value === "object") {
    for (const item of Object.values(value)) scanForbiddenValues(item);
  }
}

function validateSchema(name, value) {
  if (!validators[name](value)) fail("SCHEMA_INVALID");
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function portableReportFileName(name) {
  if (typeof name !== "string" || Buffer.byteLength(name, "utf8") > 181 || !PORTABLE_FILE_NAME.test(name)) {
    return false;
  }
  if (name.includes("..") || name.endsWith(".") || name.endsWith(" ")) return false;
  return !WINDOWS_RESERVED_NAME.test(name.split(".", 1)[0]);
}

function validateCompletionMarker(recoveryReportFileName, recoveryReportBytes, marker) {
  if (!portableReportFileName(recoveryReportFileName) || marker === null || typeof marker !== "object") {
    fail("MARKER_INVALID");
  }
  const expected = `${recoveryReportFileName}.complete.${sha256(recoveryReportBytes)}`;
  const validDirectory =
    typeof marker.directory === "string" &&
    typeof marker.recoveryReportDirectory === "string" &&
    path.resolve(marker.directory) === path.resolve(marker.recoveryReportDirectory);
  if (
    marker.fileName !== expected ||
    Buffer.byteLength(expected, "utf8") > 255 ||
    !validDirectory ||
    marker.isFile !== true ||
    marker.isSymbolicLink !== false ||
    marker.size !== 0
  ) {
    fail("MARKER_INVALID");
  }
}

function artifactMap(artifacts) {
  if (artifacts.length !== REQUIRED_ARTIFACT_COUNT) fail("EVIDENCE_MISMATCH");
  const sorted = [...artifacts].sort((left, right) => left.artifactId < right.artifactId ? -1 : left.artifactId > right.artifactId ? 1 : 0);
  if (new Set(sorted.map(({ artifactId }) => artifactId)).size !== REQUIRED_ARTIFACT_COUNT) {
    fail("EVIDENCE_MISMATCH");
  }
  return sorted;
}

function sameValue(left, right) {
  return canonicalize(left) === canonicalize(right);
}

function validateExactReference(reference) {
  const segments = reference.key.split("/");
  if (
    reference.provider !== "S3_COMPATIBLE" ||
    !reference.bucket ||
    !reference.key ||
    reference.key.startsWith("/") ||
    reference.key.includes("\\") ||
    segments.some((segment) => segment === "" || segment === "." || segment === "..") ||
    /[\u0000-\u001f\u007f]/.test(reference.key) ||
    !reference.versionId ||
    /^(?:null|latest)$/i.test(reference.versionId) ||
    !SHA256.test(reference.sha256) ||
    !Number.isSafeInteger(reference.sizeBytes) ||
    reference.sizeBytes < 1
  ) {
    fail("EVIDENCE_MISMATCH");
  }
  let locator;
  try {
    locator = new URL(reference.locator);
  } catch {
    fail("EVIDENCE_MISMATCH");
  }
  if (
    locator.protocol !== "s3:" ||
    locator.username !== "" ||
    locator.password !== "" ||
    locator.hostname !== reference.bucket ||
    locator.search !== "" ||
    locator.hash !== "" ||
    locator.pathname !== `/${reference.key}` ||
    reference.locator !== `s3://${reference.bucket}/${reference.key}`
  ) {
    fail("EVIDENCE_MISMATCH");
  }
}

function instant(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) fail("EVIDENCE_MISMATCH");
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, fraction = ""] = match;
  const [year, month, day, hour, minute, second] = [yearText, monthText, dayText, hourText, minuteText, secondText].map(Number);
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day ||
    date.getUTCHours() !== hour ||
    date.getUTCMinutes() !== minute ||
    date.getUTCSeconds() !== second
  ) {
    fail("EVIDENCE_MISMATCH");
  }
  return BigInt(date.getTime()) * 1000000n + BigInt(fraction.padEnd(9, "0"));
}

function validatePositiveRetention(value) {
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(value);
  if (!match || match.slice(1).every((part) => part === undefined || Number(part) === 0)) {
    fail("EVIDENCE_MISMATCH");
  }
}

function crossValidate(descriptor, descriptorDigest, archive, recovery) {
  if (archive.status !== "PASS" || archive.errorCode !== null) fail("STATUS_NOT_PASS");
  if (
    recovery.status !== "PASS" ||
    recovery.errorCode !== null ||
    recovery.cleanupStatus !== "PASS" ||
    recovery.cleanupErrorCode !== null
  ) {
    fail("STATUS_NOT_PASS");
  }

  if (
    descriptor.workPackageId !== WORK_PACKAGE_ID ||
    archive.workPackageId !== descriptor.workPackageId ||
    recovery.workPackageId !== descriptor.workPackageId ||
    archive.descriptorSha256 !== descriptorDigest ||
    recovery.descriptorSha256 !== descriptorDigest ||
    archive.pilotManifestSha256 !== descriptor.pilotManifest.sha256 ||
    recovery.pilotManifestSha256 !== descriptor.pilotManifest.sha256 ||
    recovery.executionId !== archive.executionId
  ) {
    fail("EVIDENCE_MISMATCH");
  }

  if (!sameValue(archive.runtimeIdentity, recovery.archiveIdentity)) fail("EVIDENCE_MISMATCH");
  if (sameValue(recovery.archiveIdentity, recovery.verifierIdentity)) fail("IDENTITY_NOT_INDEPENDENT");

  validatePositiveRetention(archive.retentionPolicy);
  const archiveStarted = instant(archive.startedAt);
  const capabilityChecked = instant(archive.capabilityCheckedAt);
  const archiveCompleted = instant(archive.completedAt);
  const recoveryStarted = instant(recovery.startedAt);
  const recoveryCompleted = instant(recovery.completedAt);
  if (
    archiveStarted > capabilityChecked ||
    capabilityChecked > archiveCompleted ||
    archiveCompleted > recoveryStarted ||
    recoveryStarted > recoveryCompleted
  ) {
    fail("EVIDENCE_MISMATCH");
  }

  const sources = artifactMap(descriptor.artifacts);
  const archived = artifactMap(archive.artifacts);
  const recovered = artifactMap(recovery.artifacts);
  for (let index = 0; index < REQUIRED_ARTIFACT_COUNT; index += 1) {
    const source = sources[index];
    const archiveArtifact = archived[index];
    const recoveryArtifact = recovered[index];
    if (
      archiveArtifact.artifactId !== source.artifactId ||
      recoveryArtifact.artifactId !== source.artifactId ||
      archiveArtifact.sourceRunId !== source.sourceRunId ||
      recoveryArtifact.sourceRunId !== source.sourceRunId ||
      archiveArtifact.sourceCommit !== source.sourceCommit ||
      recoveryArtifact.sourceCommit !== source.sourceCommit
    ) {
      fail("EVIDENCE_MISMATCH");
    }

    validateExactReference(archiveArtifact.payload);
    validateExactReference(archiveArtifact.receiptReference);
    validateExactReference(recoveryArtifact.payload.reference);
    validateExactReference(recoveryArtifact.receipt.reference);
    if (
      archiveArtifact.payload.sha256 !== source.sha256 ||
      archiveArtifact.payload.sizeBytes !== source.sizeBytes ||
      !sameValue(archiveArtifact.payload, recoveryArtifact.payload.reference) ||
      !sameValue(archiveArtifact.receiptReference, recoveryArtifact.receipt.reference) ||
      recoveryArtifact.payload.recoveredSha256 !== archiveArtifact.payload.sha256 ||
      recoveryArtifact.payload.recoveredSizeBytes !== archiveArtifact.payload.sizeBytes ||
      recoveryArtifact.receipt.recoveredSha256 !== archiveArtifact.receiptReference.sha256 ||
      recoveryArtifact.receipt.recoveredSizeBytes !== archiveArtifact.receiptReference.sizeBytes ||
      instant(recoveryArtifact.payload.protection.retainUntil) <= recoveryCompleted ||
      instant(recoveryArtifact.receipt.protection.retainUntil) <= recoveryCompleted
    ) {
      fail("EVIDENCE_MISMATCH");
    }
  }
}

export function verifyEvidence({
  descriptorBytes,
  archiveReportBytes,
  recoveryReportBytes,
  recoveryReportFileName,
  completionMarker,
} = {}) {
  const descriptorInput = parseStrict(descriptorBytes, false);
  const archiveInput = parseStrict(archiveReportBytes, true);
  const recoveryInput = parseStrict(recoveryReportBytes, true);
  validateCompletionMarker(recoveryReportFileName, recoveryInput.bytes, completionMarker);
  validateSchema("descriptor", descriptorInput.value);
  validateSchema("archiveReport", archiveInput.value);
  validateSchema("recoveryReport", recoveryInput.value);
  crossValidate(
    descriptorInput.value,
    sha256(descriptorInput.bytes),
    archiveInput.value,
    recoveryInput.value,
  );
  return {
    workPackageId: WORK_PACKAGE_ID,
    result: "PASS",
    artifactCount: REQUIRED_ARTIFACT_COUNT,
  };
}

function parseArguments(args) {
  const expected = new Set(["work-package", "archive-report", "recovery-report"]);
  const values = new Map();
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (!argument.startsWith("--")) fail("USAGE_ERROR");
    let key;
    let value;
    const separator = argument.indexOf("=");
    if (separator >= 0) {
      key = argument.slice(2, separator);
      value = argument.slice(separator + 1);
    } else {
      key = argument.slice(2);
      value = args[index + 1];
      index += 1;
    }
    if (!expected.has(key) || values.has(key) || typeof value !== "string" || value === "" || value.startsWith("--")) {
      fail("USAGE_ERROR");
    }
    values.set(key, value);
  }
  if (values.size !== expected.size) fail("USAGE_ERROR");
  return values;
}

function readRegularFile(filePath) {
  try {
    const stats = fs.lstatSync(filePath);
    if (!stats.isFile() || stats.isSymbolicLink()) fail("INPUT_INVALID");
    return fs.readFileSync(filePath);
  } catch (error) {
    if (error instanceof EvidenceVerificationError) throw error;
    fail("INPUT_INVALID");
  }
}

function markerFacts(markerPath, recoveryReportPath) {
  try {
    const stats = fs.lstatSync(markerPath);
    return {
      fileName: path.basename(markerPath),
      directory: path.dirname(path.resolve(markerPath)),
      recoveryReportDirectory: path.dirname(path.resolve(recoveryReportPath)),
      isFile: stats.isFile(),
      isSymbolicLink: stats.isSymbolicLink(),
      size: stats.size,
    };
  } catch {
    fail("MARKER_INVALID");
  }
}

function safeCode(error) {
  const allowed = new Set([
    "USAGE_ERROR",
    "INPUT_INVALID",
    "MALFORMED_JSON",
    "NON_CANONICAL_JSON",
    "SCHEMA_INVALID",
    "FORBIDDEN_VALUE",
    "MARKER_INVALID",
    "EVIDENCE_MISMATCH",
    "IDENTITY_NOT_INDEPENDENT",
    "STATUS_NOT_PASS",
  ]);
  return error instanceof EvidenceVerificationError && allowed.has(error.code) ? error.code : "UNEXPECTED_FAILURE";
}

function emitError(code) {
  process.stderr.write(`${canonicalize({ code })}\n`);
}

function runCli(args) {
  let values;
  try {
    values = parseArguments(args);
  } catch (error) {
    emitError(safeCode(error));
    return 2;
  }
  try {
    const descriptorBytes = readRegularFile(values.get("work-package"));
    const archiveReportBytes = readRegularFile(values.get("archive-report"));
    const recoveryReportPath = values.get("recovery-report");
    const recoveryReportBytes = readRegularFile(recoveryReportPath);
    const recoveryReportFileName = path.basename(recoveryReportPath);
    const markerPath = path.join(
      path.dirname(recoveryReportPath),
      `${recoveryReportFileName}.complete.${sha256(recoveryReportBytes)}`,
    );
    const result = verifyEvidence({
      descriptorBytes,
      archiveReportBytes,
      recoveryReportBytes,
      recoveryReportFileName,
      completionMarker: markerFacts(markerPath, recoveryReportPath),
    });
    process.stdout.write(`${canonicalize(result)}\n`);
    return 0;
  } catch (error) {
    emitError(safeCode(error));
    return 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === currentFilePath) {
  process.exitCode = runCli(process.argv.slice(2));
}
