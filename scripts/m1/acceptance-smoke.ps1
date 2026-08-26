$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$backendRoot = Join-Path $repositoryRoot "backend"
$gradleWrapperName = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
    "gradlew.bat"
} else {
    "gradlew"
}
$gradleWrapper = Join-Path $backendRoot $gradleWrapperName
$outputDirectory = Join-Path $backendRoot "build/m1"
$reportPath = Join-Path $outputDirectory "acceptance-smoke.json"
$schemaPath = Join-Path $outputDirectory "schema.sql"

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw "Docker is required for the PostgreSQL 17.11 recovery smoke"
}
& $docker.Source info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker daemon is unavailable"
}

Push-Location $repositoryRoot
try {
    foreach ($staleOutput in @($reportPath, $schemaPath)) {
        if (Test-Path -LiteralPath $staleOutput) {
            Remove-Item -LiteralPath $staleOutput -Force
        }
    }
    & $gradleWrapper -p $backendRoot cleanTest test `
        --tests "com.ricezhou.vsrqg.M1EndToEndTest" --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        throw "M1 end-to-end recovery test failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

foreach ($requiredFile in @($reportPath, $schemaPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "M1 smoke did not produce required file: $requiredFile"
    }
}

$report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
if ($report.status -ne "PASS") {
    throw "M1 smoke report status is '$($report.status)', expected PASS"
}
if ($report.databaseImage -ne "postgres:17.11") {
    throw "M1 smoke used '$($report.databaseImage)', expected postgres:17.11"
}
if ($report.trustedValidationFixture -ne "m1-acceptance-validator/1") {
    throw "M1 smoke trusted validator fixture is not explicit"
}
$candidateCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $report.candidateCommit -ne $candidateCommit) {
    throw "M1 smoke report commit '$($report.candidateCommit)' does not match candidate '$candidateCommit'"
}
$schemaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $schemaPath).Hash.ToLowerInvariant()
if ($report.schemaSha256 -ne $schemaHash) {
    throw "M1 smoke schema hash does not match schema.sql"
}
if ($report.lockedDigest -ne $report.exportedDigest -or $report.lockedDigest -ne $report.restoredDigest) {
    throw "Locked, exported, and restored Manifest digests differ"
}

$expectedAudit = @("RELEASE_CREATED", "MANIFEST_REGISTERED", "MANIFEST_VALIDATED", "MANIFEST_LOCKED")
if (Compare-Object -ReferenceObject $expectedAudit -DifferenceObject @($report.auditActions) -SyncWindow 0) {
    throw "M1 smoke audit timeline differs from the required sequence"
}
if (Compare-Object -ReferenceObject @($report.auditRows) -DifferenceObject @($report.restoredAuditRows) -SyncWindow 0) {
    throw "Restored Audit rows differ from source"
}
if (Compare-Object -ReferenceObject @($report.releaseHistoryRows) -DifferenceObject @($report.restoredReleaseHistoryRows) -SyncWindow 0) {
    throw "Restored Release state history rows differ from source"
}
if ($report.lockedValidationRow -ne $report.restoredLockedValidationRow) {
    throw "Restored locked Validation row differs from source"
}

Write-Output "PASS M1 smoke database=postgres:17.11 digest=$($report.lockedDigest) recovery=verified"
