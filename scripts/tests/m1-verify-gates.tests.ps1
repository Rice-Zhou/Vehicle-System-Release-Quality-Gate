$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-m1-gate-$([Guid]::NewGuid().ToString('N'))"
$fixtureScriptDirectory = Join-Path $fixtureRoot "scripts/m1"
$fixtureTestDirectory = Join-Path $fixtureRoot "scripts/tests"
$fixtureBackendDirectory = Join-Path $fixtureRoot "backend"
$fixtureBinDirectory = Join-Path $fixtureRoot "bin"
$originalPath = $env:PATH

try {
    New-Item -ItemType Directory -Path $fixtureScriptDirectory, $fixtureTestDirectory, $fixtureBackendDirectory, $fixtureBinDirectory | Out-Null
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts/m1/verify.ps1") -Destination $fixtureScriptDirectory
    "backend/build/" | Set-Content -LiteralPath (Join-Path $fixtureRoot ".gitignore") -Encoding utf8NoBOM
    "exit 0" | Set-Content -LiteralPath (Join-Path $fixtureTestDirectory "verify-contracts.tests.ps1") -Encoding utf8NoBOM
    "exit 0" | Set-Content -LiteralPath (Join-Path $fixtureScriptDirectory "acceptance-smoke.ps1") -Encoding utf8NoBOM
    "exit 0" | Set-Content -LiteralPath (Join-Path $fixtureScriptDirectory "export-schema.ps1") -Encoding utf8NoBOM
    @"
@echo off
echo invoked>"%~dp0gradle-invoked.txt"
exit /b 0
"@ | Set-Content -LiteralPath (Join-Path $fixtureBackendDirectory "gradlew.bat") -Encoding ascii
    @"
@echo off
if "%~1"=="run" if "%~2"=="test:evidence-archive" exit /b 19
exit /b 0
"@ | Set-Content -LiteralPath (Join-Path $fixtureBinDirectory "pnpm.cmd") -Encoding ascii

    & git -C $fixtureRoot init --quiet
    & git -C $fixtureRoot add .
    & git -C $fixtureRoot -c user.name=fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
    if ($LASTEXITCODE -ne 0) { throw "Unable to create M1 verification fixture" }
    $commit = (& git -C $fixtureRoot rev-parse HEAD).Trim()

    $env:PATH = "$fixtureBinDirectory$([IO.Path]::PathSeparator)$originalPath"
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
    if ($gate[0].command -ne "pnpm run test:evidence-archive") { throw "Unexpected evidence-archive command" }
    if ($gate[0].exitCode -ne 19) { throw "Evidence archive failure exit code was not preserved" }
    Write-Output "PASS m1-evidence-archive-gate failure-propagation"
}
finally {
    $env:PATH = $originalPath
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
