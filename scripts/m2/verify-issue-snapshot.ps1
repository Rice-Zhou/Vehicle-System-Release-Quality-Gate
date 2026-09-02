param(
    [ValidateSet(
        "migration",
        "sync-observation",
        "snapshot-canonical",
        "snapshot-integration",
        "snapshot-replay",
        "contracts",
        "acceptance"
    )]
    [string]$InjectFailure
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$gradleWrapper = if ($isWindowsHost) { "./backend/gradlew.bat" } else { "./backend/gradlew" }
$pnpmCommand = "pnpm"
$checks = @(
    @{ Name = "migration"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*M2MigrationConstraintTest"); Kind = "gradle" },
    @{ Name = "sync-observation"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSyncIntegrationTest"); Kind = "gradle" },
    @{ Name = "snapshot-canonical"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSnapshotCanonicalizerTest"); Kind = "gradle" },
    @{ Name = "snapshot-integration"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSnapshotIntegrationTest"); Kind = "gradle" },
    @{ Name = "snapshot-replay"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSnapshotReplayTest"); Kind = "gradle" },
    @{ Name = "contracts"; Command = @($pnpmCommand, "run", "test:contracts"); Kind = "node" },
    @{ Name = "acceptance"; Command = @($pnpmCommand, "run", "verify:acceptance"); Kind = "node" }
)

function Get-SafeTestCount {
    param([string]$Kind)
    if ($Kind -ne "gradle") { return "UNKNOWN" }
    $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
    if (-not (Test-Path -LiteralPath $resultDirectory -PathType Container)) { return "UNKNOWN" }
    $total = 0
    foreach ($file in Get-ChildItem -LiteralPath $resultDirectory -Filter "TEST-*.xml" -File -ErrorAction Stop) {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction Stop
        $encodedCount = [string]$document.testsuite.tests
        if ($encodedCount -notmatch '^[0-9]+$') {
            throw [InvalidDataException]::new("Test count is invalid")
        }
        $total += [int]$encodedCount
    }
    if ($total -eq 0) { return "UNKNOWN" }
    return $total.ToString([Globalization.CultureInfo]::InvariantCulture)
}

function Resolve-FixedExecutable {
    param([string]$Name)
    $hasDirectory = $Name.Contains([IO.Path]::DirectorySeparatorChar) -or
        $Name.Contains([IO.Path]::AltDirectorySeparatorChar)
    if ($hasDirectory) {
        $candidate = if ([IO.Path]::IsPathRooted($Name)) { $Name } else { Join-Path $repositoryRoot $Name }
        $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    } else {
        $application = Get-Command -Name $Name -CommandType Application -ErrorAction Stop |
            Select-Object -First 1
        if ($null -eq $application) { throw [InvalidOperationException]::new("Executable resolution is missing") }
        $resolved = [string]$application.Source
    }
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw [InvalidOperationException]::new("Executable resolution is invalid")
    }
    return (Get-Item -LiteralPath $resolved -ErrorAction Stop).FullName
}

function Invoke-SafeChild {
    param([object[]]$Command)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = Resolve-FixedExecutable ([string]$Command[0])
    $startInfo.WorkingDirectory = $repositoryRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @($Command | Select-Object -Skip 1)) {
        $startInfo.ArgumentList.Add([string]$argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw [InvalidOperationException]::new("Child process did not start") }
        $dockerUnavailable = $false
        $outputOpen = $true
        $errorOpen = $true
        $outputTask = $process.StandardOutput.ReadLineAsync()
        $errorTask = $process.StandardError.ReadLineAsync()
        while ($outputOpen -or $errorOpen) {
            $pending = [Collections.Generic.List[Threading.Tasks.Task]]::new()
            if ($outputOpen) { $pending.Add($outputTask) }
            if ($errorOpen) { $pending.Add($errorTask) }
            [Threading.Tasks.Task]::WhenAny($pending.ToArray()).GetAwaiter().GetResult() | Out-Null
            if ($outputOpen -and $outputTask.IsCompleted) {
                $line = $outputTask.GetAwaiter().GetResult()
                if ($null -eq $line) {
                    $outputOpen = $false
                } else {
                    if ($line.Contains("DockerClientProviderStrategy", [StringComparison]::Ordinal) -or
                        $line.Contains("valid Docker environment", [StringComparison]::Ordinal)) {
                        $dockerUnavailable = $true
                    }
                    $outputTask = $process.StandardOutput.ReadLineAsync()
                }
            }
            if ($errorOpen -and $errorTask.IsCompleted) {
                $line = $errorTask.GetAwaiter().GetResult()
                if ($null -eq $line) {
                    $errorOpen = $false
                } else {
                    if ($line.Contains("DockerClientProviderStrategy", [StringComparison]::Ordinal) -or
                        $line.Contains("valid Docker environment", [StringComparison]::Ordinal)) {
                        $dockerUnavailable = $true
                    }
                    $errorTask = $process.StandardError.ReadLineAsync()
                }
            }
        }
        $process.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            DockerUnavailable = $dockerUnavailable
        }
    } finally {
        $process.Dispose()
    }
}

Push-Location $repositoryRoot
try {
    $commit = (& git rev-parse HEAD 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch "^[0-9a-f]{40}$") {
        Write-Output "COMMIT UNKNOWN"
        Write-Output "STATUS FAILED"
        Write-Output "FAILED authority diagnostic=COMMIT_UNRESOLVED"
        exit 1
    }
    Write-Output "COMMIT $commit"
    $failed = [System.Collections.Generic.List[object]]::new()
    foreach ($check in $checks) {
        $exitCode = 1
        $diagnostic = "CHECK_FAILED"
        $tests = "UNKNOWN"
        try {
            $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
            if ($check.Kind -eq "gradle" -and (Test-Path -LiteralPath $resultDirectory)) {
                Remove-Item -LiteralPath $resultDirectory -Recurse -Force -ErrorAction Stop
            }
            if ($InjectFailure -eq $check.Name) {
                $exitCode = 97
                $diagnostic = "INJECTED_TEST_FAILURE"
            } else {
                $child = Invoke-SafeChild $check.Command
                $exitCode = $child.ExitCode
                if ($exitCode -eq 0) {
                    $diagnostic = "NONE"
                } elseif ($child.DockerUnavailable) {
                    $diagnostic = "POSTGRESQL_RUNTIME_UNAVAILABLE"
                }
                $tests = Get-SafeTestCount $check.Kind
            }
        } catch {
            $exitCode = 1
            $diagnostic = "CHECK_FAILED"
            $tests = "UNKNOWN"
        }
        $status = if ($exitCode -eq 0) { "PASS" } else { "FAILED" }
        Write-Output "CHECK $($check.Name) $status tests=$tests diagnostic=$diagnostic"
        if ($exitCode -ne 0) {
            $failed.Add([pscustomobject]@{ Name = $check.Name; Diagnostic = $diagnostic; ExitCode = $exitCode })
        }
    }
    if ($failed.Count -gt 0) {
        Write-Output "STATUS FAILED"
        foreach ($failure in $failed) {
            Write-Output "FAILED $($failure.Name) diagnostic=$($failure.Diagnostic)"
        }
        exit $failed[0].ExitCode
    }
    Write-Output "STATUS PASS"
    exit 0
} finally {
    Pop-Location
}
