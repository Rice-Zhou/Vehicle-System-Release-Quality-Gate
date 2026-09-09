# TDR-024 — Single-device Smoke Execution and Minimal Demonstration APK

- Date: 2026-09-09; status: Accepted for this demonstration design/planning and subsequent Task 1 APK, Task 2 identity/registration/machine-contract and Task 3 Run/Attempt/scheduling/lease implementation; no implementation instruction for Tasks 4–7.
- Authority: [Owner design acceptance](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md); original instructions are preserved in the [receipt](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/bbfda03a08af7dffe4bca88a38ac558f2965e4ea).
- Scope: the first M3 demonstration slice; see the [single-device design](../../superpowers/specs/2026-09-09-single-device-smoke-design.md).
- The Owner confirmed an available Android device, permitted application installation/execution, and selected a new project-owned minimal demonstration APK. Connectivity, OS version and the specific device have not been tested.

- Task 1: the subsequent Owner instruction and build checks are preserved in [build verification](../../m3/minimal-apk-build-verification.md); this is not full M3 acceptance.
- Task 2: subsequent implementation instructions and evidence are preserved in [identity and registration engineering verification](../../m3/agent-identity-registration-verification.md); runtime Context/Payload still belong to Tasks 3/4, not real-device or full M3 acceptance.
- Task 3: subsequent implementation instructions and actual verification status are preserved in [Run and lease engineering verification](../../m3/run-lease-verification.md); Evidence Payload, Agent Result submission and device execution are not authorized.

## Run Input and Environment Binding

Task 3 preserves releaseId, testPlan and deviceSelector in the strict CreateTestRun request without adding arbitrary client device paths or environment assertions. Explicit server configuration selects the registered Agent/Device and supplies bounded CONFIG content for the single-device demonstration. Creation checks project, selector and capabilities, computes the actual CONFIG bytes digest, and freezes the Environment only after matching the CONFIG checksum in the Locked Manifest. Configuration is not a second authority for Release contents. Missing, mismatched or unsupported inputs are explicitly rejected; the ordinary Backend default INCOMPLETE payload-verification policy remains unchanged. This choice reuses existing configuration and PostgreSQL without adding an environment service.

Configuration uses disabled-by-default `vsrqg.demo.smoke.enabled` and the same-prefix `agent-id`, `device-id` and `environment-config-base64`. The latter encodes the exact CONFIG bytes, limited to 64 KiB after decoding with a bounded encoded input as well; base64 is transport encoding, not encryption. CONFIG strictly contains bootSessionId, buildId and buildFingerprint, without credentials or raw device serials. Source configuration and the registered Device vehicle/platform must satisfy the request selector and Release scope.

## Choice and Alternatives

Use a Kotlin/JVM 21 host Agent calling existing ADB and reusing the accepted Agent HTTPS pull/ACK/Event/Result protocol. The Agent is a separate CLI process with no direct Backend database access or authority to write final Quality Results. The existing Backend owns orchestration, identities, leases and authoritative records. No Broker or device pool is added.

| Option | Trade-off |
|---|---|
| Host Agent + ADB + minimal test APK | Recommended: execution is separate from the device, reuses JVM/ADB and retains host records when the device disconnects; ADB capabilities and interruption paths require verification. |
| Persistent on-device Agent | Adds background execution, permissions, upgrades and power-loss recovery that the first install/launch demonstration does not need. |
| Standalone ADB script producing success JSON | Easy to demonstrate but bypasses Run/Attempt, leases and formal Evidence associations; unsuitable as the product implementation. |

## APK and Build

Add an independent Android project under `demo/android-smoke/`. Use a single Java Activity and native TextView displaying fixed explanatory text and the current Attempt marker. No Compose, dependency injection, networking, accounts or background services. Package `com.ricezhou.vsrqg.smoke`, sole entry `.SmokeActivity`; accept only a UUID `attemptId` and fixed `normal` / `assertion-failure` modes, the latter exclusively a negative demonstration fixture.

Build baseline: AGP 8.7.3, Gradle 8.9, JDK 17, compileSdk/targetSdk 35, minSdk 26, Build Tools 34.0.0. This isolated APK build leaves Backend Kotlin 2.2.21 / Gradle 8.14.4 / JVM 21 unchanged. The official Android [compatibility table](https://developer.android.com/build/releases/agp-8-7-0-release-notes?hl=en) lists Gradle 8.9, JDK 17 and maximum API 35 for AGP 8.7; this is not claimed to be the latest version. The original design confirmed only ADB availability on PATH. Subsequent Task 1 verified SDK build readiness; device API Level remains unverified. Pin the Wrapper checksum and record actual SDK/signing-certificate digests during implementation; keys stay out of Git.

## Execution and Trust

ADB is an allowlisted execution mechanism, following the [official ADB documentation](https://developer.android.com/tools/adb). Allow only test-package installation, launch, scoped log/UI reads and screenshots on an explicitly selected device. Use process argument arrays, fixed package/Activity names, and no arbitrary shell, first-device selection, uninstall, data clearing, flashing or reboot. A same-name package with a different signature blocks execution; do not uninstall it automatically.

The Agent uses the existing independent mTLS identity contract. The server controls certificate-to-project/Device/Agent bindings. User JWTs cannot substitute for Agent identity; Agents cannot publish Plans, change Manifests or create Quality Results. The APK is an untrusted test subject providing an observation marker. The Agent asserts current launch, foreground component and UI marker before reporting Test Result; Release Gate decisions remain with the future Quality Engine.

Collect only this application's current Attempt, without reading unrelated device-wide log buffers. Capture a screenshot after confirming the test Activity is foreground. Personal notifications remain a risk requiring a clean demonstration device. Real Payloads remain in controlled local storage by default and are not automatically pushed to GitHub.

## Verification, Rollback and Boundaries

Verify build/lint, input rejection, installation/signature conflicts, launch/UI assertions, stale markers, ADB disconnection, process timeout, Agent restart and same-run Result/Evidence associations. Record CI fixture and real-device evidence separately. Stopping the Agent stops new work; retain database history/spool and do not uninstall automatically.

This decision does not approve all M3, a real vehicle Release or Issue Verified. Test Run still fixes Release/Locked Manifest/Plan/Environment. Synthetic demonstration Releases must declare their tested scope; a test APK is not a complete vehicle Release. TDR-025 separately covers Evidence storage. Reevaluate for device pools, other APKs, flashing or an on-device Agent.
