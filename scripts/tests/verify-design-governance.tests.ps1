param(
    [ValidateSet("PreApproval", "ApprovedPreTag", "Frozen")]
    [string]$Stage = "Frozen"
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$verifier = Join-Path $repositoryRoot "scripts/verify-design-governance.ps1"
$ownerChecklist = Join-Path $repositoryRoot "docs/v0.2/reviews/2026-08-24-owner-acceptance-checklist.md"
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "vsrqg-design-governance-$([Guid]::NewGuid().ToString('N'))"
$pwsh = (Get-Process -Id $PID).Path

if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) {
    throw "Missing governance verifier: scripts/verify-design-governance.ps1"
}
if (-not (Test-Path -LiteralPath $ownerChecklist -PathType Leaf)) {
    throw "Missing Owner acceptance checklist"
}

function Invoke-ExpectedFailure {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [scriptblock]$Mutation
    )

    $caseRoot = Join-Path $fixtureRoot $Name
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $fixtureRoot "base/README.md") -Destination $caseRoot
    Copy-Item -LiteralPath (Join-Path $fixtureRoot "base/CHANGELOG.md") -Destination $caseRoot
    Copy-Item -LiteralPath (Join-Path $fixtureRoot "base/AGENTS.md") -Destination $caseRoot
    Copy-Item -LiteralPath (Join-Path $fixtureRoot "base/docs") -Destination $caseRoot -Recurse
    Push-Location $caseRoot
    try {
        & git init --quiet
        & git -c core.autocrlf=false add . 2>$null
        & git -c user.name=fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
        & git -c user.name=fixture -c user.email=fixture@example.invalid tag -a v0.2.0-design-zh -m "V0.2-AR-2026-08-23-01 v0.2.0-design-en"
        & git -c user.name=fixture -c user.email=fixture@example.invalid tag -a v0.2.0-design-en -m "V0.2-AR-2026-08-23-01 v0.2.0-design-zh"
        & $Mutation $caseRoot
        & $pwsh -NoProfile -NonInteractive -File $verifier -Stage Frozen -RepositoryRoot $caseRoot *> $null
        if ($LASTEXITCODE -eq 0) { throw "$Name mutation was accepted" }
    } finally {
        Pop-Location
    }
}

try {
    & $pwsh -NoProfile -NonInteractive -File $verifier -Stage $Stage
    if ($LASTEXITCODE -ne 0) { throw "Governance verifier failed with exit code $LASTEXITCODE" }

    $baseRoot = Join-Path $fixtureRoot "base"
    New-Item -ItemType Directory -Path $baseRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "README.md") -Destination $baseRoot
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "CHANGELOG.md") -Destination $baseRoot
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "AGENTS.md") -Destination $baseRoot
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "docs") -Destination $baseRoot -Recurse

    Invoke-ExpectedFailure "missing" {
        param($root)
        Remove-Item -LiteralPath (Join-Path $root "docs/v0.2/tdr/TDR-013-controlled-local-file-identity.md")
    }
    Invoke-ExpectedFailure "duplicate" {
        param($root)
        Copy-Item -LiteralPath (Join-Path $root "docs/v0.2/tdr/TDR-013-controlled-local-file-identity.md") `
            -Destination (Join-Path $root "docs/v0.2/tdr/TDR-013-duplicate.md")
    }
    Invoke-ExpectedFailure "gap" {
        param($root)
        Move-Item -LiteralPath (Join-Path $root "docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md") `
            -Destination (Join-Path $root "docs/v0.2/tdr/TDR-014-evidence-archive-acceptance-operations.md")
    }
    Invoke-ExpectedFailure "bad-status" {
        param($root)
        $path = Join-Path $root "docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md"
        (Get-Content -Raw -LiteralPath $path).Replace("- 状态：Accepted", "- 状态：Proposed") |
            Set-Content -LiteralPath $path -Encoding utf8NoBOM
    }
    Invoke-ExpectedFailure "index-mismatch" {
        param($root)
        $path = Join-Path $root "docs/v0.2/tdr/README.md"
        $content = Get-Content -Raw -LiteralPath $path
        $content.Replace(
            "| [TDR-013](TDR-013-controlled-local-file-identity.md) | 受控本地文件身份与 Windows 参数桥 | Accepted |",
            "| [TDR-013](TDR-013-controlled-local-file-identity.md) | 受控本地文件身份与 Windows 参数桥 | Proposed |"
        ) | Set-Content -LiteralPath $path -Encoding utf8NoBOM
    }

    Write-Output "PASS design governance tests stage=$Stage mutations=5"
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
