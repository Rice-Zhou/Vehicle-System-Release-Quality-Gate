param(
    [switch]$UseExistingExport
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$outputDirectory = Join-Path $repositoryRoot "backend/build/m1"
$schemaPath = Join-Path $outputDirectory "schema.sql"
$metadataPath = Join-Path $outputDirectory "schema-metadata.json"

if (-not $UseExistingExport) {
    & (Join-Path $PSScriptRoot "acceptance-smoke.ps1")
}
if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
    throw "Schema export is missing: $schemaPath"
}

$schema = Get-Content -LiteralPath $schemaPath -Raw
$requiredTables = @(
    "project",
    "principal",
    "project_assignment",
    "release_record",
    "release_state_history",
    "manifest_revision",
    "artifact",
    "manifest_artifact",
    "manifest_validation",
    "audit_event",
    "idempotency_record",
    "outbox_event"
)
foreach ($table in $requiredTables) {
    if ($schema -notmatch "(?m)^CREATE TABLE (?:public\.)?$([regex]::Escape($table)) \(") {
        throw "Schema export does not contain required table '$table'"
    }
}

$schemaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $schemaPath).Hash.ToLowerInvariant()
$reportPath = Join-Path $outputDirectory "acceptance-smoke.json"
if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
    throw "Acceptance smoke report is required to prove schema provenance"
}
$report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$candidateCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $report.status -ne "PASS" -or $report.candidateCommit -ne $candidateCommit) {
    throw "Schema export provenance does not match the current candidate"
}
if ($report.schemaSha256 -ne $schemaHash) {
    throw "Schema export hash does not match the recovery smoke report"
}
$metadata = [ordered]@{
    schemaVersion = 1
    databaseImage = "postgres:17.11"
    generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
    schemaPath = "backend/build/m1/schema.sql"
    sha256 = $schemaHash
    requiredTables = $requiredTables
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM

Write-Output "PASS M1 schema-export tables=$($requiredTables.Count) sha256=$schemaHash"
