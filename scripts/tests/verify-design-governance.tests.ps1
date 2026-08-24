param(
    [ValidateSet("PreApproval", "ApprovedPreTag", "Frozen")]
    [string]$Stage = "PreApproval"
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$verifier = Join-Path $repositoryRoot "scripts/verify-design-governance.ps1"
$ownerChecklist = Join-Path $repositoryRoot "docs/v0.2/reviews/2026-08-24-owner-acceptance-checklist.md"

if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) {
    throw "Missing governance verifier: scripts/verify-design-governance.ps1"
}

if (-not (Test-Path -LiteralPath $ownerChecklist -PathType Leaf)) {
    throw "Missing Owner acceptance checklist"
}

& $verifier -Stage $Stage
if ($LASTEXITCODE -ne 0) {
    throw "Governance verifier failed with exit code $LASTEXITCODE"
}

Write-Output "PASS design governance tests stage=$Stage"
