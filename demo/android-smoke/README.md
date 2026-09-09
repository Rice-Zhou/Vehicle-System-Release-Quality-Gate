# Android Smoke Demonstration APK

This minimal Java APK supports the VSRQG single-device Smoke flow. One native `Activity` and `TextView` display synthetic demonstration markers, without network permissions, services, background tasks, dependency injection or Compose.

## Toolchain

- JDK 17
- Android Gradle Plugin 8.7.3
- Gradle Wrapper 8.9
- compileSdk / targetSdk 35
- minSdk 26
- Android Build Tools 34.0.0
- JUnit 4.13.2

Set `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`, then run from this directory:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Inputs and Outputs

The entry point is `com.ricezhou.vsrqg.smoke/.SmokeActivity`, accepting two Intent extras:

- `attemptId`: a complete lowercase standard UUID string.
- `mode`: `normal` or `assertion-failure`.

`normal` displays `SYNTHETIC_DEMO` and `VSRQG_SMOKE_READY:<attemptId>`; `assertion-failure` displays `SYNTHETIC_DEMO` and `VSRQG_SMOKE_NOT_READY:<attemptId>`. Invalid input displays only `SMOKE_INPUT_INVALID` and finishes the Activity.

## Verification Boundary

Unit tests, lint, build and signature checks here verify only the APK build artifact and pure input-function contract. This directory performs no ADB or device commands. Later real-device tasks verify installation, launch, rotation, `onNewIntent` and actual UI behavior; unit tests cannot replace those checks.
