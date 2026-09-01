---
acceptanceId: M2-2-MAPPING-AUTHORITY-001
subject: M2.2 Mapping Profile and Adapter Version Authority implementation candidate
subjectCommit: 25f1bc0a08b3170782bff3ab4a3154ff5463cc27
pairedSubjectCommit: 72d85267573d845945070de898c5dc865caa7b98
branch: docs/m2-issue-traceability-design-en
status: PENDING
submittedAt: 2026-09-01T08:24:06Z
owner: PENDING
decisionAt: PENDING
---

# M2.2 Mapping Profile and Adapter Version Authority Acceptance Record

## Scope

**Included**

- The M2.2 implementation candidate fixed by English Subject Commit `25f1bc0a08b3170782bff3ab4a3154ff5463cc27` and paired Chinese Subject Commit `72d85267573d845945070de898c5dc865caa7b98`.
- Versioned, Project/Issue Source-scoped, INSERT-only Mapping Profile Authority, the forward-only V5 Migration, and RFC 8785 JCS/SHA-256 Mapping Versions.
- Authorized transactional profile activation, idempotency, Audit, Outbox, one authoritative Adapter Descriptor version, and pinned Adapter/Mapping Versions on Sync Runs.
- Five fail-closed runtime diagnostics with zero Process Runner calls, Profile A/B activation and sync races, synthetic-only fixtures, protected outputs, and redacted generic HTTP 500 logging.
- Contract, Acceptance, Governance, Pair Gate, and paired GitHub Actions/PostgreSQL Artifact evidence.

**Excluded**

- Any real Jira call, real workflow token persistence, expanded query scope, or Jira write. A later real retest still requires separate authorization and must remain read-only, one project, at most 20 issues, with a controlled profile.
- Company environment, Company Evidence Archive, production deployment, merging `main`/`release`, tags, or releases.
- Changes to the frozen V0.1 Core Contract, release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, or ADR governance.

## Evidence

### Immutable implementation chain

- **Type**: Git commit chain; **Locator**: the eight English Task commits [`1916e4267ecb2189c33bcd80fa25f0267cc16015`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/1916e4267ecb2189c33bcd80fa25f0267cc16015), [`3cf8f8d07c21cbaa02a69d4601adf27e13fd8d2a`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/3cf8f8d07c21cbaa02a69d4601adf27e13fd8d2a), [`c9024d4c6003de4388696cb73ed47195be43c70c`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/c9024d4c6003de4388696cb73ed47195be43c70c), [`43fa70776e80abd6f5446e3d24ade12317e98645`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/43fa70776e80abd6f5446e3d24ade12317e98645), [`418170b463a4258411decc48b8c2d6c6101a1371`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/418170b463a4258411decc48b8c2d6c6101a1371), [`8e81e46c0ade1910a08dd9b0084a6600ef3a4f4f`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/8e81e46c0ade1910a08dd9b0084a6600ef3a4f4f), [`39b8b4491d8cc9a4c761d31143627d2a434b97eb`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/39b8b4491d8cc9a4c761d31143627d2a434b97eb), and [`12e79fcefd2e4b4df319132f7e5b63dba5fc355f`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/12e79fcefd2e4b4df319132f7e5b63dba5fc355f); **Generated At**: final Subject Commit `2026-09-01T08:14:50Z`; **Subject Commit**: `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Digest / Summary**: immutable main implementation chain for the eight sequential Tasks; **Availability**: GitHub commit locators; **Owner Authorization**: `PENDING`.
- **Type**: Git hardening chain; **Locator**: English follow-up commits `3f1d15cd6509f4a92418537fb9065fb432d76cbb`, `085697ede97838e10fa8ed6e7c81aed12deccda3`, `4714896787935f221c2f5eba44cf125736b5e250`, `a55a41bc293fa2658590dd4a36fa992cc578d1b3`, `2ee37d90ebf716937e31e5d78b809a7f31224591`, `3196d600e320a61288db3f4caba1460323041f5c`, `535e4fd95944063d1fdf2fd502d3ffb56e4ac14b`, `95ff78496913e8962010ec96634e066d63503eeb`, `dc258c7c5bd752614d7a413844c57123ef6468ea`, `875dd599752d85afc1e365171f0df6cbc4f139ac`, `83a105bebe8573ec4b6ffa4c88d15ca294aabc24`, `0e828daf65f8db55250d8d24c69b224198ec15db`, `46ee49bffa3e8dbbdfdbb34a4031aa1770adbead`, `1bd01d74b9bdef7b92e2d7fa67abe156359970c4`, `76d8acd422d1ba991a7149eca442c4441c59e768`, and `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Generated At**: `2026-09-01T08:14:50Z`; **Subject Commit**: `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Digest / Summary**: immutable Migration, immutability, validation boundary, runtime, race, and logging security hardening chain; **Availability**: Git history; **Owner Authorization**: `PENDING`.
- **Type**: Paired Git commit chain; **Locator**: Chinese eight Task commits `04266673c52e650ba75a0de6780f3097a28edb8f`, `ec8a98ed601f6c60a93ed6fc166f4764151d3aec`, `375a3f5a7ae9e71864734acce521482be79683ad`, `1bb7dcce675a237c10f4a30beda4759e87494b61`, `bc0658d01f64b3d05d50cf141036ce00454108d0`, `4aa2a7b57924a4ea3111de351da39e1bfd2eed6c`, `2b85d87096ac2b697ede5bb45c3de454ca35c90f`, and `a5b40407f70cdfb548900fcb1a5a740417421c18`; Chinese follow-up commits `8bf60a6abb4833a3343216655f5c9d7da275ba79`, `04677cc215e043797fe45e4d3f29132c400418ed`, `0c2a9d949dcaaae5d24aa165ccd8b2d15971c04b`, `62fac0b61c28cd5b262eb92f3fcb5bc56adfa270`, `a41c3e99f42bf6a10de2190f4805ee012c5d2732`, `2bb9ac7674512ffb3743131c9ec1d400ec6b0bd2`, `40a065953c79d71c33ec630e2c5b6fdcce90d2dd`, `163bfebd58e9a2e077117a8a0f64dced57665081`, `edaa4c9c40c3d99128d62d26b6a07d3a91513216`, `89b01718be6a355c7116700632fa339140405976`, `64223129ba9dc43bd27a450644d76ea756fabda9`, `923e463337b99e50aa662ae3facf52a3b2f70566`, `5a6bcf2afdf8183e2ea10e3a1cbf0917ea2483be`, `2e97ae691de1f36182e24d10c0543ce407dbbc4f`, `5efedac2b84f074ad622ac9e9f9c6819886b52f5`, and `72d85267573d845945070de898c5dc865caa7b98`; **Generated At**: final Paired Subject Commit `2026-09-01T08:10:00Z`; **Subject Commit**: `72d85267573d845945070de898c5dc865caa7b98`; **Digest / Summary**: semantically paired with the English chain; **Availability**: Git history; **Owner Authorization**: `PENDING`.
- **Type**: Paired head evidence cross-check; **Locator**: Chinese Contract head `72d85267573d845945070de898c5dc865caa7b98`, Runtime head `72d85267573d845945070de898c5dc865caa7b98`, and Security head `72d85267573d845945070de898c5dc865caa7b98`; **Generated At**: `2026-09-01T08:10:00Z`; **Subject Commit**: the fixed paired implementation heads; **Digest / Summary**: all three local summaries cross-bind the same paired Chinese head; **Availability**: Git history; **Owner Authorization**: `PENDING`.

### Verification and CI evidence

- **Type**: Contract/Acceptance/Governance/local test summary; **Locator**: `scripts/contract-validator.mjs`, acceptance validators, `scripts/verify-design-governance.ps1`, and Backend test reports; **Generated At**: `2026-09-01T08:24:06Z`; **Subject Commit**: `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Digest / Summary**: Contract `operations=33`, Acceptance `37/37`, records PASS, Governance `tdr=15`, and 11 relevant classes with `89` tests and zero failures/errors/skips; **Availability**: local output and CI Artifact; **Owner Authorization**: `PENDING`.
- **Type**: Database/runtime/security report; **Locator**: `backend/src/main/resources/db/migration/V5__issue_mapping_profile.sql`, `IssueSourceRuntimeRegistryTest`, `IssueMappingProfileActivationIntegrationTest`, and `IssueMappingSecurityTest`; **Generated At**: `2026-09-01T08:24:06Z`; **Subject Commit**: `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Digest / Summary**: V5 Migration PASS; all five `MAPPING_PROFILE_NOT_CONFIGURED`, `MAPPING_PROFILE_INTEGRITY_FAILED`, `MAPPING_SCHEMA_UNSUPPORTED`, `ADAPTER_VERSION_MISMATCH`, and `MAPPING_VERSION_MISMATCH` conditions fail closed before the Process Runner with calls=0; Profile A/B race, sync version pin, rollback/wait tests PASS; sensitive production scan `matches=0`; **Availability**: Git/CI; **Owner Authorization**: `PENDING`.
- **Type**: Pair Gate; **Locator**: `scripts/verify-language-branches.ps1 -Mode Pair`, Chinese `72d85267573d845945070de898c5dc865caa7b98`, English `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Generated At**: started `2026-09-01T08:22:27Z`, completed `2026-09-01T08:23:15Z`; **Subject Commit**: the fixed paired implementation heads; **Digest / Summary**: PASS, with byte-identical non-Markdown files; **Availability**: local Pair Gate output; **Owner Authorization**: `PENDING`.
- **Type**: Chinese GitHub Actions CI Run; **Locator**: [Run `33486146835`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33486146835); **Generated At**: `2026-09-01T08:15:56Z`; **Subject Commit**: `72d85267573d845945070de898c5dc865caa7b98`; **Digest / Summary**: conclusion `success`, including the PostgreSQL/Testcontainers Gate; **Availability**: GitHub Run, retention `UNKNOWN`; **Owner Authorization**: `PENDING`.
- **Type**: Chinese CI Artifact; **Locator**: `m1-evidence-72d85267573d845945070de898c5dc865caa7b98`, [Artifact ID `9791943247`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9791943247); **Generated At**: `2026-09-01T08:19:40Z`; **Subject Commit**: `72d85267573d845945070de898c5dc865caa7b98`; **Digest / Summary**: `106076 bytes` (approximately `104 KB`), `sha256:aa532452022df8fce088e5bbb55ef7add28698025f21f633ce129fadf2cf20f8`; **Availability**: accessible at submission, retention/expires `UNKNOWN`; **Owner Authorization**: `PENDING`.
- **Type**: English GitHub Actions CI Run; **Locator**: [Run `33486146293`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33486146293); **Generated At**: `2026-09-01T08:15:55Z`; **Subject Commit**: `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Digest / Summary**: conclusion `success`, including the PostgreSQL/Testcontainers Gate; **Availability**: GitHub Run, retention `UNKNOWN`; **Owner Authorization**: `PENDING`.
- **Type**: English CI Artifact; **Locator**: `m1-evidence-25f1bc0a08b3170782bff3ab4a3154ff5463cc27`, [Artifact ID `9791978227`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9791978227); **Generated At**: `2026-09-01T08:20:45Z`; **Subject Commit**: `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`; **Digest / Summary**: `106291 bytes` (approximately `104 KB`), `sha256:0daa774df42e3cdcf0e390ab15afb5b2dc41e815ba0161b1a46358856814e1ad`; **Availability**: accessible at submission, retention/expires `UNKNOWN`; **Owner Authorization**: `PENDING`.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Eight Tasks and all hardening commits fixed | `PASS` | Paired immutable Git chains | Subject Commit and Paired Subject Commit fix the final implementation heads |
| Contract and V5 Authority Migration | `PASS` | operations=33 and paired PostgreSQL CI Gates | V0.1 Core Contract remains unchanged; V5 is forward-only |
| Five runtime fail-closed paths and zero Process calls | `PASS` | `IssueSourceRuntimeRegistryTest` | Authority failures cannot start a Jira Process |
| Profile A/B and sync transaction races | `PASS` | activation/sync race, rollback, and wait tests | A Sync Run pins the committed versions and cannot mix a newer profile |
| Fixture and security boundary | `PASS` | synthetic-only gate, aggregate security test, scan matches=0 | Definitions, issue content, URLs, paths, stdout/stderr, and credentials do not enter governance output or logs |
| Locally executable Gate | `PASS` | 89 tests, Acceptance 37/37, Governance tdr=15 | Docker is unavailable locally; the paired CI runs fix the complete database Gate |
| Paired candidate consistency | `PASS` | Pair Gate `2026-09-01T08:22:27Z`–`08:23:15Z` | Non-Markdown files are byte-identical |
| Paired CI runs and Artifacts | `PASS` | Runs `33486146835`/`33486146293` and Artifact digests | Both PostgreSQL Gates succeeded; retention is unknown |
| Controlled real Jira retest | `UNKNOWN` | Scope exclusion | This candidate did not call real Jira and still requires separate Owner authorization |
| Owner decision | `PENDING` | `N/A` | Awaiting Owner review of the fixed candidate and residual risks |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| This candidate did not call real Jira | Fixture, transaction, and CI evidence do not prove the current Jira identity/network/controlled profile | Project Owner / Implementation Owner | Separately authorize a read-only retest for one project and at most 20 issues using a controlled profile; this record cannot replace real evidence |
| Company, deployment, and release scope was not executed | Company Ready or Production Ready cannot be claimed | Project Owner / Operator | Keep Company, merge, tag, release, and deploy blocked until separately accepted |
| Generic HTTP 500 logs retain no stack | This prevents sensitive Throwable leakage, but logs alone cannot identify the exact code line | Implementation Owner | Correlate requestId, fixed code, and exception type; add only a separately designed safe error fingerprint or controlled telemetry if needed |
| Artifact retention/expires is unconfirmed | Online Artifacts may become unavailable | Project Owner / Release Engineer | Keep retention/expires as `UNKNOWN`; review while accessible and use a separately governed Evidence Archive when required |
| Docker is unavailable locally | The complete PostgreSQL/Testcontainers suite cannot run locally | Implementation Owner | The 89 non-container tests pass locally, and both fixed Subject CI PostgreSQL Gates succeeded |

## Decision Reason

`PENDING`

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Await Owner review | Project Owner | When review completes | Owner decides on the fixed paired Subject Commits and residual risks | A new commit updates metadata and Decision Reason and appends Decision History |
| Obtain separate authorization for any real Jira retest | Project Owner | After the Owner decision and before a retest | Explicit one-project, at-most-20, read-only, redacted-output, controlled-profile boundary | Separate Owner instruction and new Smoke Evidence/acceptance record |
| Keep Company, merge, and release blocked | Project Owner / Release Engineer | Until the corresponding separate authorization | No Company enablement, merge, tag, release, or production deploy | Git, deployment, and release audit records |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-01T08:24:06Z | PENDING | PENDING | The fixed paired Mapping Profile and Adapter Version Authority candidate, Pair Gate, paired CI, and security evidence were submitted for Owner review | PENDING |
