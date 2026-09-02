$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$sourceScript = Join-Path $repositoryRoot "scripts/m2/verify-issue-snapshot.ps1"
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-m23-gate-$([Guid]::NewGuid().ToString('N'))"
$fixtureScriptDirectory = Join-Path $fixtureRoot "scripts/m2"
$fixtureBackendDirectory = Join-Path $fixtureRoot "backend"
$fixtureBinDirectory = Join-Path $fixtureRoot "bin"
$originalPath = $env:PATH
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$pwsh = (Get-Process -Id $PID).Path

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

try {
    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureBackendDirectory, $fixtureBinDirectory | Out-Null
    Copy-Item -LiteralPath $sourceScript -Destination $fixtureScriptDirectory
    if ($isWindowsHost) {
        "@echo off`r`nexit /b 0`r`n" | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew.bat") -Encoding ascii
        "@echo off`r`nexit /b 0`r`n" | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "pnpm.cmd") -Encoding ascii
    } else {
        "#!/usr/bin/env sh`nexit 0`n" | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew") -Encoding utf8NoBOM
        "#!/usr/bin/env sh`nexit 0`n" | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "pnpm") -Encoding utf8NoBOM
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

    $passingOutput = @(& $pwsh -NoProfile -NonInteractive -File $scriptUnderTest 2>&1)
    Assert-True ($LASTEXITCODE -eq 0) "Stubbed success gate did not pass"
    Assert-True (($passingOutput -join "`n") -match "STATUS PASS") "Success summary status is missing"

    $recordPath = Join-Path $repositoryRoot "docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md"
    $record = Get-Content -LiteralPath $recordPath -Raw
    Assert-True ($record -match '(?m)^status: PENDING$') "Acceptance record status must start PENDING"
    Assert-True ($record -match '(?m)^owner: PENDING$') "Acceptance record owner must start PENDING"
    Assert-True ($record -match '(?m)^decisionAt: PENDING$') "Acceptance record decision time must start PENDING"

    Write-Output "PASS m2-issue-snapshot-gate checks=7 fail-closed safe-output pending-record"
} finally {
    $env:PATH = $originalPath
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
