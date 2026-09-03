[CmdletBinding()]
param(
    [switch]$RequireGithubSmoke,
    [ValidateSet(
        "contract",
        "migration",
        "canonical",
        "validator",
        "repository",
        "transaction",
        "security",
        "github-smoke",
        "contracts",
        "acceptance"
    )]
    [string]$InjectFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$gradleWrapper = if ($isWindowsHost) { "./backend/gradlew.bat" } else { "./backend/gradlew" }
$checks = @(
    @{ Name = "contract"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*M2ApiContractTest"); Kind = "gradle" },
    @{ Name = "migration"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*BuildProvenanceMigrationTest"); Kind = "gradle" },
    @{ Name = "canonical"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*BuildProvenanceCanonicalizerTest"); Kind = "gradle" },
    @{ Name = "validator"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*GithubActionsBuildProvenanceValidatorTest"); Kind = "gradle" },
    @{ Name = "repository"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*BuildProvenanceRepositoryIntegrationTest"); Kind = "gradle" },
    @{ Name = "transaction"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*BuildProvenanceIntegrationTest", "--tests", "*BuildProvenanceTransactionFailureTest"); Kind = "gradle" },
    @{ Name = "security"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*SecurityAcceptanceTest", "--tests", "*PermissionMatrixTest"); Kind = "gradle" },
    @{ Name = "github-smoke"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*BuildProvenanceGithubSmokeTest"); Kind = "gradle" },
    @{ Name = "contracts"; Command = @("npm", "run", "test:contracts"); Kind = "node" },
    @{ Name = "acceptance"; Command = @("npm", "run", "verify:acceptance"); Kind = "node" }
)

function Test-GithubSmokeContext {
    if ([Environment]::GetEnvironmentVariable("GITHUB_ACTIONS") -cne "true") { return $false }
    foreach ($name in @(
        "GITHUB_REPOSITORY",
        "GITHUB_SHA",
        "GITHUB_WORKFLOW_REF",
        "GITHUB_RUN_ID",
        "GITHUB_RUN_ATTEMPT",
        "GITHUB_JOB"
    )) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { return $false }
    }
    return $true
}

function Test-ExactProperties {
    param([object]$Value, [string[]]$Expected)
    if ($null -eq $Value -or $Value -isnot [pscustomobject]) { return $false }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    return ($actual.Count -eq $wanted.Count) -and (($actual -join "`n") -ceq ($wanted -join "`n"))
}

function Get-GithubSmokeEvidenceStatus {
    param(
        [string]$Path,
        [string]$Commit
    )
    try {
        $document = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        if (-not (Test-ExactProperties $document @(
            "schemaVersion", "exactCommit", "runId", "runAttempt", "validatorVersion",
            "envelopeDigest", "artifactDigest", "edgeRevisionIds", "replayResults",
            "fixedDiagnostics", "testCounts"
        ))) { return "INVALID" }
        if ($document.schemaVersion -isnot [long] -or $document.schemaVersion -ne 2) { return "INVALID" }
        if ($document.exactCommit -isnot [string] -or $document.exactCommit -cnotmatch '^[0-9a-f]{40}$') { return "INVALID" }
        if ($document.runId -isnot [string] -or $document.runId -cnotmatch '^[1-9][0-9]*$') { return "INVALID" }
        if ($document.runAttempt -isnot [long] -or $document.runAttempt -lt 1) { return "INVALID" }
        if ($document.validatorVersion -isnot [string] -or $document.validatorVersion -cne "github-actions-provenance/v1") { return "INVALID" }
        foreach ($digest in @($document.envelopeDigest, $document.artifactDigest)) {
            if ($digest -isnot [string] -or $digest -cnotmatch '^sha256:[0-9a-f]{64}$') { return "INVALID" }
        }
        $edges = @($document.edgeRevisionIds)
        if ($edges.Count -ne 3) { return "INVALID" }
        $edgeTypes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $edgeIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $revisionIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($edge in $edges) {
            if (-not (Test-ExactProperties $edge @("edgeType", "edgeId", "revisionId"))) { return "INVALID" }
            if ($edge.edgeType -isnot [string] -or -not $edgeTypes.Add($edge.edgeType)) { return "INVALID" }
            foreach ($binding in @(@($edge.edgeId, $edgeIds), @($edge.revisionId, $revisionIds))) {
                if ($binding[0] -isnot [string] -or $binding[0] -cnotmatch '^[A-Za-z][A-Za-z0-9_-]{2,127}$' -or
                    -not $binding[1].Add($binding[0])) { return "INVALID" }
            }
        }
        if (($edgeTypes | Sort-Object) -join ',' -cne "BUILD_ARTIFACT,COMMIT_BUILD,ISSUE_COMMIT") { return "INVALID" }
        if (-not (Test-ExactProperties $document.replayResults @("sameIdempotencyKey", "differentIdempotencyKey")) -or
            $document.replayResults.sameIdempotencyKey -isnot [bool] -or
            $document.replayResults.differentIdempotencyKey -isnot [bool] -or
            -not $document.replayResults.sameIdempotencyKey -or
            -not $document.replayResults.differentIdempotencyKey) { return "INVALID" }
        $diagnostics = @($document.fixedDiagnostics)
        if ($diagnostics.Count -ne 2 -or @($diagnostics | Where-Object { $_ -isnot [string] }).Count -ne 0 -or
            ($diagnostics -join ',') -cne "BUILD_PROVENANCE_CONFLICT,PROJECT_SCOPE_MISMATCH") { return "INVALID" }
        $expectedCounts = [ordered]@{
            acceptedRequests = 3; rejectedRequests = 3; receipts = 1; rejectedReceipts = 1
            edgeIdentities = 3; edgeRevisions = 3; auditEvents = 2; outboxEvents = 1
            artifactReleaseEdges = 0
        }
        if (-not (Test-ExactProperties $document.testCounts @($expectedCounts.Keys))) { return "INVALID" }
        foreach ($entry in $expectedCounts.GetEnumerator()) {
            $actual = $document.testCounts.($entry.Key)
            if ($actual -isnot [long] -or $actual -ne $entry.Value) { return "INVALID" }
        }
        if ([string]$document.exactCommit -cne $Commit -or
            [string]$document.exactCommit -cne [Environment]::GetEnvironmentVariable("GITHUB_SHA") -or
            [string]$document.runId -cne [Environment]::GetEnvironmentVariable("GITHUB_RUN_ID") -or
            [string]$document.runAttempt -cne [Environment]::GetEnvironmentVariable("GITHUB_RUN_ATTEMPT")) {
            return "CONTEXT_MISMATCH"
        }
        return "VALID"
    } catch {
        return "INVALID"
    }
}

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
            throw [InvalidDataException]::new("TEST_COUNT_INVALID")
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
        $application = Get-Command -Name $Name -CommandType Application -ErrorAction Stop | Select-Object -First 1
        if ($null -eq $application) { throw [InvalidOperationException]::new("EXECUTABLE_UNRESOLVED") }
        $resolved = [string]$application.Source
    }
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw [InvalidOperationException]::new("EXECUTABLE_INVALID")
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
        if (-not $process.Start()) { throw [InvalidOperationException]::new("CHILD_START_FAILED") }
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
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') {
        Write-Output "COMMIT UNKNOWN"
        Write-Output "SUMMARY total=10 passed=0 failed=10"
        Write-Output "STATUS FAILED"
        exit 1
    }
    Write-Output "COMMIT $commit"

    $failures = [Collections.Generic.List[object]]::new()
    $passed = 0
    foreach ($check in $checks) {
        $exitCode = 1
        $diagnostic = "CHECK_FAILED"
        $tests = "UNKNOWN"
        try {
            $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
            $smokeEvidence = Join-Path $repositoryRoot "backend/build/m2/build-provenance-smoke.json"
            $smokeEvidenceDirectory = Split-Path $smokeEvidence
            if ($check.Kind -eq "gradle" -and (Test-Path -LiteralPath $resultDirectory)) {
                Remove-Item -LiteralPath $resultDirectory -Recurse -Force -ErrorAction Stop
            }
            if ($check.Name -eq "github-smoke" -and (Test-Path -LiteralPath $smokeEvidence)) {
                Remove-Item -LiteralPath $smokeEvidence -Force -ErrorAction Stop
            }
            if ($check.Name -eq "github-smoke" -and (Test-Path -LiteralPath $smokeEvidenceDirectory -PathType Container)) {
                Get-ChildItem -LiteralPath $smokeEvidenceDirectory -Filter "build-provenance-smoke.json.*.tmp" -File |
                    Remove-Item -Force -ErrorAction Stop
            }
            if ($InjectFailure -eq $check.Name) {
                $exitCode = 97
                $diagnostic = "INJECTED_TEST_FAILURE"
            } elseif ($check.Name -eq "github-smoke" -and -not (Test-GithubSmokeContext)) {
                $exitCode = 1
                $diagnostic = "GITHUB_CONTEXT_MISSING"
            } elseif ($check.Name -eq "github-smoke" -and
                [Environment]::GetEnvironmentVariable("GITHUB_SHA") -cne $commit) {
                $exitCode = 1
                $diagnostic = "EXACT_HEAD_MISMATCH"
            } else {
                $child = Invoke-SafeChild $check.Command
                $exitCode = $child.ExitCode
                if ($exitCode -eq 0) {
                    $diagnostic = "NONE"
                } elseif ($child.DockerUnavailable) {
                    $diagnostic = "POSTGRESQL_RUNTIME_UNAVAILABLE"
                }
                $tests = Get-SafeTestCount $check.Kind
                if ($check.Name -eq "github-smoke" -and $exitCode -eq 0 -and
                    -not (Test-Path -LiteralPath $smokeEvidence -PathType Leaf)) {
                    $exitCode = 1
                    $diagnostic = "EVIDENCE_MISSING"
                } elseif ($check.Name -eq "github-smoke" -and $exitCode -eq 0) {
                    $temporaryEvidence = @(Get-ChildItem -LiteralPath $smokeEvidenceDirectory `
                        -Filter "build-provenance-smoke.json.*.tmp" -File -ErrorAction Stop)
                    $evidenceStatus = if ($temporaryEvidence.Count -eq 0) {
                        Get-GithubSmokeEvidenceStatus -Path $smokeEvidence -Commit $commit
                    } else {
                        "INVALID"
                    }
                    if ($evidenceStatus -cne "VALID") {
                        $exitCode = 1
                        $diagnostic = if ($evidenceStatus -ceq "CONTEXT_MISMATCH") {
                            "EVIDENCE_CONTEXT_MISMATCH"
                        } else {
                            "EVIDENCE_INVALID"
                        }
                    }
                }
            }
        } catch {
            $exitCode = 1
            $diagnostic = "CHECK_FAILED"
            $tests = "UNKNOWN"
        }

        $status = if ($exitCode -eq 0) { "PASS" } else { "FAILED" }
        Write-Output "CHECK $($check.Name) $status tests=$tests diagnostic=$diagnostic"
        if ($exitCode -eq 0) {
            $passed++
        } else {
            $failures.Add([pscustomobject]@{ Name = $check.Name; Diagnostic = $diagnostic; ExitCode = $exitCode })
        }
    }

    Write-Output "SUMMARY total=$($checks.Count) passed=$passed failed=$($failures.Count)"
    if ($failures.Count -gt 0) {
        Write-Output "STATUS FAILED"
        foreach ($failure in $failures) {
            Write-Output "FAILED $($failure.Name) diagnostic=$($failure.Diagnostic)"
        }
        exit $failures[0].ExitCode
    }
    Write-Output "STATUS PASS"
    exit 0
} finally {
    Pop-Location
}
