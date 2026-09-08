#requires -Version 7.0
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runId = [guid]::NewGuid().ToString()
$output = Join-Path $repository "backend/build/demo/m1/$runId"
$summaryPath = Join-Path $output 'summary.json'
$codeCommit = $null
$workingTreeDirty = $null
$stage = 'DEMO_OUTPUT_FAILED'
$exitCode = 0
$ownedContainer = $null
$docker = $null
$childEnvironment = @{}
$composeArguments = @('compose', '--project-name', 'vsrqg-m1-demo', '--file', (Join-Path $repository 'deploy/dev/compose.yml'))

function Invoke-Child {
    param([string]$File, [string[]]$Arguments)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.WorkingDirectory = $repository
    foreach ($name in @($start.Environment.Keys)) {
        if ($name -like 'VSRQG_DB_*' -or $name -like 'VSRQG_DEMO_*' -or $name -like 'COMPOSE_*') { $start.Environment.Remove($name) | Out-Null }
    }
    foreach ($name in $childEnvironment.Keys) { $start.Environment[$name] = $childEnvironment[$name] }
    if ([IO.Path]::GetExtension($File) -in @('.bat', '.cmd')) {
        # Constant PowerShell bridge keeps paths and each argument as data, including Windows spaces/metacharacters.
        $start.FileName = (Get-Process -Id $PID).Path
        foreach ($argument in @('-NoProfile', '-Command', '& $env:VSRQG_DEMO_CHILD_FILE @($env:VSRQG_DEMO_CHILD_ARGUMENTS | ConvertFrom-Json); exit $LASTEXITCODE')) { $start.ArgumentList.Add($argument) }
        $start.Environment['VSRQG_DEMO_CHILD_FILE'] = $File
        $start.Environment['VSRQG_DEMO_CHILD_ARGUMENTS'] = ConvertTo-Json -Compress -InputObject @($Arguments)
    } elseif ([IO.Path]::GetExtension($File) -eq '.ps1') {
        $start.FileName = (Get-Process -Id $PID).Path
        foreach ($argument in @('-NoProfile', '-File', $File) + $Arguments) { $start.ArgumentList.Add($argument) }
    } else {
        $start.FileName = $File
        foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'DEMO_PROCESS_START_FAILED' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        # Never print captured child streams: database/Gradle failures can contain credentials.
        return @{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult(); Error = $stderr.GetAwaiter().GetResult() }
    } finally { $process.Dispose() }
}
function Require-Success {
    param($Result)
    if ($Result.ExitCode -ne 0) { $script:exitCode = $Result.ExitCode; throw $script:stage }
    return $Result.Output.Trim()
}
function Write-Failure {
    param([string]$Code)
    if (Test-Path -LiteralPath $summaryPath) {
        $report = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json -AsHashtable
    } else {
        $report = @{ classification = 'SYNTHETIC_DEMO'; runId = $runId; codeCommit = $codeCommit; workingTreeDirty = $workingTreeDirty; errorCodes = @() }
    }
    $report.runId = $runId
    $report.codeCommit = $codeCommit
    $report.status = 'FAILED'
    $report.errorCodes = @($report.errorCodes) + $Code
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding utf8NoBOM
}
function Get-ContainerState {
    $ids = Require-Success (Invoke-Child $docker @('ps', '-aq', '--filter', 'label=com.docker.compose.project=vsrqg-m1-demo'))
    if (-not $ids) { return $null }
    $list = @($ids -split '\r?\n')
    if ($list.Count -ne 1 -or $list[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'DEMO_COMPOSE_CONFLICT' }
    # Restricted fields only: never inspect Config.Env or dump full container metadata.
    $format = '{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Status}}|{{json .HostConfig.PortBindings}}|{{range .Mounts}}{{.Type}}:{{.Name}}:{{.Destination}};{{end}}'
    $state = Require-Success (Invoke-Child $docker @('inspect', '--format', $format, $list[0]))
    $parts = $state -split '\|'
    if ($parts.Count -ne 4 -or $parts[0] -ne 'postgres' -or $parts[1] -notin @('running', 'exited', 'created') -or
        $parts[3] -ne 'volume:vsrqg-m1-demo_postgres-data:/var/lib/postgresql/data;') { throw 'DEMO_COMPOSE_CONFLICT' }
    $bindings = $parts[2] | ConvertFrom-Json
    if (@($bindings.PSObject.Properties).Count -ne 1 -or -not $bindings.PSObject.Properties['5432/tcp']) { throw 'DEMO_COMPOSE_CONFLICT' }
    $ports = @($bindings.'5432/tcp')
    if ($ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or $ports[0].HostPort -ne '55432') { throw 'DEMO_COMPOSE_CONFLICT' }
    return @{ Id = $list[0]; Running = $parts[1] -eq 'running' }
}
try {
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $stage = 'DEMO_GIT_UNAVAILABLE'
    $git = (Get-Command git -CommandType Application,ExternalScript -ErrorAction Stop | Select-Object -First 1).Source
    $codeCommit = Require-Success (Invoke-Child $git @('-c', 'core.longpaths=true', 'rev-parse', 'HEAD'))
    if ($codeCommit -notmatch '^[0-9a-f]{40}$') { throw $stage }
    $workingTreeDirty = [bool](Require-Success (Invoke-Child $git @('-c', 'core.longpaths=true', 'status', '--porcelain', '--untracked-files=all')))
    $stage = 'DEMO_PASSWORD_REQUIRED'
    if ([string]::IsNullOrWhiteSpace($env:VSRQG_DEMO_DATABASE_PASSWORD)) { throw $stage }
    $childEnvironment = @{
        VSRQG_DB_NAME = 'vsrqg_demo'; VSRQG_DB_USER = 'vsrqg_demo'; VSRQG_DB_PORT = '55432'
        VSRQG_DB_PASSWORD = $env:VSRQG_DEMO_DATABASE_PASSWORD
        VSRQG_DEMO_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55432/vsrqg_demo'
        VSRQG_DEMO_DATABASE_USERNAME = 'vsrqg_demo'; VSRQG_DEMO_DATABASE_PASSWORD = $env:VSRQG_DEMO_DATABASE_PASSWORD
        VSRQG_DEMO_RUN_ID = $runId; VSRQG_DEMO_CODE_COMMIT = $codeCommit
        VSRQG_DEMO_WORKING_TREE_DIRTY = $workingTreeDirty.ToString().ToLowerInvariant()
        VSRQG_DEMO_SAMPLE_FILE = (Join-Path $repository 'demo/m1/sample-config.txt')
        VSRQG_DEMO_OUTPUT_DIRECTORY = $output
    }
    $stage = 'DEMO_JDK_21_REQUIRED'
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' }) } else { (Get-Command java -CommandType Application,ExternalScript | Select-Object -First 1).Source }
    $version = Invoke-Child $java @('-version')
    if ($version.ExitCode -ne 0 -or ($version.Output + $version.Error) -notmatch 'version "21(?:\.|"|-)') { throw $stage }
    $stage = 'DEMO_GRADLE_UNAVAILABLE'
    $gradle = Join-Path $repository $(if ($IsWindows) { 'backend/gradlew.bat' } else { 'backend/gradlew' })
    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw $stage }
    Require-Success (Invoke-Child $gradle @('-p', (Join-Path $repository 'backend'), '--version')) | Out-Null
    $stage = 'DEMO_DOCKER_UNAVAILABLE'
    $docker = (Get-Command docker -CommandType Application,ExternalScript -ErrorAction Stop | Select-Object -First 1).Source
    Require-Success (Invoke-Child $docker @('compose', 'version')) | Out-Null
    $stage = 'DEMO_DOCKER_ENDPOINT_REJECTED'
    $endpoint = if ($env:DOCKER_HOST -and -not $env:DOCKER_CONTEXT) { $env:DOCKER_HOST } else {
        Require-Success (Invoke-Child $docker @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'))
    }
    if ($endpoint -notmatch '^(unix:///|npipe:////\./pipe/)' -and $endpoint -notmatch '^tcp://(localhost|127\.0\.0\.1|\[::1\]):[0-9]+$') { throw $stage }
    $stage = 'DEMO_DOCKER_DAEMON_UNAVAILABLE'
    Require-Success (Invoke-Child $docker @('info', '--format', '{{.ServerVersion}}')) | Out-Null
    $stage = 'DEMO_COMPOSE_CONFLICT'
    $initial = Get-ContainerState
    if ($null -eq $initial) {
        # Create without starting; this makes ownership explicit even when health/start subsequently fails.
        $stage = 'DEMO_COMPOSE_CREATE_FAILED'
        Require-Success (Invoke-Child $docker ($composeArguments + @('create', 'postgres'))) | Out-Null
        $stage = 'DEMO_COMPOSE_CONFLICT'
        $initial = Get-ContainerState
        if ($null -eq $initial -or $initial.Running) { throw $stage }
    }
    if (-not $initial.Running) {
        $ownedContainer = $initial.Id
        $stage = 'DEMO_COMPOSE_START_FAILED'
        Require-Success (Invoke-Child $docker ($composeArguments + @('start', '--wait', 'postgres'))) | Out-Null
    }
    $stage = 'DEMO_PROCESS_FAILED'
    Require-Success (Invoke-Child $gradle @('-p', (Join-Path $repository 'backend'), '--no-daemon', '--console=plain', 'm1Demo')) | Out-Null
    $stage = 'DEMO_REPORT_INVALID'
    $report = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    if ($report.classification -ne 'SYNTHETIC_DEMO' -or $report.status -ne 'PASS' -or $report.runId -ne $runId -or $report.codeCommit -ne $codeCommit -or
        -not (Test-Path -LiteralPath (Join-Path $output 'manifest.json'))) { throw $stage }
    $statuses = @($report.scenarioStatuses.PSObject.Properties)
    $scenarioNames = @('validFileLockExport', 'corruptFileRejected', 'unauthenticatedRejected', 'viewerWriteRejected', 'idempotentReplay', 'historicalExportStable')
    if (@(Compare-Object $scenarioNames @($statuses.Name)).Count -ne 0) { throw $stage }
    if ($statuses.Count -ne 6 -or @($statuses | Where-Object Value -ne 'PASS').Count -ne 0 -or
        -not $report.releaseId -or -not $report.manifestId -or -not $report.corruptReleaseId -or -not $report.corruptManifestId -or
        $report.contentDigest -notmatch '^sha256:[0-9a-f]{64}$' -or $report.payloadSha256 -notmatch '^[0-9a-f]{64}$' -or
        @($report.errorCodes).Count -ne 0) { throw $stage }
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $output 'manifest.json') | ConvertFrom-Json
    if ($manifest.releaseId -ne $report.releaseId -or $manifest.artifacts[0].checksum.value -ne $report.payloadSha256) { throw $stage }
} catch {
    # The stage is a constant; never expose native diagnostics, URLs, passwords or arbitrary exception text.
    [Console]::Error.WriteLine($stage)
    if ($exitCode -eq 0) { $exitCode = 1 }
    try { Write-Failure $stage } catch { [Console]::Error.WriteLine('DEMO_OUTPUT_FAILED') }
} finally {
    if ($ownedContainer) {
        try {
            $stage = 'DEMO_COMPOSE_STOP_FAILED'
            Require-Success (Invoke-Child $docker @('stop', $ownedContainer)) | Out-Null
        } catch {
            [Console]::Error.WriteLine('DEMO_COMPOSE_STOP_FAILED')
            if ($exitCode -eq 0) { $exitCode = 1 }
            try { Write-Failure 'DEMO_COMPOSE_STOP_FAILED' } catch { [Console]::Error.WriteLine('DEMO_OUTPUT_FAILED') }
        }
    }
}
Write-Output "SYNTHETIC_DEMO $runId summary: $summaryPath"
exit $exitCode
