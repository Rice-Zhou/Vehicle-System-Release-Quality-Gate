$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$sourceScript = Join-Path $repositoryRoot "scripts/m2/verify-m25.ps1"
$workflowPath = Join-Path $repositoryRoot ".github/workflows/m2-backend.yml"
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-m25-gate-$([Guid]::NewGuid().ToString('N'))"
$fixtureScriptDirectory = Join-Path $fixtureRoot "scripts/m2"
$fixtureBackendDirectory = Join-Path $fixtureRoot "backend"
$fixtureAcceptanceDirectory = Join-Path $fixtureRoot "docs/governance/acceptance/records"
$fixtureBinDirectory = Join-Path $fixtureRoot "bin"
$originalPath = $env:PATH
$originalTrace = $env:VSRQG_M25_STUB_TRACE
$originalFailurePattern = $env:VSRQG_M25_STUB_FAIL_PATTERN
$originalPerformanceSource = $env:VSRQG_M25_STUB_PERFORMANCE_SOURCE
$originalRecoverySource = $env:VSRQG_M25_STUB_RECOVERY_SOURCE
$originalGithubSha = $env:GITHUB_SHA
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$pwsh = (Get-Process -Id $PID).Path

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$tokens = $null
$parseErrors = $null
$gateAst = [Management.Automation.Language.Parser]::ParseFile($sourceScript, [ref]$tokens, [ref]$parseErrors)
Assert-True ($parseErrors.Count -eq 0) "Unable to parse M2.5 verification gate"
foreach ($functionName in @("Resolve-FixedExecutable", "Invoke-SafeChild")) {
    $definition = @($gateAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $functionName
    }, $true) | Select-Object -First 1)
    Assert-True ($definition.Count -eq 1) "Missing Gate function: $functionName"
    Set-Item -Path "Function:$functionName" -Value $definition[0].Body.GetScriptBlock()
}

function Invoke-Gate {
    param([string[]]$Arguments = @())
    $output = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest @Arguments 2>&1)
    [pscustomobject]@{ Output = $output; Text = $output -join "`n"; ExitCode = $LASTEXITCODE }
}

function Write-PerformanceFixture {
    param([scriptblock]$Mutation)
    $document = [ordered]@{
        schemaVersion = 1
        fixture = [ordered]@{ issues = 20; edges = 2000 }
        samples = 3
        start = [ordered]@{ p50Ms = 11; p95Ms = 12; maxMs = 12; targetP95Ms = 1000; hardLimitMs = 30000 }
        worker = [ordered]@{ p50Ms = 21; p95Ms = 22; maxMs = 22; targetP95Ms = 10000; hardLimitMs = 60000 }
        query = [ordered]@{ p50Ms = 5; p95Ms = 6; maxMs = 6; targetP95Ms = 1000; hardLimitMs = 30000 }
        queryCounts = [ordered]@{ release = 1; header = 1; issues = 1; paths = 1; gaps = 1 }
        hardware = [ordered]@{ processors = 4; maxMemoryBytes = 1024 }
        runtime = [ordered]@{ java = "fixture"; os = "fixture" }
    }
    if ($null -ne $Mutation) { & $Mutation $document }
    $document | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $env:VSRQG_M25_STUB_PERFORMANCE_SOURCE -Encoding utf8NoBOM
}

function Write-RecoveryFixture {
    param([scriptblock]$Mutation)
    $document = [ordered]@{
        schemaVersion = 1
        backupRestore = "PASS"
        replayDigest = "sha256:$('a' * 64)"
        dbRestartReclaim = "PASS"
        deadLetter = "PASS"
        manualRetry = "PASS"
    }
    if ($null -ne $Mutation) { & $Mutation $document }
    $document | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $env:VSRQG_M25_STUB_RECOVERY_SOURCE -Encoding utf8NoBOM
}

try {
    Assert-True (Test-Path -LiteralPath $sourceScript -PathType Leaf) "Missing M2.5 verification gate"
    $workflow = Get-Content -LiteralPath $workflowPath -Raw -ErrorAction Stop
    $installIndex = $workflow.IndexOf("pnpm install --frozen-lockfile", [StringComparison]::Ordinal)
    $gateIndex = $workflow.IndexOf("./scripts/m2/verify-m25.ps1", [StringComparison]::Ordinal)
    Assert-True ($installIndex -ge 0) "M2.5 workflow must install the frozen Node dependency graph"
    Assert-True ($gateIndex -gt $installIndex) "M2.5 workflow must install Node dependencies before the Gate"
    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureBackendDirectory, `
        $fixtureAcceptanceDirectory, $fixtureBinDirectory | Out-Null
    Copy-Item -LiteralPath $sourceScript -Destination $fixtureScriptDirectory
    "backend/build/`nstub-evidence/" | Set-Content -LiteralPath (Join-Path $fixtureRoot ".gitignore") -Encoding utf8NoBOM
    @'
---
recordId: M2-5-OWNER-GATE-001
status: PENDING
owner: PENDING
submittedAt: 2026-09-04T00:00:00Z
decisionAt: PENDING
---
# Fixture
'@ | Set-Content -LiteralPath (Join-Path $fixtureAcceptanceDirectory "2026-09-04-m2-5-owner-gate-001.md") -Encoding utf8NoBOM

    $tracePath = Join-Path $fixtureRoot "child-invocations.txt"
    if ($isWindowsHost) {
@'
@echo off
echo gradle^|%*>>"%VSRQG_M25_STUB_TRACE%"
mkdir backend\build\test-results\test 2>nul
echo ^<testsuite tests="2" skipped="0" failures="0" errors="0" /^> > backend\build\test-results\test\TEST-fixture.xml
echo %* | findstr /C:"TraceabilityVerificationPerformanceTest" >nul
if not errorlevel 1 (
  mkdir backend\build\m2 2>nul
  copy /y "%VSRQG_M25_STUB_PERFORMANCE_SOURCE%" backend\build\m2\traceability-performance.json >nul
)
echo %* | findstr /C:"TraceabilityVerificationRecoveryTest" >nul
if not errorlevel 1 (
  mkdir backend\build\m2 2>nul
  copy /y "%VSRQG_M25_STUB_RECOVERY_SOURCE%" backend\build\m2\traceability-recovery.json >nul
)
echo SYNTHETIC-SECRET bearer-fixture-token
echo %* | findstr /C:"%VSRQG_M25_STUB_FAIL_PATTERN%" >nul
if not "%VSRQG_M25_STUB_FAIL_PATTERN%"=="" if not errorlevel 1 exit /b 23
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew.bat") -Encoding ascii
@'
@echo off
echo node^|%*>>"%VSRQG_M25_STUB_TRACE%"
echo SYNTHETIC-SECRET bearer-fixture-token
echo %* | findstr /C:"%VSRQG_M25_STUB_FAIL_PATTERN%" >nul
if not "%VSRQG_M25_STUB_FAIL_PATTERN%"=="" if not errorlevel 1 exit /b 23
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "node.cmd") -Encoding ascii
@'
@echo off
echo npm-shim^|%*>>"%VSRQG_M25_STUB_TRACE%"
exit /b 86
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "npm.cmd") -Encoding ascii
    } else {
@'
#!/usr/bin/env sh
printf '%s|%s\n' 'gradle' "$*" >> "$VSRQG_M25_STUB_TRACE"
mkdir -p backend/build/test-results/test
printf '%s\n' '<testsuite tests="2" skipped="0" failures="0" errors="0" />' > backend/build/test-results/test/TEST-fixture.xml
case "$*" in *TraceabilityVerificationPerformanceTest*) mkdir -p backend/build/m2; cp "$VSRQG_M25_STUB_PERFORMANCE_SOURCE" backend/build/m2/traceability-performance.json;; esac
case "$*" in *TraceabilityVerificationRecoveryTest*) mkdir -p backend/build/m2; cp "$VSRQG_M25_STUB_RECOVERY_SOURCE" backend/build/m2/traceability-recovery.json;; esac
printf '%s\n' 'SYNTHETIC-SECRET bearer-fixture-token'
case "$*" in *"$VSRQG_M25_STUB_FAIL_PATTERN"*) [ -n "$VSRQG_M25_STUB_FAIL_PATTERN" ] && exit 23;; esac
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew") -Encoding utf8NoBOM
@'
#!/usr/bin/env sh
printf '%s|%s\n' 'node' "$*" >> "$VSRQG_M25_STUB_TRACE"
printf '%s\n' 'SYNTHETIC-SECRET bearer-fixture-token'
case "$*" in *"$VSRQG_M25_STUB_FAIL_PATTERN"*) [ -n "$VSRQG_M25_STUB_FAIL_PATTERN" ] && exit 23;; esac
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "node") -Encoding utf8NoBOM
@'
#!/usr/bin/env sh
printf '%s|%s\n' 'npm-shim' "$*" >> "$VSRQG_M25_STUB_TRACE"
exit 86
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "npm") -Encoding utf8NoBOM
        & chmod +x (Join-Path $fixtureBackendDirectory "gradlew") (Join-Path $fixtureBinDirectory "node") `
            (Join-Path $fixtureBinDirectory "npm")
    }

    $resolutionFailure = Invoke-SafeChild @("vsrqg-m25-definitely-missing-child")
    Assert-True ($resolutionFailure.Category -ceq "RESOLUTION_FAILED") "Resolution failure lost its category"
    Assert-True ($resolutionFailure.Executable -ceq "vsrqg-m25-definitely-missing-child") `
        "Resolution failure exposed an unexpected executable identity"
    Assert-True ($resolutionFailure.ExitCode -eq 1) "Resolution failure lost its fixed exit code"

    $brokenChild = Join-Path $fixtureBinDirectory $(if ($isWindowsHost) { "broken-child.exe" } else { "broken-child" })
    "not an executable" | Set-Content -LiteralPath $brokenChild -Encoding ascii
    if (-not $isWindowsHost) { & chmod +x $brokenChild }
    $startFailure = Invoke-SafeChild @($brokenChild)
    Assert-True ($startFailure.Category -ceq "START_FAILED") "Start failure lost its category"
    Assert-True ($startFailure.Executable -ceq ([IO.Path]::GetFileName($brokenChild))) `
        "Start failure exposed more than the executable basename"
    Assert-True ($startFailure.ExitCode -eq 1) "Start failure lost its fixed exit code"

    $nonzeroChild = Join-Path $fixtureBinDirectory $(if ($isWindowsHost) { "exit-23.cmd" } else { "exit-23" })
    if ($isWindowsHost) {
@'
@echo off
echo bearer-diagnostic-secret
echo C:\private\diagnostic.txt 1>&2
exit /b 23
'@ | Set-Content -LiteralPath $nonzeroChild -Encoding ascii
    } else {
@'
#!/usr/bin/env sh
printf '%s\n' 'bearer-diagnostic-secret'
printf '%s\n' '/home/private/diagnostic.txt' >&2
exit 23
'@ | Set-Content -LiteralPath $nonzeroChild -Encoding utf8NoBOM
        & chmod +x $nonzeroChild
    }
    $nonzero = Invoke-SafeChild @($nonzeroChild)
    Assert-True ($nonzero.Category -ceq "EXIT_NONZERO") "Nonzero child exit lost its category"
    Assert-True ($nonzero.Executable -ceq ([IO.Path]::GetFileName($nonzeroChild))) `
        "Nonzero child exposed more than the executable basename"
    Assert-True ($nonzero.ExitCode -eq 23) "Nonzero child exit code was not preserved"
    Assert-True (($nonzero | ConvertTo-Json -Compress) -notmatch 'bearer-diagnostic-secret|private[/\\]diagnostic') `
        "Child output escaped through the diagnostic result"

    & git -C $fixtureRoot init --quiet
    & git -C $fixtureRoot add .
    & git -C $fixtureRoot -c user.name=fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
    Assert-True ($LASTEXITCODE -eq 0) "Unable to create M2.5 gate fixture"
    $fixtureCommit = (& git -C $fixtureRoot rev-parse HEAD).Trim()
    $env:PATH = "$fixtureBinDirectory$([IO.Path]::PathSeparator)$originalPath"
    $env:VSRQG_M25_STUB_TRACE = $tracePath
    $env:VSRQG_M25_STUB_FAIL_PATTERN = ""
    $stubEvidenceDirectory = Join-Path $fixtureRoot "stub-evidence"
    New-Item -ItemType Directory -Path $stubEvidenceDirectory | Out-Null
    $env:VSRQG_M25_STUB_PERFORMANCE_SOURCE = Join-Path $stubEvidenceDirectory "performance.json"
    $env:VSRQG_M25_STUB_RECOVERY_SOURCE = Join-Path $stubEvidenceDirectory "recovery.json"
    Write-PerformanceFixture
    Write-RecoveryFixture
    $env:GITHUB_SHA = $fixtureCommit
    $scriptUnderTest = Join-Path $fixtureScriptDirectory "verify-m25.ps1"

    $injected = Invoke-Gate -Arguments @("-InjectFailure", "transaction")
    Assert-True ($injected.ExitCode -ne 0) "Injected failure must fail the complete gate"
    $actualOrder = @($injected.Output | Where-Object { $_ -match '^CHECK ' } | ForEach-Object { ($_ -split ' ')[1] })
    $expectedOrder = @(
        "clean-tree", "fixed-commit", "contract", "migration", "domain", "transaction",
        "concurrency", "replay", "performance", "secret", "acceptance", "evidence-digest"
    )
    Assert-True (($actualOrder -join ',') -ceq ($expectedOrder -join ',')) "Gate check order changed"
    Assert-True ($injected.Text -match "CHECK transaction FAILED") "Injected failure lost its check"
    Assert-True ($injected.Text -match "STATUS FAILED") "Injected failure lost total status"
    Assert-True ($injected.Text -notmatch "SYNTHETIC-SECRET|bearer-fixture-token") "Raw child output escaped"
    Assert-True (-not $injected.Text.Contains($fixtureRoot, [StringComparison]::OrdinalIgnoreCase)) "Absolute path escaped"

    $evidencePath = Join-Path $fixtureRoot "backend/build/m2/m2-5-evidence.json"
    $digestPath = "$evidencePath.sha256"
    Assert-True (Test-Path -LiteralPath $evidencePath -PathType Leaf) "Failure did not produce evidence summary"
    Assert-True (Test-Path -LiteralPath $digestPath -PathType Leaf) "Failure did not produce evidence digest"
    $failedEvidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    Assert-True ($failedEvidence.status -ceq "FAILED") "Failure evidence status was not FAILED"
    Assert-True ($failedEvidence.exactCommit -ceq $fixtureCommit) "Evidence did not bind exact commit"
    Assert-True ($failedEvidence.migrationVersion -ceq "V11") "Evidence lost migration version"
    Assert-True ($failedEvidence.checks.Count -eq 12) "Evidence did not retain every check"
    Assert-True (($failedEvidence.checks | Where-Object name -eq transaction).status -ceq "FAILED") "Evidence hid failing check"
    Assert-True ((Get-Content -LiteralPath $evidencePath -Raw) -notmatch "bearer-fixture-token") "Evidence leaked child output"
    $actualDigest = "sha256:$((Get-FileHash -LiteralPath $evidencePath -Algorithm SHA256).Hash.ToLowerInvariant())"
    Assert-True ((Get-Content -LiteralPath $digestPath -Raw).Trim() -ceq $actualDigest) "Evidence sidecar digest mismatch"

    & git -C $fixtureRoot status --porcelain | Out-Null
    "dirty" | Set-Content -LiteralPath (Join-Path $fixtureRoot "dirty.txt") -Encoding ascii
    $dirty = Invoke-Gate
    Assert-True ($dirty.ExitCode -ne 0) "Dirty worktree must fail closed"
    Assert-True ($dirty.Text -match "CHECK clean-tree FAILED.*diagnostic=WORKTREE_DIRTY") "Dirty failure lost diagnostic"
    Remove-Item -LiteralPath (Join-Path $fixtureRoot "dirty.txt") -Force

    $env:GITHUB_SHA = "0" * 40
    $mismatch = Invoke-Gate
    Assert-True ($mismatch.ExitCode -ne 0) "Mismatched GitHub commit must fail closed"
    Assert-True ($mismatch.Text -match "CHECK fixed-commit FAILED.*diagnostic=EXACT_HEAD_MISMATCH") "Commit mismatch lost diagnostic"

    $env:GITHUB_SHA = $fixtureCommit
    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $success = Invoke-Gate
    Assert-True ($success.ExitCode -eq 0) "Successful checks must pass"
    Assert-True ($success.Text -match "SUMMARY total=12 passed=12 failed=0") "Success summary count changed"
    Assert-True ($success.Text -match "STATUS PASS") "Success status missing"
    $successEvidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    Assert-True ($successEvidence.status -ceq "PASS") "Success evidence status was not PASS"
    Assert-True ($successEvidence.performance.fixture.issues -eq 20) "Evidence reduced issue fixture"
    Assert-True ($successEvidence.performance.fixture.edges -eq 2000) "Evidence reduced edge fixture"
    Assert-True ($successEvidence.recovery.backupRestore -ceq "PASS") "Recovery evidence missing: $((Get-Content -LiteralPath $evidencePath -Raw))"
    Assert-True ($successEvidence.replayDigest -match '^sha256:[0-9a-f]{64}$') "Replay digest missing"

    $invalidEvidence = @(
        @{ Name = "secret"; Prepare = { Write-PerformanceFixture { param($d) $d.token = "bearer-fixture-token" } } },
        @{ Name = "windows path"; Prepare = { Write-PerformanceFixture { param($d) $d.debugPath = "C:\\private\\fixture.txt" } } },
        @{ Name = "unix path"; Prepare = { Write-RecoveryFixture { param($d) $d.debugPath = "/home/private/fixture.txt" } } }
    )
    foreach ($case in $invalidEvidence) {
        Write-PerformanceFixture
        Write-RecoveryFixture
        & $case.Prepare
        $invalid = Invoke-Gate
        Assert-True ($invalid.ExitCode -ne 0) "$($case.Name) evidence must fail closed"
        Assert-True ($invalid.Text -match "diagnostic=EVIDENCE_INVALID") "$($case.Name) lost fixed evidence diagnostic"
        $summary = Get-Content -LiteralPath $evidencePath -Raw
        Assert-True ($summary -notmatch 'bearer-fixture-token|private[/\\]fixture') "$($case.Name) leaked into total evidence"
        foreach ($childReport in @(
            (Join-Path $fixtureRoot "backend/build/m2/traceability-performance.json"),
            (Join-Path $fixtureRoot "backend/build/m2/traceability-recovery.json")
        )) {
            if (Test-Path -LiteralPath $childReport) {
                Assert-True ((Get-Content -LiteralPath $childReport -Raw) -notmatch 'bearer-fixture-token|private[/\\]fixture') `
                    "$($case.Name) remained in an uploadable child report"
            }
        }
    }

    Write-PerformanceFixture
    Write-RecoveryFixture
    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M25_STUB_FAIL_PATTERN = "TraceabilityVerificationConcurrencyTest"
    $childFailure = Invoke-Gate
    Assert-True ($childFailure.ExitCode -eq 23) "Real child exit code was not preserved"
    Assert-True ($childFailure.Text -match "CHECK concurrency FAILED") "Real child failure lost its check"
    $expectedGradleIdentity = if ($isWindowsHost) { "gradlew.bat" } else { "gradlew" }
    Assert-True ($childFailure.Text -match "CHILD check=concurrency executable=$([Regex]::Escape($expectedGradleIdentity)) category=EXIT_NONZERO exit=23") `
        "Real child failure lost its safe process diagnostic"
    Assert-True (@($childFailure.Output | Where-Object { $_ -match '^CHECK ' }).Count -eq 12) `
        "Real child failure stopped later checks"
    Assert-True ($childFailure.Text -match "CHECK acceptance PASS") "Real child failure did not reach acceptance"
    Assert-True ($childFailure.Text -match "STATUS FAILED") "Real child failure lost total status"
    Assert-True ((Test-Path -LiteralPath $evidencePath) -and (Test-Path -LiteralPath $digestPath)) `
        "Real child failure did not generate evidence and digest"
    $realFailureEvidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    Assert-True ($realFailureEvidence.status -ceq "FAILED") "Real child failure evidence was not FAILED"
    Assert-True (($realFailureEvidence.checks | Where-Object name -eq concurrency).status -ceq "FAILED") `
        "Real child failure was hidden in evidence"
    Assert-True (($realFailureEvidence.checks | Where-Object name -eq concurrency).diagnostic -ceq "EXIT_NONZERO") `
        "Real child failure lost its fixed Evidence diagnostic"
    $realFailureInvocations = @(Get-Content -LiteralPath $tracePath)
    Assert-True ($realFailureInvocations[-1] -ceq "node|scripts/acceptance-record-validator.mjs") `
        "Real child failure did not execute all later stub commands"
    $env:VSRQG_M25_STUB_FAIL_PATTERN = ""

    $ownerRecord = Get-Content -LiteralPath (Join-Path $fixtureAcceptanceDirectory "2026-09-04-m2-5-owner-gate-001.md") -Raw
    Assert-True ($ownerRecord -match '(?m)^status: PENDING$') "Owner fixture must remain PENDING"
    $expectedInvocations = @(
        "gradle|-p backend test --tests *M2ApiContractTest --rerun-tasks",
        "node|scripts/contract-validator.mjs",
        "gradle|-p backend test --tests *TraceabilityVerificationMigrationTest --rerun-tasks",
        "gradle|-p backend test --tests *TraceabilityVerifierTest --tests *TraceabilityCanonicalizerTest --rerun-tasks",
        "gradle|-p backend test --tests *TraceabilityVerificationStartIntegrationTest --tests *TraceabilityVerificationStartFailureTest --tests *TraceabilityVerificationWorkerFailureTest --rerun-tasks",
        "gradle|-p backend test --tests *TraceabilityVerificationConcurrencyTest --rerun-tasks",
        "gradle|-p backend test --tests *TraceabilityReplayTest --rerun-tasks",
        "gradle|-p backend test --tests *TraceabilityVerificationRecoveryTest --rerun-tasks",
        "gradle|-p backend test --tests *TraceabilityVerificationPerformanceTest --rerun-tasks",
        "gradle|-p backend test --tests *SecurityAcceptanceTest --rerun-tasks",
        "node|scripts/acceptance-record-validator.mjs"
    )
    $actualInvocations = @(Get-Content -LiteralPath $tracePath)
    Assert-True (($actualInvocations -join "`n") -ceq ($expectedInvocations -join "`n")) "Gate command binding changed"
    Assert-True (-not ($actualInvocations -match '^npm-shim\|')) "Gate executed the npm shell shim"

    Write-Output "PASS m2-5-verify-gates"
} finally {
    $env:PATH = $originalPath
    $env:VSRQG_M25_STUB_TRACE = $originalTrace
    $env:VSRQG_M25_STUB_FAIL_PATTERN = $originalFailurePattern
    $env:VSRQG_M25_STUB_PERFORMANCE_SOURCE = $originalPerformanceSource
    $env:VSRQG_M25_STUB_RECOVERY_SOURCE = $originalRecoverySource
    $env:GITHUB_SHA = $originalGithubSha
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
