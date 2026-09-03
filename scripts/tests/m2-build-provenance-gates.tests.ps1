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
$originalSkipEvidence = $env:VSRQG_M24_STUB_SKIP_EVIDENCE
$originalEvidenceCommit = $env:VSRQG_M24_STUB_EVIDENCE_COMMIT
$originalEvidenceSource = $env:VSRQG_M24_STUB_EVIDENCE_SOURCE
$originalEvidenceTemp = $env:VSRQG_M24_STUB_EVIDENCE_TEMP
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
    param([string]$Commit)
    $env:GITHUB_ACTIONS = "true"
    $env:GITHUB_REPOSITORY = "owner/repository"
    $env:GITHUB_SHA = $Commit
    $env:GITHUB_WORKFLOW_REF = "owner/repository/.github/workflows/m1-backend.yml@refs/heads/test"
    $env:GITHUB_RUN_ID = "33705417856"
    $env:GITHUB_RUN_ATTEMPT = "1"
    $env:GITHUB_JOB = "verify"
}

function Write-EvidenceFixture {
    param([scriptblock]$Mutation)
    $document = [ordered]@{
        schemaVersion = 2
        exactCommit = $fixtureCommit
        runId = $env:GITHUB_RUN_ID
        runAttempt = 1
        validatorVersion = "github-actions-provenance/v1"
        envelopeDigest = "sha256:$('a' * 64)"
        artifactDigest = "sha256:$('b' * 64)"
        edgeRevisionIds = @(
            [ordered]@{ edgeType = "ISSUE_COMMIT"; edgeId = "ted_fixture1"; revisionId = "icr_fixture1" },
            [ordered]@{ edgeType = "COMMIT_BUILD"; edgeId = "ted_fixture2"; revisionId = "cbr_fixture2" },
            [ordered]@{ edgeType = "BUILD_ARTIFACT"; edgeId = "ted_fixture3"; revisionId = "bar_fixture3" }
        )
        replayResults = [ordered]@{ sameIdempotencyKey = $true; differentIdempotencyKey = $true }
        fixedDiagnostics = @("BUILD_PROVENANCE_CONFLICT", "PROJECT_SCOPE_MISMATCH")
        testCounts = [ordered]@{
            acceptedRequests = 3; rejectedRequests = 3; receipts = 1; rejectedReceipts = 1
            edgeIdentities = 3; edgeRevisions = 3; auditEvents = 2; outboxEvents = 1
            artifactReleaseEdges = 0
        }
    }
    if ($null -ne $Mutation) { & $Mutation $document }
    $document | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $env:VSRQG_M24_STUB_EVIDENCE_SOURCE -Encoding utf8NoBOM
}

try {
    Assert-True (Test-Path -LiteralPath $sourceScript -PathType Leaf) "Missing M2.4 build provenance gate"
    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureBackendDirectory, $fixtureBinDirectory | Out-Null
    Copy-Item -LiteralPath $sourceScript -Destination $fixtureScriptDirectory
    $tracePath = Join-Path $fixtureRoot "child-invocations.txt"

    if ($isWindowsHost) {
@'
@echo off
setlocal EnableDelayedExpansion
echo gradle^|%*>>"%VSRQG_M24_STUB_TRACE%"
mkdir backend\build\test-results\test 2>nul
echo ^<testsuite tests="1" skipped="0" failures="0" errors="0" /^> > backend\build\test-results\test\TEST-fixture.xml
echo %* | findstr /C:"BuildProvenanceGithubSmokeTest" >nul
if not errorlevel 1 (
  echo ^<testsuite name="com.ricezhou.vsrqg.traceability.BuildProvenanceGithubSmokeTest" tests="1" skipped="0" failures="0" errors="0" /^> > backend\build\test-results\test\TEST-com.ricezhou.vsrqg.traceability.BuildProvenanceGithubSmokeTest.xml
  if "%VSRQG_M24_STUB_SKIP_EVIDENCE%"=="" (
    mkdir backend\build\m2 2>nul
    copy /y "%VSRQG_M24_STUB_EVIDENCE_SOURCE%" backend\build\m2\build-provenance-smoke.json >nul
    if not "%VSRQG_M24_STUB_EVIDENCE_TEMP%"=="" echo partial>backend\build\m2\build-provenance-smoke.json.fixture.tmp
  )
)
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
case "$*" in *BuildProvenanceGithubSmokeTest*)
  printf '%s\n' '<testsuite name="com.ricezhou.vsrqg.traceability.BuildProvenanceGithubSmokeTest" tests="1" skipped="0" failures="0" errors="0" />' > backend/build/test-results/test/TEST-com.ricezhou.vsrqg.traceability.BuildProvenanceGithubSmokeTest.xml
  if [ -z "$VSRQG_M24_STUB_SKIP_EVIDENCE" ]; then
    mkdir -p backend/build/m2
    cp "$VSRQG_M24_STUB_EVIDENCE_SOURCE" backend/build/m2/build-provenance-smoke.json
    [ -z "$VSRQG_M24_STUB_EVIDENCE_TEMP" ] || printf partial > backend/build/m2/build-provenance-smoke.json.fixture.tmp
  fi;;
esac
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
    $fixtureCommit = (& git -C $fixtureRoot rev-parse HEAD).Trim()
    $env:PATH = "$fixtureBinDirectory$([IO.Path]::PathSeparator)$originalPath"
    $env:VSRQG_M24_STUB_TRACE = $tracePath
    $env:VSRQG_M24_STUB_FAIL_PATTERN = ""
    $env:VSRQG_M24_STUB_SKIP_EVIDENCE = ""
    $env:VSRQG_M24_STUB_EVIDENCE_COMMIT = ""
    $env:VSRQG_M24_STUB_EVIDENCE_SOURCE = Join-Path $fixtureRoot "evidence-source.json"
    $env:VSRQG_M24_STUB_EVIDENCE_TEMP = ""
    Set-GithubFixtureContext -Commit $fixtureCommit
    Write-EvidenceFixture
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

    Set-GithubFixtureContext -Commit ("0" * 40)
    $headMismatchOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
    $headMismatchExit = $LASTEXITCODE
    $headMismatchText = $headMismatchOutput -join "`n"
    Assert-True ($headMismatchExit -ne 0) "Checkout and GITHUB_SHA mismatch must fail closed"
    Assert-True ($headMismatchText -match "CHECK github-smoke FAILED.*diagnostic=EXACT_HEAD_MISMATCH") "Exact-head mismatch lost its fixed diagnostic"

    Set-GithubFixtureContext -Commit $fixtureCommit
    $env:VSRQG_M24_STUB_FAIL_PATTERN = "BuildProvenanceTransactionFailureTest"
    $childFailureOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    $childFailureExit = $LASTEXITCODE
    $childFailureText = $childFailureOutput -join "`n"
    Assert-True ($childFailureExit -eq 23) "Real child failure exit code was not preserved"
    Assert-True ($childFailureText -match "CHECK transaction FAILED") "Real child failure lost its check name"
    Assert-True ($childFailureText -notmatch "SYNTHETIC-UNSAFE-CHILD-OUTPUT") "Real child failure exposed raw output"

    $staleEvidence = Join-Path $fixtureRoot "backend/build/m2/build-provenance-smoke.json"
    New-Item -ItemType Directory -Path (Split-Path $staleEvidence) -Force | Out-Null
    "stale" | Set-Content -LiteralPath $staleEvidence -Encoding utf8NoBOM
    $env:VSRQG_M24_STUB_FAIL_PATTERN = ""
    $env:VSRQG_M24_STUB_SKIP_EVIDENCE = "1"
    $missingEvidenceOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
    $missingEvidenceExit = $LASTEXITCODE
    $missingEvidenceText = $missingEvidenceOutput -join "`n"
    Assert-True ($missingEvidenceExit -ne 0) "A successful child without fresh Smoke Evidence must fail closed"
    Assert-True ($missingEvidenceText -match "CHECK github-smoke FAILED.*diagnostic=EVIDENCE_MISSING") "Missing Smoke Evidence lost its fixed diagnostic"
    Assert-True (-not (Test-Path -LiteralPath $staleEvidence)) "The gate retained stale Smoke Evidence"

    $env:VSRQG_M24_STUB_SKIP_EVIDENCE = ""
    Write-EvidenceFixture { param($document) $document.exactCommit = "f" * 40 }
    $forgedEvidenceOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
    $forgedEvidenceExit = $LASTEXITCODE
    $forgedEvidenceText = $forgedEvidenceOutput -join "`n"
    Assert-True ($forgedEvidenceExit -ne 0) "Fresh Evidence with a forged context must fail closed"
    Assert-True ($forgedEvidenceText -match "CHECK github-smoke FAILED.*diagnostic=EVIDENCE_CONTEXT_MISMATCH") "Forged Evidence context lost its fixed diagnostic"

    $invalidCases = @(
        @{ Name = "missing field"; Mutate = { param($d) $d.Remove("validatorVersion") } },
        @{ Name = "extra sensitive field"; Mutate = { param($d) $d.token = "must-not-leak" } },
        @{ Name = "wrong boolean"; Mutate = { param($d) $d.replayResults.sameIdempotencyKey = "true" } },
        @{ Name = "wrong count"; Mutate = { param($d) $d.testCounts.receipts = 2 } },
        @{ Name = "wrong digest"; Mutate = { param($d) $d.envelopeDigest = "sha256:not-a-digest" } },
        @{ Name = "wrong cardinality"; Mutate = { param($d) $d.edgeRevisionIds = @($d.edgeRevisionIds[0], $d.edgeRevisionIds[1]) } }
    )
    foreach ($case in $invalidCases) {
        Write-EvidenceFixture $case.Mutate
        $invalidOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
        Assert-True ($LASTEXITCODE -ne 0) "Invalid Evidence ($($case.Name)) must fail closed"
        Assert-True (($invalidOutput -join "`n") -match "CHECK github-smoke FAILED.*diagnostic=EVIDENCE_INVALID") "Invalid Evidence ($($case.Name)) lost its fixed diagnostic"
        Assert-True (($invalidOutput -join "`n") -notmatch "must-not-leak") "Invalid Evidence exposed a sensitive value"
    }

    Write-EvidenceFixture
    $env:VSRQG_M24_STUB_EVIDENCE_TEMP = "1"
    $leftoverOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -RequireGithubSmoke 2>&1)
    Assert-True ($LASTEXITCODE -ne 0) "A leftover temporary Evidence file must fail closed"
    Assert-True (($leftoverOutput -join "`n") -match "CHECK github-smoke FAILED.*diagnostic=EVIDENCE_INVALID") "Temporary Evidence leftover lost its fixed diagnostic"
    $env:VSRQG_M24_STUB_EVIDENCE_TEMP = ""

    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M24_STUB_FAIL_PATTERN = ""
    $env:VSRQG_M24_STUB_SKIP_EVIDENCE = ""
    $env:VSRQG_M24_STUB_EVIDENCE_COMMIT = ""
    Write-EvidenceFixture
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
    $env:VSRQG_M24_STUB_SKIP_EVIDENCE = $originalSkipEvidence
    $env:VSRQG_M24_STUB_EVIDENCE_COMMIT = $originalEvidenceCommit
    $env:VSRQG_M24_STUB_EVIDENCE_SOURCE = $originalEvidenceSource
    $env:VSRQG_M24_STUB_EVIDENCE_TEMP = $originalEvidenceTemp
    foreach ($name in $githubVariables) {
        [Environment]::SetEnvironmentVariable($name, $originalGithub[$name])
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
