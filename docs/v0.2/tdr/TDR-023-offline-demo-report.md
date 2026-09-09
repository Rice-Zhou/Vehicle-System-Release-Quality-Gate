# TDR-023 — Offline Read-Only M1/M2 Demonstration Report

- Date: 2026-09-09; status: Proposed. The current instruction authorizes the design and plan, not report code implementation or substitute Owner acceptance.
- Fixed design baseline: Chinese 79dc3637c5bae9a6f282c7d5fc41327661659ab1; English af835a5040f66f7b6849f69a1679d89593bb7f5d.
- Basis: [post-acceptance gap inventory](../reviews/2026-09-08-demonstrable-product-gap-inventory.md), [M1 acceptance](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md), [M2 acceptance](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md).

## Goal and Choice

Organize existing same-run JSON into a read-only report opened directly in a browser, explaining Release content, both Issues' Fixed/Included/Verified, A/B paths and Gaps, history status and demonstration failures. Present recorded facts only; do not execute a Gate, query services, or recompute the graph or canonical digest.

| Approach | Trade-off |
|---|---|
| Existing Node tooling generates standalone static HTML | Recommended; built-in file APIs and node:test, no new npm dependencies; opening the report requires no Node, database or network |
| Extend the Kotlin demo to generate HTML | Couples offline presentation to backend builds/JVM lifecycle and complicates processing existing ZIP results |
| New online frontend and APIs | Existing data satisfies this read-only need; new services, identities and deployment exceed scope |

Use existing Node environments (local v20.14.0 verified, existing CI configured for Node 24); do not upgrade project dependencies or install runtimes. Rely only on built-ins already available in both, including node:fs/promises, node:path and node:test, and verify locally and in CI. Use inline CSS, semantic headings/tables and native details; no scripts, external fonts, CDN, fetch, embedded raw JSON or executable input.

Do not modify frozen V0.1 semantics, production APIs, database, authentication, DemoReport/M2DemoReport output formats or run-m1.ps1 lifecycle. This is not the final Release Quality Report; M3/M4, real Providers, Company, merge, Tag, release and deployment are out of scope.

## Inputs, Scope and Failure

Add scripts/demo/render-report.mjs as the command entry and scripts/demo/demo-report.mjs as the pure presentation module. Require --run-dir, --output, --scope m1|m2 and --language zh|en; do not select the latest directory or autodetect M2 mode. Reject missing, repeated or unknown arguments. --run-dir identifies one existing run/extracted directory with fixed filenames summary.json, manifest.json and m2-summary.json; m1 mode does not read m2-summary.json and explicitly labels M2 outside presentation scope.

Each input is limited to 1 MiB of UTF-8 JSON with an object root; reject invalid encoding, malformed JSON, nonregular files and symbolic links. This limit applies only to the current two-synthetic-Issue report tool, not business limits. Render only the allowlisted fields below, ignoring unknown extension fields; never dump input objects, raw exceptions or absolute paths.

| Input | Display fields and consistency |
|---|---|
| summary.json (required) | classification=SYNTHETIC_DEMO; status PASS/FAILED; UUID runId; codeCommit as 40 lowercase hexadecimal characters or null on failure; workingTreeDirty as boolean or null on failure; scenarioStatuses, httpStatuses, errorCodes, apiErrorCodes, Release/Manifest identifiers, contentDigest and payloadSha256. Minimal pre-start FAILED records may lack business fields; display unavailable without inventing NOT_RUN or PASS. |
| manifest.json | Display only releaseId, manifestVersion, vehicle/platform/systemVersion/buildId and Artifact artifactId/type/name/version/required/checksum. Do not display project, source or target. This file is registration input; cite summary scenario status for Lock/export. Match summary releaseId and the single sample Artifact checksum/payloadSha256; do not equate a JSON byte digest with a canonical digest. |
| m2-summary.json | classification=SYNTHETIC_DEMO, proofKind=SYNTHETIC_FIXTURE; status, runId, codeCommit, workingTreeDirty; scenario/HTTP statuses, fixed error codes; releaseId/manifestId, syncRunId, issueSnapshotId, verificationRunIds, traceabilitySnapshotIds, contentDigests, issues.A/B and history. Run/code/dirty identifiers must match summary exactly; Release/Manifest IDs present on both sides must match. |

summary=PASS requires all six existing M1 scenario fields, valid identifiers/digests and Manifest. Display scenario statuses separately from summary.status rather than generating another M1 verdict. m2=PASS requires all ten existing scenarios, A/B identifiers and display fields for both Issues. Use existing scenario names; accept only PASS, FAILED, NOT_RUN and RUNNING scenario statuses, integer HTTP statuses 100–599 and existing identifier/digest/error-code formats. Issue flags must be boolean, with Verified=false; paths/Gaps accept only the existing four edgeTypes and five diagnosticCodes. Validate shape and association without reimplementing TraceabilityVerifier or full business scenario assertions.

FAILED reports may lack business fields, Manifest or M2 that were not produced; any supplied fields must still meet type and association checks, displaying fixed errorCodes. In m2 mode with summary=PASS, absent Manifest or m2-summary.json is incomplete-input failure; with summary=FAILED, label missing data not produced without inferring Worker failure. Display valid m2=FAILED and NOT_RUN/RUNNING scenarios unchanged; M1 PASS alone must never make the combined flow appear successful. Ignoring the M2 file in m1 mode does not validate that file.

Read/format/association errors return nonzero without generating HTML. Fixed diagnostic codes are REPORT_ARGUMENT_INVALID, REPORT_INPUT_INVALID, REPORT_INPUT_MISMATCH and REPORT_OUTPUT_FAILED. Valid failed records can render normally; exit 0 means only REPORT_RENDERED, while the page still shows FAILED. This command is not an acceptance or demonstration-run gate. The output must not exist and its parent directory must be prepared by the caller; never overwrite input or old reports. Read, validate and render all inputs before exclusive creation; on write failure clean up only the incomplete output newly created by this invocation, preserving preexisting files.

## Page and Bilingual Delivery

Page order: synthetic demonstration/source identifiers; original M1/M2 results and missing-data notices; Release/Manifest and Artifact table; A/B Issue flags, paths and Gaps; history checks; scenario/HTTP status and fixed error details. Explain that negative-scenario PASS means expected rejection, not Release PASS. history.snapshotABytesStable is a recorded run check, not history reverified by this tool.

Use one HTML text escape function for all dynamic text, escaping &, <, >, double and single quotes. Dynamic content never enters href, style, scripts or event attributes. Use fixed templates and bounded zh/en copy dictionaries; preserve enums, IDs and error codes. Wrap long IDs, allow narrow-screen table scrolling and never communicate status through color alone. A meta CSP blocks scripts, network and objects while allowing inline styles. CSP supplements correct escaping rather than replacing it.

Non-Markdown source and samples are byte-identical on both branches. --language selects output language explicitly, never implicitly from the Git branch. Generate report.zh.html and report.en.html separately with identical technical facts and no frontend build chain. Preserve input runId/codeCommit/workingTreeDirty rather than replacing the source commit with the generator HEAD; show limitations for dirty=true or codeCommit=null.

## Samples, Verification and Delivery

Reuse the three JSON files from one successful IncludeM2 run in approved M2 CI demonstration Artifact 10087409439; its ZIP SHA-256 is 1490d7979dcac9815bdbaadeaed832be1a3c401ff905f5a385e63dfceb1d65b4. Read an existing local copy or download through the acceptance record. Verify the ZIP digest, same-run membership, source codeCommit=8d5354dcf21ae7b506b27f56eae4d044b9beb895 and sanitization boundaries before preserving original bytes under demo/report/sample/. Record source, original member paths and three file digests in that directory's README, without a new archive service or second business-digest authority. If unavailable, report missing source and use explicitly labeled test fixtures for implementation verification rather than inventing CI provenance.

Use the real sample and in-memory variants to test Chinese/English, M1-only, normal M2, minimal FAILED, partial FAILED, missing/malformed input, mixed runId/commit/dirty/Release/Manifest, invalid types, size limits, symlinks, existing output, special-character safety and unchanged source files. Assert that displayed values come from input, not merely that an HTML file or headings exist.

The existing M1 workflow runs node:test and, after existing demo lifecycle checks, generates both report languages for every produced summary directory with explicit m1 or m2 scope. CI selects scope by m2-summary.json presence only within its known sample directories. Generation failure fails that step; original run failure remains visible. Extend existing Artifact upload with report.*.html alongside JSON, retaining 30 days. Do not override demo gate results with generation success, change the M2 workflow or add a pipeline.

Inspect actual browser rendering of both languages for normal/failed reports, long IDs and narrow tables; check no network dependency or scripts and compare original fields. Record rendering evidence. If no browser is available, state visual verification is incomplete rather than substituting text tests. Bilingual contract/acceptance-record validation, Pair Gate, non-Markdown parity and diff review must pass; implementation and final Owner acceptance are recorded separately.

## Rollback and Reassessment

Stop using render-report to roll back; retain original JSON, generated reports and the database. Reassess the TDR for live queries, real business data, online permissions, more Issues, final quality decisions or input format changes; use an ADR for frozen semantics. Adding HTML does not change M1/M2 synthetic scope, Verified=false, Artifact expiry or existing performance/digest coverage limits.

## Implementation and Next Step

The [implementation plan](../../superpowers/plans/2026-09-09-offline-demo-report.md) covers generator, samples, tests, CI and runbook as one independently verifiable work package. Design self-review covers all five gap-inventory criteria; no new report implementation has run or been accepted.

Current result: the minimum design and implementation steps are written; TDR-023 is Proposed. Git status: the design is versioned as bilingual documentation; remote verification determines push status. Next action: implement plan Task 1 and deliver the offline read-only report. Prerequisites: Owner confirmation of this implementation scope; no Company resources or new runtime environment. Acceptance target: same-run samples produce accurate bilingual offline reports with visible failures/missing data, unchanged inputs, and automated plus actual-rendering evidence.
