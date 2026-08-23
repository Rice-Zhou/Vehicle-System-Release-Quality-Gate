[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$verifier = Join-Path $repositoryRoot 'scripts\verify-language-branches.ps1'
if (-not (Test-Path -LiteralPath $verifier)) {
    throw "Verifier script missing: $verifier"
}

$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$fixture = Join-Path $tempRoot ("vsrqg-language-test-" + [guid]::NewGuid().ToString('N'))

function Invoke-FixtureGit {
    param([string[]]$Arguments)
    $output = @(& git -C $fixture @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Write-FixtureFile {
    param([string]$Path, [string]$Content)
    $fullPath = Join-Path $fixture $Path
    $directory = Split-Path -Parent $fullPath
    if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    [System.IO.File]::WriteAllText($fullPath, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-Verifier {
    param([string[]]$Arguments)
    Push-Location $fixture
    try {
        $output = @(& pwsh -NoProfile -File $verifier @Arguments 2>&1)
        return @{ ExitCode = $LASTEXITCODE; Output = $output -join [Environment]::NewLine }
    } finally {
        Pop-Location
    }
}

function Assert-ExitCode {
    param([int]$Expected, [hashtable]$Actual, [string]$Case)
    if ($Actual.ExitCode -ne $Expected) {
        throw "$Case expected exit $Expected, got $($Actual.ExitCode): $($Actual.Output)"
    }
}

try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    Invoke-FixtureGit -Arguments @('init', '-b', 'main') | Out-Null
    Invoke-FixtureGit -Arguments @('config', 'user.name', 'VSRQG Test') | Out-Null
    Invoke-FixtureGit -Arguments @('config', 'user.email', 'vsrqg-test@example.invalid') | Out-Null

    Write-FixtureFile -Path 'README.md' -Content "# 文档`n`n中文说明包含 ``Release``。`n`n[详情](docs/detail.md)`n"
    Write-FixtureFile -Path 'docs/detail.md' -Content "# 详情`n`n``Manifest`` 必须锁定。`n"
    Write-FixtureFile -Path 'data.json' -Content "{`"version`":1}`n"
    Invoke-FixtureGit -Arguments @('add', '--', 'README.md', 'docs/detail.md', 'data.json') | Out-Null
    Invoke-FixtureGit -Arguments @('commit', '-m', 'zh') | Out-Null

    Assert-ExitCode -Expected 1 -Actual (Invoke-Verifier -Arguments @('-Mode', 'Pair', '-ChineseRef', 'main', '-EnglishRef', 'release')) -Case 'missing ref'

    Invoke-FixtureGit -Arguments @('switch', '-c', 'release') | Out-Null
    Write-FixtureFile -Path 'README.md' -Content "# Documentation`n`nEnglish prose contains ``Release``.`n`n[Details](docs/detail.md)`n"
    Write-FixtureFile -Path 'docs/detail.md' -Content "# Details`n`n``Manifest`` must be locked.`n"
    Invoke-FixtureGit -Arguments @('add', '--', 'README.md', 'docs/detail.md') | Out-Null
    Invoke-FixtureGit -Arguments @('commit', '-m', 'en') | Out-Null

    Assert-ExitCode -Expected 0 -Actual (Invoke-Verifier -Arguments @('-Mode', 'Pair', '-ChineseRef', 'main', '-EnglishRef', 'release')) -Case 'valid pair'

    Write-FixtureFile -Path 'README.md' -Content "# Documentation`n`nEnglish prose contains 中文 and ``Release``.`n`n[Details](docs/detail.md)`n"
    Invoke-FixtureGit -Arguments @('add', '--', 'README.md') | Out-Null
    Invoke-FixtureGit -Arguments @('commit', '-m', 'cjk') | Out-Null
    Assert-ExitCode -Expected 1 -Actual (Invoke-Verifier -Arguments @('-Mode', 'Pair', '-ChineseRef', 'main', '-EnglishRef', 'release')) -Case 'CJK in English'
    Invoke-FixtureGit -Arguments @('reset', '--hard', 'HEAD~1') | Out-Null

    Write-FixtureFile -Path 'data.json' -Content "{`"version`":2}`n"
    Invoke-FixtureGit -Arguments @('add', '--', 'data.json') | Out-Null
    Invoke-FixtureGit -Arguments @('commit', '-m', 'blob mismatch') | Out-Null
    Assert-ExitCode -Expected 1 -Actual (Invoke-Verifier -Arguments @('-Mode', 'Pair', '-ChineseRef', 'main', '-EnglishRef', 'release')) -Case 'non-Markdown mismatch'
    Invoke-FixtureGit -Arguments @('reset', '--hard', 'HEAD~1') | Out-Null

    Write-FixtureFile -Path 'extra.md' -Content "# Extra`n"
    Invoke-FixtureGit -Arguments @('add', '--', 'extra.md') | Out-Null
    Invoke-FixtureGit -Arguments @('commit', '-m', 'path mismatch') | Out-Null
    Assert-ExitCode -Expected 1 -Actual (Invoke-Verifier -Arguments @('-Mode', 'Pair', '-ChineseRef', 'main', '-EnglishRef', 'release')) -Case 'path mismatch'
    Invoke-FixtureGit -Arguments @('reset', '--hard', 'HEAD~1') | Out-Null

    Write-FixtureFile -Path 'README.md' -Content "# Documentation`n`nEnglish prose contains ``Release``.`n`n[Missing](docs/missing.md)`n"
    Invoke-FixtureGit -Arguments @('add', '--', 'README.md') | Out-Null
    Invoke-FixtureGit -Arguments @('commit', '-m', 'broken link') | Out-Null
    Assert-ExitCode -Expected 1 -Actual (Invoke-Verifier -Arguments @('-Mode', 'Pair', '-ChineseRef', 'main', '-EnglishRef', 'release')) -Case 'broken link'

    Write-Output 'PASS tests=6'
} finally {
    $resolvedFixture = [System.IO.Path]::GetFullPath($fixture)
    if ($resolvedFixture.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedFixture)) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
