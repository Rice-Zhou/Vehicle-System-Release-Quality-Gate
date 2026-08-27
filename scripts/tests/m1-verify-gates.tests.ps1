$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-m1-gate-$([Guid]::NewGuid().ToString('N'))"
$fixtureScriptDirectory = Join-Path $fixtureRoot "scripts/m1"
$fixtureTestDirectory = Join-Path $fixtureRoot "scripts/tests"
$fixtureBackendDirectory = Join-Path $fixtureRoot "backend"
$fixtureEvidenceDirectory = Join-Path $fixtureRoot "ops/evidence-archive/fixtures/offline-test"
$fixtureBinDirectory = Join-Path $fixtureRoot "bin"
$originalPath = $env:PATH
$originalGateMode = $env:VSRQG_TEST_GATE_MODE

try {
    $offlineFixture = (Resolve-Path (Join-Path $repositoryRoot "ops/evidence-archive/fixtures/offline-test")).Path
    $offlineWorkPackage = (Resolve-Path (Join-Path $offlineFixture "work-package.json")).Path
    $offlineArchiveReport = (Resolve-Path (Join-Path $offlineFixture "archive-report.json")).Path
    $offlineRecoveryReport = (Resolve-Path (Join-Path $offlineFixture "recovery-report.json")).Path
    $offlineOutput = @(& pnpm --silent run verify:evidence-archive -- --work-package $offlineWorkPackage --archive-report $offlineArchiveReport --recovery-report $offlineRecoveryReport 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Offline fixture verification failed" }
    $canonicalOutput = '{"artifactCount":2,"result":"PASS","workPackageId":"V0-2-EVIDENCE-ARCHIVE-001"}'
    if (($offlineOutput -join "`n").Trim() -cne $canonicalOutput) { throw "Offline fixture output was not canonical safe JSON" }
    if (($offlineOutput -join "`n").Contains($repositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Offline fixture output exposed the repository path"
    }

    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureTestDirectory, $fixtureBackendDirectory, $fixtureBinDirectory, $fixtureEvidenceDirectory | Out-Null
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts/m1/verify.ps1") -Destination $fixtureScriptDirectory
    Copy-Item -Path (Join-Path $repositoryRoot "ops/evidence-archive/fixtures/offline-test/*") -Destination $fixtureEvidenceDirectory
    "backend/build/" | Set-Content -LiteralPath (Join-Path $fixtureRoot ".gitignore") -Encoding utf8NoBOM
    "exit 0" | Set-Content -LiteralPath (Join-Path $fixtureTestDirectory "verify-contracts.tests.ps1") -Encoding utf8NoBOM
    @'
if ($env:VSRQG_TEST_GATE_MODE -eq "windows-args") { exit 23 }
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureTestDirectory "evidence-archive-gradle-args.tests.ps1") -Encoding utf8NoBOM
    "exit 0" | Set-Content -LiteralPath (Join-Path $fixtureScriptDirectory "acceptance-smoke.ps1") -Encoding utf8NoBOM
    "exit 0" | Set-Content -LiteralPath (Join-Path $fixtureScriptDirectory "export-schema.ps1") -Encoding utf8NoBOM
    @"
@echo off
echo invoked>"%~dp0gradle-invoked.txt"
exit /b 0
"@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew.bat") -Encoding ascii
    @"
@echo off
if "%VSRQG_TEST_GATE_MODE%"=="evidence" if "%~1"=="--silent" if "%~2"=="run" if "%~3"=="verify:evidence-archive" exit /b 19
exit /b 0
"@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "pnpm.cmd") -Encoding ascii

    & git -C $fixtureRoot init --quiet
    & git -C $fixtureRoot add .
    & git -C $fixtureRoot -c user.name=fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
    if ($LASTEXITCODE -ne 0) { throw "Unable to create M1 verification fixture" }
    $commit = (& git -C $fixtureRoot rev-parse HEAD).Trim()

    $env:PATH = "$fixtureBinDirectory$([IO.Path]::PathSeparator)$originalPath"
    $env:VSRQG_TEST_GATE_MODE = "evidence"
    & (Join-Path $PSHOME "pwsh.exe") -NoProfile -NonInteractive -File (Join-Path $fixtureScriptDirectory "verify.ps1") *> $null
    $verifyExit = $LASTEXITCODE

    if ($verifyExit -eq 0) { throw "M1 verification must propagate the evidence archive gate failure" }
    if (Test-Path -LiteralPath (Join-Path $fixtureBackendDirectory "gradle-invoked.txt")) {
        throw "Backend gate ran after the evidence archive gate failed"
    }
    $evidencePath = Join-Path $fixtureRoot "backend/build/m1/evidence/$commit/evidence.json"
    if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) { throw "Failure evidence was not written" }
    $evidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    $gate = @($evidence.gates | Where-Object name -eq "evidence-archive")
    if ($gate.Count -ne 1) { throw "Expected exactly one evidence-archive gate" }
    $expectedCommand = "pnpm run test:evidence-archive + pnpm --silent run verify:evidence-archive"
    if ($gate[0].command -ne $expectedCommand) { throw "Unexpected evidence-archive command" }
    if ($gate[0].exitCode -ne 19) { throw "Evidence archive failure exit code was not preserved" }

    $env:VSRQG_TEST_GATE_MODE = "windows-args"
    & (Join-Path $PSHOME "pwsh.exe") -NoProfile -NonInteractive -File (Join-Path $fixtureScriptDirectory "verify.ps1") *> $null
    $windowsProbeExit = $LASTEXITCODE
    if ($windowsProbeExit -eq 0) { throw "M1 verification must propagate the Windows argument probe failure" }
    if (Test-Path -LiteralPath (Join-Path $fixtureBackendDirectory "gradle-invoked.txt")) {
        throw "Backend gate ran after the Windows argument probe failed"
    }
    $windowsEvidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    $windowsGate = @($windowsEvidence.gates | Where-Object name -eq "evidence-archive-windows-args")
    if ($windowsGate.Count -ne 1) { throw "Expected exactly one Windows argument probe gate" }
    if ($windowsGate[0].command -ne "./scripts/tests/evidence-archive-gradle-args.tests.ps1") {
        throw "Unexpected Windows argument probe command metadata"
    }
    if ($windowsGate[0].exitCode -ne 23) { throw "Windows argument probe failure exit code was not preserved" }
    Write-Output "PASS m1-evidence-archive-gate failure-propagation"
}
finally {
    $env:PATH = $originalPath
    if ($null -eq $originalGateMode) {
        Remove-Item Env:VSRQG_TEST_GATE_MODE -ErrorAction SilentlyContinue
    } else {
        $env:VSRQG_TEST_GATE_MODE = $originalGateMode
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
