[CmdletBinding()]
param(
    [ValidateSet("PreApproval", "ApprovedPreTag", "Frozen")]
    [string]$Stage = "PreApproval"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$expectedReviewId = "V0.2-AR-2026-08-23-01"
$reviewPath = Join-Path $repositoryRoot "docs/v0.2/reviews/2026-08-23-architecture-review.md"
$checklistPath = Join-Path $repositoryRoot "docs/v0.2/reviews/2026-08-24-owner-acceptance-checklist.md"
$failures = [System.Collections.Generic.List[string]]::new()

function Require-Text {
    param([string]$Path, [string]$Expected)
    $content = Get-Content -Raw -LiteralPath $Path
    if (-not $content.Contains($Expected)) {
        $failures.Add("$Path does not contain required text: $Expected")
    }
}

Require-Text -Path $reviewPath -Expected $expectedReviewId
Require-Text -Path $checklistPath -Expected $expectedReviewId
Require-Text -Path $checklistPath -Expected "v0.2.0-design-zh"
Require-Text -Path $checklistPath -Expected "v0.2.0-design-en"

$expectedVersion = if ($Stage -eq "PreApproval") { "0.2.0-draft.2" } else { "0.2.0" }
$expectedTdrStatus = if ($Stage -eq "PreApproval") { "Proposed for V0.2 Review" } else { "Accepted" }
Require-Text -Path (Join-Path $repositoryRoot "docs/v0.2/README.md") -Expected $expectedVersion
Require-Text -Path (Join-Path $repositoryRoot "docs/language-policy.md") -Expected $expectedVersion
if ($Stage -eq "PreApproval") {
    Require-Text -Path $reviewPath -Expected "READY_FOR_OWNER_FINAL_REVIEW"
    Require-Text -Path $reviewPath -Expected "AWAITING_OWNER_FINAL_APPROVAL"
    Require-Text -Path $checklistPath -Expected "OWNER_DECISION_REQUIRED"
} else {
    Require-Text -Path $reviewPath -Expected "APPROVED_FOR_DESIGN_FREEZE"
    Require-Text -Path $checklistPath -Expected "Decision: APPROVE"
}

$markdownFiles = @(
    Get-Item -LiteralPath (Join-Path $repositoryRoot "README.md")
    Get-Item -LiteralPath (Join-Path $repositoryRoot "CHANGELOG.md")
    Get-Item -LiteralPath (Join-Path $repositoryRoot "AGENTS.md")
    Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "docs") -Recurse -File -Filter "*.md"
)
foreach ($file in $markdownFiles) {
    $bareTag = Select-String -LiteralPath $file.FullName -SimpleMatch '`v0.2.0-design`'
    if ($bareTag) {
        $failures.Add("Bare unpaired Design Freeze tag in $($file.FullName)")
    }
}

$tdrFiles = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "docs/v0.2/tdr") -File -Filter "TDR-*.md")
if ($tdrFiles.Count -ne 10) {
    $failures.Add("Expected 10 TDR files, found $($tdrFiles.Count)")
}
foreach ($tdrFile in $tdrFiles) {
    $statusLines = @(Get-Content -LiteralPath $tdrFile.FullName | Where-Object { $_ -match '^-[ ]+(状态|Status)[：:][ ]*' })
    if ($statusLines.Count -ne 1 -or -not $statusLines[0].EndsWith($expectedTdrStatus)) {
        $failures.Add("$($tdrFile.Name) must have exactly one $expectedTdrStatus status")
    }
}

$review = Get-Content -Raw -LiteralPath $reviewPath
for ($number = 1; $number -le 10; $number++) {
    $finding = "AR-{0:D2}" -f $number
    $nextFinding = "AR-{0:D2}" -f ($number + 1)
    $start = $review.IndexOf("### $finding")
    if ($start -lt 0) {
        $failures.Add("Missing finding $finding")
        continue
    }
    $end = if ($number -lt 10) { $review.IndexOf("### $nextFinding", $start) } else { $review.IndexOf("## 6.", $start) }
    if ($end -lt 0) { $end = $review.Length }
    $block = $review.Substring($start, $end - $start)
    if ($block -notmatch 'Resolution Status[：:].*(DESIGN_RESOLVED|GOVERNANCE_READY)') {
        $failures.Add("$finding lacks a resolved governance status")
    }
}

$designTags = @(& git -C $repositoryRoot tag --list "v0.2.0-design*")
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Git tags"
}
if ($Stage -eq "Frozen") {
    $expectedTags = @("v0.2.0-design-en", "v0.2.0-design-zh")
    $actualTags = @($designTags | Sort-Object)
    if (($actualTags -join ',') -ne ($expectedTags -join ',')) {
        $failures.Add("Frozen stage requires exactly the paired Design Freeze tags")
    } else {
        foreach ($tag in $expectedTags) {
            $pairedTag = if ($tag.EndsWith("-zh")) { "v0.2.0-design-en" } else { "v0.2.0-design-zh" }
            $tagType = (& git -C $repositoryRoot cat-file -t "refs/tags/$tag" 2>$null)
            $tagMessage = (& git -C $repositoryRoot tag -l $tag --format='%(contents)') -join "`n"
            if ($tagType -ne "tag") { $failures.Add("$tag must be annotated") }
            if (-not $tagMessage.Contains($expectedReviewId) -or -not $tagMessage.Contains($pairedTag)) {
                $failures.Add("$tag message must reference Review ID and $pairedTag")
            }
        }
    }
} elseif ($designTags.Count -ne 0) {
    $failures.Add("Design Freeze tags exist before the Frozen stage: $($designTags -join ', ')")
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

$tagCount = $designTags.Count
Write-Output "PASS design-governance stage=$Stage version=$expectedVersion tdr=10 findings=10 tags=$tagCount"
