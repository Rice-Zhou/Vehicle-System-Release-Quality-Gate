#requires -Version 7.0
[CmdletBinding()]
param([string]$Config)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$stage='CONFIG_INVALID'
$reportPath=$null
$sourceCommit=$null
$dirty=$null
$phase='VALIDATE'
$configurationValidated=$false
$executionMode=$null
function Read-Config([string]$Path) {
    if(-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -gt 65536) { throw 'CONFIG_INVALID' }
    $doc=[System.Text.Json.JsonDocument]::Parse([IO.File]::ReadAllText($Path))
    try {
        $names=@($doc.RootElement.EnumerateObject() | ForEach-Object Name)
        $required=@('server','identityConfig','apk','deviceConfig','payloadRoot','spool','outputRoot','planVersion')
        if($names.Count -ne $required.Count -or @($names | Select-Object -Unique).Count -ne $names.Count -or @(Compare-Object $names $required).Count) { throw 'CONFIG_INVALID' }
        $value=[IO.File]::ReadAllText($Path) | ConvertFrom-Json -AsHashtable
        if($value.planVersion -isnot [long] -and $value.planVersion -isnot [int]) { throw 'CONFIG_INVALID' }
        if($value.planVersion -notin @(1,2)) { throw 'CONFIG_INVALID' }
        foreach($field in @('identityConfig','apk','deviceConfig','payloadRoot','spool','outputRoot')) {
            if($value[$field] -isnot [string] -or -not [IO.Path]::IsPathFullyQualified($value[$field])) { throw 'CONFIG_INVALID' }
        }
        return $value
    } finally { $doc.Dispose() }
}
. (Join-Path $PSScriptRoot 'm3-process.ps1')
function Invoke-Bounded([string]$File,[string[]]$Arguments,[int]$Seconds=900) {
    try {
        $result=Invoke-M3Child -File $File -Arguments $Arguments -WorkingDirectory $repository -TimeoutSeconds $Seconds -Environment @{
            VSRQG_M3_CONFIG=[IO.Path]::GetFullPath($Config);VSRQG_M3_COMMIT=$sourceCommit;VSRQG_M3_DIRTY=$dirty.ToString().ToLowerInvariant();VSRQG_M3_PHASE=$phase
        }
    } catch {
        if($_.Exception.Message -in @('PROCESS_TIMEOUT','PROCESS_OUTPUT_LIMIT','PROCESS_CLEANUP_FAILED')) { $script:stage=$_.Exception.Message }
        throw
    }
    if($result.ExitCode -ne 0) {
        if(($result.Output+$result.Error) -match '(?m)^CONFIG_INVALID\s*$') { $script:stage='CONFIG_INVALID' }
        throw $script:stage
    }
}
try {
    $settings=Read-Config $Config
    $reportPath=Join-Path $settings.outputRoot 'summary.json'
    if(Test-Path -LiteralPath $settings.outputRoot) { throw 'CONFIG_INVALID' }
    $sourceCommit=(& git -C $repository rev-parse HEAD 2>$null) -join ''
    if($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[a-f0-9]{40}$') { throw 'CONFIG_INVALID' }
    $status=& git -C $repository status --porcelain --untracked-files=all 2>$null
    if($LASTEXITCODE -ne 0) { throw 'CONFIG_INVALID' }
    $dirty=[bool]$status
    $stage='CONFIG_CHECK_FAILED'
    $suffix=if($IsWindows) { 'gradlew.bat' } else { 'gradlew' }
    Invoke-Bounded (Join-Path $repository "backend/$suffix") @('-p',(Join-Path $repository 'backend'),'--no-daemon','--console=plain','m3Demo')
    $configurationValidated=$true
    $phase='RUN'
    $stage='BUILD_FAILED'
    Invoke-Bounded (Join-Path $repository "agent/$suffix") @('-p',(Join-Path $repository 'agent'),'--no-daemon','--console=plain','installDist')
    $stage='SCENARIO_PROCESS_FAILED'
    Invoke-Bounded (Join-Path $repository "backend/$suffix") @('-p',(Join-Path $repository 'backend'),'--no-daemon','--console=plain','m3Demo')
    $stage='REPORT_INVALID'
    $report=Get-Content -Raw -LiteralPath $reportPath | ConvertFrom-Json
    if($report.executionMode -in @('REAL_DEVICE','CI_FIXTURE')) { $executionMode=$report.executionMode }
    if($report.schemaVersion -ne '1.0' -or $report.classification -ne 'SYNTHETIC_DEMO' -or $report.executionMode -notin @('REAL_DEVICE','CI_FIXTURE') -or
       $report.generationStatus -ne 'SUCCEEDED' -or $report.scenarioOutcome -ne 'PASS' -or $report.codeCommit -ne $sourceCommit -or
       $report.caseStatus -ne $(if($settings.planVersion -eq 1) {'PASS'} else {'FAIL'}) -or $report.evidence.Count -ne 2) { throw 'REPORT_INVALID' }
    if(@($report.evidence.type | Select-Object -Unique).Count -ne 2) { throw 'REPORT_INVALID' }
    foreach($item in $report.evidence) {
        $expectedFile=if($item.type -eq 'LOG') {'log.txt'} elseif($item.type -eq 'SCREENSHOT') {'screenshot.png'} else { throw 'REPORT_INVALID' }
        if($item.file -ne $expectedFile -or $item.evidenceId -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'REPORT_INVALID' }
        $file=Join-Path $settings.outputRoot $expectedFile
        $actual='sha256:'+(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        if($actual -ne $item.payloadChecksum -or $actual -ne $item.downloadSha256 -or (Get-Item -LiteralPath $file).Length -ne $item.sizeBytes) { throw 'REPORT_INVALID' }
    }
    Write-Output "SYNTHETIC_DEMO $($report.executionMode) scenario=$($report.scenarioOutcome) case=$($report.caseStatus)"
    exit 0
} catch {
    [Console]::Error.WriteLine($stage)
    if($stage -ne 'CONFIG_INVALID' -and $reportPath -and $configurationValidated) {
        try {
            if(-not (Test-Path -LiteralPath $reportPath) -or $stage -eq 'REPORT_INVALID') {
                New-Item -ItemType Directory -Force (Split-Path -Parent $reportPath) | Out-Null
                @{schemaVersion='1.0';classification='SYNTHETIC_DEMO';executionMode=$executionMode;codeCommit=$sourceCommit;workingTreeDirty=$dirty;
                  generationStatus='FAILED';scenarioOutcome='FAILED';caseStatus=$null;errorCodes=@($stage);notCovered=@('REAL_DEVICE_COMPLETION','CRASH','ANR','POWER_LOSS','COMPANY')} |
                    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8NoBOM
            }
        } catch { [Console]::Error.WriteLine('REPORT_WRITE_FAILED') }
    }
    exit 1
}
