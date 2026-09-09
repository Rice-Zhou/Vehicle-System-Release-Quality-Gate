$Tool = $args[0]
$Arguments = @($args | Select-Object -Skip 1)
$ErrorActionPreference = 'Stop'
$mode = $env:DEMO_TEST_MODE
$stateFile = Join-Path $env:DEMO_TEST_ROOT 'state.txt'
$traceFile = Join-Path $env:DEMO_TEST_ROOT 'trace.txt'
Add-Content $traceFile "$Tool $($Arguments -join ' ')"
if ($Tool -eq 'git') {
 if ($Arguments -contains 'rev-parse') { 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }
 exit 0
}
if ($Tool -eq 'java') { if ($mode -eq 'jdk') { 'openjdk version "17.0.1"' } else { 'openjdk version "21.0.1"' }; exit 0 }
if ($Tool -eq 'gradle') {
 if ($Arguments -contains '--version') { if ($mode -eq 'gradle') { exit 4 }; 'Gradle'; exit 0 }
 if ($env:VSRQG_DB_NAME -ne 'vsrqg_demo' -or $env:VSRQG_DB_USER -ne 'vsrqg_demo' -or $env:VSRQG_DB_PORT -ne '55432' -or $env:VSRQG_DEMO_DATABASE_URL -ne 'jdbc:postgresql://127.0.0.1:55432/vsrqg_demo') { exit 92 }
 if ($env:VSRQG_DB_PASSWORD -ne $env:VSRQG_DEMO_DATABASE_PASSWORD) { exit 93 }
 if ($mode -in @('database', 'password', 'child')) { Write-Error 'PASSWORD_SECRET_SENTINEL jdbc:secret' -ErrorAction Continue; exit 7 }
 if ($mode -eq 'missing-report') { exit 0 }
 $summary = @{ classification='SYNTHETIC_DEMO'; status='PASS'; runId=$env:VSRQG_DEMO_RUN_ID; codeCommit=$env:VSRQG_DEMO_CODE_COMMIT; errorCodes=@() }
 $summary.scenarioStatuses = @{validFileLockExport='PASS'; corruptFileRejected='PASS'; unauthenticatedRejected='PASS'; viewerWriteRejected='PASS'; idempotentReplay='PASS'; historicalExportStable='PASS'}
 $summary.releaseId='release'; $summary.manifestId='manifest'; $summary.corruptReleaseId='corrupt'; $summary.corruptManifestId='rejected'
 $summary.contentDigest='sha256:'+('a'*64); $summary.payloadSha256='b'*64
 if ($mode -eq 'wrong-scenario') { $summary.scenarioStatuses.Remove('historicalExportStable'); $summary.scenarioStatuses['invented']='PASS' }
 if ($mode -eq 'invalid-report') { $summary.runId='wrong' }
 $summary | ConvertTo-Json | Set-Content (Join-Path $env:VSRQG_DEMO_OUTPUT_DIRECTORY 'summary.json')
 @{ releaseId='release'; artifacts=@(@{checksum=@{value='b'*64}}) } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $env:VSRQG_DEMO_OUTPUT_DIRECTORY 'manifest.json')
 if ($env:VSRQG_DEMO_INCLUDE_M2 -eq 'true') {
  $issueA1=@{fixed=$true;included=$true;verified=$false;path=@(@{edgeType='ISSUE_COMMIT'},@{edgeType='COMMIT_BUILD'},@{edgeType='BUILD_ARTIFACT'},@{edgeType='ARTIFACT_RELEASE'});gaps=@(@{diagnosticCode='TEST_RESULT_EVIDENCE_MISSING'})}
  $issueA2=@{fixed=$false;included=$false;verified=$false;path=@();gaps=@(@{diagnosticCode='ISSUE_COMMIT_MISSING'})}
  $issueB1=$issueA1; $issueB2=$issueA1
  @{classification='SYNTHETIC_DEMO';proofKind='SYNTHETIC_FIXTURE';status='PASS';runId=$env:VSRQG_DEMO_RUN_ID;codeCommit=$env:VSRQG_DEMO_CODE_COMMIT;scenarioStatuses=@{mappingProfile='PASS';issueSync='PASS';issueSnapshot='PASS';buildIngestion='PASS';snapshotA='PASS';snapshotB='PASS';sameKeyReplay='PASS';userIngestionRejected='PASS';invalidFactsRejected='PASS';historyStable='PASS'};issues=@{A=@{'DEMO-1'=$issueA1;'DEMO-2'=$issueA2};B=@{'DEMO-1'=$issueB1;'DEMO-2'=$issueB2}};traceabilitySnapshotIds=@{A='a';B='b'};history=@{snapshotABytesStable=$true;latestSnapshotId='b'}} | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $env:VSRQG_DEMO_OUTPUT_DIRECTORY 'm2-summary.json')
 }
 exit 0
}
if ($Tool -eq 'docker') {
 if ($Arguments[0] -eq 'context') { if ($mode -eq 'remote') { 'ssh://company-host' } else { 'unix:///var/run/docker.sock' }; exit 0 }
 if ($Arguments[0] -eq 'info') { if ($mode -eq 'daemon') { exit 6 }; '27.0.0'; exit 0 }
 if ($Arguments[0] -eq 'ps') { if (Test-Path $stateFile) { 'aaaaaaaaaaaa'; if ($mode -eq 'extra-service') { 'bbbbbbbbbbbb' } }; exit 0 }
 if ($Arguments[0] -eq 'inspect') {
  if ($Arguments[2] -match 'Config.Env' -or $Arguments.Count -ne 4) { exit 94 }
  $port=if ($mode -eq 'wrong-port') { '5432' } else { '55432' }
  $volume=if ($mode -eq 'wrong-volume') { 'company-data' } else { 'vsrqg-m1-demo_postgres-data' }
  "postgres|$(Get-Content $stateFile)|{`"5432/tcp`":[{`"HostIp`":`"127.0.0.1`",`"HostPort`":`"$port`"}]}|volume:${volume}:/var/lib/postgresql/data;"
  exit 0
 }
 if ($Arguments[0] -eq 'stop') { if ($mode -eq 'stop') { exit 9 }; 'exited' | Set-Content $stateFile; exit 0 }
 if ($Arguments -contains 'version') { if ($mode -eq 'compose') { exit 5 }; 'v2'; exit 0 }
 if ($Arguments -contains 'create') { 'created' | Set-Content $stateFile; exit 0 }
 # Docker Compose 2.38.2 has no start --wait; up owns the supported wait flag.
 if ($Arguments -contains 'start' -and $Arguments -contains '--wait') { exit 2 }
 if ($Arguments -contains 'up') {
  if ($Arguments -notcontains '--wait' -or $Arguments -notcontains '--no-recreate' -or -not (Test-Path $stateFile)) { exit 97 }
  'running' | Set-Content $stateFile
  if ($mode -eq 'start') { exit 8 }
  exit 0
 }
 exit 95
}
exit 96
