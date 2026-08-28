$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$probeRoot = Join-Path $temporaryRoot ("vsrqg archive argument probe " + [guid]::NewGuid().ToString("N"))
$savedEnvironment = @{}
$bridgePrefix = "VSRQG_EVIDENCE_OPERATION_"
$expectedSummary = '{"artifactCount":0,"errorCode":"ARCHIVE_INPUT_FAILURE","result":"FAIL"}'
$pollutedValues = @("PROVIDER_SECRET_SENTINEL", "PROFILE_SECRET_SENTINEL", "WEB_IDENTITY_SECRET_SENTINEL")

function Clear-BridgeEnvironment {
    Get-ChildItem Env: | Where-Object { $_.Name.StartsWith($bridgePrefix, [StringComparison]::Ordinal) } | ForEach-Object {
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
}

function Invoke-OperationProbe {
    param(
        [Parameter(Mandatory)] [hashtable] $Bridge,
        [Parameter(Mandatory)] [string] $ExpectedMode
    )

    Clear-BridgeEnvironment
    foreach ($entry in $Bridge.GetEnumerator()) {
        Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
    }
    $output = @(& (Join-Path $repositoryRoot "backend/gradlew.bat") --no-daemon -q -p (Join-Path $repositoryRoot "backend") evidenceArchiveOperation 2>&1)
    $exitCode = $LASTEXITCODE
    $text = ($output -join "`n")
    if ($exitCode -ne 1) { throw "$ExpectedMode did not preserve the native operation exit code" }
    if ($text -match "Task '.*' not found" -or $text -match 'USAGE_ERROR') {
        throw "$ExpectedMode arguments did not reach the JVM operation"
    }
    if ($text.Contains($probeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$ExpectedMode output exposed a controlled input path"
    }
    foreach ($polluted in $pollutedValues) {
        if ($text.Contains($polluted, [StringComparison]::Ordinal)) {
            throw "$ExpectedMode output exposed provider environment"
        }
    }
    $summaries = @($output | Where-Object { $_ -is [string] -and $_.StartsWith('{"artifactCount":', [StringComparison]::Ordinal) })
    if ($summaries.Count -ne 1 -or $summaries[0] -cne $expectedSummary) {
        throw "$ExpectedMode did not emit the fixed pre-provider failure summary"
    }
    return $summaries[0]
}

function Assert-InvalidBridgeFailsClosed {
    param(
        [Parameter(Mandatory)] [hashtable] $Bridge,
        [Parameter(Mandatory)] [string] $CaseName
    )

    Clear-BridgeEnvironment
    foreach ($entry in $Bridge.GetEnumerator()) {
        Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
    }
    $output = @(& (Join-Path $repositoryRoot "backend/gradlew.bat") --no-daemon -q -p (Join-Path $repositoryRoot "backend") evidenceArchiveOperation 2>&1)
    $text = ($output -join "`n")
    if ($LASTEXITCODE -eq 0 -or -not $text.Contains("EVIDENCE_OPERATION_ENV_INVALID", [StringComparison]::Ordinal)) {
        throw "$CaseName bridge environment was not rejected"
    }
    if ($text.Contains($probeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$CaseName bridge failure exposed an environment value"
    }
}

try {
    Get-ChildItem Env: | Where-Object {
        $_.Name -like 'VSRQG_*' -or $_.Name -like 'AWS_*'
    } | ForEach-Object {
        $savedEnvironment[$_.Name] = $_.Value
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    $env:AWS_ACCESS_KEY_ID = $pollutedValues[0]
    $env:AWS_SECRET_ACCESS_KEY = $pollutedValues[0]
    $env:AWS_PROFILE = $pollutedValues[1]
    $env:AWS_WEB_IDENTITY_TOKEN_FILE = $pollutedValues[2]
    $env:AWS_EC2_METADATA_DISABLED = "true"

    New-Item -ItemType Directory -Path $probeRoot | Out-Null
    $sourceRoot = New-Item -ItemType Directory -Path (Join-Path $probeRoot "source root")
    $recoveryRoot = New-Item -ItemType Directory -Path (Join-Path $probeRoot "recovery root")
    $workPackage = Join-Path $probeRoot "work package.json"
    $archiveReport = Join-Path $probeRoot "archive report.json"
    [System.IO.File]::WriteAllText($workPackage, "{}", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($archiveReport, "{}", [System.Text.UTF8Encoding]::new($false))

    $archiveOutput = Join-Path $probeRoot "new archive report.json"
    $archiveBridge = @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "archive"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
        VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT = $sourceRoot.FullName
        VSRQG_EVIDENCE_OPERATION_OUTPUT = $archiveOutput
    }
    $archiveResults = @(1..2 | ForEach-Object { Invoke-OperationProbe -Bridge $archiveBridge -ExpectedMode "archive" })
    if ($archiveResults[0] -cne $archiveResults[1]) { throw "archive probe was not deterministic" }

    $recoveryOutput = Join-Path $probeRoot "new recovery report.json"
    $verifyBridge = @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "verify"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
        VSRQG_EVIDENCE_OPERATION_ARCHIVE_REPORT = $archiveReport
        VSRQG_EVIDENCE_OPERATION_RECOVERY_ROOT = $recoveryRoot.FullName
        VSRQG_EVIDENCE_OPERATION_OUTPUT = $recoveryOutput
    }
    $verifyResults = @(1..2 | ForEach-Object { Invoke-OperationProbe -Bridge $verifyBridge -ExpectedMode "verify" })
    if ($verifyResults[0] -cne $verifyResults[1]) { throw "verify probe was not deterministic" }

    Assert-InvalidBridgeFailsClosed -CaseName "partial" -Bridge @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "archive"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $probeRoot
    }
    Assert-InvalidBridgeFailsClosed -CaseName "unknown" -Bridge @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "archive"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
        VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT = $sourceRoot.FullName
        VSRQG_EVIDENCE_OPERATION_OUTPUT = $archiveOutput
        VSRQG_EVIDENCE_OPERATION_UNEXPECTED = $probeRoot
    }
    if ((Test-Path -LiteralPath $archiveOutput) -or (Test-Path -LiteralPath $recoveryOutput)) {
        throw "probe created an operation output"
    }
    if (@(Get-ChildItem -LiteralPath $probeRoot -Filter "new recovery report.json.complete.*" -Force).Count -ne 0) {
        throw "probe created a completion marker"
    }

    Write-Output "PASS evidence-archive-gradle-args space-safe"
} finally {
    Get-ChildItem Env: | Where-Object {
        $_.Name -like 'VSRQG_*' -or $_.Name -like 'AWS_*'
    } | ForEach-Object {
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
    }
    $resolvedProbeRoot = [System.IO.Path]::GetFullPath($probeRoot)
    $expectedPrefix = $temporaryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (
        $resolvedProbeRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedProbeRoot).StartsWith("vsrqg archive argument probe ", [StringComparison]::Ordinal)
    ) {
        if (Test-Path -LiteralPath $resolvedProbeRoot) {
            Remove-Item -LiteralPath $resolvedProbeRoot -Recurse -Force
        }
        if (Test-Path -LiteralPath $resolvedProbeRoot) { throw "probe temporary directory cleanup failed" }
    } else {
        throw "probe temporary directory escaped the approved root"
    }
}

exit 0
