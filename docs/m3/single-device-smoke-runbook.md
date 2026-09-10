# Single-Device Smoke Integration Runbook

This entry implements the `SYNTHETIC_DEMO` described in [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md) and [TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md). It creates a Release through formal APIs, validates and Locks an APK/CONFIG Manifest, creates a Run, starts the host Agent, then queries the Result and downloads both Evidence payloads to recompute SHA-256. `Run COMPLETED` or a passing scenario does not establish Release PASS, Issue Verified, full M3 or Company completion.

## Prerequisites

Use an existing JDK 21, PowerShell 7, PostgreSQL, Android SDK and one explicitly authorized device. See the [APK README](../../demo/android-smoke/README.md) for the build baseline and the [Agent README](../../agent/README.md) for configuration and recovery limits. This entry does not install SDKs, databases or services, enumerate or automatically select devices, uninstall, clear data, flash or reboot devices.

Before the first device operation, the operator must verify one explicit ADB serial, authorization, API Level ≥26, and permission to install and launch the test package, collect directed logs, read the UI hierarchy and capture screenshots. Only `com.ricezhou.vsrqg.smoke/.SmokeActivity` is allowed. Keep raw device identity in controlled configuration files. Clear personal notification overlays before screenshots; real screenshots, configuration, certificates and spool are not automatically uploaded to GitHub.

`START` connects only to a loopback `vsrqg_demo` database and starts an isolated HTTPS Backend bound to loopback. It closes the application and Agent child processes it started, retaining the database, Payloads, spool, APK and result directory. `EXISTING` uses an existing controlled demo service and its existing user JWT, without initializing or stopping that service. The server must already enable demo file verification, Smoke, Agent registration and Evidence, with the corresponding project, identities, Device and Published Plan registered. The ordinary Backend's default `INCOMPLETE` policy is unchanged.

## Strict Configuration

All JSON rejects unknown fields, duplicate fields and incorrect types. Keep the main, identity, device, environment and TLS configurations and password files outside the repository and public directories, using absolute canonical paths without symbolic links or Windows junctions. JSON only references controlled credential files. `outputRoot` must not exist; `payloadRoot`, `spool` and `outputRoot` must not contain one another. The APK limit is 1 MiB per file, using the existing M1 file verifier.

Main configuration example (replace placeholders with prepared files):

```json
{
  "server": { "origin": "https://localhost:8443", "lifecycle": "START" },
  "identityConfig": "D:/controlled/m3/identity.json",
  "apk": "D:/controlled/m3-input/smoke.apk",
  "deviceConfig": "D:/controlled/m3/device.json",
  "payloadRoot": "D:/controlled/m3-payload",
  "spool": "D:/controlled/m3-spool",
  "outputRoot": "D:/controlled/m3-output-normal",
  "planVersion": 1
}
```

`server.origin` accepts only a loopback HTTPS origin without user information, query, fragment or application path, with an explicit port from 1–65535. `lifecycle` accepts only `START` / `EXISTING`. There is no fixture mode field; the normal entry always uses the real Agent main class.

The `START` identity file:

```json
{
  "databaseConfig": "D:/controlled/m3/database.json",
  "serverTlsConfig": "D:/controlled/m3/server-tls.json",
  "agentTlsConfig": "D:/controlled/m3/agent-tls.json"
}
```

`database.json` accepts only the following three fields. The URL must be `jdbc:postgresql://localhost:PORT/vsrqg_demo` or an equivalent loopback address, without connection parameters. Username and password are read from controlled text files.

```json
{
  "url": "jdbc:postgresql://127.0.0.1:55432/vsrqg_demo",
  "usernameFile": "D:/controlled/m3/db-user.txt",
  "passwordFile": "D:/controlled/m3/db-password.txt"
}
```

Both TLS configurations use the same four-field structure as the Agent, referencing the server and Agent PKCS12 identities respectively. The Agent keystore must contain exactly one private-key entry. The server certificate must match the origin hostname and the trust chain must be valid. Server TLS uses `want` so user JWT routes can operate without a client certificate; the separate Agent SecurityFilterChain still enforces trusted mTLS, which JWT cannot replace.

```json
{
  "keyStore": "D:/controlled/m3/agent.p12",
  "trustStore": "D:/controlled/m3/trust.p12",
  "keyStorePasswordFile": "D:/controlled/m3/key-password.txt",
  "trustStorePasswordFile": "D:/controlled/m3/trust-password.txt"
}
```

The `EXISTING` identity file accepts only:

```json
{
  "projectKey": "existing-synthetic-project",
  "userTokenFile": "D:/controlled/m3/user-jwt.txt",
  "agentTlsConfig": "D:/controlled/m3/agent-tls.json"
}
```

The user JWT must belong to an existing USER in that project, with `release:create release:read manifest:write manifest:lock test:execute test:read evidence:read` scopes and roles permitted by the existing permission catalog. The Agent SERVICE identity cannot substitute for it. Token validity must cover the entire scenario. The Backend rejects mismatched identities, projects, devices or Plans.

`device.json`：

```json
{
  "agentId": "agt_explicit_demo",
  "deviceId": "dev_explicit_demo",
  "adbConfig": "D:/controlled/m3/agent-adb.json",
  "environmentConfig": "D:/controlled/m3/environment.json",
  "versionCode": 1,
  "signingCertificateSha256": "REPLACE_WITH_64_LOWERCASE_HEX_CHARACTERS"
}
```

`agent-adb.json` accepts only `serial`, `adbExecutable`, `aaptExecutable` and `apksignerJar`, matching the [Agent README](../../agent/README.md). `environment.json` accepts only `bootSessionId`, `buildId` and `buildFingerprint`, using accurate values read by the operator from the selected device. Its exact bytes are registered and fixed as CONFIG. Read the APK version and signing digest from aapt/apksigner output for that same APK; the Agent independently verifies the local APK and installed identity before execution.

`payloadRoot/artifacts` holds content-addressed APK/CONFIG inputs. Existing files with the same hash must contain identical bytes; conflicting bytes are never overwritten. `payloadRoot/evidence` must already exist with service-account-exclusive permissions: POSIX `0700`, or a Windows ACL allowing only the current service account, SYSTEM and Administrators. Ordinary shared directories fail explicitly; the entry does not silently relax or rewrite existing directory permissions.

## Execution and Checks

```powershell
$env:JAVA_HOME='<JDK21_DIRECTORY>'
./scripts/demo/run-m3.ps1 -Config D:/controlled/m3/config.json
if ($LASTEXITCODE -ne 0) { throw 'Smoke scenario failed; inspect fixed codes and summary' }
```

The wrapper first performs read-only validation through the same Kotlin configuration parser, then builds the Agent distribution and invokes the separate Backend `m3Demo` entry. Missing or invalid configuration returns nonzero with the fixed code `CONFIG_INVALID`, without a result report. Toolchain failures that prevent validation produce `CONFIG_CHECK_FAILED`, without fabricating a scenario report. Build or child-process failures after successful validation retain a failure summary; `executionMode` is null when the actual mode has not yet been established. Normal execution derives its mode from the execution main class, not configuration guesses.

The Plan is fixed to `single-device-smoke` and the Case to `apk-launch-smoke`. `planVersion=1` uses `normal` and requires an original Case PASS. `planVersion=2` uses `assertion-failure` and requires an original Case FAIL with `scenarioOutcome=PASS`. Each subsequent execution uses a new outputRoot and Run. Do not rewrite a Published Plan or an old Manifest digest to switch modes.

The first `START` creates a synthetic project and separate USER/SERVICE identities. Repeated `START` may reuse only exactly matching synthetic Project/Agent/Device/certificate bindings and the original Published v1/v2 definitions. It creates a fresh scoped USER session in the same project only for the current in-process JWT decoder. It does not rebind or update identities, modify Plans, or compare dynamic observations such as lastHeartbeat. Partial initialization, non-demo data, revoked or mismatched bindings fail explicitly. Existing Run/Result/Evidence records remain; each new Run fixes its environment through exact CONFIG bytes.

Successful output contains only `summary.json`, `log.txt` and `screenshot.png`. The summary retains source commit/dirty, mode, Release/Manifest digests, Run/Attempt, original Case/Result status and digest, both Evidence IDs/sizes/checksums and recomputed download hashes. The Result API does not expose a separate database Result ID, so the report references the Result by Attempt ID and the formal resultDigest without inventing an ID. `generationStatus` and `caseStatus` are separate; `releaseQuality=NOT_EVALUATED` and `verified=false` remain.

The scenario first checks the registered Agent. The formal Create Run API validates the project, Device, Locked Manifest and Published Plan, synchronously creating a QUEUED Attempt. The coordinator reads its UUID from formal Results and starts a finite Agent with the optional `--until-attempt-acked=<UUID>`. The Agent owns poll→Context→durable journal→ACK; the coordinator cannot query Context before dispatch. At completion, user APIs query Run→Result→Evidence, obtain separate download grants, bound download sizes, recompute each SHA-256, and read the same Run result again to confirm the historical projection remains stable. The coordinator then waits at most 30 seconds for the Agent to validate and persist this target's Result receipt as RESULT_ACKED and exit naturally with code zero. Timeout, nonzero exit or unfinished fixture assertions cannot produce a successful summary. Completing an old Attempt replay does not complete the current target; the original six-argument Agent continues running. The summary excludes raw serials, account identities, JWTs, private keys, download grant URLs and local absolute paths.

Process output is bounded. Timeouts terminate only process trees owned by this invocation: the wrapper child deadline is 900 seconds, HTTP is 30 seconds, and Run observation is at most 610 seconds. Backend business deadlines remain allocation 60 seconds, Case/Command 300 seconds and Run 600 seconds; heartbeat 20 seconds, poll 20 seconds, lease 90 seconds and recovery window 120 seconds. Errors attempt to cancel this Run through the user API, recording cancellation failures separately. Old output, Payloads and spool are not deleted, and the App is not uninstalled.

## CI and Recovery Coverage

The [separate M3 workflow](../../.github/workflows/m3-smoke.yml) explicitly checks the existing SDK/JDK before building the same APK and Agent. `m3IntegrationTest` is an opt-in source set; ordinary Backend `test` does not require an Android SDK or Agent. The controlled fixture replaces only `SmokeDevice`, using the formal AgentLoop and AgentClient, a real mTLS Backend, PostgreSQL and uploaded/downloaded bytes. It does not mock production Controllers or directly seed Result or AVAILABLE.

```powershell
./scripts/tests/m3-demo.tests.ps1
./agent/gradlew.bat -p agent --no-daemon test build installDist m3FixtureClasspath
# Prepare the same APK, signing digest, commit/dirty and fixture classpath environment variables as in the workflow:
./backend/gradlew.bat -p backend --no-daemon m3IntegrationTest
```

`m3IntegrationTest` executes every time, disabling up-to-date and build-cache reuse. Separate invocation directories preserve old materials. Local execution generates a UUID automatically, and `backend/build/m3/current-invocation.txt` identifies the current UUID. CI explicitly generates the test-only `VSRQG_M3_INVOCATION`; reusing a UUID fails. This is not product configuration. Only files generated in the current `backend/build/m3/fixtures/<UUID>/plan-{1,2,2-reuse}/{summary.json,log.txt,screenshot.png}` are uploaded.

The CI artifact is named `m3-ci-fixture-<commit>` and includes the APK and measured identity summary, XML, and `plan-1`/`plan-2` summary/LOG/PNG files. Only explicitly enumerated controlled files are uploaded, excluding temporary identity/TLS/database configuration. `CI_FIXTURE` never counts as `REAL_DEVICE`. v1 covers a newly started service and another START using the same identity to execute v2; `plan-2-reuse` retains the repeated-start output. Regression checks reject mismatched Agent bindings while retaining USER counts and old Result digests. Independent v2 covers an existing service remaining alive after execution. Missing DB/SDK dependencies fail without silent skips.

This integration CI does not inject device disconnection, Agent restart or backup recovery. The summary's `notCovered` retains these items, along with full Crash, ANR, power loss, Company and full M3. Existing Agent recovery unit tests prove component behavior only. Actual recovery exercises require an authorized existing demo service, explicitly owned separate Agent processes, the same spool and formal Run APIs. Retain pre-injection state, exact timing, leases and fencing, recovered bytes, terminal state and evidence of no repeated installation. Do not automatically replay actions during an uncertain installation stage or power off/reboot the device.

Backup recovery requires the operator to separately save and restore database and Payload copies from the same point in time, then verify the inventory and each payload's SHA-256. Merely copying or reading the current directory does not establish recovery. When device, database or recovery resources are unavailable, record those items as `UNKNOWN`; this runbook and CI fixture cannot replace actual delivery evidence.
