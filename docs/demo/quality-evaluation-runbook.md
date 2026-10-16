# Controlled-configuration quality evaluation demo runbook

## 1. Purpose and boundary

The `scripts/demo/run-quality.ps1` entry reuses existing formal APIs to create a new Test Run for each of four scenarios, wait for a terminal state, submit quality evaluation, retain the exact Evaluation ID returned by 202, and generate bilingual read-only reports. It does not evaluate rules, prepare or publish rules, create Manifest/Issue/Traceability, start an Agent or real Provider, start/deploy Backend, download Payload, or substitute for Owner acceptance.

Basis: [Task 6 plan](../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md) and [TDR-026](../v0.2/tdr/TDR-026-minimal-quality-evaluation.md). V0.2 is Draft and TDR-026 is Proposed / REVIEW_REQUIRED. This runbook defines HOW, without granting rule publication, device execution, or Company resource permissions.

No isolated API configuration is available this round. Entry orchestration is tested with a local simulated HTTP service, while M1 still verifies the existing formal-source fixture; these cannot be combined into a claim that this CLI completed an end-to-end run under a real configuration. Actual configuration execution, bilingual full-page/narrow-screen evidence, and complete Evidence navigation remain unverified.

## 2. Prerequisites

- Use an authorized isolated environment with the same Release bound to a Locked Manifest, an exact Traceability Snapshot, published rule sets, and Test Plans. Backend validates all formal bindings; do not edit the database, disable constraints, or combine old demo files to manufacture results.
- The isolated Agent/controlled fixture must already be ready to consume new Runs. The entry only submits and waits; it does not start an Agent or execute adb. Without an authorized consumer it times out. A real-device phase needs separate authorization; a loopback address alone establishes neither safety nor device authorization.
- The identity needs project membership and scopes: `test:execute`, `test:read`, `quality:evaluate`, `quality:read`, and `traceability:read`; metadata checks additionally need `evidence:read` and the Evidence routes. Existing server scope + project-role checks remain authoritative, without a new permission bypass.
- Install the project's locked Node dependencies and provide PowerShell 7 and Node. The entry does not install dependencies. TLS uses system/Node trust; configure `NODE_EXTRA_CA_CERTS` for a private CA where required, without disabling certificate checks.
- Create the output parent directory beforehand; the output directory itself must not exist. Use an absolute path without symlinks in existing parent directories. Use a new directory for every invocation; historical files are not overwritten.

| Scenario | Required existing isolated sources | Expected record |
| --- | --- | --- |
| smokePass | Normal Smoke Plan; requiredIssueRefs explicitly empty | COMPLETED / PASS |
| smokeFail | Published deterministic-failure Smoke Plan and matching rule Case version | COMPLETED / BLOCK |
| requiredIssueBlock | Case PASS; rule explicitly requires an Issue that is not Verified | COMPLETED / BLOCK |
| missingEvidence | Valid terminal input missing an Evidence type required by the quality rule | ERROR / QUALITY_REQUIRED_EVIDENCE_MISSING |

missingEvidence requires an existing legitimate Plan/fixture that can produce this input in the isolated environment. Do not corrupt uploaded files, fabricate a terminal state, or relax rules to obtain it. Test Plan version 3 in the example is a replaceable configuration value, not a claim that this repository published that version. If any source prerequisite is missing, prepare the reviewed environment first rather than deleting a scenario or changing its expectation.

The existing test-only `M3FixtureAgentMain` supports explicitly setting `VSRQG_M3_SCREENSHOT_INPUT=PNG_INVALID` under `VSRQG_M3_EXECUTION_MODE=CI_FIXTURE`: the non-device fixture returns invalid PNG bytes, which the real ScreenshotCollector rejects, retaining LOG and submitting ERROR / PNG_INVALID. When unset it still generates a normal PNG; unknown or blank values raise `FIXTURE_SCREENSHOT_INPUT_INVALID` before protocol activity. This class is available only on the test classpath, built through the existing `m3FixtureClasspath` task, excluded from the production Agent distribution, and is not a quality-entry configuration field.

This input can reuse the existing normal Plan/Case v1. After required screenshot collection fails, formal Quality source reading should report QUALITY_REQUIRED_EVIDENCE_MISSING and leave the report unpinned/undetermined rather than BLOCK. Child-JVM/mock-HTTPS fixture tests do not replace G4 execution for the same formal Release. The author and independent reviewer remain unassigned; rule publication, actual Runs, and consumer startup need separate authorization. See the [PNG_INVALID engineering record](../v0.2/reviews/2026-10-16-quality-png-invalid-fixture-engineering.md).

The entry also checks the pinned Snapshot's single-Case facts: smokeFail must be FAIL, while other completed scenarios must be PASS. smokePass requires empty requiredIssueRefs. requiredIssueBlock needs a fact matching an exact requiredIssueRefs entry with required=true and verified=false. Two BLOCK outcomes do not make scenarios interchangeable; this does not rerun rules or claim per-rule causal attribution.

## 3. Configuration and invocation

Copy `demo/quality/config.example.json` to a controlled location and replace every sample ID, version, vehicle, origin, and outputRoot. All four scenarios must reference the same Release; each may reference its exact Traceability/rule version. Configuration strictly rejects unknown fields and accepts no plaintext Token, existing runId/evaluationId, arbitrary command, or executor path.

| Field | Constraint |
| --- | --- |
| origin | Only an http/https root address on 127.0.0.1 or [::1]; no username, password, path, query, or fragment; redirects are not followed |
| tokenEnvironment | Environment variable name only; credentials come from the current process environment, never configuration, output, or command-line arguments |
| outputRoot | New absolute path with an existing parent; existing output is explicitly rejected |
| pollIntervalMs | Integer, 10–5000 |
| timeoutSeconds | Integer per scenario, 1–600; each HTTP request is bounded to 10 seconds and each response to 16 MiB |
| verifyEvidenceMetadata | true separately accesses fixed Evidence IDs and checks references; false records NOT_REQUESTED |
| scenarios | Exactly the four scenarios above; releaseId, ruleSet, traceabilitySnapshotId, testPlan, and deviceSelector are formal references |

Inject the Token into `VSRQG_QUALITY_DEMO_TOKEN` through your secure local credential process, then run:

```powershell
./scripts/demo/run-quality.ps1 -Config D:/controlled/quality/config.json
```

Success exits with 0 and prints QUALITY_DEMO_REPORTS_READY. A nonzero exit retains an explicit error code; process success does not authorize Release publication. Adapt the example absolute paths to the local machine.

## 4. Outputs and failure handling

The root `session.json` retains Run/Evaluation IDs, request idempotency keys, and progress for the four scenarios. Keys are saved before POST, and returned IDs are immediately saved. Normal root states are RUNNING → SUCCEEDED; scenario states are STARTING → RUN_CREATED → EVALUATION_QUEUED → EXPORTED. Failure sets the root to FAILED with errorCode. Created Runs and queued Evaluations are not automatically cancelled, deleted, replaced, or retried. For an uncertain request outcome, preserve the key and reconcile through existing query/idempotent replay APIs; rerunning the entry creates a new invocation and must not be described as the original task.

Each scenario produces `quality-report-export.json`, `report.zh.html`, and `report.en.html`. Report provenance stays UNKNOWN because configuration assertions do not establish real-device or synthetic-fixture origin. Quality actions and errors come unchanged from the formal Evaluation; the entry checks the four expectations without recomputing decisions.

When metadata checks are enabled, `evidence-access.json` separately records the fixed evaluationId, check time, exact Evidence IDs, and VERIFIED observations, with payloadDownloaded=false. These calls use Evidence metadata GET, which may record integrity observations on the server; they are separate from the pure renderer and establish neither download authorization nor permanent Payload integrity. Session evidenceAccess is VERIFIED, NOT_REQUESTED, NOT_PINNED, or PENDING before a failure. Pre-pin ERROR does not query absent Traceability/Test/Evidence or turn unknown facts into empty collections.

For timeout, HTTP 401/403, reference conflicts, unexpected decisions, or report generation failures, locate records using the fixed session IDs. Preserve the failure rather than selecting latest/successful history. If a later scenario fails after earlier exports, the root remains FAILED; partial HTML does not establish success for all four scenarios. Payload download needs separate authorization, purpose, and audit; this entry never calls download routes.

TDR-026 still applies: initial bytes verification is outside the transaction, while seal rechecks sources/metadata/fencing; historical digests do not prove current file integrity. Read-only reporting does not change Evaluation ERROR or acceptance status.

## 5. Automated verification and evidence checklist

```powershell
node --test scripts/tests/quality-demo.test.mjs scripts/tests/quality-report-export.test.mjs scripts/tests/quality-fixture-ci.test.mjs
node scripts/contract-validator.mjs
```

Entry tests cover four scenarios against a locally simulated API, exact IDs across history pages, failure progress, redirects, metadata permission/digest conflicts, configuration rejection, fresh output, and a real PowerShell invocation with a configuration path containing spaces. The existing M1 contract chain executes these tests while retaining formal PostgreSQL/source fixtures and report regressions; no duplicate workflow is created.

Once configuration is available, retain the invocation Subject, nonsensitive configuration references, session, four scenario exports, and digests. Check new Runs, the same Release, exact Snapshot/rule versions, Evaluation IDs, original ERROR, and field-by-field projections. Then capture complete Chinese/English pages, narrow-screen readability, and authorized locator access evidence. Unexecuted items remain UNKNOWN; old screenshots are not substitutes, Task 5/6 are not checked as wholly complete, and A1–A8 are not self-marked PASS.
