# TDR-020 — Preserve Current Project Evidence in Git

- Date: 2026-09-08.
- Status: Recorded; implements the Owner's explicit GitHub preservation direction, limited to these project materials.
- Authority: [current stage decision](../reviews/2026-09-08-demonstrable-product-priority.md).

## Choice and Rationale

Use the existing Git repository for the two original M2.5 Evidence ZIPs, retaining filenames, source commits, sizes, and SHA-256 values in the existing preservation manifest. Together the ZIPs contain 3509 bytes; no new service, Git LFS, automated upload workflow, or database changes are needed. Actions links alone cannot supply original content after Artifact expiry; local copies alone cannot be retrieved from GitHub. Company object storage is outside current stage needs.

## Preservation and Retrieval

ZIPs reside in [repository-evidence](../../../ops/evidence-archive/m2-5-preparation/README.md), committed unchanged under original filenames to both language branches. To retrieve them, pin a commit containing the files, check them out at that commit, and verify sizes, ZIP SHA-256, member digests, and summary sidecars against the manifest in the same commit. Distinguish original implementation Subjects from later preservation commits; do not repackage or substitute latest CI evidence.

The existing manifest remains the single input digest record; its historical classification and Company completion fields remain unchanged. Ordinary Git preservation does not claim administrator-resistant deletion protection or compliance with historical Company immutable archive conditions.

## Scope, Verification, and Recovery

This decision concerns project acceptance materials only. It does not replace PostgreSQL, runtime large-Evidence storage, or frozen historical Snapshot semantics, and does not change existing implementation contracts in TDR-004, TDR-012, or TDR-019. Company tools remain available without requiring enablement. No deployment or database migration is introduced.

Verify both source and Git ZIPs, every member, sidecars, and bilingual byte parity, and run existing language gates and acceptance-record validation. Correct any content error through a normal subsequent commit with explanation while retaining history; do not force push. Reassess concrete storage only if material volume, sensitivity, or business retention requirements change; this decision does not permit committing real company data to a public repository.
