# Test-only command boundary. It is copied into an isolated fake checkout by m3-demo.tests.ps1.
param([string]$Mode)
$ErrorActionPreference='Stop'
if($Mode -eq 'sleep') { if($env:VSRQG_M3_TEST_PIDFILE) { $PID | Set-Content $env:VSRQG_M3_TEST_PIDFILE }; Start-Sleep -Seconds 30; exit 0 }
if($Mode -eq 'failure') { [Console]::Error.WriteLine('CHILD_SECRET_SENTINEL'); exit 7 }
if($env:VSRQG_M3_PHASE -eq 'VALIDATE') { exit 0 }
$mode=$env:VSRQG_M3_TEST_MODE
$config=Get-Content -Raw -LiteralPath $env:VSRQG_M3_CONFIG | ConvertFrom-Json
if($args -contains 'installDist') { if($mode -eq 'build-failure') { exit 7 }; exit 0 }
if($mode -eq 'missing-report') { exit 0 }
New-Item -ItemType Directory -Force $config.outputRoot | Out-Null
if($mode -eq 'child-failure') { [Console]::Error.WriteLine('CHILD_SECRET_SENTINEL'); exit 7 }
$log=[Text.Encoding]::UTF8.GetBytes('SYNTHETIC_DEMO wrapper command fixture')
$png=[byte[]]@(137,80,78,71,13,10,26,10)
[IO.File]::WriteAllBytes((Join-Path $config.outputRoot 'log.txt'),$log)
[IO.File]::WriteAllBytes((Join-Path $config.outputRoot 'screenshot.png'),$png)
$evidence=@()
foreach($pair in @(@('LOG','log.txt'),@('SCREENSHOT','screenshot.png'))) {
 $file=Join-Path $config.outputRoot $pair[1]
 $hash='sha256:'+(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
 $evidence+=@{evidenceId='ev_'+$pair[0];type=$pair[0];file=$pair[1];sizeBytes=(Get-Item $file).Length;payloadChecksum=$hash;downloadSha256=$hash}
}
if($mode -eq 'wrong-hash') { $evidence[0].downloadSha256='sha256:'+('0'*64) }
@{schemaVersion='1.0';classification='SYNTHETIC_DEMO';executionMode='CI_FIXTURE';generationStatus='SUCCEEDED';scenarioOutcome='PASS';
 codeCommit=$env:VSRQG_M3_COMMIT;workingTreeDirty=[bool]::Parse($env:VSRQG_M3_DIRTY);runId='run_wrapper_fixture';attemptId='00000000-0000-4000-8000-000000000001';
 caseStatus=$(if($config.planVersion -eq 1){'PASS'}else{'FAIL'});evidence=$evidence;errorCodes=@()} |
 ConvertTo-Json -Depth 8 | Set-Content (Join-Path $config.outputRoot 'summary.json')
exit 0
