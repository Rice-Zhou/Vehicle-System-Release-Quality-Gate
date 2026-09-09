# Offline Demonstration Report Implementation Verification

## Scope and Provenance

Implement Task 1 under [TDR-023](../v0.2/tdr/TDR-023-offline-demo-report.md), adding only offline presentation tooling, tests, samples and existing M1 CI integration. The Owner's next-step instruction authorized implementation of design 8b1dbc3 / 78bf739, not final acceptance.

See the [sample record](../../demo/report/sample/README.md) for provenance, original members and digests. Three JSON files were copied unchanged from the verified CI ZIP; six M1 and ten M2 scenarios are PASS, with runId/commit/dirty/Release/Manifest and file checksum associations checked. Sample source commit 8d5354d is not the report generator implementation commit.

## Local Verification

- RED: tests first failed with ERR_MODULE_NOT_FOUND while the core module was absent, exit 1. GREEN: Node v20.14.0 running node --test scripts/tests/demo-report.test.mjs initially produced 21/21 PASS. Three independently reviewed boundary defects led to regression additions; the first fix reached 24/24; after the final null-type fix, Node v20.14.0 and v24.19.0 both produced 25 tests, 25 PASS, 0 failed/skipped.
- Both new modules passed syntax checks; workflow YAML and the added embedded PowerShell passed syntax checks.
- Actual headless Edge 152.0.4191.66 opened normal, minimal FAILED, partial FAILED/HTTP-only and special-text inputs in zh/en using offline contexts: 8/8 PASS, checking source statuses, identifiers, Gaps, missing fields and escaping; zero HTTP requests, page errors and script/img elements, with special text never executed.
- Inspected 1280px desktop and 390px narrow layouts; narrow document width was 390px with tables scrolling inside containers. Initial wrapped headers were corrected through Issue/Artifact column widths, desktop width and Chinese copy, then retested.
- Local HTML, screenshots and machine-check results are under backend/build/report-verification/1788935729190/. Check scripts, implementation report and later reviews remain in this plan's independent SDD workspace. Screenshots are local verification materials, not claimed as CI uploads.

## Independent Review and Fixes

Initial task review found three defects: requiring complete partial FAILED Issue/history objects, omitting HTTP-only fields, and an unbounded read after a size check. Added regressions first reproduced the two presentation defects, then preserved available partial fields, rendered the union of scenario/HTTP keys and used single-handle metadata checks with at most 1 MiB + 1 bytes read, rejecting excess. The 25 tests and eight browser checks above cover the final repaired code; scoped re-review closed all three findings with Spec Compliance / Task Quality PASS; the final delivery evidence below records final review and CI.

Final review additionally found explicit path/gaps=null accepted as absence. Array validation now follows source-key presence, rejecting explicit null in PASS/FAILED while preserving unavailable markers for omitted FAILED fields. A new regression first reproduced the failure, then passed; the final scoped re-review is Approved; the P2 finding is closed with no direct regression found.

## Delivery and Limitations

Local checks are distinct from exact-implementation-commit CI. Verified delivery evidence is recorded below; no Owner approval is inferred.

The browser reads derived reports without rerunning database/HTTP/history verification. All data is synthetic, Verified=false; REPORT_RENDERED is not Release or Owner PASS. Existing M2.5 performance-reference gaps, canonical coverage limits, Windows ACL skips and Worker FAILED/timeout propagation without separate fault injection remain. Original CI Artifacts have finite retention; three sample JSON files are preserved in Git.

## Exact-commit Delivery Evidence

Implementation Subjects: Chinese c5400fd33e6fa502f141f6ce25bf950a9b350fb6; English 1fc37d4f795f815a7643c3791d7fc4878d6f1681. The final scoped review is Approved with no remaining actionable findings. This is engineering review only. Implementation Pair Gate passed and both branches were atomically pushed.

All four runs were created at 2026-09-09T06:40:04Z. GitHub API checks confirmed exact head_sha and completed/success: [Chinese M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107876), [Chinese M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107724), [English M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107781), [English M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107709). Both M1 jobs passed the added report tests and rendering step.

Both downloaded demonstration ZIPs contain four same-run JSON groups and eight HTML files each (zh/en): three source M1 PASS runs and one expected bad-password FAILED run, with M2 included twice. M2 scenario statuses are 10/10 PASS; commits and workingTreeDirty=false match the Subjects. For all 16 HTML files, offline regeneration from the packaged same-directory JSON matched the complete HTML text exactly; source IDs/statuses/snapshot IDs/Gap codes were additionally checked. No script, event-handler attribute or remote resource reference was found. FAILED remains FAILED, independent of rendering success.

| Branch | Artifact ID | Created UTC | Expires UTC | ZIP SHA-256 |
|---|---|---|---|---|
| zh | 10091796746 | 2026-09-09T06:50:06Z | 2026-10-09T06:50:05Z | 4e7852199fcda03ff8cda11b03362314e6eb34833c22a00864c8f55363758241 |
| en | 10091809014 | 2026-09-09T06:50:32Z | 2026-10-09T06:50:31Z | 3808ca4dc9368b9f7699beb615518cd9e9e6145ae01a9d6c0b28c9d3c65aafa4 |

These Artifacts belong to the linked M1 runs; locators and ZIP hashes identify the downloaded bytes. The record does not claim permanent retention. Original sample JSON is preserved separately in Git. See the [Owner review record](../governance/acceptance/records/2026-09-09-tdr-023-demo-report-review-001.md), status APPROVE following the explicit Owner decision; documentation commits are separate from implementation Subjects.

## Next Execution Plan

Current result: the Owner approved TDR-023-DEMO-REPORT-REVIEW-001 for Subjects c5400fd / 1fc37d4; the decision and original reply are recorded separately. Git status: bilingual approval records are versioned; remote checks determine push status. Next action: compare the accepted M1/M2 demonstration with the existing MVP plan and propose one next work package. Prerequisites: authorization to execute the next-step review; this approval does not start another milestone. Acceptance target: distinguish closed demonstration gaps from remaining product capabilities, with one bounded proposal and no new environment prerequisites.
