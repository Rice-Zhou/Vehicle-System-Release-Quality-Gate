# 06 — Traceability Design

## 1. Goal

Preserve the complete V0.1 logical chain and make every relationship verifiable, explainable, and snapshot-capable:

```text
Issue → Commit → Build → Artifact → Release → Test Run → Test Result → Evidence
```

Fixed, Included, and Verified are three independent facts. None may automatically substitute for another.

## 2. Strongly Typed Edges

| Edge | Source | HIGH Proof Example | Verification |
|---|---|---|---|
| Issue→Commit | SCM message/PR link/manual assertion | Structured Issue link from Git provider + existing commit | Issue and repo/commit are readable |
| Commit→Build | CI provenance | Build system records exact source revision | Build metadata signature/API recheck |
| Build→Artifact | CI artifact metadata | Build-job checksum matches Artifact | Download/repository checksum recheck |
| Artifact→Release | Locked Manifest | Artifact checksum appears in Locked Manifest | Manifest digest and association recheck |

Issue→Commit, Commit→Build, and Build→Artifact persist as append-only Edge Revisions. Each logical Edge has a stable `edgeId`. Any change to source proof, verification status, Confidence, verifier program version, or reason creates an incremented `revision` and never overwrites an old Revision. Multiple logical Edges naturally express many-to-many: one Issue with multiple Commits and one Commit with multiple Issues require no special branch.

Artifact→Release has no writable Edge table. It is always derived through `release.lockedManifestId → manifest_artifact`. Artifact reuse across Releases appears as multiple Locked Manifests referencing the same content-addressed Artifact. Traceability Snapshot materializes that derived relationship as an Edge Fact whose Source is MANIFEST, but the materialized Fact cannot modify Manifest.

## 3. Confidence

- `HIGH`: authoritative structured metadata directly proves the relationship and can be rechecked.
- `MEDIUM`: two independent trusted sources agree but end-to-end provenance is absent.
- `LOW`: inferred from naming, version text, or one non-authoritative assertion.
- `UNKNOWN`: insufficient information or unavailable source.

Confidence is orthogonal to `verificationStatus`: even a HIGH source can fail validation; UNKNOWN is neither false nor true. A Quality Rule may require a minimum Confidence but cannot modify the Edge fact.

## 4. Creation and Verification Flow

```text
Ingest facts from adapters/CI/Manifest
→ create candidate typed edges
→ validate both endpoints and source proof
→ assign status + confidence
→ compute gaps
→ freeze Traceability Snapshot for Release
```

Automated inference may create only LOW/UNKNOWN candidates and must preserve the inference method. Manual links use MANUAL_ASSERTION and require actor, reason, and proof reference. A manual assertion does not automatically receive HIGH.

## 5. Fixed / Included / Verified

- **Fixed**: at least one valid Issue→Commit Edge exists, and the Commit meets Fix policy.
- **Included**: a continuous VALID path exists from that Commit through Build and Artifact to the target Release, with every segment meeting policy.
- **Verified**: Included is true, and a PASS Test Result on the target Release is linked to that Issue's verification criteria and required Evidence.

```text
Commit exists ≠ Fixed proven
Fixed ≠ Included
Included ≠ Verified
```

For each Issue, the three states independently output status, reason, path, missing Edges, and Evidence references.

## 6. Missing and Untrusted Relationships

- Missing: create `TraceabilityGap` with expected Edge, affected Issue/Release, detection time, and actionable description.
- INVALID: retain the invalid Edge and validation diagnostics; do not physically delete it.
- LOW/UNKNOWN: show explicitly in reports. A versioned Quality Rule determines whether it blocks.
- External unavailable: current verification is ERROR and must not claim current validity using an old validation time; historical Snapshots remain explainable.
- Data conflict: preserve every source fact and mark CONFLICT. Quality Evaluation rejects an inconsistent required chain by default.

## 7. Snapshot and Replay

Traceability Snapshot materializes each complete Edge Fact used in an evaluation rather than keeping only a reference to a current Edge row. Each Snapshot Edge freezes Edge type, both endpoint identities, source Edge ID/revision, source proof, verification status, Confidence, validator version, reason, Evidence reference, and fact digest. Artifact→Release also freezes Locked Manifest Revision and Manifest digest. Gaps are materialized as complete Facts as well.

The transaction creating a Snapshot calculates its content digest in stable ordinal order and prohibits UPDATE/DELETE after commit. Replay reads only Snapshot Edges/Gaps; it never queries the latest Edge Revision or reparses an external Source. A repaired link or new verification creates only a new Edge Revision, Snapshot, and Evaluation.

Example: `edgeId=E1, revision=1` is VALID/HIGH and enters Snapshot S1. When external proof becomes invalid, insert `E1/revision=2` as INVALID. Replaying S1 still reads the materialized VALID/HIGH Fact and original digest; only S2 can read Revision 2.

## 8. API/Interface

```text
TraceabilityPort
  ingestFacts(batch)
  verifyRelease(releaseId, policyVersion)
  createSnapshot(releaseId, verificationRunId)
  getIssuePath(releaseId, issueId, snapshotId)
  listGaps(releaseId, snapshotId)
```

GET `/releases/{id}/traceability` returns paths, Confidence, and gaps. POST `:verify` runs asynchronously and returns verificationRunId.

## 9. MVP and Deferred Scope

MVP uses strongly typed PostgreSQL association tables and fixed-chain queries. Graph databases, fuzzy matching, intelligent cross-repository inference, and AI attribution are deferred. Re-evaluate only if real query/scale evidence shows that a relational database is inadequate.

## 10. Acceptance

- Cover one-to-many, many-to-many, and Artifact reuse.
- If any required Edge is missing, Included is false and the exact gap is reported.
- Commit existence alone must never display Verified.
- Replaying the same Snapshot produces the same path and Confidence.
- After Edge re-verification, the old Snapshot's Path, verification status, Confidence, and digest remain unchanged.
- Build→Artifact has no second Artifact FK; Artifact→Release can only derive from the Locked Manifest.
- Manual links and conflicts are auditable.

Evidence: known-chain fixtures, negative/conflict tests, Edge Revision Integration Test, real Release traceability report, and old-Snapshot digest replay record.
