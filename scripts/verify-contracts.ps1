$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$expectedV01ManifestHash = "5d06d84c449615f917ad187599edf5e19ab468b335291a1faa0474278bddae82"
$v01Manifest = Join-Path $repositoryRoot "schemas/release-manifest.schema.json"
$actualV01ManifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v01Manifest).Hash.ToLowerInvariant()

if ($actualV01ManifestHash -ne $expectedV01ManifestHash) {
    throw "Frozen V0.1 manifest schema changed: expected $expectedV01ManifestHash, got $actualV01ManifestHash"
}

$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if ($null -eq $nodeCommand) {
    throw "Node.js is required. Install the pinned development dependencies with npm ci before validation."
}

if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot "node_modules") -PathType Container)) {
    throw "Missing node_modules. Run npm ci before scripts/verify-contracts.ps1."
}

Push-Location $repositoryRoot
try {
    & $nodeCommand.Source "scripts/contract-validator.mjs"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}

Write-Output "PASS frozen-v0.1-manifest sha256=$actualV01ManifestHash"
