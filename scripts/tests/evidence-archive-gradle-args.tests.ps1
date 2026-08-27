$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$probeRoot = Join-Path $temporaryRoot ("vsrqg archive argument probe " + [guid]::NewGuid().ToString("N"))
$providerEnvironment = @{}
$expectedSummary = '{"artifactCount":0,"errorCode":"WORK_PACKAGE_READ_FAILED","result":"FAIL"}'

function Invoke-OperationProbe {
    param(
        [Parameter(Mandatory)] [string] $Arguments,
        [Parameter(Mandatory)] [string] $ExpectedMode
    )

    $output = @(& (Join-Path $repositoryRoot "backend/gradlew.bat") --no-daemon -q -p (Join-Path $repositoryRoot "backend") evidenceArchiveOperation "--args=$Arguments" 2>&1)
    $text = ($output -join "`n")
    if ($text -match "Task '.*' not found" -or $text -match 'USAGE_ERROR') {
        throw "$ExpectedMode arguments did not reach the JVM operation"
    }
    if ($text.Contains($probeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$ExpectedMode output exposed a controlled input path"
    }
    $summaries = @($output | Where-Object { $_ -is [string] -and $_.StartsWith('{"artifactCount":', [StringComparison]::Ordinal) })
    if ($summaries.Count -ne 1 -or $summaries[0] -cne $expectedSummary) {
        throw "$ExpectedMode did not emit the fixed pre-provider failure summary"
    }
    return $summaries[0]
}

try {
    Get-ChildItem Env: | Where-Object { $_.Name -like 'VSRQG_*' -or $_.Name -like 'AWS_*' } | ForEach-Object {
        $providerEnvironment[$_.Name] = $_.Value
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    $env:AWS_EC2_METADATA_DISABLED = "true"

    New-Item -ItemType Directory -Path $probeRoot | Out-Null
    $sourceRoot = New-Item -ItemType Directory -Path (Join-Path $probeRoot "source root")
    $recoveryRoot = New-Item -ItemType Directory -Path (Join-Path $probeRoot "recovery root")
    $workPackage = Join-Path $probeRoot "work package.json"
    $archiveReport = Join-Path $probeRoot "archive report.json"
    [System.IO.File]::WriteAllText($workPackage, "{}", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($archiveReport, "{}", [System.Text.UTF8Encoding]::new($false))

    $archiveOutput = Join-Path $probeRoot "new archive report.json"
    $archiveArgs = 'archive --work-package=\"' + $workPackage + '\" --source-root=\"' + $sourceRoot.FullName + '\" --output=\"' + $archiveOutput + '\"'
    $archiveResults = @(1..2 | ForEach-Object { Invoke-OperationProbe -Arguments $archiveArgs -ExpectedMode "archive" })
    if ($archiveResults[0] -cne $archiveResults[1]) { throw "archive probe was not deterministic" }

    $recoveryOutput = Join-Path $probeRoot "new recovery report.json"
    $verifyArgs = 'verify --work-package=\"' + $workPackage + '\" --archive-report=\"' + $archiveReport + '\" --recovery-root=\"' + $recoveryRoot.FullName + '\" --output=\"' + $recoveryOutput + '\"'
    $verifyResults = @(1..2 | ForEach-Object { Invoke-OperationProbe -Arguments $verifyArgs -ExpectedMode "verify" })
    if ($verifyResults[0] -cne $verifyResults[1]) { throw "verify probe was not deterministic" }

    if ((Test-Path -LiteralPath $archiveOutput) -or (Test-Path -LiteralPath $recoveryOutput)) {
        throw "probe created an operation output"
    }
    if (@(Get-ChildItem -LiteralPath $probeRoot -Filter "new recovery report.json.complete.*" -Force).Count -ne 0) {
        throw "probe created a completion marker"
    }

    Write-Output "PASS evidence-archive-gradle-args space-safe"
} finally {
    Get-ChildItem Env: | Where-Object { $_.Name -like 'VSRQG_*' -or $_.Name -like 'AWS_*' } | ForEach-Object {
        Remove-Item -LiteralPath "Env:$($_.Name)"
    }
    foreach ($entry in $providerEnvironment.GetEnumerator()) {
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
