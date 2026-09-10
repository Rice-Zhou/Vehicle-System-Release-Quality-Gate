# Minimal Single-device Smoke and Test Result/Evidence Design

## Status, Inputs and Goal

Status: Accepted for design and detailed planning under [M3-SMOKE-DESIGN-REVIEW-001](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md); Task 1 APK implementation is now separately instructed and built; no complete M3 or product acceptance is approved. Baselines: Chinese 519f88c4fbc667d743f6cb98586c0a998b6f9aff, English fd2421b19910196805dcc23ce5c488e43551ed8d. The Owner confirmed an Android device permitting installation/execution and selected a new project-owned minimal demonstration APK. Actual device connectivity and API Level remain unverified. Subsequent Task 1 verified the host build toolchain without device commands.

Goal: use formal Run/Attempt execution to install/launch a minimal APK on one explicitly selected device, persist/query objective Results, logs and screenshots through the server, and distinguish success from disconnection/failure. Existing M1/M2/offline-report acceptance and Verified=false remain unchanged. This slice does not deliver complete Crash/ANR Collectors, M4 Quality Engine or M5 real-Release end-to-end acceptance.

Sources: [original MVP plan](../../v0.2/14-mvp-implementation-plan.md), [test states/completion contract](../../v0.2/07-test-architecture.md), [Agent protocol](../../v0.2/08-test-agent-protocol.md), [Evidence design](../../v0.2/09-evidence-design.md). Technology proposals: [TDR-024](../../v0.2/tdr/TDR-024-single-device-smoke-execution.md), [TDR-025](../../v0.2/tdr/TDR-025-local-demo-evidence-payload.md). Both TDRs are Accepted within the recorded demonstration scope. Subsequent Task 1/2 implementation instructions and engineering checks are preserved in their respective records. Task 3 implementation and verification are recorded. Task 4 implementation, independent reviews and exact-commit CI are complete; actual evidence is in the [local Evidence engineering record](../../m3/local-evidence-verification.md). Task 5 implementation, independent reviews and exact-commit CI/Artifact verification are complete; actual evidence is in the [Event and Result engineering record](../../m3/attempt-result-verification.md). Tasks 6–7 remain unexecuted.

## Responsibilities and Delivery Boundaries

| Unit | Responsibility and Code Location | Excluded Responsibility |
|---|---|---|
| Demonstration APK | New independent Java/Android project `demo/android-smoke/`; fixed Activity displays an Attempt marker, with a fixed negative mode showing an incorrect marker. | Networking, identities, Evidence upload, Run states or quality decisions. |
| Host Agent | New independent Kotlin/JVM 21 CLI `agent/`; mTLS registration/polling, durable execution records, ADB executor, LOG/SCREENSHOT Collectors, local spool and Result submission. Reuse the existing Kotlin version without depending on the Spring service process. | Direct database writes, creating/modifying Releases/Plans, arbitrary commands or Release PASS decisions. |
| Test Management | New Backend `testmanagement` module; Device/Agent/Environment, versioned Plan/Case, Run/Attempt/Command, leases, idempotency and terminal states. | Recalculating M2 Snapshots or implementing Quality Engine early. |
| Evidence | New Backend `evidence` module; upload sessions, streaming storage, verification, Metadata, download authorization and reconciliation. | Calling ordinary directories WORM, Company archiving or publishing real Payloads to GitHub. |
| Demonstration entry | Future `scripts/demo/run-m3.ps1` assembles existing Backend/database, test APK, explicit Agent/Device and configuration. Dedicated demo initialization only publishes Plans and registers permitted identities/devices. | Seeding Run results, Evidence AVAILABLE or Traceability Verified, or automatically purchasing/enabling resources. |

APK and Agent are execution tools; Backend remains the domain authority. Existing `shared/.../archive` is not the runtime Evidence module, and its Company workflow is not copied. Preserve M1/M2 command/input compatibility. This slice first provides standard result APIs and a machine-readable demonstration summary without expanding offline HTML features.

## Release Identity and Test Scope

Drive Runs from Release + Locked Manifest, not an APK path as Release ID. Run creation transactionally fixes project, releaseId, Manifest revision/digest, Published Plan Version, Device and Environment Snapshot. Before and after execution, the Agent checks device boot/session and system build/fingerprint; mismatched or changed environments explicitly block/interrupt execution.

The first demonstration declares a `SYNTHETIC_DEMO` project and APK install/launch scope. Its Manifest records the actual built APK checksum, packageName, versionCode and signingCertificateSha256. Environment configuration is a CONFIG Artifact with actual bytes/checksum. Reading real device environment fixes the Environment only; it fabricates no system-image bytes and claims no complete vehicle Artifact testing. A future complete target Release requires its actual Manifest scope and verified required Artifacts. Reject content beyond this executor's capabilities rather than ignoring it.

Reuse actual file verification and Lock application paths from the M1 demonstration, supplying real APK/configuration files. Do not weaken the ordinary Backend's default INCOMPLETE policy. The host Agent accepts only an explicitly configured APK file, checks its bytes against the server-fixed Manifest before execution, and verifies installed package version/signature and readable installed-APK checksum afterward. Missing required identity information means BLOCKED; install exit code 0 is not identity verification. Rebuilds/signing produce new APK digests requiring a new Manifest/Run, not reuse of an old digest.

## Observable Behavior of the First Case

Fix `planId=single-device-smoke`, `caseId=apk-launch-smoke`. Plan v1 references Case v1 with mode=normal. Separate Plan v2 references Case v2 with mode=assertion-failure as an explicit negative fixture. Published versions cannot change; CLI arguments cannot inject arbitrary modes into running Cases.

1. Preflight: API Level at least 26; ADB explicitly selects one authorized device; boot/session matches Environment; APK/signature matches. This Agent advertises only ADB, APK_INSTALL, LOG and SCREENSHOT capabilities, never Crash/ANR.
2. Persist Command/Attempt and the latest phase, then ACK for lease/fencing token before execution. A same-signature package may be replaced; conflicting signatures, installation restrictions or unverifiable identity explicitly stop execution. Never automatically uninstall, clear data or bypass system policy.
3. Launch the fixed Activity with a validated UUID Attempt marker. Case v1 displays `VSRQG_SMOKE_READY:<attemptId>`. Case v2 displays `VSRQG_SMOKE_NOT_READY:<attemptId>` while the assertion still requires READY, producing deterministic FAIL.
4. The Agent checks launch outcome, foreground component and exact current marker in the UI hierarchy. Neither App logs nor screenshot existence alone are sufficient. Disable external XML entities and limit XML input to 1 MiB. Unavailable UI inspection means BLOCKED; unmet UI assertions mean FAIL, execution-tool failure means ERROR, and deadline expiry means TIMEOUT.
5. LOG Collector retains only current execution steps, sanitized diagnostics and test-package markers. SCREENSHOT Collector saves PNG while the test Activity is foreground. Both are required, limited to 1 MiB / 8 MiB respectively. Notifications may overlay screenshots, so use a clean demonstration device; unscreened raw device files are not automatically committed to GitHub.
6. Create Upload Sessions, upload, Complete verification, then submit one Result. The server checks Evidence ownership, type, integrity and required satisfaction. Reject wrong-Run, cross-project or fabricated AVAILABLE references. Capture/upload failure results in explicit ERROR with acquired partial Evidence; required failures remain visible and cannot masquerade as Case PASS.

ADB execution permits only the fixed operations in TDR-024. This slice does not automatically reboot/power-cycle devices or perform arbitrary system actions. Permission to install/run the app does not authorize device-wide cleanup or system flashing.

## APIs, Identity and Persistence

Reuse existing user Create/Cancel Test Run, Get Results and Evidence Metadata/Payload APIs; reuse Agent registration, heartbeat, poll, ACK, Event, Create/Complete Upload and PUT Result fields, complete version prefixes, permissions and idempotency requirements.

Add `GET /agent-api/v1/attempts/{attemptId}/context`, readable only by the currently assigned Agent with existing `agent:execute` permission; GET requires no Idempotency-Key. Return server-fixed Release/Manifest digests, APK identity, Plan/Case parameters and Environment/Device references, without credentials, local paths or raw device serials. This supplies execution context absent from the existing Command Envelope without silently adding fields to the strict protocol 1.0 Command Payload.

Per TDR-025 add mTLS `PUT /agent-api/v1/evidence/uploads/{id}/payload`, reusing `agent:evidence:write`. Stream binary content without Idempotency-Key; the bound Session and byte digest define retransmission semantics. Sessions bind current Agent, Attempt, project, type, size/checksum and expire after five minutes, never extending beyond Attempt lease validity/terminal state. Update OpenAPI, the Agent table and exact Method/Path contract tests for both new endpoints.

Agent routes use a separate ordered SecurityFilterChain that authenticates trusted mTLS certificates and persisted identity bindings. Missing certificates cannot fall back to user JWTs, and Agent certificates grant no user-route permissions. Generate/store development certificates outside Git without deploying an external identity service. Test trust, expiry, project/Agent revocation and request Audit. Untrusted headers cannot establish identities.

Incrementally migrate Device, Agent, Capability, Environment Snapshot, Plan/Case Version, Run, Attempt, Result, Command/Event and Evidence/Upload Session according to the existing ER. Add neither a generic task platform nor a second permission table. Reuse principal/project_assignment and Audit/Job capabilities. Run+Manifest+Environment+Audit/Outbox, terminal Attempt+Result+Outbox, and Evidence Metadata+Audit/Outbox each commit transactionally. Existing composite-FK design and transactional checks enforce Evidence Release/Run associations.

## Idempotency, Time and Recovery

The first Plan executes one required Case sequentially, allows one Attempt and never automatically retries installation. Heartbeat: 20 seconds; poll wait: 20 seconds; lease: 90 seconds. Only matching current-Agent heartbeats renew leases using Server time. Allocation deadline: 60 seconds; Case/Command deadline: 300 seconds; Run deadline: 600 seconds. RECOVERY_PENDING recovery window: 120 seconds, never beyond the Run deadline. These are fixed Plan/lease policies independent of device wall time.

Persist reception before ACK and phases before/after device actions. Retrying a lost ACK returns the same lease. After restart, resume reporting/uploading only with the same valid lease, same boot/session and a provable phase. If installation/launch completion is uncertain, do not replay automatically: enter RECOVERY_PENDING and end explicitly as ERROR/TIMEOUT when recovery expires. Expired leases cannot resume business writes, only controlled diagnostics. New execution requires a new Run/Attempt without overwriting prior facts.

Events are idempotent by commandId/sequence. Results use the existing attemptId PUT; differing digests conflict. Compute Result digest as SHA-256 of JCS over validated request fields excluding resultDigest, including evidenceIds normalized to sorted unique values. Client/server use the same rule and retain complete returned facts for review. After terminal state, identical digests return the original acknowledgment; conflicting digests/illegal sequences enter diagnostics. No late valid Evidence can alter a closed Run.

Run COMPLETED still obeys the original completion contract and may contain FAIL or explicit required-Evidence failures; it is not Release PASS. Cancellation/Run deadline first fences active Attempts and writes terminal Results, then closes the Run, leaving no UPLOADING/RECOVERY_PENDING work. Case status remains separate from final quality decisions.

## Verification and Implementation Decomposition

| Work Unit (Detailed Steps Follow Design Review) | Independently Verifiable Exit |
|---|---|
| Minimal APK and build | assemble/lint, invalid-marker rejection, normal/negative UI, fixed APK/signing digests and explicit device support range. |
| Backend Run/Agent/leases | Context endpoint contract, transactions/state machine, unlocked-Manifest rejection, capability matching, exclusive Device, mTLS/project isolation, duplicates, expiry, cancellation and recovery. |
| Evidence storage/query | Upload endpoint contract, streaming limits, retries/conflicts, path/link rejection, cross-Run rejection, DB/filesystem reconciliation, authorized download and restore verification. |
| Host Agent/Collectors | ADB argument allowlist, real subprocess timeout, durable phases, stale markers/restart/disconnection, upload retries and Result idempotency; protocol fixtures are not device tests. |
| Single-device delivery | Actual normal Case and deterministic FAIL, explicit disconnect/Agent restart, Run→Result→two Evidence queries and original-byte checksum recomputation, independent review and Owner acceptance record. |

Backend unit tests default to a 60-second timeout. The real-device Case's 300 seconds is a business deadline, not permission to wait indefinitely. CI may use controlled ADB substitutes to test protocol/process boundaries and build APKs. Only actual hardware execution is real-device evidence. Unperformed device power-loss tests, full Crash/ANR coverage and other M3 exits must remain explicit omissions; this slice is not all M3.

The original design round ran no new build, API, ADB action or migration; its approval covered design and planning only. Subsequent Task 1 builds are recorded in [build verification](../../m3/minimal-apk-build-verification.md), while Task 2 V12 migrations, Agent registration and mTLS checks are recorded in [identity and registration verification](../../m3/agent-identity-registration-verification.md). Task 3 now has a separate implementation instruction; see [Run and lease verification](../../m3/run-lease-verification.md) for actual status. ADB and real-device behavior remain unexecuted. Document checks cannot replace runtime evidence.

## Next Execution Plan

See the [Event and Result engineering record](../../m3/attempt-result-verification.md) for current implementation progress, Git status, the sole next action and acceptance target. The original design Subjects and approval scope remain unchanged; actual tests are recorded in each Task's engineering record.
