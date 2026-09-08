#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$entry = Join-Path $PSScriptRoot '../demo/run-m1.ps1'
if (-not (Test-Path -LiteralPath $entry)) { throw 'M1 demo entry is missing' }
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('vsrqg demo contracts ' + [guid]::NewGuid().ToString('N'))
$pwsh = (Get-Process -Id $PID).Path
foreach ($directory in @('scripts/demo', 'backend', 'bin', 'demo/m1', 'deploy/dev')) { New-Item -ItemType Directory -Force (Join-Path $fixture $directory) | Out-Null }
Copy-Item -LiteralPath $entry -Destination (Join-Path $fixture 'scripts/demo/run-m1.ps1')
$stub = Join-Path $fixture 'stub.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixtures/m1-demo-command.ps1') -Destination $stub
function Make-Shim([string]$Path, [string]$Tool) {
 if ($IsWindows) { "@`"$pwsh`" -NoProfile -File `"$stub`" $Tool %*`r`n@exit /b %errorlevel%" | Set-Content $Path }
 else { "#!/bin/sh`nexec '$pwsh' -NoProfile -File '$stub' '$Tool' `"`$@`"" | Set-Content $Path; & chmod +x $Path }
}
$extension=if ($IsWindows) { '.cmd' } else { '' }
foreach ($tool in @('git','java','docker')) {
 if ($IsWindows) {
  ('$Tool = "' + $tool + '"' + [Environment]::NewLine + '& "' + $stub + '" $Tool @args; exit $LASTEXITCODE') | Set-Content (Join-Path $fixture "bin/$tool.ps1")
 } else { Make-Shim (Join-Path $fixture "bin/$tool$extension") $tool }
}
Make-Shim (Join-Path $fixture $(if ($IsWindows) { 'backend/gradlew.bat' } else { 'backend/gradlew' })) 'gradle'
function Run-Case([string]$Mode, [string]$Initial, [int]$Expected, [bool]$ShouldStop) {
 $stateFile=Join-Path $fixture 'state.txt'; $traceFile=Join-Path $fixture 'trace.txt'
 if ($Initial) { $Initial | Set-Content $stateFile } elseif (Test-Path $stateFile) { Remove-Item -LiteralPath $stateFile }
 if (Test-Path $traceFile) { Remove-Item -LiteralPath $traceFile }
 $start=[Diagnostics.ProcessStartInfo]::new($pwsh)
 $start.UseShellExecute=$false; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
 foreach ($arg in @('-NoProfile','-File',(Join-Path $fixture 'scripts/demo/run-m1.ps1'))) { $start.ArgumentList.Add($arg) }
 $start.Environment['PATH']=(Join-Path $fixture 'bin')+[IO.Path]::PathSeparator+$env:PATH
 $start.Environment.Remove('JAVA_HOME') | Out-Null
 $start.Environment.Remove('DOCKER_CONTEXT') | Out-Null
 $start.Environment.Remove('DOCKER_HOST') | Out-Null
 $start.Environment['DEMO_TEST_ROOT']=$fixture; $start.Environment['DEMO_TEST_MODE']=$Mode
 $start.Environment['VSRQG_DEMO_DATABASE_PASSWORD']=if ($Mode -eq 'missing-password') { '' } else { 'PASSWORD_SECRET_SENTINEL' }
 $start.Environment['VSRQG_DB_NAME']='company'; $start.Environment['VSRQG_DB_USER']='company'; $start.Environment['VSRQG_DB_PORT']='5432'
 $process=[Diagnostics.Process]::Start($start)
 $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync(); $process.WaitForExit()
 $text=$stdout.Result+$stderr.Result
 if ($process.ExitCode -ne $Expected) { throw "$Mode exit $($process.ExitCode), expected $Expected; $text" }
 if ($text -match 'PASSWORD_SECRET_SENTINEL|jdbc:secret') { throw "$Mode leaked secret" }
 $trace=if (Test-Path $traceFile) { Get-Content -Raw $traceFile } else { '' }
 if ([bool]($trace -match 'docker stop ') -ne $ShouldStop) { throw "$Mode stop ownership incorrect: $trace" }
 if ($trace -match 'docker .*\b(down|rm|volume)\b') { throw "$Mode deleted retained state" }
 if ($Initial -eq 'running' -and (Get-Content $stateFile) -ne 'running') { throw "$Mode changed preexisting running service" }
 if ($Initial -eq 'running' -and $trace -match 'docker compose .*\b(create|start|up)\b') { throw "$Mode touched preexisting running service" }
 $summary=(Get-ChildItem (Join-Path $fixture 'backend/build/demo/m1') -Recurse -Filter summary.json | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
 $report=Get-Content -Raw $summary.FullName | ConvertFrom-Json
 if ($report.status -ne $(if ($Expected -eq 0) { 'PASS' } else { 'FAILED' })) { throw "$Mode wrong summary status" }
 if ($Expected -ne 0 -and $report.errorCodes.Count -eq 0) { throw "$Mode missing failure code" }
 $codes = @{ 'missing-password'='DEMO_PASSWORD_REQUIRED'; jdk='DEMO_JDK_21_REQUIRED'; gradle='DEMO_GRADLE_UNAVAILABLE'; compose='DEMO_DOCKER_UNAVAILABLE'; daemon='DEMO_DOCKER_DAEMON_UNAVAILABLE'; remote='DEMO_DOCKER_ENDPOINT_REJECTED'; 'extra-service'='DEMO_COMPOSE_CONFLICT'; 'wrong-port'='DEMO_COMPOSE_CONFLICT'; 'wrong-volume'='DEMO_COMPOSE_CONFLICT'; database='DEMO_PROCESS_FAILED'; password='DEMO_PROCESS_FAILED'; child='DEMO_PROCESS_FAILED'; start='DEMO_COMPOSE_START_FAILED'; 'missing-report'='DEMO_REPORT_INVALID'; 'invalid-report'='DEMO_REPORT_INVALID'; 'wrong-scenario'='DEMO_REPORT_INVALID'; stop='DEMO_COMPOSE_STOP_FAILED' }
 if ($Expected -ne 0 -and $report.errorCodes -notcontains $codes[$Mode]) { throw "$Mode wrong failure stage" }
 Write-Output "PASS $Mode ($Initial)"
 $process.Dispose()
}
try {
 Run-Case 'missing-password' '' 1 $false
 Run-Case 'jdk' '' 1 $false
 Run-Case 'gradle' '' 4 $false
 Run-Case 'compose' '' 5 $false
 Run-Case 'daemon' '' 6 $false
 Run-Case 'remote' '' 1 $false
 Run-Case 'extra-service' 'running' 1 $false
 Run-Case 'wrong-port' 'running' 1 $false
 Run-Case 'wrong-volume' 'running' 1 $false
 Run-Case 'database' 'exited' 7 $true
 Run-Case 'password' 'running' 7 $false
 Run-Case 'child' '' 7 $true
 Run-Case 'start' 'exited' 8 $true
 Run-Case 'missing-report' 'exited' 1 $true
 Run-Case 'invalid-report' 'exited' 1 $true
 Run-Case 'wrong-scenario' 'exited' 1 $true
 Run-Case 'stop' 'exited' 9 $true
 Run-Case 'success-new' '' 0 $true
 Run-Case 'success-reuse' 'exited' 0 $true
 Run-Case 'success-running' 'running' 0 $false
} finally {
 $resolved=[IO.Path]::GetFullPath($fixture)
 if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
