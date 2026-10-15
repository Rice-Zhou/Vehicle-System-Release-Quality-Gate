#requires -Version 7.0
[CmdletBinding()]
param([string]$Config)
$ErrorActionPreference = 'Stop'
if (-not $Config -or -not [IO.Path]::IsPathFullyQualified($Config)) {
    [Console]::Error.WriteLine('QUALITY_DEMO_CONFIG_INVALID')
    exit 1
}
try {
    $node = (Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    & $node (Join-Path $PSScriptRoot 'quality-demo.mjs') $Config
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('QUALITY_DEMO_LAUNCH_FAILED')
    exit 1
}
