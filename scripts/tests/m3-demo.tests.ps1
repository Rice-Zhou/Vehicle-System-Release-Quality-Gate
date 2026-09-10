#requires -Version 7.0
$ErrorActionPreference='Stop'
$pwsh=(Get-Command pwsh -CommandType Application -ErrorAction Stop).Source
$entry=Join-Path $PSScriptRoot '../demo/run-m3.ps1'
$root=Join-Path ([IO.Path]::GetTempPath()) ('m3-wrapper-tests-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $root | Out-Null
foreach($name in @('missing','absent','unknown','duplicate')) {
    $config=Join-Path $root "$name.json"
    $reportPath=Join-Path $root "$name-output/summary.json"
    if($name -eq 'missing') { @{planVersion=1;outputRoot=(Split-Path -Parent $reportPath)} | ConvertTo-Json | Set-Content $config }
    if($name -eq 'unknown') { '{"planVersion":1,"token":"SECRET_SENTINEL"}' | Set-Content $config }
    if($name -eq 'duplicate') { '{"planVersion":1,"planVersion":2}' | Set-Content $config }
    $diagnostic=& $pwsh -NoProfile -File $entry -Config $config 2>&1
    if($LASTEXITCODE -eq 0 -or ($diagnostic -join "`n") -notmatch 'CONFIG_INVALID') { throw "${name}: expected CONFIG_INVALID and nonzero exit" }
    if(Test-Path -LiteralPath $reportPath) { throw "$name generated a result report" }
    if(($diagnostic -join "`n") -match 'SECRET_SENTINEL') { throw "$name leaked input" }
    Write-Output "PASS $name configuration"
}

$diagnostic=& $pwsh -NoProfile -File $entry 2>&1
if($LASTEXITCODE -eq 0 -or ($diagnostic -join "`n") -notmatch 'CONFIG_INVALID') { throw 'missing Config argument was not rejected' }
Write-Output 'PASS missing Config argument'
$fixtureEntry=Join-Path $PSScriptRoot 'fixtures/m3-demo-command.ps1'
$checkout=Join-Path $root 'checkout with spaces'
foreach($directory in @('scripts/demo','backend','agent')) { New-Item -ItemType Directory -Force (Join-Path $checkout $directory) | Out-Null }
Copy-Item -LiteralPath $entry -Destination (Join-Path $checkout 'scripts/demo/run-m3.ps1')
$helper=Join-Path $PSScriptRoot '../demo/m3-process.ps1'
if(Test-Path $helper) { Copy-Item -LiteralPath $helper -Destination (Join-Path $checkout 'scripts/demo/m3-process.ps1') }
$stub=Join-Path $checkout 'command.ps1';Copy-Item -LiteralPath $fixtureEntry -Destination $stub
foreach($component in @('backend','agent')) {
    $shim=Join-Path $checkout "$component/$(if($IsWindows){'gradlew.bat'}else{'gradlew'})"
    if($IsWindows) { "@`"$pwsh`" -NoProfile -File `"$stub`" wrapper %*`r`n@exit /b %errorlevel%" | Set-Content $shim }
    else { "#!/bin/sh`nexec '$pwsh' -NoProfile -File '$stub' wrapper `"`$@`"" | Set-Content $shim; & chmod +x $shim }
}
& git -C $checkout init --quiet
if($LASTEXITCODE -ne 0) { throw 'fixture git init failed' }
& git -C $checkout -c user.name=Fixture -c user.email=fixture@example.invalid commit --quiet --allow-empty -m fixture
if($LASTEXITCODE -ne 0) { throw 'fixture git commit failed' }
foreach($mode in @('success','expected-fail','build-failure','child-failure','missing-report','wrong-hash')) {
    $output=Join-Path $root $mode
    $path=Join-Path $root "$mode-config.json"
    @{server=@{origin='https://localhost:8443';lifecycle='START'};identityConfig=(Join-Path $root 'identity.json');apk=(Join-Path $root 'smoke.apk');
      deviceConfig=(Join-Path $root 'device.json');payloadRoot=(Join-Path $root 'payload');spool=(Join-Path $root 'spool');outputRoot=$output;
      planVersion=$(if($mode -eq 'expected-fail'){2}else{1})} | ConvertTo-Json -Depth 5 | Set-Content $path
    $env:VSRQG_M3_TEST_MODE=$mode
    $diagnostic=& $pwsh -NoProfile -File (Join-Path $checkout 'scripts/demo/run-m3.ps1') -Config $path 2>&1
    $exit=$LASTEXITCODE
    $report=Get-Content -Raw (Join-Path $output 'summary.json') | ConvertFrom-Json
    $success=$mode -in @('success','expected-fail')
    if(($exit -eq 0) -ne $success) { throw "$mode wrong exit: $exit; $diagnostic" }
    if(($report.generationStatus -eq 'SUCCEEDED') -ne $success) { throw "$mode wrong generation status" }
    if(($diagnostic -join "`n") -match 'CHILD_SECRET_SENTINEL') { throw "$mode leaked child diagnostics" }
    if($success) {
        if($report.runId -ne 'run_wrapper_fixture' -or $report.caseStatus -ne $(if($mode -eq 'expected-fail'){'FAIL'}else{'PASS'})) { throw "$mode wrong source results" }
        foreach($item in $report.evidence) {
            $hash='sha256:'+(Get-FileHash (Join-Path $output $item.file) -Algorithm SHA256).Hash.ToLowerInvariant()
            if($item.payloadChecksum -ne $hash -or $item.downloadSha256 -ne $hash) { throw "$mode wrong retained bytes" }
        }
    } elseif($report.errorCodes.Count -eq 0) { throw "$mode omitted failure code" }
    Write-Output "PASS wrapper $mode"
}
Remove-Item Env:VSRQG_M3_TEST_MODE
. $helper
$existingStart=[Diagnostics.ProcessStartInfo]::new($pwsh)
$existingStart.UseShellExecute=$false;$existingStart.CreateNoWindow=$true
foreach($arg in @('-NoProfile','-File',$fixtureEntry,'sleep')) { $existingStart.ArgumentList.Add($arg) }
$existing=[Diagnostics.Process]::Start($existingStart)
try {
    $pidFile=Join-Path $root 'owned.pid'
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $failure=$null
    try { Invoke-M3Child -File $pwsh -Arguments @('-NoProfile','-File',$fixtureEntry,'sleep') -WorkingDirectory $root -TimeoutSeconds 1 -Environment @{VSRQG_M3_TEST_PIDFILE=$pidFile} | Out-Null }
    catch { $failure=$_.Exception.Message }
    if($failure -ne 'PROCESS_TIMEOUT' -or $timer.Elapsed.TotalSeconds -gt 12) { throw 'bounded timeout was not enforced' }
    $ownedPid=[int](Get-Content $pidFile)
    if(Get-Process -Id $ownedPid -ErrorAction SilentlyContinue) { throw 'timed out owned process remains alive' }
    if($existing.HasExited) { throw 'cleanup stopped existing unrelated service process' }
    Write-Output 'PASS owned timeout and existing process isolation'
    $result=Invoke-M3Child -File $pwsh -Arguments @('-NoProfile','-File',$fixtureEntry,'failure') -WorkingDirectory $root -TimeoutSeconds 5
    if($result.ExitCode -ne 7) { throw 'native child exit code was lost' }
    Write-Output 'PASS child nonzero propagation'
    $failure=$null
    try { Invoke-M3Child -File $pwsh -Arguments @('-NoProfile','-Command',"[Console]::Out.Write('x'*10000)") -WorkingDirectory $root -TimeoutSeconds 5 -OutputLimit 64 | Out-Null }
    catch { $failure=$_.Exception.Message }
    if($failure -ne 'PROCESS_OUTPUT_LIMIT') { throw 'process output limit was not enforced' }
    Write-Output 'PASS child output bound'
} finally {
    if(-not $existing.HasExited) { $existing.Kill($true);$existing.WaitForExit(10000) | Out-Null }
    $existing.Dispose()
}
