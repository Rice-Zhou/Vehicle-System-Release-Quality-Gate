$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$backendRoot = (Resolve-Path (Join-Path $repositoryRoot "backend")).Path
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$gradleWrapper = Join-Path $backendRoot $(if ($isWindowsHost) { "gradlew.bat" } else { "gradlew" })
$probeParent = Join-Path $backendRoot "build"
New-Item -ItemType Directory -Path $probeParent -Force | Out-Null
$probeParent = (Resolve-Path $probeParent).Path
$probeId = [guid]::NewGuid().ToString("N")
$probeRoot = Join-Path $probeParent "vsrqg archive argument probe $probeId"
$legacyWorkPackage = Join-Path $probeParent "legacy-invalid-$probeId.json"
$legacyOutput = Join-Path $probeParent "legacy-output-$probeId.json"
$savedEnvironment = @{}
$bridgePrefix = "VSRQG_EVIDENCE_OPERATION_"
$expectedSummary = '{"artifactCount":0,"errorCode":"ARCHIVE_INPUT_FAILURE","result":"FAIL"}'
$pollutedValues = @("PROVIDER_SECRET_SENTINEL", "PROFILE_SECRET_SENTINEL", "WEB_IDENTITY_SECRET_SENTINEL")

function Clear-BridgeEnvironment {
    Get-ChildItem Env: | Where-Object { $_.Name.StartsWith($bridgePrefix, [StringComparison]::Ordinal) } | ForEach-Object {
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
}

function Assert-SafeOperationFailure {
    param(
        [Parameter(Mandatory)] [object[]]$Output,
        [Parameter(Mandatory)] [int]$ExitCode,
        [Parameter(Mandatory)] [string]$CaseName
    )

    $text = ($Output -join "`n")
    if ($ExitCode -ne 1) { throw "$CaseName did not preserve the native operation exit code" }
    if ($text -match "Task '.*' not found" -or $text -match 'USAGE_ERROR|WORK_PACKAGE_READ_FAILED') {
        throw "$CaseName arguments did not reach strict work-package validation"
    }
    if ($text.Contains($probeParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$CaseName output exposed a controlled input path"
    }
    foreach ($polluted in $pollutedValues) {
        if ($text.Contains($polluted, [StringComparison]::Ordinal)) {
            throw "$CaseName output exposed provider environment"
        }
    }
    $summaries = @($Output | Where-Object { $_ -is [string] -and $_.StartsWith('{"artifactCount":', [StringComparison]::Ordinal) })
    if ($summaries.Count -ne 1 -or $summaries[0] -cne $expectedSummary) {
        throw "$CaseName did not emit the fixed pre-provider failure summary"
    }
    return $summaries[0]
}

function Invoke-OperationProbe {
    param(
        [Parameter(Mandatory)] [hashtable]$Bridge,
        [Parameter(Mandatory)] [string]$CaseName
    )

    Clear-BridgeEnvironment
    foreach ($entry in $Bridge.GetEnumerator()) {
        Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
    }
    $output = @(& $gradleWrapper --no-daemon -q -p $backendRoot evidenceArchiveOperation 2>&1)
    $exitCode = $LASTEXITCODE
    return Assert-SafeOperationFailure -Output $output -ExitCode $exitCode -CaseName $CaseName
}

function Assert-InvalidBridgeFailsClosed {
    param(
        [Parameter(Mandatory)] [hashtable]$Bridge,
        [Parameter(Mandatory)] [string]$CaseName
    )

    Clear-BridgeEnvironment
    foreach ($entry in $Bridge.GetEnumerator()) {
        Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
    }
    $output = @(& $gradleWrapper --no-daemon -q -p $backendRoot evidenceArchiveOperation 2>&1)
    $text = ($output -join "`n")
    if ($LASTEXITCODE -eq 0 -or -not $text.Contains("EVIDENCE_OPERATION_ENV_INVALID", [StringComparison]::Ordinal)) {
        throw "$CaseName bridge environment was not rejected"
    }
    if ($text.Contains($probeParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$CaseName bridge failure exposed an environment value"
    }
}

function Assert-LegacyArgsCompatibility {
    Clear-BridgeEnvironment
    $legacyArgs = "archive --work-package=$legacyWorkPackage --source-root=$probeParent --output=$legacyOutput"
    $output = @(& $gradleWrapper --no-daemon -q -p $backendRoot evidenceArchiveOperation "--args=$legacyArgs" 2>&1)
    $exitCode = $LASTEXITCODE
    [void](Assert-SafeOperationFailure -Output $output -ExitCode $exitCode -CaseName "legacy --args")
}

try {
    Get-ChildItem Env: | Where-Object { $_.Name -like 'VSRQG_*' -or $_.Name -like 'AWS_*' } | ForEach-Object {
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
    [IO.File]::WriteAllText($workPackage, "{}", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($archiveReport, "{}", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($legacyWorkPackage, "{}", [Text.UTF8Encoding]::new($false))

    $archiveOutput = Join-Path $probeRoot "new archive report.json"
    $archiveBridge = @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "archive"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
        VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT = $sourceRoot.FullName
        VSRQG_EVIDENCE_OPERATION_OUTPUT = $archiveOutput
    }
    $archiveResults = @(1..2 | ForEach-Object { Invoke-OperationProbe -Bridge $archiveBridge -CaseName "archive" })
    if ($archiveResults[0] -cne $archiveResults[1]) { throw "archive probe was not deterministic" }

    $recoveryOutput = Join-Path $probeRoot "new recovery report.json"
    $verifyBridge = @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "verify"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
        VSRQG_EVIDENCE_OPERATION_ARCHIVE_REPORT = $archiveReport
        VSRQG_EVIDENCE_OPERATION_RECOVERY_ROOT = $recoveryRoot.FullName
        VSRQG_EVIDENCE_OPERATION_OUTPUT = $recoveryOutput
    }
    $verifyResults = @(1..2 | ForEach-Object { Invoke-OperationProbe -Bridge $verifyBridge -CaseName "verify" })
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
    Assert-InvalidBridgeFailsClosed -CaseName "blank" -Bridge @{
        VSRQG_EVIDENCE_OPERATION_COMMAND = "archive"
        VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
        VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT = $sourceRoot.FullName
        VSRQG_EVIDENCE_OPERATION_OUTPUT = " "
    }
    Assert-LegacyArgsCompatibility

    if ((Test-Path -LiteralPath $archiveOutput) -or (Test-Path -LiteralPath $recoveryOutput) -or (Test-Path -LiteralPath $legacyOutput)) {
        throw "probe created an operation output"
    }
    if (@(Get-ChildItem -LiteralPath $probeRoot -Filter "new recovery report.json.complete.*" -Force).Count -ne 0) {
        throw "probe created a completion marker"
    }

    Write-Output "PASS evidence-archive-gradle-args env-bridge host=$(if ($isWindowsHost) { 'windows' } else { 'unix' })"
} finally {
    Get-ChildItem Env: | Where-Object { $_.Name -like 'VSRQG_*' -or $_.Name -like 'AWS_*' } | ForEach-Object {
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        Set-Item -LiteralPath "Env:$($entry.Key)" -Value $entry.Value
    }
    $resolvedProbeRoot = [IO.Path]::GetFullPath($probeRoot)
    $expectedPrefix = $probeParent.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (
        $resolvedProbeRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedProbeRoot).StartsWith("vsrqg archive argument probe ", [StringComparison]::Ordinal)
    ) {
        if (Test-Path -LiteralPath $resolvedProbeRoot) { Remove-Item -LiteralPath $resolvedProbeRoot -Recurse -Force }
        if (Test-Path -LiteralPath $legacyWorkPackage) { Remove-Item -LiteralPath $legacyWorkPackage -Force }
        if (Test-Path -LiteralPath $legacyOutput) { Remove-Item -LiteralPath $legacyOutput -Force }
        if (Test-Path -LiteralPath $resolvedProbeRoot) { throw "probe temporary directory cleanup failed" }
    } else {
        throw "probe temporary directory escaped the approved root"
    }
}

exit 0
