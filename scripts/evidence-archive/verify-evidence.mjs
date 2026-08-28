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
const NONBLOCK_READ_FLAG = fs.constants.O_NONBLOCK ?? 0;
const SHA256 = /^[0-9a-f]{64}$/;
const PORTABLE_FILE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const WINDOWS_RESERVED_NAME = /^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/i;
const RAW_PRINCIPAL = /(?:arn:(?:aws|aws-cn|aws-us-gov):(?:iam|sts):|\b(?:principal|account|subject|session[\s_-]*name|user[\s_-]*id|iam[\s_-]*(?:user|role)|role[\s_-]*session)\b\s*[:=])/i;
const ASSIGNED_SECRET = /\b(?:credential|secret|password|private[\s_-]*key)\b\s*[:=]/i;
const QUERY_CREDENTIAL = /[?&](?:x-amz-|signature=|credential=|security-token=|access[_-]?token=)/i;
const HTTP_URL = /https?:\/\//i;
const URI_SCHEME = /[a-z][a-z0-9+.-]*:\/\//i;
const FILE_URI = /(?:^|[^a-z0-9])file:/i;
const URL_USER_INFO = /[a-z][a-z0-9+.-]*:\/\/[^/?#\s]*@/i;
const AWS_ACCESS_KEY = /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/;
const PEM_PRIVATE_KEY = /-{5}BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-{5}/i;
const GITHUB_TOKEN = /(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{50,255})/;
const AUTHORIZATION = /(?:authorization\s*[:=]\s*bearer\b|\bbearer\s+[A-Za-z0-9._~+/=-]+)/i;
const WINDOWS_ABSOLUTE_PATH = /(?:^|[\s=:;,(\[])[A-Za-z]:[\\/]/;
const UNC_PATH = /(?:^|[\s=;,(\[])(?:\\\\|\/\/)[^/\\]+[/\\][^/\\]+/;
const POSIX_ABSOLUTE_PATH = /(?:^|[\s=:;,(\[])\/(?!\/)/;
const ISO_CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
const JVM_WHITESPACE_CODE_UNIT = /[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/;
const UTF8 = new TextDecoder("utf-8", { fatal: true });

const currentFilePath = fileURLToPath(import.meta.url);
const repositoryRoot = path.resolve(path.dirname(currentFilePath), "../..");
const schemaDirectory = path.join(repositoryRoot, "ops", "evidence-archive", "schemas");

class EvidenceVerificationError extends Error {
  constructor(code, cause) {
    super(code, cause === undefined ? undefined : { cause });
    this.name = "EvidenceVerificationError";
    this.code = code;
  }
}

let schemaInitialization = null;

function schemaValidators() {
  if (schemaInitialization?.validators) return schemaInitialization.validators;
  if (schemaInitialization?.error) throw schemaInitialization.error;
  try {
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
    schemaInitialization = { validators };
    return validators;
  } catch (cause) {
    const error = new EvidenceVerificationError("SCHEMA_INITIALIZATION_FAILED", cause);
    schemaInitialization = { error };
    throw error;
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

function scanForbiddenValues(value, fieldPath = []) {
  if (typeof value === "string") {
    const fieldName = fieldPath.findLast((part) => typeof part === "string") ?? null;
    if (
      RAW_PRINCIPAL.test(value) ||
      ASSIGNED_SECRET.test(value) ||
      QUERY_CREDENTIAL.test(value) ||
      HTTP_URL.test(value) ||
      FILE_URI.test(value) ||
      (fieldName !== "locator" && URI_SCHEME.test(value)) ||
      URL_USER_INFO.test(value) ||
      AWS_ACCESS_KEY.test(value) ||
      PEM_PRIVATE_KEY.test(value) ||
      GITHUB_TOKEN.test(value) ||
      AUTHORIZATION.test(value) ||
      WINDOWS_ABSOLUTE_PATH.test(value) ||
      UNC_PATH.test(value) ||
      POSIX_ABSOLUTE_PATH.test(value) ||
      ISO_CONTROL.test(value)
    ) {
      fail("FORBIDDEN_VALUE");
    }
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => scanForbiddenValues(item, [...fieldPath, index]));
    return;
  }
  if (value && typeof value === "object") {
    for (const [name, item] of Object.entries(value)) scanForbiddenValues(item, [...fieldPath, name]);
  }
}

function validateSchema(name, value) {
  if (!schemaValidators()[name](value)) fail("SCHEMA_INVALID");
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

function isJvmBlank(value) {
  if (value.length === 0) return true;
  for (let index = 0; index < value.length; index += 1) {
    if (!JVM_WHITESPACE_CODE_UNIT.test(value[index])) return false;
  }
  return true;
}

function validateExactReference(reference) {
  const segments = reference.key.split("/");
  if (
    reference.provider !== "S3_COMPATIBLE" ||
    isJvmBlank(reference.bucket) ||
    isJvmBlank(reference.key) ||
    Buffer.byteLength(reference.key, "utf8") > 1024 ||
    reference.key.startsWith("/") ||
    reference.key.includes("\\") ||
    segments.some((segment) => segment === "" || segment === "." || segment === "..") ||
    isJvmBlank(reference.versionId) ||
    /^null$/i.test(reference.versionId) ||
    !SHA256.test(reference.sha256) ||
    !Number.isSafeInteger(reference.sizeBytes) ||
    reference.sizeBytes < 1
  ) {
    fail("EVIDENCE_MISMATCH");
  }
  if (reference.locator !== `s3://${reference.bucket}/${reference.key}`) {
    fail("EVIDENCE_MISMATCH");
  }
}

function canonicalInstant(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) fail("EVIDENCE_MISMATCH");
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, fraction = ""] = match;
  if (
    fraction !== "" &&
    (![3, 6, 9].includes(fraction.length) || fraction === "000" || (fraction.length > 3 && fraction.endsWith("000")))
  ) {
    fail("EVIDENCE_MISMATCH");
  }
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

function retentionNanoseconds(value) {
  const daysOnly = /^P(\d+)D$/.exec(value);
  const withTime = /^P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?$/.exec(value);
  let days = "0";
  let hours = "0";
  let minutes = "0";
  let seconds = "0";
  let fraction = "";
  if (daysOnly) {
    days = daysOnly[1];
  } else if (withTime && withTime.slice(2, 5).some((part) => part !== undefined)) {
    [, days = "0", hours = "0", minutes = "0", seconds = "0", fraction = ""] = withTime;
  } else {
    fail("EVIDENCE_MISMATCH");
  }
  const wholeSeconds = ((BigInt(days) * 24n + BigInt(hours)) * 60n + BigInt(minutes)) * 60n + BigInt(seconds);
  const result = wholeSeconds * 1000000000n + BigInt(fraction.padEnd(9, "0") || "0");
  if (result <= 0n) fail("EVIDENCE_MISMATCH");
  return result;
}

function exactObjectIdentity(reference) {
  return [reference.provider, reference.bucket, reference.key, reference.versionId].join("\u0000");
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

  const retention = retentionNanoseconds(archive.retentionPolicy);
  const archiveStarted = canonicalInstant(archive.startedAt);
  const capabilityChecked = canonicalInstant(archive.capabilityCheckedAt);
  const archiveCompleted = canonicalInstant(archive.completedAt);
  const recoveryStarted = canonicalInstant(recovery.startedAt);
  const recoveryCompleted = canonicalInstant(recovery.completedAt);
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
  const exactObjects = new Map();
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
    for (const reference of [archiveArtifact.payload, archiveArtifact.receiptReference]) {
      const identity = exactObjectIdentity(reference);
      if (exactObjects.has(identity)) fail("EVIDENCE_MISMATCH");
      exactObjects.set(identity, reference);
    }
    const receiptArchivedAt = canonicalInstant(recoveryArtifact.receiptArchivedAt);
    const requiredRetainUntil = receiptArchivedAt + retention;
    const maximumSupportedInstant = canonicalInstant("9999-12-31T23:59:59.999999999Z");
    if (
      exactObjectIdentity(archiveArtifact.payload) === exactObjectIdentity(archiveArtifact.receiptReference) ||
      archiveArtifact.payload.sha256 !== source.sha256 ||
      archiveArtifact.payload.sizeBytes !== source.sizeBytes ||
      !sameValue(archiveArtifact.payload, recoveryArtifact.payload.reference) ||
      !sameValue(archiveArtifact.receiptReference, recoveryArtifact.receipt.reference) ||
      recoveryArtifact.payload.recoveredSha256 !== archiveArtifact.payload.sha256 ||
      recoveryArtifact.payload.recoveredSizeBytes !== archiveArtifact.payload.sizeBytes ||
      recoveryArtifact.receipt.recoveredSha256 !== archiveArtifact.receiptReference.sha256 ||
      recoveryArtifact.receipt.recoveredSizeBytes !== archiveArtifact.receiptReference.sizeBytes ||
      receiptArchivedAt < archiveStarted ||
      receiptArchivedAt > archiveCompleted ||
      requiredRetainUntil > maximumSupportedInstant ||
      canonicalInstant(recoveryArtifact.payload.protection.retainUntil) < requiredRetainUntil ||
      canonicalInstant(recoveryArtifact.receipt.protection.retainUntil) < requiredRetainUntil ||
      canonicalInstant(recoveryArtifact.payload.protection.retainUntil) <= recoveryCompleted ||
      canonicalInstant(recoveryArtifact.receipt.protection.retainUntil) <= recoveryCompleted
    ) {
      fail("EVIDENCE_MISMATCH");
    }
  }
}

function verifyEvidenceBytes({
  descriptorBytes,
  archiveReportBytes,
  recoveryReportBytes,
} = {}) {
  const descriptorInput = parseStrict(descriptorBytes, false);
  const archiveInput = parseStrict(archiveReportBytes, true);
  const recoveryInput = parseStrict(recoveryReportBytes, true);
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

export function verifyEvidenceFiles({ workPackagePath, archiveReportPath, recoveryReportPath } = {}) {
  schemaValidators();
  const descriptorBytes = readStableRegularFile(workPackagePath, "EVIDENCE_INPUT_INVALID");
  const archiveReportBytes = readStableRegularFile(archiveReportPath, "EVIDENCE_INPUT_INVALID");
  const recoveryReportBytes = readStableRegularFile(recoveryReportPath, "EVIDENCE_INPUT_INVALID");
  validateCompletionMarker(recoveryReportPath, recoveryReportBytes);
  return verifyEvidenceBytes({ descriptorBytes, archiveReportBytes, recoveryReportBytes });
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

function stableRegularSnapshot(stats) {
  return stats.isFile() && !stats.isSymbolicLink() &&
    typeof stats.dev === "bigint" && stats.dev > 0n &&
    typeof stats.ino === "bigint" && stats.ino > 0n;
}

function sameStableSnapshot(left, right) {
  return left.dev === right.dev && left.ino === right.ino &&
    left.size === right.size && left.mtimeNs === right.mtimeNs && left.ctimeNs === right.ctimeNs;
}

function readStableRegularFile(filePath, errorCode, maxBytes = MAX_INPUT_BYTES, exactBytes = null) {
  if (
    typeof filePath !== "string" ||
    !path.isAbsolute(filePath) ||
    path.normalize(filePath) !== filePath ||
    path.resolve(filePath) !== filePath ||
    !Number.isSafeInteger(maxBytes) ||
    maxBytes < 0 ||
    (exactBytes !== null && (!Number.isSafeInteger(exactBytes) || exactBytes < 0 || exactBytes > maxBytes))
  ) {
    fail(errorCode);
  }
  let descriptor;
  let bytes;
  let failure = null;
  try {
    descriptor = fs.openSync(filePath, fs.constants.O_RDONLY | NONBLOCK_READ_FLAG);
    const opened = fs.fstatSync(descriptor, { bigint: true });
    const pathBefore = fs.lstatSync(filePath, { bigint: true });
    if (
      !stableRegularSnapshot(opened) ||
      !stableRegularSnapshot(pathBefore) ||
      !sameStableSnapshot(opened, pathBefore) ||
      opened.size > BigInt(maxBytes) ||
      (exactBytes !== null && opened.size !== BigInt(exactBytes))
    ) {
      fail(errorCode);
    }
    const expectedSize = Number(opened.size);
    bytes = Buffer.allocUnsafe(expectedSize);
    let offset = 0;
    while (offset < expectedSize) {
      const count = fs.readSync(descriptor, bytes, offset, expectedSize - offset, offset);
      if (!Number.isSafeInteger(count) || count <= 0 || count > expectedSize - offset) fail(errorCode);
      offset += count;
    }
    const sentinel = Buffer.allocUnsafe(1);
    const extra = fs.readSync(descriptor, sentinel, 0, 1, expectedSize);
    if (extra !== 0) fail(errorCode);
    const read = fs.fstatSync(descriptor, { bigint: true });
    const pathAfter = fs.lstatSync(filePath, { bigint: true });
    if (
      !stableRegularSnapshot(read) ||
      !stableRegularSnapshot(pathAfter) ||
      !sameStableSnapshot(opened, read) ||
      !sameStableSnapshot(opened, pathAfter) ||
      read.size !== BigInt(bytes.length)
    ) {
      fail(errorCode);
    }
  } catch (error) {
    failure = error instanceof EvidenceVerificationError ? error : new EvidenceVerificationError(errorCode);
  } finally {
    if (descriptor !== undefined) {
      try {
        fs.closeSync(descriptor);
      } catch {
        if (failure === null) failure = new EvidenceVerificationError(errorCode);
      }
    }
  }
  if (failure !== null) throw failure;
  return bytes;
}

function validateCompletionMarker(recoveryReportPath, recoveryReportBytes) {
  const recoveryReportFileName = path.basename(recoveryReportPath);
  if (!portableReportFileName(recoveryReportFileName)) fail("MARKER_INVALID");
  const markerFileName = `${recoveryReportFileName}.complete.${sha256(recoveryReportBytes)}`;
  if (Buffer.byteLength(markerFileName, "utf8") > 255) fail("MARKER_INVALID");
  const directory = path.dirname(recoveryReportPath);
  const markerPath = path.join(directory, markerFileName);
  if (path.dirname(markerPath) !== directory || path.basename(markerPath) !== markerFileName) fail("MARKER_INVALID");
  readStableRegularFile(markerPath, "MARKER_INVALID", 0, 0);
}

function safeCode(error) {
  const allowed = new Set([
    "USAGE_ERROR",
    "EVIDENCE_INPUT_INVALID",
    "SCHEMA_INITIALIZATION_FAILED",
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
    schemaValidators();
  } catch (error) {
    emitError(safeCode(error));
    return 2;
  }
  try {
    const result = verifyEvidenceFiles({
      workPackagePath: values.get("work-package"),
      archiveReportPath: values.get("archive-report"),
      recoveryReportPath: values.get("recovery-report"),
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
