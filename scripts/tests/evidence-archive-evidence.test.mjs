import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import canonicalize from "canonicalize";
import { verifyEvidence } from "../evidence-archive/verify-evidence.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);
const schemaPath = path.join(
  repositoryRoot,
  "ops",
  "evidence-archive",
  "schemas",
  "work-package.schema.json",
);
const workPackagePath = path.join(
  repositoryRoot,
  "ops",
  "evidence-archive",
  "v0-2-evidence-archive-001.json",
);
const archiveSchemaPath = path.join(
  repositoryRoot,
  "ops",
  "evidence-archive",
  "schemas",
  "archive-execution.schema.json",
);
const recoverySchemaPath = path.join(
  repositoryRoot,
  "ops",
  "evidence-archive",
  "schemas",
  "recovery-verification.schema.json",
);
const verifierPath = path.join(
  repositoryRoot,
  "scripts",
  "evidence-archive",
  "verify-evidence.mjs",
);
const EXPECTED_WORK_PACKAGE = {
  schemaVersion: 1,
  workPackageId: "V0-2-EVIDENCE-ARCHIVE-001",
  subjectCommit: "e3576582b08c154189eb9e7f2796f39280cdb8a5",
  pairedSubjectCommit: "6ef2cd2fb234737fad78e96cff4172ef8f92fc45",
  pilotManifest: {
    fileName: "pilot-preservation-manifest.json",
    sha256: "7bcb4d9df5ce0e28fe6150e0593c9824ea2533a2f7885f17d61d3ae813aa4a32",
    classification: "LOCAL_PILOT_NOT_IMMUTABLE",
    conditionBClosed: false,
  },
  artifacts: [
    {
      artifactId: "9631253528",
      artifactName: "m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3",
      fileName: "m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3.zip",
      sourceRunId: "33033752846",
      sourceCommit: "892fb23ce75e7f74a05c1b5e304fccace70ee8d3",
      sizeBytes: 55065,
      sha256: "1f087ef27cfabbb2152d06fc002eb0772c2efbbb63964d6b13ec5f0d7a73ed7a",
    },
    {
      artifactId: "9631250285",
      artifactName: "m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b",
      fileName: "m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b.zip",
      sourceRunId: "33033740162",
      sourceCommit: "8687d49c9566030bb0829752dbe5dda45af02f4b",
      sizeBytes: 55099,
      sha256: "e7602924fe67fd6eff75ebfe5d48122240639d883edc58dc164c419893d979ca",
    },
  ],
};
const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
const validateSchema = new Ajv2020({ allErrors: true, strict: true }).compile(schema);
const validWorkPackage = JSON.parse(fs.readFileSync(workPackagePath, "utf8"));

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function canonicalBytes(value) {
  return Buffer.from(canonicalize(value), "utf8");
}

function exactReference(artifact, kind) {
  const key = `evidence/${artifact.artifactId}/${kind}.json`;
  const digest = kind === "payload" ? artifact.sha256 : sha256(Buffer.from(`receipt-${artifact.artifactId}`));
  const sizeBytes = kind === "payload" ? artifact.sizeBytes : 512 + Number(artifact.artifactId.at(-1));
  return {
    provider: "S3_COMPATIBLE",
    locator: `s3://company-evidence/${key}`,
    bucket: "company-evidence",
    key,
    versionId: `${kind}-version-${artifact.artifactId}`,
    sha256: digest,
    sizeBytes,
  };
}

function evidenceFixture() {
  const descriptorBytes = fs.readFileSync(workPackagePath);
  const descriptor = JSON.parse(descriptorBytes.toString("utf8"));
  const descriptorSha256 = sha256(descriptorBytes);
  const archiveIdentity = {
    provider: "S3_COMPATIBLE",
    principalFingerprint: "d".repeat(64),
  };
  const archiveReport = {
    schemaVersion: 1,
    workPackageId: descriptor.workPackageId,
    executionId: "11111111-2222-4333-8444-555555555555",
    descriptorSha256,
    pilotManifestSha256: descriptor.pilotManifest.sha256,
    startedAt: "2026-08-27T10:00:00Z",
    completedAt: "2026-08-27T10:05:00Z",
    policyFingerprint: "c".repeat(64),
    capabilityCheckedAt: "2026-08-27T10:01:00Z",
    runtimeIdentity: archiveIdentity,
    artifacts: descriptor.artifacts.map((artifact) => ({
      artifactId: artifact.artifactId,
      sourceRunId: artifact.sourceRunId,
      sourceCommit: artifact.sourceCommit,
      payload: exactReference(artifact, "payload"),
      receiptReference: exactReference(artifact, "receipt"),
    })),
    accessOwner: "security-archive-team",
    retentionPolicy: "P365D",
    immutabilityControl: "COMPLIANCE",
    status: "PASS",
    errorCode: null,
  };
  const recoveryReport = {
    schemaVersion: 1,
    workPackageId: descriptor.workPackageId,
    executionId: archiveReport.executionId,
    descriptorSha256,
    pilotManifestSha256: descriptor.pilotManifest.sha256,
    startedAt: "2026-08-27T10:10:00Z",
    completedAt: "2026-08-27T10:15:00Z",
    archiveIdentity: structuredClone(archiveIdentity),
    verifierIdentity: {
      provider: "S3_COMPATIBLE",
      principalFingerprint: "e".repeat(64),
    },
    artifacts: archiveReport.artifacts.map((artifact) => ({
      artifactId: artifact.artifactId,
      sourceRunId: artifact.sourceRunId,
      sourceCommit: artifact.sourceCommit,
      payload: {
        reference: structuredClone(artifact.payload),
        recoveredSha256: artifact.payload.sha256,
        recoveredSizeBytes: artifact.payload.sizeBytes,
        protection: {
          actualMode: "COMPLIANCE",
          retainUntil: "2027-08-27T10:15:01Z",
        },
      },
      receipt: {
        reference: structuredClone(artifact.receiptReference),
        recoveredSha256: artifact.receiptReference.sha256,
        recoveredSizeBytes: artifact.receiptReference.sizeBytes,
        protection: {
          actualMode: "COMPLIANCE",
          retainUntil: "2027-08-27T10:15:01Z",
        },
      },
    })),
    status: "PASS",
    errorCode: null,
    cleanupStatus: "PASS",
    cleanupErrorCode: null,
  };
  const archiveReportBytes = canonicalBytes(archiveReport);
  const recoveryReportBytes = canonicalBytes(recoveryReport);
  const recoveryReportFileName = "recovery-report.json";
  const reportDirectory = path.join(path.parse(repositoryRoot).root, "safe-evidence");
  const markerFileName = `${recoveryReportFileName}.complete.${sha256(recoveryReportBytes)}`;
  const completionMarker = {
    fileName: markerFileName,
    directory: reportDirectory,
    recoveryReportDirectory: reportDirectory,
    isFile: true,
    isSymbolicLink: false,
    size: 0,
  };
  return {
    descriptor,
    descriptorBytes,
    archiveReport,
    archiveReportBytes,
    recoveryReport,
    recoveryReportBytes,
    recoveryReportFileName,
    completionMarker,
  };
}

function verifyFixture(fixture = evidenceFixture()) {
  return verifyEvidence({
    descriptorBytes: fixture.descriptorBytes,
    archiveReportBytes: fixture.archiveReportBytes,
    recoveryReportBytes: fixture.recoveryReportBytes,
    recoveryReportFileName: fixture.recoveryReportFileName,
    completionMarker: fixture.completionMarker,
  });
}

function mutateCanonicalReport(fixture, reportName, mutate) {
  const report = structuredClone(fixture[reportName]);
  mutate(report);
  fixture[reportName] = report;
  fixture[`${reportName}Bytes`] = canonicalBytes(report);
  if (reportName === "recoveryReport") {
    fixture.completionMarker.fileName = `${fixture.recoveryReportFileName}.complete.${sha256(fixture.recoveryReportBytes)}`;
  }
  return fixture;
}

function assertRejects(fixture, code) {
  assert.throws(
    () => verifyFixture(fixture),
    (error) => error?.code === code && error.message === code,
  );
}

function validateWorkPackage(candidate) {
  if (!validateSchema(candidate)) {
    return {
      valid: false,
      errors: structuredClone(validateSchema.errors),
    };
  }

  const artifactIds = candidate.artifacts.map(({ artifactId }) => artifactId);
  const duplicateArtifactIds = artifactIds.filter(
    (artifactId, index) => artifactIds.indexOf(artifactId) !== index,
  );
  if (duplicateArtifactIds.length > 0) {
    return {
      valid: false,
      errors: [
        {
          instancePath: "/artifacts",
          keyword: "uniqueArtifactId",
          params: { duplicateArtifactIds },
          message: "artifactId values must be unique",
        },
      ],
    };
  }

  return { valid: true, errors: [] };
}

test("accepts the fixed evidence archive work package", () => {
  const result = validateWorkPackage(validWorkPackage);

  assert.equal(
    result.valid,
    true,
    `schema validation failed:\n${JSON.stringify(result.errors, null, 2)}`,
  );
  assert.deepStrictEqual(validWorkPackage, EXPECTED_WORK_PACKAGE);
});

test("rejects unknown fields at every object boundary", () => {
  const candidates = [
    { ...structuredClone(validWorkPackage), unexpected: true },
    structuredClone(validWorkPackage),
    structuredClone(validWorkPackage),
  ];
  candidates[1].pilotManifest.unexpected = true;
  candidates[2].artifacts[0].unexpected = true;

  for (const candidate of candidates) {
    assert.equal(validateWorkPackage(candidate).valid, false);
  }
});

test("rejects path, root, and credential fields including local absolute paths", () => {
  const forbiddenFields = {
    sourcePath: "C:\\evidence\\artifact.zip",
    sourceRoot: "/var/tmp/evidence",
    credential: "external-only",
    credentialPath: "C:\\credentials\\provider.json",
  };

  for (const [field, value] of Object.entries(forbiddenFields)) {
    const candidate = structuredClone(validWorkPackage);
    candidate[field] = value;
    assert.equal(validateWorkPackage(candidate).valid, false, field);
  }
});

test("rejects SHA-256 values that are not 64 lowercase hexadecimal characters", () => {
  for (const invalidSha256 of [
    "a".repeat(63),
    "A".repeat(64),
    `sha256:${"a".repeat(64)}`,
    `${"a".repeat(63)}g`,
  ]) {
    const manifestCandidate = structuredClone(validWorkPackage);
    manifestCandidate.pilotManifest.sha256 = invalidSha256;
    assert.equal(validateWorkPackage(manifestCandidate).valid, false);

    const artifactCandidate = structuredClone(validWorkPackage);
    artifactCandidate.artifacts[0].sha256 = invalidSha256;
    assert.equal(validateWorkPackage(artifactCandidate).valid, false);
  }
});

test("rejects artifact sizes that are not positive integers", () => {
  for (const invalidSize of [0, -1, 1.5, "55065"]) {
    const candidate = structuredClone(validWorkPackage);
    candidate.artifacts[0].sizeBytes = invalidSize;
    assert.equal(validateWorkPackage(candidate).valid, false);
  }
});

test("rejects duplicate artifact IDs", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.artifacts[1].artifactId = candidate.artifacts[0].artifactId;
  const result = validateWorkPackage(candidate);

  assert.equal(result.valid, false, "duplicate artifactId must be rejected");
  assert.match(JSON.stringify(result.errors), /artifactId values must be unique/);
});

test("requires the Pilot manifest to remain local and non-immutable", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.pilotManifest.classification = "EXTERNAL_VERIFIED";

  assert.equal(validateWorkPackage(candidate).valid, false);
});

test("requires condition B to remain open", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.pilotManifest.conditionBClosed = true;

  assert.equal(validateWorkPackage(candidate).valid, false);
});

test("requires exactly two artifacts", () => {
  const oneArtifact = structuredClone(validWorkPackage);
  oneArtifact.artifacts.pop();
  const threeArtifacts = structuredClone(validWorkPackage);
  threeArtifacts.artifacts.push(structuredClone(threeArtifacts.artifacts[0]));
  threeArtifacts.artifacts[2].artifactId = "9631259999";

  assert.equal(validateWorkPackage(oneArtifact).valid, false);
  assert.equal(validateWorkPackage(threeArtifacts).valid, false);
});

test("rejects unsafe filenames", () => {
  for (const unsafeFileName of [
    "../artifact.zip",
    "nested/artifact.zip",
    "nested\\artifact.zip",
    "artifact..zip",
    "CON.zip",
    "artifact.",
    ".",
  ]) {
    const manifestCandidate = structuredClone(validWorkPackage);
    manifestCandidate.pilotManifest.fileName = unsafeFileName;
    assert.equal(validateWorkPackage(manifestCandidate).valid, false);

    const artifactCandidate = structuredClone(validWorkPackage);
    artifactCandidate.artifacts[0].fileName = unsafeFileName;
    assert.equal(validateWorkPackage(artifactCandidate).valid, false);
  }
});

test("rejects commits that are not 40 lowercase hexadecimal characters", () => {
  for (const invalidCommit of ["a".repeat(39), "A".repeat(40), `${"a".repeat(39)}g`]) {
    for (const field of ["subjectCommit", "pairedSubjectCommit"]) {
      const candidate = structuredClone(validWorkPackage);
      candidate[field] = invalidCommit;
      assert.equal(validateWorkPackage(candidate).valid, false, field);
    }

    const artifactCandidate = structuredClone(validWorkPackage);
    artifactCandidate.artifacts[0].sourceCommit = invalidCommit;
    assert.equal(validateWorkPackage(artifactCandidate).valid, false, "sourceCommit");
  }
});

test("archive and recovery schemas describe runtime PASS and FAIL reports", () => {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  const validateArchive = ajv.compile(JSON.parse(fs.readFileSync(archiveSchemaPath, "utf8")));
  const validateRecovery = ajv.compile(JSON.parse(fs.readFileSync(recoverySchemaPath, "utf8")));
  const fixture = evidenceFixture();

  assert.equal(validateArchive(fixture.archiveReport), true, JSON.stringify(validateArchive.errors));
  assert.equal(validateRecovery(fixture.recoveryReport), true, JSON.stringify(validateRecovery.errors));

  const failedArchive = structuredClone(fixture.archiveReport);
  failedArchive.artifacts = [];
  failedArchive.policyFingerprint = null;
  failedArchive.capabilityCheckedAt = null;
  failedArchive.runtimeIdentity = null;
  failedArchive.accessOwner = null;
  failedArchive.retentionPolicy = null;
  failedArchive.immutabilityControl = null;
  failedArchive.status = "FAIL";
  failedArchive.errorCode = "ARCHIVE_UNAVAILABLE";
  assert.equal(validateArchive(failedArchive), true, JSON.stringify(validateArchive.errors));

  const failedRecovery = structuredClone(fixture.recoveryReport);
  failedRecovery.executionId = null;
  failedRecovery.descriptorSha256 = null;
  failedRecovery.pilotManifestSha256 = null;
  failedRecovery.archiveIdentity = { provider: null, principalFingerprint: null };
  failedRecovery.verifierIdentity = { provider: null, principalFingerprint: null };
  failedRecovery.artifacts = [];
  failedRecovery.status = "FAIL";
  failedRecovery.errorCode = "DOWNLOAD_FAILED";
  assert.equal(validateRecovery(failedRecovery), true, JSON.stringify(validateRecovery.errors));
});

test("report schemas reject unknown and missing fields", () => {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  const validateArchive = ajv.compile(JSON.parse(fs.readFileSync(archiveSchemaPath, "utf8")));
  const validateRecovery = ajv.compile(JSON.parse(fs.readFileSync(recoverySchemaPath, "utf8")));
  const fixture = evidenceFixture();
  const archiveUnknown = structuredClone(fixture.archiveReport);
  archiveUnknown.artifacts[0].payload.unknown = true;
  const archiveMissing = structuredClone(fixture.archiveReport);
  delete archiveMissing.completedAt;
  const recoveryUnknown = structuredClone(fixture.recoveryReport);
  recoveryUnknown.artifacts[0].payload.protection.unknown = true;
  const recoveryMissing = structuredClone(fixture.recoveryReport);
  delete recoveryMissing.cleanupStatus;

  assert.equal(validateArchive(archiveUnknown), false);
  assert.equal(validateArchive(archiveMissing), false);
  assert.equal(validateRecovery(recoveryUnknown), false);
  assert.equal(validateRecovery(recoveryMissing), false);
});

test("accepts canonical raw evidence with a required completion marker", () => {
  assert.deepEqual(verifyFixture(), {
    workPackageId: "V0-2-EVIDENCE-ARCHIVE-001",
    result: "PASS",
    artifactCount: 2,
  });
});

test("rejects duplicate keys, trailing data, and non-canonical report bytes", () => {
  const duplicate = evidenceFixture();
  duplicate.archiveReportBytes = Buffer.from(
    duplicate.archiveReportBytes.toString("utf8").replace(
      /^\{/,
      '{"schemaVersion":1,',
    ),
  );
  assertRejects(duplicate, "MALFORMED_JSON");

  const trailingRoot = evidenceFixture();
  trailingRoot.recoveryReportBytes = Buffer.concat([trailingRoot.recoveryReportBytes, Buffer.from("{}")]);
  assertRejects(trailingRoot, "MALFORMED_JSON");

  const trailingGarbage = evidenceFixture();
  trailingGarbage.descriptorBytes = Buffer.concat([trailingGarbage.descriptorBytes, Buffer.from("garbage")]);
  assertRejects(trailingGarbage, "MALFORMED_JSON");

  for (const suffix of ["\n", " "]) {
    const nonCanonical = evidenceFixture();
    nonCanonical.archiveReportBytes = Buffer.concat([nonCanonical.archiveReportBytes, Buffer.from(suffix)]);
    assertRejects(nonCanonical, "NON_CANONICAL_JSON");
  }
});

test("completion marker is mandatory and bound to exact recovery bytes and filename", () => {
  const missing = evidenceFixture();
  missing.completionMarker = null;
  assertRejects(missing, "MARKER_INVALID");

  const wrongDigest = evidenceFixture();
  wrongDigest.completionMarker.fileName = `${wrongDigest.recoveryReportFileName}.complete.${"f".repeat(64)}`;
  assertRejects(wrongDigest, "MARKER_INVALID");

  const symlink = evidenceFixture();
  symlink.completionMarker.isSymbolicLink = true;
  assertRejects(symlink, "MARKER_INVALID");

  const nonzero = evidenceFixture();
  nonzero.completionMarker.size = 1;
  assertRejects(nonzero, "MARKER_INVALID");

  const wrongFileName = evidenceFixture();
  wrongFileName.recoveryReportFileName = "other-report.json";
  assertRejects(wrongFileName, "MARKER_INVALID");

  const wrongDirectory = evidenceFixture();
  wrongDirectory.completionMarker.directory = path.join(path.parse(repositoryRoot).root, "other");
  assertRejects(wrongDirectory, "MARKER_INVALID");
});

test("rejects descriptor and report identity or digest mismatches", () => {
  const mutations = [
    ["archiveReport", (report) => { report.workPackageId = "V0-2-EVIDENCE-ARCHIVE-999"; }, "SCHEMA_INVALID"],
    ["recoveryReport", (report) => { report.workPackageId = "V0-2-EVIDENCE-ARCHIVE-999"; }, "SCHEMA_INVALID"],
    ["archiveReport", (report) => { report.descriptorSha256 = "a".repeat(64); }, "EVIDENCE_MISMATCH"],
    ["recoveryReport", (report) => { report.descriptorSha256 = "a".repeat(64); }, "EVIDENCE_MISMATCH"],
    ["archiveReport", (report) => { report.pilotManifestSha256 = "a".repeat(64); }, "EVIDENCE_MISMATCH"],
    ["recoveryReport", (report) => { report.executionId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"; }, "EVIDENCE_MISMATCH"],
  ];
  for (const [reportName, mutate, code] of mutations) {
    assertRejects(mutateCanonicalReport(evidenceFixture(), reportName, mutate), code);
  }
});

test("rejects missing and duplicate artifact evidence", () => {
  const fewerArchive = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => report.artifacts.pop());
  assertRejects(fewerArchive, "SCHEMA_INVALID");

  const duplicateDescriptor = evidenceFixture();
  duplicateDescriptor.descriptor.artifacts[1].artifactId = duplicateDescriptor.descriptor.artifacts[0].artifactId;
  duplicateDescriptor.descriptorBytes = Buffer.from(JSON.stringify(duplicateDescriptor.descriptor));
  assertRejects(duplicateDescriptor, "EVIDENCE_MISMATCH");

  const duplicateRecovery = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.artifacts[1].artifactId = report.artifacts[0].artifactId;
  });
  assertRejects(duplicateRecovery, "EVIDENCE_MISMATCH");
});

test("mutation proof rejects schema-valid source facts that disagree across documents", () => {
  const fixture = evidenceFixture();
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  const validateArchive = ajv.compile(JSON.parse(fs.readFileSync(archiveSchemaPath, "utf8")));
  fixture.archiveReport.artifacts[0].sourceRunId = "99999999999";
  fixture.archiveReportBytes = canonicalBytes(fixture.archiveReport);

  assert.equal(validateArchive(fixture.archiveReport), true, JSON.stringify(validateArchive.errors));
  assertRejects(fixture, "EVIDENCE_MISMATCH");
});

test("rejects source, exact reference, and recovered fact mismatches", () => {
  const archiveMutations = [
    (report) => { report.artifacts[0].sourceCommit = "a".repeat(40); },
    (report) => { report.artifacts[0].payload.sha256 = "a".repeat(64); },
    (report) => { report.artifacts[0].payload.sizeBytes += 1; },
    (report) => { report.artifacts[0].payload.versionId = "different-version"; },
    (report) => { report.artifacts[0].payload.bucket = "other-bucket"; },
    (report) => { report.artifacts[0].payload.key = "evidence/other/payload.json"; },
  ];
  for (const mutate of archiveMutations) {
    assertRejects(mutateCanonicalReport(evidenceFixture(), "archiveReport", mutate), "EVIDENCE_MISMATCH");
  }

  const recoveryMutations = [
    (report) => { report.artifacts[0].payload.reference.versionId = "different-version"; },
    (report) => { report.artifacts[0].receipt.reference.sha256 = "a".repeat(64); },
    (report) => { report.artifacts[0].payload.recoveredSha256 = "a".repeat(64); },
    (report) => { report.artifacts[0].receipt.recoveredSizeBytes += 1; },
  ];
  for (const mutate of recoveryMutations) {
    assertRejects(mutateCanonicalReport(evidenceFixture(), "recoveryReport", mutate), "EVIDENCE_MISMATCH");
  }
});

test("rejects latest and literal-null exact versions", () => {
  for (const versionId of ["latest", "LATEST", "null", "NULL", ""]) {
    const fixture = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => {
      report.artifacts[0].payload.versionId = versionId;
    });
    assertRejects(fixture, "SCHEMA_INVALID");
  }
});

test("requires complete matching archive identity and a fully different verifier identity", () => {
  const same = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.verifierIdentity = structuredClone(report.archiveIdentity);
  });
  assertRejects(same, "IDENTITY_NOT_INDEPENDENT");

  const archiveFingerprintMismatch = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.archiveIdentity.principalFingerprint = "a".repeat(64);
  });
  assertRejects(archiveFingerprintMismatch, "EVIDENCE_MISMATCH");

  const providerVariation = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.verifierIdentity.provider = "FILESYSTEM_STAGING";
  });
  assertRejects(providerVariation, "SCHEMA_INVALID");

  const nullArchive = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.archiveIdentity = { provider: null, principalFingerprint: null };
  });
  assertRejects(nullArchive, "SCHEMA_INVALID");
});

test("offline acceptance rejects FAIL, UNKNOWN, cleanup failure, and non-null errors", () => {
  const archiveFail = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => {
    report.status = "FAIL";
    report.errorCode = "ARCHIVE_UNAVAILABLE";
  });
  assertRejects(archiveFail, "STATUS_NOT_PASS");

  const recoveryFail = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.status = "FAIL";
    report.errorCode = "DOWNLOAD_FAILED";
  });
  assertRejects(recoveryFail, "STATUS_NOT_PASS");

  const unknown = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => {
    report.status = "UNKNOWN";
    report.errorCode = "ARCHIVE_UNAVAILABLE";
  });
  assertRejects(unknown, "SCHEMA_INVALID");

  const cleanupFail = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.status = "FAIL";
    report.errorCode = "RECOVERY_CLEANUP_FAILED";
    report.cleanupStatus = "FAIL";
    report.cleanupErrorCode = "RECOVERY_CLEANUP_FAILED";
  });
  assertRejects(cleanupFail, "STATUS_NOT_PASS");
});

test("rejects unsafe S3 locators and non-normalized keys", () => {
  const mutations = [
    (reference) => { reference.locator += "?X-Amz-Signature=abc"; },
    (reference) => { reference.locator = reference.locator.replace("s3://", "s3://user@") },
    (reference) => { reference.locator += "#fragment"; },
    (reference) => { reference.key = `/leading/${reference.key}`; reference.locator = `s3://${reference.bucket}/${reference.key}`; },
    (reference) => { reference.key = "evidence/../payload.json"; reference.locator = `s3://${reference.bucket}/${reference.key}`; },
    (reference) => { reference.key = "evidence\\payload.json"; reference.locator = `s3://${reference.bucket}/${reference.key}`; },
  ];
  for (const mutate of mutations) {
    const fixture = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => mutate(report.artifacts[0].payload));
    assert.throws(() => verifyFixture(fixture));
  }

  const relativeKey = evidenceFixture();
  assert.match(relativeKey.archiveReport.artifacts[0].payload.key, /^evidence\//);
  assert.deepEqual(verifyFixture(relativeKey).result, "PASS");
});

test("scans all string values for temporary URLs, local paths, secrets, and raw principals", () => {
  const forbiddenValues = [
    "https://storage.example/temp",
    "C:\\private\\evidence.json",
    "\\\\server\\share\\evidence.json",
    "/var/tmp/evidence.json",
    "provider-secret-value",
    "session-token-value",
    "-----BEGIN PRIVATE KEY-----",
    "arn:aws:iam::123456789012:role/archive",
  ];
  for (const forbidden of forbiddenValues) {
    const fixture = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => {
      report.artifacts[0].payload.versionId = forbidden;
    });
    assertRejects(fixture, "FORBIDDEN_VALUE");
  }
});

test("enforces chronology and current retention", () => {
  const reversedArchive = mutateCanonicalReport(evidenceFixture(), "archiveReport", (report) => {
    report.completedAt = "2026-08-27T09:59:59Z";
  });
  assertRejects(reversedArchive, "EVIDENCE_MISMATCH");

  const overlappingInvocations = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.startedAt = "2026-08-27T10:04:59Z";
  });
  assertRejects(overlappingInvocations, "EVIDENCE_MISMATCH");

  const expiredProtection = mutateCanonicalReport(evidenceFixture(), "recoveryReport", (report) => {
    report.artifacts[0].payload.protection.retainUntil = report.completedAt;
  });
  assertRejects(expiredProtection, "EVIDENCE_MISMATCH");
});

test("CLI requires exactly the three path arguments and emits safe JSON", (t) => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "vsrqg-evidence-cli-"));
  t.after(() => fs.rmSync(temporaryDirectory, { recursive: true, force: true }));
  const fixture = evidenceFixture();
  const descriptor = path.join(temporaryDirectory, "work-package.json");
  const archive = path.join(temporaryDirectory, "archive.json");
  const recovery = path.join(temporaryDirectory, "recovery.json");
  fs.writeFileSync(descriptor, fixture.descriptorBytes);
  fs.writeFileSync(archive, fixture.archiveReportBytes);
  fs.writeFileSync(recovery, fixture.recoveryReportBytes);
  const marker = `${recovery}.complete.${sha256(fixture.recoveryReportBytes)}`;
  fs.writeFileSync(marker, Buffer.alloc(0));

  const success = spawnSync(process.execPath, [
    verifierPath,
    "--work-package", descriptor,
    "--archive-report", archive,
    "--recovery-report", recovery,
  ], { cwd: repositoryRoot, encoding: "utf8" });
  assert.equal(success.status, 0, success.stderr);
  assert.equal(success.stdout, '{"artifactCount":2,"result":"PASS","workPackageId":"V0-2-EVIDENCE-ARCHIVE-001"}\n');
  assert.equal(success.stderr, "");

  for (const args of [
    [],
    ["--work-package", descriptor, "--archive-report", archive],
    ["--work-package", descriptor, "--work-package", descriptor, "--archive-report", archive, "--recovery-report", recovery],
    ["--work-package", descriptor, "--archive-report", archive, "--recovery-report", recovery, "--unknown", "value"],
  ]) {
    const failure = spawnSync(process.execPath, [verifierPath, ...args], {
      cwd: repositoryRoot,
      encoding: "utf8",
    });
    assert.equal(failure.status, 2);
    assert.equal(failure.stdout, "");
    assert.equal(failure.stderr, '{"code":"USAGE_ERROR"}\n');
    assert.doesNotMatch(failure.stderr, /vsrqg-evidence-cli|work-package\.json|archive\.json|recovery\.json/);
  }
});

test("CLI derives and validates the completion marker without exposing input paths", (t) => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "vsrqg-evidence-marker-"));
  t.after(() => fs.rmSync(temporaryDirectory, { recursive: true, force: true }));
  const fixture = evidenceFixture();
  const descriptor = path.join(temporaryDirectory, "work-package.json");
  const archive = path.join(temporaryDirectory, "archive.json");
  const recovery = path.join(temporaryDirectory, "recovery.json");
  fs.writeFileSync(descriptor, fixture.descriptorBytes);
  fs.writeFileSync(archive, fixture.archiveReportBytes);
  fs.writeFileSync(recovery, fixture.recoveryReportBytes);

  const run = () => spawnSync(process.execPath, [
    verifierPath,
    `--work-package=${descriptor}`,
    `--archive-report=${archive}`,
    `--recovery-report=${recovery}`,
  ], { cwd: repositoryRoot, encoding: "utf8" });

  const missing = run();
  assert.equal(missing.status, 1);
  assert.equal(missing.stdout, "");
  assert.equal(missing.stderr, '{"code":"MARKER_INVALID"}\n');
  assert.doesNotMatch(missing.stderr, new RegExp(temporaryDirectory.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));

  const marker = `${recovery}.complete.${sha256(fixture.recoveryReportBytes)}`;
  fs.writeFileSync(marker, "not-empty");
  assert.equal(run().status, 1);
});
