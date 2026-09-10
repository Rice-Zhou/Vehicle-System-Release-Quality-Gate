# Single-device Smoke Host Agent

This independent Kotlin/JVM 21 CLI consumes the formal Agent HTTPS protocol and executes one `apk-launch-smoke` Case through an explicitly selected ADB serial. It does not create Releases, Plans or Quality Results, or change Issue Verified. Passing host tests does not establish real-device or complete M3 success.

## Build and Startup

Use an existing JDK 21; the Wrapper and Backend both pin Gradle 8.14.4. The Kotlin version is imported from the Backend version catalog; Jackson/JUnit versions use the same Spring Boot BOM. Agent tests require neither Spring nor a running database.

```powershell
$env:JAVA_HOME='<JDK21_ABSOLUTE_DIRECTORY>'
.\gradlew.bat clean test build
.\gradlew.bat installDist
.\build\install\vsrqg-agent\bin\vsrqg-agent.bat --server=https://<backend-host>:8443 --tls-config=C:/controlled/agent-tls.json --device=<registered-device-id> --adb-config=C:/controlled/agent-adb.json --apk=C:/controlled/smoke.apk --spool=C:/controlled/spool
```

On Linux/macOS, build with `./gradlew clean test build` and use `bin/vsrqg-agent` in the distribution directory. Configuration paths must still be absolute paths on that host. This task was verified only on Windows/JDK 21; other hosts require separate verification.

The CLI accepts only six required `--name=value` arguments. Unknown, duplicate or empty arguments, non-HTTPS URLs, missing configuration/files and symbolic links or Windows junctions in configuration/output paths are rejected. Errors print only stable codes or exception types, without credential contents, serials, command output or Payload paths.

The structure of `agent-adb.json` follows. Every value is a placeholder; keep this file outside the repository:

```json
{
  "serial": "EXPLICIT_USB_SERIAL",
  "adbExecutable": "C:/Android/platform-tools/adb.exe",
  "aaptExecutable": "C:/Android/build-tools/34.0.0/aapt.exe",
  "apksignerJar": "C:/Android/build-tools/34.0.0/lib/apksigner.jar"
}
```

Only an explicit USB/emulator serial is accepted (letters, digits, dots, underscores and hyphens). The Agent does not enumerate devices, select the first device, or accept IP/port selectors or transport aliases. `device` must reference a server-registered Device bound to that serial. The Agent does not infer this administrative configuration relationship. ADB and Build Tools 34.0.0 must already be supplied on the controlled host; the Agent does not install a toolchain. Signing commands invoke the JAR directly with the current Java runtime, without running `apksigner.bat`.

`agent-tls.json` references only PKCS12 and password files:

```json
{
  "keyStore": "C:/controlled/agent-identity.p12",
  "trustStore": "C:/controlled/backend-trust.p12",
  "keyStorePasswordFile": "C:/controlled/identity-password.txt",
  "trustStorePasswordFile": "C:/controlled/trust-password.txt"
}
```

The identity store must contain exactly one private-key entry; isolate these files using host account permissions. Passwords are not passed as CLI values or written to logs. The server verifies mTLS; user JWTs do not participate in Agent calls. Redirects are not followed, and only fixed same-origin endpoints from the repository OpenAPI are permitted.

## Single Instance and Persistence

The device lock resides in the execution account's `~/.vsrqg-agent-locks`, using the serial's SHA-256 as the filename without exposing the raw serial. It covers separate spool directories and processes under the same service account and serial; the spool also has an exclusive journal lock.

**Deployment prerequisite: only one controlled service account runs this Agent for a device, using its unique canonical serial.** An account-directory lock cannot guarantee exclusion across operating-system accounts or identify two selector aliases referring to one device. This demonstration does not provide a device pool or coordination locks across hosts.

Each Attempt retains its Command, Context, journal, unconfirmed Event, observations, Evidence bytes, upload sessions and original Result. The journal uses temporary files, file `force(true)` and atomic replacement; unsupported atomic replacement fails explicitly. Windows JDK does not support directory fsync, so absolute persistence of directory metadata during power loss is not claimed. Missing or corrupt records prevent execution and cannot be treated as a new task to reinstall.

The spool must reside outside the repository and public directories, under service-account control. Below 778 MiB of free space, heartbeats report DEGRADED and new work stops. This reserves space for three APK snapshots capped at 256 MiB each and bounded Evidence; it is resource protection, not a quality threshold. This implementation retains all spool files, including acknowledged Results, and has no automatic cleanup, retention period or disk expansion feature.

## Execution and Recovery

- Heartbeats run independently every 20 seconds; poll waits 20 seconds, leases last 90 seconds, and Cases last at most 300 seconds. Validity uses Server time and local monotonic elapsed time, never exceeding the Command deadline; the server continues to enforce the Run deadline.
- Preflight first reads the fixed property `ro.build.version.sdk`, accepting only an integer API Level >= 26; missing, invalid or lower values are rejected before installation intent. Context fixes the APK bytes checksum, signature, versionCode and boot/build/fingerprint. Installation uses a verified spool snapshot; an existing package must expose one readable base APK with the same signature. Split packages, unknown signature/version and unreadable APKs stop execution explicitly, without uninstalling, clearing data or automatically downgrading.
- Intent is persisted before installation/launch. Recovery from `INSTALL_INTENT` / `LAUNCH_INTENT` reports recovery waiting without repeating actions. `INSTALLED` continues only a launch without a saved intent; `ACKED` with an acknowledged STARTED does not add another STARTED. An unknown Event response replays only the saved request and sequence.
- Current boot/build/fingerprint are checked before installation, after UI observation, after collection and after uploading before the Result is frozen. Any change retains the spool and stops execution; the replayable Result is written only after the final environment check, avoiding a saved PASS after a failed check.
- The foreground component and the current UUID's READY line jointly support the UI assertion. Negative mode still requires READY and therefore produces a deterministic FAIL. XML forbids external entities and is capped at 1 MiB. uiautomator writes only the current UUID path, and cleanup targets only that complete path.
- LOG reads only the test package PID, retaining current fixed markers and host step codes rather than device-wide logs. SCREENSHOT reads binary PNG only while the test Activity is foreground. Their limits are 1 MiB / 8 MiB; PNG decoding also has a 16M-pixel resource limit. Collectors make no quality decisions.
- All child processes read bounded stdout/stderr concurrently. Limit breaches, nonzero exits, timeouts and lease invalidation are visible; only the currently owned process is terminated, and `adb kill-server` is never executed.
- Recovery from `OBSERVED` uploads only existing bytes; Evidence IDs are recorded after Session/Complete receipt verification. Explicit server content, integrity or rejected-Session errors are recognized through a narrow allowlist: bounded Problem JSON must match the HTTP status and current fixed request path, and only a stable code is retained. The original rejection reason is persisted before checking the valid lease and submitting an ERROR Result with confirmed partial Evidence IDs. Unconfirmed files remain; restart does not upload a recorded permanent failure again. A 503, unknown response/error, temporary I/O failure, permission denial or stale lease does not become a writable ERROR result. The Result uses shared JCS rules and is persisted unchanged; `localFile` / `fileName` never enter the wire or digest.
- On restart, the Agent may first PUT the identical persisted Result to confirm an existing idempotent server receipt, without acquiring a new writable lease. Success requires matching the original digest and every request field. A 409, invalid lease or binding conflict retains the spool for diagnostics, without new Evidence or execution. `RESULT_ACKED` performs no actions.

Common stable failure codes: `CLI_REQUIRED_ARGUMENTS`, `SYMLINK_DENIED`, `DEVICE_LOCKED`, `JOURNAL_CORRUPT`, `DEVICE_API_LEVEL_UNSUPPORTED`, `DEVICE_API_LEVEL_INVALID`, `ENVIRONMENT_IDENTITY_CHANGED`, `APK_SIGNATURE_CONFLICT`, `APK_BASE_UNAVAILABLE`, `SMOKE_ASSERTION_FAILED`, `PROCESS_TIMEOUT`, `PROCESS_OUTPUT_LIMIT`, `LEASE_LOST`, `RECOVERY_WAIT_FOR_DEADLINE`, `HTTP_STATUS_409`, `SPOOL_INTEGRITY_ERROR`. After failure, inspect the server Attempt/Run and controlled spool first; do not delete intent files to force a rerun. A new Run/Attempt drives new execution.

## Verification Boundaries

Tests directly import repository Schema, OpenAPI and Backend canonical golden JSON; there is no second wire Schema. Tests include real bounded child JVMs, cross-process device locks, Windows junctions, localhost JVM HTTPS/mTLS, redirect/upload retry handling, exact Result replay after an unknown response, independent heartbeats during a long installation and the complete Smoke protocol flow.

The device port in the complete Smoke flow is an explicit test double; no real Android device was enumerated, installed, launched or read. Real ADB, real APK behavior on a device and Task 7 integration remain unverified. Ordinary Backend M1 file verification and default INCOMPLETE behavior are unchanged.
