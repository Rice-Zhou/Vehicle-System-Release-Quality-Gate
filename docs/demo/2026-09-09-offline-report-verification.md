# Offline Demonstration Report Implementation Verification

## Scope and Provenance

Implement Task 1 under [TDR-023](../v0.2/tdr/TDR-023-offline-demo-report.md), adding only offline presentation tooling, tests, samples and existing M1 CI integration. The Owner's next-step instruction authorized implementation of design 8b1dbc3 / 78bf739, not final acceptance.

See the [sample record](../../demo/report/sample/README.md) for provenance, original members and digests. Three JSON files were copied unchanged from the verified CI ZIP; six M1 and ten M2 scenarios are PASS, with runId/commit/dirty/Release/Manifest and file checksum associations checked. Sample source commit 8d5354d is not the report generator implementation commit.

## Local Verification

- RED: tests first failed with ERR_MODULE_NOT_FOUND while the core module was absent, exit 1. GREEN: Node v20.14.0 running node --test scripts/tests/demo-report.test.mjs initially produced 21/21 PASS. Three independently reviewed boundary defects led to regression additions; final Node v20.14.0 and v24.19.0 runs both produced 24 tests, 24 PASS, 0 failed/skipped.
- Both new modules passed syntax checks; workflow YAML and the added embedded PowerShell passed syntax checks.
- Actual headless Edge 152.0.4191.66 opened normal, minimal FAILED, partial FAILED/HTTP-only and special-text inputs in zh/en using offline contexts: 8/8 PASS, checking source statuses, identifiers, Gaps, missing fields and escaping; zero HTTP requests, page errors and script/img elements, with special text never executed.
- Inspected 1280px desktop and 390px narrow layouts; narrow document width was 390px with tables scrolling inside containers. Initial wrapped headers were corrected through Issue/Artifact column widths, desktop width and Chinese copy, then retested.
- Local HTML, screenshots and machine-check results are under backend/build/report-verification/1788935345202/. Check scripts, implementation report and later reviews remain in this plan's independent SDD workspace. Screenshots are local verification materials, not claimed as CI uploads.

## Independent Review and Fixes

Initial task review found three defects: requiring complete partial FAILED Issue/history objects, omitting HTTP-only fields, and an unbounded read after a size check. Added regressions first reproduced the two presentation defects, then preserved available partial fields, rendered the union of scenario/HTTP keys and used single-handle metadata checks with at most 1 MiB + 1 bytes read, rejecting excess. The 24 tests and eight browser checks above cover the repaired code; scoped re-review closed all three findings with Spec Compliance / Task Quality PASS; final review and CI will be added with delivery evidence.

## Delivery and Limitations

Local checks do not substitute for exact-implementation-commit CI. After pushing the implementation, add bilingual M1/M2 CI, comparisons of eight HTML files per branch with same-directory JSON, and independent review conclusions. This initial record does not claim those pending checks passed.

The browser reads derived reports without rerunning database/HTTP/history verification. All data is synthetic, Verified=false; REPORT_RENDERED is not Release or Owner PASS. Existing M2.5 performance-reference gaps, canonical coverage limits, Windows ACL skips and Worker FAILED/timeout propagation without separate fault injection remain. Original CI Artifacts have finite retention; three sample JSON files are preserved in Git.

## Next Execution Plan

Current result: implementation and local checks are complete; independent review and exact-commit CI remain to close. Git status: versioned with implementation under bilingual governance; remote checks determine push status. Next action: complete fixed-implementation CI/Artifact comparisons and prepare the Owner review record. Prerequisites: actual independent-review and bilingual CI results, without new environments. Acceptance target: original report statuses/fields are traceable, failures remain visible and evidence binds exact implementation commits for an Owner decision.
