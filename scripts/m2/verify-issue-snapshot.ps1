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
$checks = @(
    @{ Name = "migration"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*M2MigrationConstraintTest"); Kind = "gradle" },
    @{ Name = "sync-observation"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSyncIntegrationTest"); Kind = "gradle" },
    @{ Name = "snapshot-canonical"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSnapshotCanonicalizerTest"); Kind = "gradle" },
    @{ Name = "snapshot-integration"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSnapshotIntegrationTest"); Kind = "gradle" },
    @{ Name = "snapshot-replay"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*IssueSnapshotReplayTest"); Kind = "gradle" },
    @{ Name = "contracts"; Command = @("pnpm", "run", "test:contracts"); Kind = "node" },
    @{ Name = "acceptance"; Command = @("pnpm", "run", "verify:acceptance"); Kind = "node" }
)

function Get-SafeTestCount {
    param([string]$Kind)
    if ($Kind -ne "gradle") { return "UNKNOWN" }
    $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
    if (-not (Test-Path -LiteralPath $resultDirectory -PathType Container)) { return "UNKNOWN" }
    $total = 0
    foreach ($file in Get-ChildItem -LiteralPath $resultDirectory -Filter "TEST-*.xml" -File) {
        try {
            [xml]$document = Get-Content -LiteralPath $file.FullName -Raw
            $total += [int]$document.testsuite.tests
        } catch {
            return "UNKNOWN"
        }
    }
    if ($total -eq 0) { return "UNKNOWN" }
    return $total.ToString([Globalization.CultureInfo]::InvariantCulture)
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
        $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
        if ($check.Kind -eq "gradle" -and (Test-Path -LiteralPath $resultDirectory)) {
            Remove-Item -LiteralPath $resultDirectory -Recurse -Force
        }
        $captured = @()
        $exitCode = 0
        if ($InjectFailure -eq $check.Name) {
            $exitCode = 97
            $diagnostic = "INJECTED_TEST_FAILURE"
        } else {
            try {
                $executable = $check.Command[0]
                $arguments = @($check.Command | Select-Object -Skip 1)
                $captured = @(& $executable @arguments 2>&1 | ForEach-Object { $_.ToString() })
                $exitCode = $LASTEXITCODE
            } catch {
                $captured = @($_.Exception.Message)
                $exitCode = 1
            }
            if ($exitCode -eq 0) {
                $diagnostic = "NONE"
            } elseif (($captured -join "`n") -match "DockerClientProviderStrategy|valid Docker environment") {
                $diagnostic = "POSTGRESQL_RUNTIME_UNAVAILABLE"
            } else {
                $diagnostic = "CHECK_FAILED"
            }
        }
        $status = if ($exitCode -eq 0) { "PASS" } else { "FAILED" }
        $tests = Get-SafeTestCount $check.Kind
        Write-Output "CHECK $($check.Name) $status tests=$tests diagnostic=$diagnostic"
        if ($exitCode -ne 0) {
            $failed.Add([pscustomobject]@{ Name = $check.Name; Diagnostic = $diagnostic })
        }
    }
    if ($failed.Count -gt 0) {
        Write-Output "STATUS FAILED"
        foreach ($failure in $failed) {
            Write-Output "FAILED $($failure.Name) diagnostic=$($failure.Diagnostic)"
        }
        exit 1
    }
    Write-Output "STATUS PASS"
    exit 0
} finally {
    Pop-Location
}
