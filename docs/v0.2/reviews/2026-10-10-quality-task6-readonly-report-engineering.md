# Task 6 Fixed Evaluation Export and Read-Only Report Engineering Record

- Date: 2026-10-10; scope: a subsequent Task 6 engineering segment, not milestone or Owner acceptance.
- Baseline: the [export contract](2026-10-10-quality-task6-export-contract.md), [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), and proposed TDR-026/TDR-023 additions.

## Implementation in this segment

`buildQualityReportExport` traverses all history pages for the same Release and selects only the specified `evaluationId`. Missing or duplicate IDs, mixed Releases, cursor loops, and source binding conflicts fail. A pre-pin `ERROR` retains `NOT_EVALUATED` and the original error and does not query absent sources. Exact Traceability/Test responses can be supplied after pinning and are checked by the earlier schema and binding validator. When unavailable, the export leaves the gap visible and does not claim navigation was verified. Provenance defaults to `UNKNOWN`; ordinary callers cannot assert synthetic proof.

`render-report.mjs --scope quality` reads only `quality-report-export.json`. Its bilingual static HTML shows the recorded decision or error, pinned IDs/versions/digests, rule outcomes, uncovered facts, and Evidence locators. Dynamic text is escaped and CSP forbids scripts and network access. It neither downloads Evidence nor evaluates rules nor selects the latest result. Existing `m1|m2` inputs and limits remain; the quality validator loads only for the explicit scope. Output still uses exclusive creation.

The existing M1 isolated integration test writes raw HTTP `COMPLETED/BLOCK` and pre-pin `ERROR` query responses to a synthetic fixture tied to the commit ID. The existing M1 workflow then checks fixture identity, generates both language reports, and uploads them to its existing 30-day Artifact. No service or real Provider is added. The test uses isolated Traceability/Test ports; this Artifact does not yet include the two formal GET responses, so the page must show that they were not queried. This segment also does not prove PASS/WARNING, real-device Evidence, or rule publication.

## Verification and limits

Local `node scripts/contract-validator.mjs`, `node --test scripts/tests/demo-report.test.mjs`, `node scripts/acceptance-record-validator.mjs`, workflow YAML parsing, and `backend` `compileTestKotlin` pass. This Windows host cannot create symlinks; Linux CI executes that rejection case. Docker is unavailable locally; the persisted HTTP fixture and Artifact status rely on the exact-commit CI below. The TDR additions await formal review, and A1–A8 and Owner acceptance remain open.

## Exact-commit CI review

Chinese implementation `44d75a31826b4c880b42f2fd36bb54eac39493d0`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38021326269), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38021326310), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38021326238) succeeded. English final commit `7f7719bc20613202b4bad3b827e3f6a01f07b057`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38021331294), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38021331313), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38021331310) succeeded. Both M1 runs completed the fixture-rendering and existing demo Artifact upload steps successfully. Artifact IDs are `11657928580` and `11658073790`; the API reports that neither has expired. ZIP members have not been read independently, so Artifact metadata alone does not establish the content of every file.

The local browser security policy rejected opening a local `file://` page; the restriction was not bypassed. Browser visual review remains open in a permitted environment. This CI is engineering evidence, not proof of formal Traceability/Test GET navigation, real-device data, four actual decisions, or Owner acceptance.
