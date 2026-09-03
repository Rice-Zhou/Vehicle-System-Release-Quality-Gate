$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$sourceScript = Join-Path $repositoryRoot "scripts/m2/verify-build-provenance.ps1"
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-m24-gate-$([Guid]::NewGuid().ToString('N'))"
$fixtureScriptDirectory = Join-Path $fixtureRoot "scripts/m2"
$fixtureBackendDirectory = Join-Path $fixtureRoot "backend"
$fixtureBinDirectory = Join-Path $fixtureRoot "bin"
$originalPath = $env:PATH
$originalTrace = $env:VSRQG_M24_STUB_TRACE
$originalFailurePattern = $env:VSRQG_M24_STUB_FAIL_PATTERN
$githubVariables = @(
    "GITHUB_ACTIONS",
    "GITHUB_REPOSITORY",
    "GITHUB_SHA",
    "GITHUB_WORKFLOW_REF",
    "GITHUB_RUN_ID",
    "GITHUB_RUN_ATTEMPT",
    "GITHUB_JOB"
)
$originalGithub = @{}
foreach ($name in $githubVariables) {
    $originalGithub[$name] = [Environment]::GetEnvironmentVariable($name)
}
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$pwsh = (Get-Process -Id $PID).Path

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Set-GithubFixtureContext {
    $env:GITHUB_ACTIONS = "true"
    $env:GITHUB_REPOSITORY = "owner/repository"
    $env:GITHUB_SHA = "0123456789abcdef0123456789abcdef01234567"
    $env:GITHUB_WORKFLOW_REF = "owner/repository/.github/workflows/m1-backend.yml@refs/heads/test"
    $env:GITHUB_RUN_ID = "33705417856"
    $env:GITHUB_RUN_ATTEMPT = "1"
    $env:GITHUB_JOB = "verify"
}

try {
    Assert-True (Test-Path -LiteralPath $sourceScript -PathType Leaf) "Missing M2.4 build provenance gate"
    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureBackendDirectory, $fixtureBinDirectory | Out-Null
    Copy-Item -LiteralPath $sourceScript -Destination $fixtureScriptDirectory
    $tracePath = Join-Path $fixtureRoot "child-invocations.txt"

    if ($isWindowsHost) {
        @'
@echo off
echo gradle^|%*>>"%VSRQG_M24_STUB_TRACE%"
mkdir backend\build\test-results\test 2>nul
echo ^<testsuite tests="1" skipped="0" failures="0" errors="0" /^> > backend\build\test-results\test\TEST-fixture.xml
echo SYNTHETIC-UNSAFE-CHILD-OUTPUT
echo %* | findstr /C:"%VSRQG_M24_STUB_FAIL_PATTERN%" >nul
if not "%VSRQG_M24_STUB_FAIL_PATTERN%"=="" if not errorlevel 1 exit /b 23
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew.bat") -Encoding ascii
        @'
@echo off
echo npm^|%*>>"%VSRQG_M24_STUB_TRACE%"
echo SYNTHETIC-UNSAFE-CHILD-OUTPUT
echo %* | findstr /C:"%VSRQG_M24_STUB_FAIL_PATTERN%" >nul
if not "%VSRQG_M24_STUB_FAIL_PATTERN%"=="" if not errorlevel 1 exit /b 23
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "npm.cmd") -Encoding ascii
    } else {
        @'
#!/usr/bin/env sh
printf '%s|%s\n' 'gradle' "$*" >> "$VSRQG_M24_STUB_TRACE"
mkdir -p backend/build/test-results/test
printf '%s\n' '<testsuite tests="1" skipped="0" failures="0" errors="0" />' > backend/build/test-results/test/TEST-fixture.xml
printf '%s\n' 'SYNTHETIC-UNSAFE-CHILD-OUTPUT'
case "$*" in *"$VSRQG_M24_STUB_FAIL_PATTERN"*) [ -n "$VSRQG_M24_STUB_FAIL_PATTERN" ] && exit 23;; esac
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew") -Encoding utf8NoBOM
        @'
#!/usr/bin/env sh
printf '%s|%s\n' 'npm' "$*" >> "$VSRQG_M24_STUB_TRACE"
printf '%s\n' 'SYNTHETIC-UNSAFE-CHILD-OUTPUT'
case "$*" in *"$VSRQG_M24_STUB_FAIL_PATTERN"*) [ -n "$VSRQG_M24_STUB_FAIL_PATTERN" ] && exit 23;; esac
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "npm") -Encoding utf8NoBOM
        & chmod +x (Join-Path $fixtureBackendDirectory "gradlew") (Join-Path $fixtureBinDirectory "npm")
    }

    & git -C $fixtureRoot init --quiet
    & git -C $fixtureRoot -c core.autocrlf=false add .
    & git -C $fixtureRoot -c user.name=fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
    Assert-True ($LASTEXITCODE -eq 0) "Unable to create M2.4 gate fixture"
    $env:PATH = "$fixtureBinDirectory$([IO.Path]::PathSeparator)$originalPath"
    $env:VSRQG_M24_STUB_TRACE = $tracePath
    $env:VSRQG_M24_STUB_FAIL_PATTERN = ""
    Set-GithubFixtureContext
    $scriptUnderTest = Join-Path $fixtureScriptDirectory "verify-build-provenance.ps1"

    $injectedOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -InjectFailure transaction 2>&1)
    $injectedExit = $LASTEXITCODE
    $injectedText = $injectedOutput -join "`n"
    Assert-True ($injectedExit -ne 0) "Injected transaction failure must fail closed"
    Assert-True ($injectedText -match "CHECK transaction FAILED") "Injected failure did not name transaction"
    $actualOrder = @($injectedOutput | Where-Object { $_ -match '^CHECK ' } | ForEach-Object { ($_ -split ' ')[1] })
    $expectedOrder = @("contract", "migration", "canonical", "validator", "repository", "transaction", "security", "github-smoke", "contracts", "acceptance")
    Assert-True (($actualOrder -join ',') -ceq ($expectedOrder -join ',')) "Gate check order changed"
    Assert-True ($injectedText -notmatch "SYNTHETIC-UNSAFE-CHILD-OUTPUT") "Raw child output escaped the safe gate grammar"
    Assert-True (-not $injectedText.Contains($fixtureRoot, [StringComparison]::OrdinalIgnoreCase)) "Gate output exposed an absolute path"

    $env:GITHUB_JOB = $null
    $missingSmokeOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
    $missingSmokeExit = $LASTEXITCODE
    $missingSmokeText = $missingSmokeOutput -join "`n"
    Assert-True ($missingSmokeExit -ne 0) "Missing required GitHub smoke context must fail closed"
    Assert-True ($missingSmokeText -match "CHECK github-smoke FAILED") "Missing live smoke did not name github-smoke"
    Assert-True ($missingSmokeText -match "diagnostic=GITHUB_CONTEXT_MISSING") "Missing live smoke lost its fixed diagnostic"

    Set-GithubFixtureContext
    $env:VSRQG_M24_STUB_FAIL_PATTERN = "BuildProvenanceTransactionFailureTest"
    $childFailureOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    $childFailureExit = $LASTEXITCODE
    $childFailureText = $childFailureOutput -join "`n"
    Assert-True ($childFailureExit -eq 23) "Real child failure exit code was not preserved"
    Assert-True ($childFailureText -match "CHECK transaction FAILED") "Real child failure lost its check name"
    Assert-True ($childFailureText -notmatch "SYNTHETIC-UNSAFE-CHILD-OUTPUT") "Real child failure exposed raw output"

    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M24_STUB_FAIL_PATTERN = ""
    $successOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
    $successExit = $LASTEXITCODE
    $successText = $successOutput -join "`n"
    Assert-True ($successExit -eq 0) "All successful checks must pass the gate"
    Assert-True ($successText -match "SUMMARY total=10 passed=10 failed=0") "Fixed success summary is missing"
    Assert-True ($successText -match "STATUS PASS") "Success status is missing"

    $expectedInvocations = @(
        "gradle|-p backend test --tests *M2ApiContractTest",
        "gradle|-p backend test --tests *BuildProvenanceMigrationTest",
        "gradle|-p backend test --tests *BuildProvenanceCanonicalizerTest",
        "gradle|-p backend test --tests *GithubActionsBuildProvenanceValidatorTest",
        "gradle|-p backend test --tests *BuildProvenanceRepositoryIntegrationTest",
        "gradle|-p backend test --tests *BuildProvenanceIntegrationTest --tests *BuildProvenanceTransactionFailureTest",
        "gradle|-p backend test --tests *SecurityAcceptanceTest --tests *PermissionMatrixTest",
        "gradle|-p backend test --tests *BuildProvenanceGithubSmokeTest",
        "npm|run test:contracts",
        "npm|run verify:acceptance"
    )
    $actualInvocations = @(Get-Content -LiteralPath $tracePath)
    Assert-True (($actualInvocations -join "`n") -ceq ($expectedInvocations -join "`n")) "Gate command or argument binding changed"

    Write-Output "PASS m2-build-provenance-gates"
} finally {
    $env:PATH = $originalPath
    $env:VSRQG_M24_STUB_TRACE = $originalTrace
    $env:VSRQG_M24_STUB_FAIL_PATTERN = $originalFailurePattern
    foreach ($name in $githubVariables) {
        [Environment]::SetEnvironmentVariable($name, $originalGithub[$name])
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
