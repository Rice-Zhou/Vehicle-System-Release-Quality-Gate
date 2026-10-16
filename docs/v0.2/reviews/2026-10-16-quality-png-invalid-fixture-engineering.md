# G4 PNG_INVALID non-device fixture engineering record

- Date: 2026-10-16; scope: the confirmed D3 controlled input, failure regression, and minimal test-only fixture, not formal G4 execution or Owner acceptance.
- References: [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), [G4 entry](2026-10-15-quality-controlled-entry-engineering.md), and [runbook](../../demo/quality-evaluation-runbook.md). The author and independent reviewer remain unassigned; this change grants no project permissions or rule publication.

## Implementation and isolation

Reuse the test-source `M3FixtureAgentMain`, existing SmokeDevice, AgentLoop, ScreenshotCollector, LogCollector, and mTLS client. The entry still requires `VSRQG_M3_EXECUTION_MODE=CI_FIXTURE`, adding only the explicit test input `VSRQG_M3_SCREENSHOT_INPUT=PNG_INVALID`. When unset, normal PNG behavior remains; unknown or blank values fail with `FIXTURE_SCREENSHOT_INPUT_INVALID` before configuration parsing and protocol activity.

Invalid mode changes only the screenshot bytes: after normal launch with foreground true, it returns eight zero bytes. The existing ScreenshotCollector raises PNG_INVALID; the original AgentLoop retains confirmed LOG, submits ERROR with the original reason, and waits for RESULT_ACKED. No fabricated result, bypassed validation, or production collector change is involved. The failed screenshot has no candidate, declaration, upload, or file.

The class remains on the test classpath, built through the existing m3FixtureClasspath task and excluded from the production Agent JAR/distribution. No G4 configuration field, production entry, Plan/Case, rule, migration, or second executor is added. The existing normal Plan/Case v1 is a legitimate later source choice; v3 need not be assumed.

## Failure and verification

First add `M3FixturePngInputTest`, running the existing test entry in a child JVM against a local mock mTLS HTTPS service. The initial offline build lacked cached kotlin-reflect and never executed tests, so it is not counted as red. After normal dependency resolution, three of four cases genuinely failed: PNG_INVALID still returned PASS, while unknown and blank inputs still completed with exit 0; the default PNG path passed. The cause was the old fixture ignoring the input selection.

After adding explicit input parsing and invalid bytes, all four targeted cases passed, with no failures, errors, or skips. Counterexamples cover unknown and blank values without silent fallback; the normal control retains PASS, LOG, and SCREENSHOT. The missing-screenshot test checks normal installation and Smoke assertions, exact ERROR/PNG_INVALID, LOG-only declarations and uploads, no screenshot file, durable observation, result receipt, and RESULT_ACKED.

Reuse the existing M3 workflow's full Agent tests and XML retention without new CI infrastructure. Final bilingual full Agent tests/builds, production packaging exclusion checks, Pair Gate, pushed Subjects, and six GitHub CI results are recorded in the delivery receipt; pending CI is not declared successful in advance. Red/green XML, build logs, and receipt directory: `D:/VSRQG-local-smoke/g4-readonly-audit-20261015-d3516207/g4-png-fixture-20261016`.

## Unverified boundaries and next step

This iteration runs only mock services and temporary output created by automated tests. It does not connect to the private database/Backend, create business Runs in the user's environment, publish rules, create identities, grant permissions, or start the controlled environment's Agent. There is no real-device execution, real Provider, Company resource, merge, Tag, or deployment.

This proves that the collector input becomes a durable ERROR through the real AgentLoop. The mock service does not validate formal database Evidence requirement states or create a Quality Evaluation. QUALITY_REQUIRED_EVIDENCE_MISSING, NOT_EVALUATED, no pinned Input Snapshot/Quality Result, and unpinned/undetermined reporting for the same formal Release still need separate evidence from later authorized G4 execution; these tests cannot be spliced into a completion claim. TDR-026's external byte validation and sealing window semantics remain unchanged, with Proposed / REVIEW_REQUIRED status; Task 5/6, A1-A8, and acceptance states are unchanged.

Current result: D3's PNG_INVALID input and independent failure regression are implemented. Git status: commit and push separately in both languages; fixed Subjects and verification results are in the delivery receipt. Next action: once the Owner designates the author and independent reviewer responsible persons, inspect identity mappings read-only. Prerequisites: distinct responsible persons and corresponding identities; identity creation, permission grants, rule publication, and actual Runs each still need authorization. Acceptance target: clear identity/project mappings and inspectable permission gaps; these fixture tests replace neither Owner acceptance nor four-scenario G4 evidence.
