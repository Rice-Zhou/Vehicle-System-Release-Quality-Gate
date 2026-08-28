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
        $changed = @(& $Mutation $caseRoot)
        if ($changed.Count -ne 1 -or $changed[0] -ne 1) {
            throw "Mutation must change exactly one target"
        }
        & $pwsh -NoProfile -NonInteractive -File $verifier -Stage Frozen -RepositoryRoot $caseRoot *> $null
        if ($LASTEXITCODE -eq 0) { throw "Governance mutation was accepted" }
    } finally {
        Pop-Location
    }
}

function Set-ExactlyOneRegex {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Pattern,
        [Parameter(Mandatory)] [scriptblock]$Replacement
    )

    $content = Get-Content -Raw -LiteralPath $Path
    $regex = [regex]::new($Pattern)
    $matches = $regex.Matches($content)
    if ($matches.Count -ne 1) { throw "Mutation target count is not one" }
    $updated = $regex.Replace(
        $content,
        [Text.RegularExpressions.MatchEvaluator] { param($match) & $Replacement $match },
        1
    )
    if ($updated -ceq $content) { throw "Mutation did not change content" }
    Set-Content -LiteralPath $Path -Value $updated -Encoding utf8NoBOM -NoNewline
    return 1
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
        $target = Join-Path $root "docs/v0.2/tdr/TDR-013-controlled-local-file-identity.md"
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Mutation target is unavailable" }
        Remove-Item -LiteralPath $target
        if (Test-Path -LiteralPath $target) { throw "Mutation did not remove target" }
        return 1
    }
    Invoke-ExpectedFailure "duplicate" {
        param($root)
        $source = Join-Path $root "docs/v0.2/tdr/TDR-013-controlled-local-file-identity.md"
        $target = Join-Path $root "docs/v0.2/tdr/TDR-013-duplicate.md"
        if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Test-Path -LiteralPath $target)) {
            throw "Mutation target is unavailable"
        }
        Copy-Item -LiteralPath $source -Destination $target
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Mutation did not create target" }
        return 1
    }
    Invoke-ExpectedFailure "gap" {
        param($root)
        $source = Join-Path $root "docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md"
        $target = Join-Path $root "docs/v0.2/tdr/TDR-014-evidence-archive-acceptance-operations.md"
        if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Test-Path -LiteralPath $target)) {
            throw "Mutation target is unavailable"
        }
        Move-Item -LiteralPath $source -Destination $target
        if ((Test-Path -LiteralPath $source) -or -not (Test-Path -LiteralPath $target -PathType Leaf)) {
            throw "Mutation did not move target"
        }
        return 1
    }
    Invoke-ExpectedFailure "bad-status" {
        param($root)
        $path = Join-Path $root "docs/v0.2/tdr/TDR-013-controlled-local-file-identity.md"
        Set-ExactlyOneRegex -Path $path `
            -Pattern '(?m)^(?<prefix>-\s*(?:状态：|Status:\s*))Accepted\s*$' `
            -Replacement { param($match) "$($match.Groups['prefix'].Value)Proposed" }
    }
    Invoke-ExpectedFailure "index-mismatch" {
        param($root)
        $path = Join-Path $root "docs/v0.2/tdr/README.md"
        Set-ExactlyOneRegex -Path $path `
            -Pattern '(?m)^(?<prefix>\|\s*\[TDR-013\]\([^)]+\)\s*\|[^|\r\n]+\|\s*)Accepted(?<suffix>\s*\|\s*)$' `
            -Replacement { param($match) "$($match.Groups['prefix'].Value)Proposed$($match.Groups['suffix'].Value)" }
    }

    Write-Output "PASS design governance tests stage=$Stage mutations=5"
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
