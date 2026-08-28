$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$probeRoot = Join-Path $temporaryRoot ("vsrqg archive argument probe " + [guid]::NewGuid().ToString("N"))

function Invoke-OperationProbe {
    param(
        [Parameter(Mandatory)] [string] $Arguments,
        [Parameter(Mandatory)] [string] $ExpectedMode
    )

    $output = @(& (Join-Path $repositoryRoot "backend/gradlew.bat") -q -p (Join-Path $repositoryRoot "backend") evidenceArchiveOperation "--args=$Arguments" 2>&1)
    $text = ($output -join "`n")
    if ($text -match "Task '.*' not found" -or $text -match 'USAGE_ERROR') {
        throw "$ExpectedMode arguments did not reach the JVM operation"
    }
    if ($text.Contains($probeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$ExpectedMode output exposed a controlled input path"
    }
    if ($text -notmatch '(?m)^\{"artifactCount":(?:0|2),.*"result":"(?:FAIL|PASS)".*\}$') {
        throw "$ExpectedMode did not emit a canonical JVM operation summary"
    }
}

try {
    New-Item -ItemType Directory -Path $probeRoot | Out-Null
    $sourceRoot = New-Item -ItemType Directory -Path (Join-Path $probeRoot "source root")
    $recoveryRoot = New-Item -ItemType Directory -Path (Join-Path $probeRoot "recovery root")
    $workPackage = Join-Path $probeRoot "work package.json"
    $archiveReport = Join-Path $probeRoot "archive report.json"
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "ops/evidence-archive/fixtures/offline-test/work-package.json") -Destination $workPackage
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "ops/evidence-archive/fixtures/offline-test/archive-report.json") -Destination $archiveReport

    $archiveOutput = Join-Path $probeRoot "new archive report.json"
    $archiveArgs = 'archive --work-package=\"' + $workPackage + '\" --source-root=\"' + $sourceRoot.FullName + '\" --output=\"' + $archiveOutput + '\"'
    Invoke-OperationProbe -Arguments $archiveArgs -ExpectedMode "archive"

    $recoveryOutput = Join-Path $probeRoot "new recovery report.json"
    $verifyArgs = 'verify --work-package=\"' + $workPackage + '\" --archive-report=\"' + $archiveReport + '\" --recovery-root=\"' + $recoveryRoot.FullName + '\" --output=\"' + $recoveryOutput + '\"'
    Invoke-OperationProbe -Arguments $verifyArgs -ExpectedMode "verify"

    Write-Output "PASS evidence-archive-gradle-args space-safe"
} finally {
    $resolvedProbeRoot = [System.IO.Path]::GetFullPath($probeRoot)
    $expectedPrefix = $temporaryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (
        $resolvedProbeRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedProbeRoot).StartsWith("vsrqg archive argument probe ", [StringComparison]::Ordinal)
    ) {
        Remove-Item -LiteralPath $resolvedProbeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
