[CmdletBinding()]
param(
    [ValidateSet("PreApproval", "ApprovedPreTag", "Frozen")]
    [string]$Stage = "Frozen",
    [string]$RepositoryRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
} else {
    (Resolve-Path $RepositoryRoot).Path
}
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
$expectedBaselineTdrStatus = if ($Stage -eq "PreApproval") { "Proposed for V0.2 Review" } else { "Accepted" }
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

$baselineTdrCount = 10
$tdrDirectory = Join-Path $repositoryRoot "docs/v0.2/tdr"
$tdrIndexPath = Join-Path $tdrDirectory "README.md"
$tdrFiles = @(Get-ChildItem -LiteralPath $tdrDirectory -File -Filter "TDR-*.md")
$tdrByNumber = @{}
foreach ($tdrFile in $tdrFiles) {
    if ($tdrFile.Name -notmatch '^TDR-(?<number>\d{3})-[a-z0-9-]+\.md$') {
        $failures.Add("Invalid TDR filename: $($tdrFile.Name)")
        continue
    }
    $number = [int]$Matches.number
    if ($tdrByNumber.ContainsKey($number)) {
        $failures.Add("Duplicate TDR number: TDR-{0:D3}" -f $number)
        continue
    }
    $tdrByNumber[$number] = $tdrFile
}

if ($tdrByNumber.Count -lt $baselineTdrCount) {
    $failures.Add("Architecture Review baseline requires TDR-001 through TDR-010")
}
$highestTdr = if ($tdrByNumber.Count -eq 0) { 0 } else { ($tdrByNumber.Keys | Measure-Object -Maximum).Maximum }
for ($number = 1; $number -le $highestTdr; $number++) {
    if (-not $tdrByNumber.ContainsKey($number)) {
        $failures.Add("Missing TDR in continuous sequence: TDR-{0:D3}" -f $number)
    }
}

$tdrStatuses = @{}
foreach ($entry in $tdrByNumber.GetEnumerator()) {
    $number = [int]$entry.Key
    $tdrFile = $entry.Value
    $expectedTdrStatus = if ($number -le $baselineTdrCount) { $expectedBaselineTdrStatus } else { "Accepted" }
    $statusLines = @(Get-Content -LiteralPath $tdrFile.FullName | Where-Object { $_ -match '^-[ ]+(状态|Status)[：:][ ]*' })
    if ($statusLines.Count -ne 1 -or -not $statusLines[0].EndsWith($expectedTdrStatus)) {
        $failures.Add("$($tdrFile.Name) must have exactly one $expectedTdrStatus status")
    } else {
        $tdrStatuses[$number] = $expectedTdrStatus
    }
}

$indexByNumber = @{}
foreach ($line in Get-Content -LiteralPath $tdrIndexPath) {
    if ($line -notmatch '^\|\s*\[TDR-(?<number>\d{3})\]\((?<file>TDR-\d{3}-[^)]+\.md)\)\s*\|.*\|\s*(?<status>[^|]+?)\s*\|\s*$') {
        continue
    }
    $number = [int]$Matches.number
    if ($indexByNumber.ContainsKey($number)) {
        $failures.Add("Duplicate TDR index entry: TDR-{0:D3}" -f $number)
        continue
    }
    $indexByNumber[$number] = [ordered]@{ file = $Matches.file; status = $Matches.status.Trim() }
}
foreach ($entry in $tdrByNumber.GetEnumerator()) {
    $number = [int]$entry.Key
    if (-not $indexByNumber.ContainsKey($number)) {
        $failures.Add("Missing TDR index entry: TDR-{0:D3}" -f $number)
        continue
    }
    $index = $indexByNumber[$number]
    if ($index.file -ne $entry.Value.Name) {
        $failures.Add("TDR-{0:D3} index filename does not match its file" -f $number)
    }
    if ($tdrStatuses.ContainsKey($number) -and $index.status -ne $tdrStatuses[$number]) {
        $failures.Add("TDR-{0:D3} index status does not match its file" -f $number)
    }
}
foreach ($number in $indexByNumber.Keys) {
    if (-not $tdrByNumber.ContainsKey($number)) {
        $failures.Add("TDR index references missing file: TDR-{0:D3}" -f $number)
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
Write-Output "PASS design-governance stage=$Stage version=$expectedVersion tdr=$($tdrByNumber.Count) baseline=$baselineTdrCount findings=10 tags=$tagCount"
