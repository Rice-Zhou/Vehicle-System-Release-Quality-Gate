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
