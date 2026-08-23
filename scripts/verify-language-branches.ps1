[CmdletBinding()]
param(
    [string]$ChineseRef = 'main',
    [string]$EnglishRef = 'release',
    [ValidateSet('Pair', 'ChineseOnly', 'EnglishOnly')]
    [string]$Mode = 'Pair'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Invoke-GitLines {
    param([string[]]$Arguments)
    $output = @(& git @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Test-GitRef {
    param([string]$Ref)
    & git rev-parse --verify --quiet "$Ref^{commit}" *> $null
    return $LASTEXITCODE -eq 0
}

function Get-RefPaths {
    param([string]$Ref)
    return @(Invoke-GitLines -Arguments @('ls-tree', '-r', '--name-only', $Ref))
}

function Get-RefText {
    param([string]$Ref, [string]$Path)
    return (Invoke-GitLines -Arguments @('show', "${Ref}:$Path")) -join "`n"
}

function Resolve-RepoPath {
    param([string]$SourcePath, [string]$Target)
    $targetPath = $Target.Trim('<', '>').Split('#')[0].Replace('\', '/')
    if (-not $targetPath) { return $null }

    $parts = [System.Collections.Generic.List[string]]::new()
    if (-not $targetPath.StartsWith('/')) {
        $sourceDirectory = [System.IO.Path]::GetDirectoryName($SourcePath)
        if ($sourceDirectory) {
            foreach ($part in $sourceDirectory.Replace('\', '/').Split('/')) {
                $parts.Add($part)
            }
        }
    }
    foreach ($part in $targetPath.TrimStart('/').Split('/')) {
        if (-not $part -or $part -eq '.') { continue }
        if ($part -eq '..') {
            if ($parts.Count -eq 0) { return $null }
            $parts.RemoveAt($parts.Count - 1)
        } else {
            $parts.Add($part)
        }
    }
    return $parts -join '/'
}

function Get-InlineTokens {
    param([string]$Text)
    return @([regex]::Matches($Text, '`([^`\r\n]+)`') | ForEach-Object {
        $_.Groups[1].Value
    } | Sort-Object)
}

function Get-HeadingShape {
    param([string]$Text)
    return @([regex]::Matches($Text, '(?m)^(#{1,6})\s+') | ForEach-Object {
        $_.Groups[1].Value.Length
    })
}

function Test-MarkdownRef {
    param(
        [string]$Ref,
        [ValidateSet('Chinese', 'English')]
        [string]$Language,
        [string[]]$Paths
    )

    $pathSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$Paths)
    foreach ($path in $Paths | Where-Object { $_.EndsWith('.md') }) {
        $text = Get-RefText -Ref $Ref -Path $path
        $fenceCount = ([regex]::Matches($text, '(?m)^```')).Count
        if (($fenceCount % 2) -ne 0) {
            $failures.Add("$Ref unbalanced fence: $path")
        }
        if ($Language -eq 'English' -and [regex]::IsMatch($text, '[\u4e00-\u9fff]')) {
            $failures.Add("$Ref contains CJK: $path")
        }
        if ($Language -eq 'Chinese') {
            $prose = [regex]::Replace($text, '(?ms)^```.*?^```\s*$', '')
            if (-not [regex]::IsMatch($prose, '[\u4e00-\u9fff]')) {
                $failures.Add("$Ref lacks Chinese prose: $path")
            }
        }
        foreach ($match in [regex]::Matches($text, '\[[^\]]+\]\(([^)]+)\)')) {
            $target = $match.Groups[1].Value
            if ($target -match '^(https?://|mailto:|#)') { continue }
            $resolved = Resolve-RepoPath -SourcePath $path -Target $target
            if (-not $resolved -or -not $pathSet.Contains($resolved)) {
                $failures.Add("$Ref broken link: $path -> $target")
            }
        }
    }
}

if ($Mode -in @('Pair', 'ChineseOnly') -and -not (Test-GitRef -Ref $ChineseRef)) {
    Write-Error "missing ref: $ChineseRef"
    exit 1
}
if ($Mode -in @('Pair', 'EnglishOnly') -and -not (Test-GitRef -Ref $EnglishRef)) {
    Write-Error "missing ref: $EnglishRef"
    exit 1
}

if ($Mode -eq 'ChineseOnly') {
    $paths = Get-RefPaths -Ref $ChineseRef
    Test-MarkdownRef -Ref $ChineseRef -Language Chinese -Paths $paths
} elseif ($Mode -eq 'EnglishOnly') {
    $paths = Get-RefPaths -Ref $EnglishRef
    Test-MarkdownRef -Ref $EnglishRef -Language English -Paths $paths
} else {
    $chinesePaths = Get-RefPaths -Ref $ChineseRef
    $englishPaths = Get-RefPaths -Ref $EnglishRef
    $pathDiff = Compare-Object $chinesePaths $englishPaths
    foreach ($difference in $pathDiff) {
        $failures.Add("path mismatch $($difference.SideIndicator): $($difference.InputObject)")
    }

    Test-MarkdownRef -Ref $ChineseRef -Language Chinese -Paths $chinesePaths
    Test-MarkdownRef -Ref $EnglishRef -Language English -Paths $englishPaths

    foreach ($path in $chinesePaths | Where-Object { -not $_.EndsWith('.md') }) {
        if ($englishPaths -notcontains $path) { continue }
        $zhBlob = Invoke-GitLines -Arguments @('rev-parse', "${ChineseRef}:$path") | Select-Object -First 1
        $enBlob = Invoke-GitLines -Arguments @('rev-parse', "${EnglishRef}:$path") | Select-Object -First 1
        if ($zhBlob -ne $enBlob) {
            $failures.Add("non-Markdown mismatch: $path")
        }
    }
    foreach ($path in $chinesePaths | Where-Object { $_.EndsWith('.md') }) {
        if ($englishPaths -notcontains $path) { continue }
        $zhText = Get-RefText -Ref $ChineseRef -Path $path
        $enText = Get-RefText -Ref $EnglishRef -Path $path
        $zhHeadings = (Get-HeadingShape -Text $zhText) -join ','
        $enHeadings = (Get-HeadingShape -Text $enText) -join ','
        if ($zhHeadings -ne $enHeadings) {
            $failures.Add("heading shape mismatch: $path")
        }
        $zhTokens = (Get-InlineTokens -Text $zhText) -join "`n"
        $enTokens = (Get-InlineTokens -Text $enText) -join "`n"
        if ($zhTokens -ne $enTokens) {
            $failures.Add("inline token mismatch: $path")
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | Sort-Object -Unique | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "PASS mode=$Mode chinese=$ChineseRef english=$EnglishRef"
