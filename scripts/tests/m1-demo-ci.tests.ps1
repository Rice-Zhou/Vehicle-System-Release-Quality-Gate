[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composeFile = Join-Path $repositoryRoot 'deploy/dev/compose.yml'
$entry = Join-Path $repositoryRoot 'scripts/demo/run-m1.ps1'
$outputRoot = Join-Path $repositoryRoot 'backend/build/demo/m1'
$sample = Join-Path $repositoryRoot 'demo/m1/sample-config.txt'
$shellExecutable = (Get-Process -Id $PID).Path
$projectName = 'vsrqg-m1-demo'
$volumeName = 'vsrqg-m1-demo_postgres-data'
$composeArguments = @('compose', '--project-name', $projectName, '--file', $composeFile)

function Invoke-DemoDocker {
    param([string[]]$Arguments)
    $output = @(& docker @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'DEMO_CI_DOCKER_FAILED' }
    return $output
}

function Assert-DemoCondition {
    param([bool]$Condition, [string]$Code)
    if (-not $Condition) { throw $Code }
}

function Get-DemoContainers {
    param([switch]$Running)
    $arguments = @('ps', '--quiet', '--filter', "label=com.docker.compose.project=$projectName")
    if (-not $Running) { $arguments += '--all' }
    return @(Invoke-DemoDocker -Arguments $arguments)
}

function Invoke-DemoAndReadReport {
    param([string]$ExpectedStatus, [switch]$IncludeM2)
    $before = if (Test-Path -LiteralPath $outputRoot) {
        @(Get-ChildItem -LiteralPath $outputRoot -Filter summary.json -Recurse | ForEach-Object FullName)
    } else { @() }
    $arguments = @('-NoProfile', '-File', $entry)
    if ($IncludeM2) { $arguments += '-IncludeM2' }
    & $shellExecutable @arguments | ForEach-Object { Write-Host $_ }
    $exitCode = $LASTEXITCODE
    Assert-DemoCondition (($ExpectedStatus -eq 'PASS' -and $exitCode -eq 0) -or
        ($ExpectedStatus -eq 'FAILED' -and $exitCode -ne 0)) 'DEMO_CI_EXIT_MISMATCH'
    $created = @(Get-ChildItem -LiteralPath $outputRoot -Filter summary.json -Recurse |
        Where-Object FullName -NotIn $before)
    Assert-DemoCondition ($created.Count -eq 1) 'DEMO_CI_REPORT_COUNT'
    $raw = Get-Content -LiteralPath $created[0].FullName -Raw
    Assert-DemoCondition (-not $raw.Contains($script:demoPassword) -and
        -not $raw.Contains($env:VSRQG_DEMO_DATABASE_PASSWORD)) 'DEMO_CI_SECRET_IN_REPORT'
    $report = $raw | ConvertFrom-Json
    Assert-DemoCondition ($report.classification -eq 'SYNTHETIC_DEMO' -and
        $report.status -eq $ExpectedStatus -and $report.codeCommit -eq $script:subjectCommit -and
        $report.runId -eq $created[0].Directory.Name) 'DEMO_CI_REPORT_IDENTITY'
    if ($ExpectedStatus -eq 'FAILED') {
        Assert-DemoCondition (@($report.errorCodes) -contains 'DEMO_STARTUP_FAILED') 'DEMO_CI_WRONG_PASSWORD_FAILURE_MISMATCH'
        return $report
    }
    Assert-DemoCondition ($report.workingTreeDirty -eq $false) 'DEMO_CI_WORKTREE_DIRTY'
    $scenarios = @('validFileLockExport', 'corruptFileRejected', 'unauthenticatedRejected',
        'viewerWriteRejected', 'idempotentReplay', 'historicalExportStable')
    foreach ($scenario in $scenarios) {
        Assert-DemoCondition ($report.scenarioStatuses.$scenario -eq 'PASS') 'DEMO_CI_SCENARIO_FAILED'
    }
    $statuses = @($report.httpStatuses.PSObject.Properties | ForEach-Object { @($_.Value) })
    foreach ($status in @(200, 201, 401, 403, 409, 422)) {
        Assert-DemoCondition ($statuses -contains $status) 'DEMO_CI_HTTP_STATUS_MISSING'
    }
    foreach ($code in @('MANIFEST_VALIDATION_FAILED', 'ARTIFACT_CHECKSUM_MISMATCH', 'MANIFEST_LOCK_CONFLICT')) {
        Assert-DemoCondition (@($report.apiErrorCodes) -contains $code) 'DEMO_CI_API_ERROR_CODE_MISSING'
    }
    Assert-DemoCondition (-not [string]::IsNullOrWhiteSpace($report.releaseId) -and
        -not [string]::IsNullOrWhiteSpace($report.manifestId) -and
        -not [string]::IsNullOrWhiteSpace($report.corruptReleaseId) -and
        -not [string]::IsNullOrWhiteSpace($report.corruptManifestId) -and
        $report.contentDigest -match '^sha256:[0-9a-f]{64}$' -and
        $report.payloadSha256 -match '^[0-9a-f]{64}$') 'DEMO_CI_RESULT_FIELDS'
    Assert-DemoCondition ($report.payloadSha256 -eq $script:sampleHash.ToLowerInvariant()) 'DEMO_CI_PAYLOAD_HASH_MISMATCH'
    $manifestRaw = Get-Content -LiteralPath (Join-Path $created[0].Directory.FullName 'manifest.json') -Raw
    Assert-DemoCondition (-not $manifestRaw.Contains($script:demoPassword)) 'DEMO_CI_SECRET_IN_MANIFEST'
    $manifest = $manifestRaw | ConvertFrom-Json
    Assert-DemoCondition ($manifest.releaseId -eq $report.releaseId -and
        $manifest.artifacts[0].checksum.value -eq $report.payloadSha256) 'DEMO_CI_MANIFEST_MISMATCH'
    if ($IncludeM2) {
        $m2Path = Join-Path $created[0].Directory.FullName 'm2-summary.json'
        Assert-DemoCondition (Test-Path -LiteralPath $m2Path) 'DEMO_CI_M2_REPORT_MISSING'
        $m2Raw = Get-Content -LiteralPath $m2Path -Raw
        Assert-DemoCondition (-not $m2Raw.Contains($script:demoPassword)) 'DEMO_CI_SECRET_IN_M2_REPORT'
        $m2 = $m2Raw | ConvertFrom-Json
        Assert-DemoCondition ($m2.status -eq 'PASS' -and $m2.runId -eq $report.runId -and
            $m2.history.snapshotABytesStable -eq $true -and
            $m2.traceabilitySnapshotIds.A -ne $m2.traceabilitySnapshotIds.B -and
            $m2.history.latestSnapshotId -eq $m2.traceabilitySnapshotIds.B) 'DEMO_CI_M2_RESULT_INVALID'
    }
    return $report
}

# This test owns a fresh, dedicated CI project; it never deletes volumes.
Assert-DemoCondition ((Get-DemoContainers).Count -eq 0) 'DEMO_CI_PROJECT_ALREADY_EXISTS'
$volumes = @(Invoke-DemoDocker -Arguments @('volume', 'ls', '--quiet', '--filter', "name=^${volumeName}$"))
Assert-DemoCondition ($volumes.Count -eq 0) 'DEMO_CI_VOLUME_ALREADY_EXISTS'
$subjectCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
Assert-DemoCondition ($LASTEXITCODE -eq 0 -and $subjectCommit -match '^[0-9a-f]{40}$') 'DEMO_CI_COMMIT_INVALID'
$sampleHash = (Get-FileHash -LiteralPath $sample -Algorithm SHA256).Hash
$demoPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
Write-Output "::add-mask::$demoPassword"
$env:VSRQG_DEMO_DATABASE_PASSWORD = $demoPassword
$env:VSRQG_DB_PASSWORD = $demoPassword
$env:VSRQG_DB_NAME = 'vsrqg_demo'
$env:VSRQG_DB_USER = 'vsrqg_demo'
$env:VSRQG_DB_PORT = '55432'
try {
    $first = Invoke-DemoAndReadReport 'PASS' -IncludeM2
    Assert-DemoCondition ((Get-DemoContainers -Running).Count -eq 0) 'DEMO_CI_FIRST_SERVICE_NOT_STOPPED'
    $volumeBefore = @(Invoke-DemoDocker -Arguments @('volume', 'inspect', '--format', '{{.CreatedAt}}', $volumeName))[0]
    $second = Invoke-DemoAndReadReport 'PASS' -IncludeM2
    Assert-DemoCondition ($second.runId -ne $first.runId -and $second.releaseId -ne $first.releaseId) 'DEMO_CI_REUSE_NOT_DISTINCT'
    Assert-DemoCondition ((Get-DemoContainers -Running).Count -eq 0) 'DEMO_CI_REUSED_SERVICE_NOT_STOPPED'
    Invoke-DemoDocker -Arguments ($composeArguments + @('start', 'postgres')) | Out-Null
    $third = Invoke-DemoAndReadReport 'PASS'
    Assert-DemoCondition ((Get-DemoContainers -Running).Count -eq 1) 'DEMO_CI_PREEXISTING_SERVICE_STOPPED'
    $wrongPassword = [Guid]::NewGuid().ToString('N')
    Write-Output "::add-mask::$wrongPassword"
    $env:VSRQG_DEMO_DATABASE_PASSWORD = $wrongPassword
    $failed = Invoke-DemoAndReadReport 'FAILED'
    Assert-DemoCondition ((Get-DemoContainers -Running).Count -eq 1) 'DEMO_CI_FAILURE_STOPPED_PREEXISTING_SERVICE'
    $volumeAfter = @(Invoke-DemoDocker -Arguments @('volume', 'inspect', '--format', '{{.CreatedAt}}', $volumeName))[0]
    Assert-DemoCondition ($volumeBefore -eq $volumeAfter) 'DEMO_CI_VOLUME_REPLACED'
    Assert-DemoCondition ((Get-FileHash -LiteralPath $sample -Algorithm SHA256).Hash -eq $sampleHash) 'DEMO_CI_SOURCE_CHANGED'
    Write-Output 'PASS demonstration: fresh, retained-volume reuse, preexisting service, wrong password, source preservation'
} finally {
    # The harness started this service explicitly; stop it while preserving its volume and reports.
    $env:VSRQG_DEMO_DATABASE_PASSWORD = $demoPassword
    Invoke-DemoDocker -Arguments ($composeArguments + @('stop', 'postgres')) | Out-Null
}
