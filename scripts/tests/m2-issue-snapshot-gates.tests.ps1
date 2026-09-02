$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$sourceScript = Join-Path $repositoryRoot "scripts/m2/verify-issue-snapshot.ps1"
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-m23-gate-$([Guid]::NewGuid().ToString('N'))"
$fixtureScriptDirectory = Join-Path $fixtureRoot "scripts/m2"
$fixtureBackendDirectory = Join-Path $fixtureRoot "backend"
$fixtureBinDirectory = Join-Path $fixtureRoot "bin"
$originalPath = $env:PATH
$originalTrace = $env:VSRQG_M23_STUB_TRACE
$originalFailurePattern = $env:VSRQG_M23_STUB_FAIL_PATTERN
$originalMalformedPattern = $env:VSRQG_M23_STUB_MALFORMED_PATTERN
$originalLargePattern = $env:VSRQG_M23_STUB_LARGE_PATTERN
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$pwsh = (Get-Process -Id $PID).Path

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

try {
    $productionGate = Get-Content -LiteralPath $sourceScript -Raw
    Assert-True ($productionGate -notmatch 'VSRQG_M23_STUB_') "Production gate must not depend on fixture controls"
    Assert-True ($productionGate -notmatch '\$captured|ReadToEnd|-join "`n"') "Production gate must stream and discard child output"
    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureBackendDirectory, $fixtureBinDirectory | Out-Null
    Copy-Item -LiteralPath $sourceScript -Destination $fixtureScriptDirectory
    $tracePath = Join-Path $fixtureRoot "child-invocations.txt"
    if ($isWindowsHost) {
        @'
@echo off
echo ./backend/gradlew.bat^|%*>>"%VSRQG_M23_STUB_TRACE%"
echo SYNTHETIC-UNSAFE-CHILD-STDOUT
echo SYNTHETIC-UNSAFE-CHILD-STDERR 1>&2
echo %* | findstr /C:"%VSRQG_M23_STUB_MALFORMED_PATTERN%" >nul
if not "%VSRQG_M23_STUB_MALFORMED_PATTERN%"=="" if not errorlevel 1 (
  mkdir backend\build\test-results\test 2>nul
  echo ^<testsuite tests="broken"^> > backend\build\test-results\test\TEST-malformed.xml
)
echo %* | findstr /C:"%VSRQG_M23_STUB_LARGE_PATTERN%" >nul
if not "%VSRQG_M23_STUB_LARGE_PATTERN%"=="" if not errorlevel 1 for /L %%i in (1,1,4000) do (
  echo XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
  echo YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY 1>&2
)
echo %* | findstr /C:"%VSRQG_M23_STUB_FAIL_PATTERN%" >nul
if not "%VSRQG_M23_STUB_FAIL_PATTERN%"=="" if not errorlevel 1 exit /b 23
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew.bat") -Encoding ascii
        @'
@echo off
echo pnpm.cmd^|%*>>"%VSRQG_M23_STUB_TRACE%"
echo SYNTHETIC-UNSAFE-CHILD-STDOUT
echo SYNTHETIC-UNSAFE-CHILD-STDERR 1>&2
echo %* | findstr /C:"%VSRQG_M23_STUB_FAIL_PATTERN%" >nul
if not "%VSRQG_M23_STUB_FAIL_PATTERN%"=="" if not errorlevel 1 exit /b 23
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "pnpm.cmd") -Encoding ascii
    } else {
        @'
#!/usr/bin/env sh
printf '%s|%s\n' './backend/gradlew' "$*" >> "$VSRQG_M23_STUB_TRACE"
printf '%s\n' 'SYNTHETIC-UNSAFE-CHILD-STDOUT'
printf '%s\n' 'SYNTHETIC-UNSAFE-CHILD-STDERR' >&2
case "$*" in *"$VSRQG_M23_STUB_MALFORMED_PATTERN"*)
  if [ -n "$VSRQG_M23_STUB_MALFORMED_PATTERN" ]; then mkdir -p backend/build/test-results/test; printf '%s\n' '<testsuite tests="broken">' > backend/build/test-results/test/TEST-malformed.xml; fi;;
esac
case "$*" in *"$VSRQG_M23_STUB_LARGE_PATTERN"*)
  if [ -n "$VSRQG_M23_STUB_LARGE_PATTERN" ]; then i=0; while [ "$i" -lt 4000 ]; do printf '%0256d\n' 0; printf '%0256d\n' 1 >&2; i=$((i+1)); done; fi;;
esac
case "$*" in *"$VSRQG_M23_STUB_FAIL_PATTERN"*) [ -n "$VSRQG_M23_STUB_FAIL_PATTERN" ] && exit 23;; esac
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew") -Encoding utf8NoBOM
        @'
#!/usr/bin/env sh
printf '%s|%s\n' 'pnpm' "$*" >> "$VSRQG_M23_STUB_TRACE"
printf '%s\n' 'SYNTHETIC-UNSAFE-CHILD-STDOUT'
printf '%s\n' 'SYNTHETIC-UNSAFE-CHILD-STDERR' >&2
case "$*" in *"$VSRQG_M23_STUB_FAIL_PATTERN"*) [ -n "$VSRQG_M23_STUB_FAIL_PATTERN" ] && exit 23;; esac
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "pnpm") -Encoding utf8NoBOM
        & chmod +x (Join-Path $fixtureBackendDirectory "gradlew") (Join-Path $fixtureBinDirectory "pnpm")
    }
    & git -C $fixtureRoot init --quiet
    & git -C $fixtureRoot add .
    & git -C $fixtureRoot -c user.name=fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
    Assert-True ($LASTEXITCODE -eq 0) "Unable to create M2.3 gate fixture"
    $env:PATH = "$fixtureBinDirectory$([IO.Path]::PathSeparator)$originalPath"
    $scriptUnderTest = Join-Path $fixtureScriptDirectory "verify-issue-snapshot.ps1"

    $failedOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest -InjectFailure snapshot-replay 2>&1)
    $failedExit = $LASTEXITCODE
    $failedText = $failedOutput -join "`n"
    Assert-True ($failedExit -ne 0) "Injected failure must fail the complete gate"
    Assert-True ($failedText -match "STATUS FAILED") "Failure summary status is missing"
    Assert-True ($failedText -match "FAILED snapshot-replay diagnostic=INJECTED_TEST_FAILURE") "Failing check was not preserved"
    $actualOrder = @($failedOutput | Where-Object { $_ -match '^CHECK ' } | ForEach-Object { ($_ -split ' ')[1] })
    $expectedOrder = @("migration", "sync-observation", "snapshot-canonical", "snapshot-integration", "snapshot-replay", "contracts", "acceptance")
    Assert-True (($actualOrder -join ',') -ceq ($expectedOrder -join ',')) "Gate check order changed"
    Assert-True (-not $failedText.Contains($fixtureRoot, [StringComparison]::OrdinalIgnoreCase)) "Gate output exposed an absolute path"
    $safeLine = '^(COMMIT [0-9a-f]{40}|CHECK [a-z-]+ (PASS|FAILED) tests=([0-9]+|UNKNOWN) diagnostic=[A-Z_]+|STATUS FAILED|FAILED [a-z-]+ diagnostic=[A-Z_]+)$'
    Assert-True (@($failedOutput | Where-Object { $_ -notmatch $safeLine }).Count -eq 0) "Gate output escaped the fixed safe grammar"

    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M23_STUB_TRACE = $tracePath
    $env:VSRQG_M23_STUB_FAIL_PATTERN = "*IssueSnapshotIntegrationTest"
    $realFailureOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    $realFailureExit = $LASTEXITCODE
    $realFailureText = $realFailureOutput -join "`n"
    Assert-True ($realFailureExit -eq 23) "A real child-process failure must preserve its exact exit code"
    Assert-True ($realFailureText -match "CHECK snapshot-integration FAILED tests=UNKNOWN diagnostic=CHECK_FAILED") "Real child failure lost its exact check"
    Assert-True ($realFailureText -match "FAILED snapshot-integration diagnostic=CHECK_FAILED") "Real child failure summary is missing"
    Assert-True ($realFailureText -notmatch "SYNTHETIC-UNSAFE-CHILD") "Raw child output escaped the safe summary"
    $expectedInvocations = if ($isWindowsHost) {
        @(
            "./backend/gradlew.bat|-p backend test --tests *M2MigrationConstraintTest",
            "./backend/gradlew.bat|-p backend test --tests *IssueSyncIntegrationTest",
            "./backend/gradlew.bat|-p backend test --tests *IssueSnapshotCanonicalizerTest",
            "./backend/gradlew.bat|-p backend test --tests *IssueSnapshotIntegrationTest",
            "./backend/gradlew.bat|-p backend test --tests *IssueSnapshotReplayTest",
            "pnpm.cmd|run test:contracts",
            "pnpm.cmd|run verify:acceptance"
        )
    } else {
        @(
            "./backend/gradlew|-p backend test --tests *M2MigrationConstraintTest",
            "./backend/gradlew|-p backend test --tests *IssueSyncIntegrationTest",
            "./backend/gradlew|-p backend test --tests *IssueSnapshotCanonicalizerTest",
            "./backend/gradlew|-p backend test --tests *IssueSnapshotIntegrationTest",
            "./backend/gradlew|-p backend test --tests *IssueSnapshotReplayTest",
            "pnpm|run test:contracts",
            "pnpm|run verify:acceptance"
        )
    }
    $actualInvocations = @(Get-Content -LiteralPath $tracePath)
    Assert-True (($actualInvocations -join "`n") -ceq ($expectedInvocations -join "`n")) "Gate command or argument binding changed"

    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M23_STUB_FAIL_PATTERN = ""
    $env:VSRQG_M23_STUB_MALFORMED_PATTERN = "*M2MigrationConstraintTest"
    $malformedOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    $malformedText = $malformedOutput -join "`n"
    Assert-True ($LASTEXITCODE -ne 0) "Malformed result preparation must fail the complete gate"
    Assert-True ($malformedText -match "CHECK migration FAILED tests=UNKNOWN diagnostic=CHECK_FAILED") "Result preparation failure lost its check"
    Assert-True (@($malformedOutput | Where-Object { $_ -notmatch $safeLine }).Count -eq 0) "Preparation failure escaped the safe grammar"
    Assert-True (@($malformedOutput | Where-Object { $_ -match '^CHECK ' }).Count -eq 7) "Preparation failure stopped later checks"
    Assert-True (-not $malformedText.Contains($fixtureRoot, [StringComparison]::OrdinalIgnoreCase)) "Preparation failure exposed an absolute path"

    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M23_STUB_MALFORMED_PATTERN = ""
    $env:VSRQG_M23_STUB_LARGE_PATTERN = "*IssueSnapshotReplayTest"
    $largeStarted = [DateTimeOffset]::UtcNow
    $largeOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    $largeElapsed = [DateTimeOffset]::UtcNow - $largeStarted
    Assert-True ($LASTEXITCODE -eq 0) "Large child output must not fail the gate"
    Assert-True ($largeElapsed -lt [TimeSpan]::FromSeconds(30)) "Large redirected streams did not drain within the fixed bound"
    Assert-True (@($largeOutput | Where-Object { $_ -notmatch $safeLine -and $_ -ne 'STATUS PASS' }).Count -eq 0) "Large child output escaped the safe grammar"
    Assert-True (($largeOutput -join "`n") -notmatch '[XY]{32}') "Large child stdout or stderr leaked"

    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath -Force }
    $env:VSRQG_M23_STUB_LARGE_PATTERN = ""
    $env:VSRQG_M23_STUB_FAIL_PATTERN = ""
    $passingOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    Assert-True ($LASTEXITCODE -eq 0) "Stubbed success gate did not pass"
    Assert-True (($passingOutput -join "`n") -match "STATUS PASS") "Success summary status is missing"
    Assert-True (($passingOutput -join "`n") -notmatch "SYNTHETIC-UNSAFE-CHILD") "Successful child output escaped the safe summary"

    $recordPath = Join-Path $repositoryRoot "docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md"
    $record = Get-Content -LiteralPath $recordPath -Raw
    Assert-True ($record -match '(?m)^status: PENDING$') "Acceptance record status must start PENDING"
    Assert-True ($record -match '(?m)^owner: PENDING$') "Acceptance record owner must start PENDING"
    Assert-True ($record -match '(?m)^decisionAt: PENDING$') "Acceptance record decision time must start PENDING"
    Assert-True ($record -notmatch '(?m)Owner Authorization.*PENDING') "Evidence authorization must remain UNKNOWN until verified"
    $historyRows = @($record -split "`r?`n" | Where-Object { $_ -match '^\| \d{4}-\d{2}-\d{2}T.*\| PENDING \| PENDING \|' })
    Assert-True ($historyRows.Count -ge 2) "PENDING evidence corrections must preserve appended Decision History rows"
    Assert-True ($historyRows[-1] -match '\| [0-9a-f]{40} \|$') "Evidence correction must reference its parent record commit"

    Write-Output "PASS m2-issue-snapshot-gate checks=7 fail-closed safe-output pending-record"
} finally {
    $env:PATH = $originalPath
    if ($null -eq $originalTrace) { Remove-Item Env:VSRQG_M23_STUB_TRACE -ErrorAction SilentlyContinue } else { $env:VSRQG_M23_STUB_TRACE = $originalTrace }
    if ($null -eq $originalFailurePattern) { Remove-Item Env:VSRQG_M23_STUB_FAIL_PATTERN -ErrorAction SilentlyContinue } else { $env:VSRQG_M23_STUB_FAIL_PATTERN = $originalFailurePattern }
    if ($null -eq $originalMalformedPattern) { Remove-Item Env:VSRQG_M23_STUB_MALFORMED_PATTERN -ErrorAction SilentlyContinue } else { $env:VSRQG_M23_STUB_MALFORMED_PATTERN = $originalMalformedPattern }
    if ($null -eq $originalLargePattern) { Remove-Item Env:VSRQG_M23_STUB_LARGE_PATTERN -ErrorAction SilentlyContinue } else { $env:VSRQG_M23_STUB_LARGE_PATTERN = $originalLargePattern }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
