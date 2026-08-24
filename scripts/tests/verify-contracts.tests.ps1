$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$verifier = Join-Path $repositoryRoot "scripts/verify-contracts.ps1"

$requiredArtifacts = @(
    "contracts/openapi/v0.2/openapi.json",
    "schemas/v0.2/agent-protocol.schema.json",
    "schemas/v0.2/quality-rule.schema.json",
    "schemas/v0.2/fact-catalog.schema.json",
    "contracts/facts/v0.2/fact-catalog.json",
    "schemas/v0.2/release-manifest.schema.json"
)

foreach ($relativePath in $requiredArtifacts) {
    $absolutePath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Missing required AR-01 artifact: $relativePath"
    }
}

if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) {
    throw "Missing contract verifier: scripts/verify-contracts.ps1"
}

& $verifier
if ($LASTEXITCODE -ne 0) {
    throw "Contract verifier failed with exit code $LASTEXITCODE"
}

Write-Output "PASS contract artifact tests"
