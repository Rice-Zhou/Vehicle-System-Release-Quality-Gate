# Minimal Demonstration APK — Task 1 Build Verification

## Scope and Implementation Instruction

On 2026-09-09, the Owner replied with the original text below after the explicit next step to execute Task 1 and build the minimal demonstration APK. In context it means execute the next step. Only Task 1 is implemented; this does not authorize Tasks 2–7, device operations, Company, merge, Tag, release or deployment. This is engineering verification, not Owner acceptance or an additional component approval gate.

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

Sources: [implementation plan](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [design](../superpowers/specs/2026-09-09-single-device-smoke-design.md), [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md). See the [APK README](../../demo/android-smoke/README.md) for the entry point and usage.

## Fixed Source and Artifact

- Chinese implementation Subject: [9a636999beb5bdcc0563e44f4d146794285a6497](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/9a636999beb5bdcc0563e44f4d146794285a6497).
- Paired English Subject: [b27fc82521481893cd83394b2faad039108ced52](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/b27fc82521481893cd83394b2faad039108ced52).
- The actual local build ran in the Chinese worktree. English non-Markdown Git blobs and file modes were compared individually; no independent English build is claimed.
- APK output is `demo/android-smoke/app/build/outputs/apk/debug/app-debug.apk`; build directories stay outside Git. This table records this specific artifact, without guaranteeing identical digests from another host using different debug signing. Task 7 must recheck the actual APK before Manifest creation.

| Item | Observed value |
|---|---|
| APK SHA-256 | 282187c056abe33b6f2b7629896d32a27d6244318990d6ec909e7136a4377ea6 |
| APK bytes | 7677 |
| packageName | com.ricezhou.vsrqg.smoke |
| versionCode / versionName | 1 / 1.0 |
| minSdk / compileSdk / targetSdk | 26 / 35 / 35 |
| Signing | Android debug / APK Signature Scheme v2 / 1 signer |
| Certificate SHA-256 | 6a52389eda39ba559e04c54549ba325db01258ea0eb18fe0668c4589f6ec43c1 |
| Gradle 8.9 distribution SHA-256 | d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab |
| Gradle 8.9 Wrapper JAR SHA-256 | 498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17 |

Official distribution/JAR digests were compared with [Gradle distribution checksums](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256) and [Wrapper checksums](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256). No debug private key, local.properties, build cache or proxy configuration was committed.

## Executed Checks

Actual toolchain: JDK 17.0.12, AGP 8.7.3, Gradle 8.9, Android platform 35 revision 2, Build Tools 34.0.0, JUnit 4.13.2. Explicit process-local JAVA_HOME and SDK paths were used, rather than the Android CLI's other default SDK. Commands below run from the APK project, using apksigner/aapt from those Build Tools:

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat assembleDebug --no-daemon
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

| Check | Result | Evidence and limits |
|---|---|---|
| TDD RED | PASS | Test written before production class; Gradle exit 1, sole failed task compileDebugUnitTestJavaWithJavac with 4 undefined SmokeMarker errors, not dependency failure. |
| TDD GREEN | PASS | Exit 0; JUnit 1 test, 0 failure/error/skipped; covers both modes, abbreviated UUID rejection and unknown mode rejection. |
| Final lintDebug | PASS with warnings | Exit 0; 3 Warning, 0 Error; see next section. |
| Final assembleDebug | PASS | Exit 0; actual APK exists and recomputed SHA-256/size match the table. |
| Signature and metadata | PASS | apksigner/aapt exit 0; package/version/SDK match and v2 signature verifies. |
| Manifest scope | PASS | One Activity; no permission/service/receiver/provider. |
| Wrapper | PASS | Official distribution/JAR digests match; gradlew Git mode is 100755 in both languages. |
| Independent Task 1 review | PASS | Initial review found missing Wrapper executable mode; after repair, spec/quality re-review Approved with no new breakage. |
| Final independent engineering review | PASS | Full authorized APK slice Approved; no Critical/Important or new actionable findings; no Owner/merge approval implied. |
| Device installation and UI | NOT RUN | No ADB, install, launch, rotation, onNewIntent or UI checks; Task 7 verifies these. |

The initial log-path mistake failed visibly before Gradle started. It was corrected and the valid RED rerun was retained. Android CLI telemetry connection failure is not SDK/build success evidence. Windows long-path listing warnings were distinguished explicitly; versioned checks use core.longpaths=true.

## Known Warnings and Boundaries

- OldTargetApi: targetSdk 35 is fixed by design and is not upgraded merely to remove a warning.
- MissingApplicationIcon: this minimal demonstration has no icon; its explicit component entry point is unaffected.
- SetTextI18n: the screen joins a fixed machine marker with the Attempt UUID, outside localized copy in this slice.
- The redundant screenOrientation attribute was removed and DiscouragedApi disappeared. Lint remains enabled without suppression rules.

Task review classified the remaining three warnings as nonblocking Minor findings. This verification does not cover full M3, Run/Result, Evidence upload, real vehicle Releases or Company behavior. Existing performance, canonical and historical Artifact-retention limits remain open.

## Next Execution Plan

Current result: Task 1 APK built, engineering checks and task re-review passed; no Owner acceptance performed. Git status: implementation Subjects above; record commits remain separate from implementation commits, and remote checks determine push status. Next action: execute Task 2 for Agent identity, registration and the context machine contract. Prerequisites: Task 2 implementation instruction; reuse the accepted design without Company resources. Acceptance target: mTLS/JWT isolation, positive/negative registration/context checks, cross-project/revocation rejection and verifiable bilingual commits.
