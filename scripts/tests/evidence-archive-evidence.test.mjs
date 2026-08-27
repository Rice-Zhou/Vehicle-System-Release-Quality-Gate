import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

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
const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
const validateSchema = new Ajv2020({ allErrors: true, strict: true }).compile(schema);
const validWorkPackage = JSON.parse(fs.readFileSync(workPackagePath, "utf8"));

function validateWorkPackage(candidate) {
  if (!validateSchema(candidate)) {
    return false;
  }

  const artifactIds = candidate.artifacts.map(({ artifactId }) => artifactId);
  return new Set(artifactIds).size === artifactIds.length;
}

test("accepts the fixed evidence archive work package", () => {
  assert.equal(validateWorkPackage(validWorkPackage), true);
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
    assert.equal(validateWorkPackage(candidate), false);
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
    assert.equal(validateWorkPackage(candidate), false, field);
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
    assert.equal(validateWorkPackage(manifestCandidate), false);

    const artifactCandidate = structuredClone(validWorkPackage);
    artifactCandidate.artifacts[0].sha256 = invalidSha256;
    assert.equal(validateWorkPackage(artifactCandidate), false);
  }
});

test("rejects artifact sizes that are not positive integers", () => {
  for (const invalidSize of [0, -1, 1.5, "55065"]) {
    const candidate = structuredClone(validWorkPackage);
    candidate.artifacts[0].sizeBytes = invalidSize;
    assert.equal(validateWorkPackage(candidate), false);
  }
});

test("rejects duplicate artifact IDs", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.artifacts[1].artifactId = candidate.artifacts[0].artifactId;

  assert.equal(validateWorkPackage(candidate), false);
});

test("requires the Pilot manifest to remain local and non-immutable", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.pilotManifest.classification = "EXTERNAL_VERIFIED";

  assert.equal(validateWorkPackage(candidate), false);
});

test("requires condition B to remain open", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.pilotManifest.conditionBClosed = true;

  assert.equal(validateWorkPackage(candidate), false);
});

test("requires exactly two artifacts", () => {
  const oneArtifact = structuredClone(validWorkPackage);
  oneArtifact.artifacts.pop();
  const threeArtifacts = structuredClone(validWorkPackage);
  threeArtifacts.artifacts.push(structuredClone(threeArtifacts.artifacts[0]));
  threeArtifacts.artifacts[2].artifactId = "9631259999";

  assert.equal(validateWorkPackage(oneArtifact), false);
  assert.equal(validateWorkPackage(threeArtifacts), false);
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
    assert.equal(validateWorkPackage(manifestCandidate), false);

    const artifactCandidate = structuredClone(validWorkPackage);
    artifactCandidate.artifacts[0].fileName = unsafeFileName;
    assert.equal(validateWorkPackage(artifactCandidate), false);
  }
});

test("rejects commits that are not 40 lowercase hexadecimal characters", () => {
  for (const invalidCommit of ["a".repeat(39), "A".repeat(40), `${"a".repeat(39)}g`]) {
    for (const field of ["subjectCommit", "pairedSubjectCommit"]) {
      const candidate = structuredClone(validWorkPackage);
      candidate[field] = invalidCommit;
      assert.equal(validateWorkPackage(candidate), false, field);
    }

    const artifactCandidate = structuredClone(validWorkPackage);
    artifactCandidate.artifacts[0].sourceCommit = invalidCommit;
    assert.equal(validateWorkPackage(artifactCandidate), false, "sourceCommit");
  }
});
