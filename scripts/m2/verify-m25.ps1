param(
    [ValidateSet(
        "clean-tree", "fixed-commit", "contract", "migration", "domain", "transaction",
        "concurrency", "replay", "performance", "secret", "acceptance", "evidence-digest"
    )]
    [string]$InjectFailure
)

class EvidenceValidationException : System.Exception {
    EvidenceValidationException([string]$message) : base($message) { }
}

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$gradleWrapper = if ($isWindowsHost) { "./backend/gradlew.bat" } else { "./backend/gradlew" }
$evidenceDirectory = Join-Path $repositoryRoot "backend/build/m2"
$evidencePath = Join-Path $evidenceDirectory "m2-5-evidence.json"
$evidenceDigestPath = "$evidencePath.sha256"
$performancePath = Join-Path $evidenceDirectory "traceability-performance.json"
$recoveryPath = Join-Path $evidenceDirectory "traceability-recovery.json"
$acceptancePath = Join-Path $repositoryRoot "docs/governance/acceptance/records/2026-09-04-m2-5-owner-gate-001.md"
$checks = @(
    @{ Name = "clean-tree"; Kind = "internal" },
    @{ Name = "fixed-commit"; Kind = "internal" },
    @{ Name = "contract"; Kind = "multi"; Commands = @(
        @($gradleWrapper, "-p", "backend", "test", "--tests", "*M2ApiContractTest", "--rerun-tasks"),
        @("npm", "run", "test:contracts")
    ) },
    @{ Name = "migration"; Kind = "gradle"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityVerificationMigrationTest", "--rerun-tasks") },
    @{ Name = "domain"; Kind = "gradle"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityVerifierTest", "--tests", "*TraceabilityCanonicalizerTest", "--rerun-tasks") },
    @{ Name = "transaction"; Kind = "gradle"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityVerificationStartIntegrationTest", "--tests", "*TraceabilityVerificationStartFailureTest", "--tests", "*TraceabilityVerificationWorkerFailureTest", "--rerun-tasks") },
    @{ Name = "concurrency"; Kind = "gradle"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityVerificationConcurrencyTest", "--rerun-tasks") },
    @{ Name = "replay"; Kind = "multi"; Commands = @(
        @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityReplayTest", "--rerun-tasks"),
        @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityVerificationRecoveryTest", "--rerun-tasks")
    ) },
    @{ Name = "performance"; Kind = "gradle"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*TraceabilityVerificationPerformanceTest", "--rerun-tasks") },
    @{ Name = "secret"; Kind = "secret"; Command = @($gradleWrapper, "-p", "backend", "test", "--tests", "*SecurityAcceptanceTest", "--rerun-tasks") },
    @{ Name = "acceptance"; Kind = "acceptance"; Command = @("npm", "run", "verify:acceptance") },
    @{ Name = "evidence-digest"; Kind = "evidence" }
)

function Resolve-FixedExecutable {
    param([string]$Name)
    $hasDirectory = $Name.Contains([IO.Path]::DirectorySeparatorChar) -or
        $Name.Contains([IO.Path]::AltDirectorySeparatorChar)
    if ($hasDirectory) {
        $candidate = if ([IO.Path]::IsPathRooted($Name)) { $Name } else { Join-Path $repositoryRoot $Name }
        $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    } else {
        $application = Get-Command -Name $Name -CommandType Application -ErrorAction Stop | Select-Object -First 1
        if ($null -eq $application) { throw [InvalidOperationException]::new("Executable resolution is missing") }
        $resolved = [string]$application.Source
    }
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw [InvalidOperationException]::new("Executable resolution is invalid")
    }
    (Get-Item -LiteralPath $resolved -ErrorAction Stop).FullName
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
    foreach ($argument in @($Command | Select-Object -Skip 1)) { $startInfo.ArgumentList.Add([string]$argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw [InvalidOperationException]::new("Child process did not start") }
        $dockerUnavailable = $false
        $streams = @($process.StandardOutput, $process.StandardError)
        $tasks = @($streams[0].ReadLineAsync(), $streams[1].ReadLineAsync())
        $open = @($true, $true)
        while ($open[0] -or $open[1]) {
            $pending = [Collections.Generic.List[Threading.Tasks.Task]]::new()
            for ($index = 0; $index -lt 2; $index++) { if ($open[$index]) { $pending.Add($tasks[$index]) } }
            [Threading.Tasks.Task]::WhenAny($pending.ToArray()).GetAwaiter().GetResult() | Out-Null
            for ($index = 0; $index -lt 2; $index++) {
                if ($open[$index] -and $tasks[$index].IsCompleted) {
                    $line = $tasks[$index].GetAwaiter().GetResult()
                    if ($null -eq $line) {
                        $open[$index] = $false
                    } else {
                        if ($line.Contains("DockerClientProviderStrategy", [StringComparison]::Ordinal) -or
                            $line.Contains("valid Docker environment", [StringComparison]::Ordinal)) {
                            $dockerUnavailable = $true
                        }
                        $tasks[$index] = $streams[$index].ReadLineAsync()
                    }
                }
            }
        }
        $process.WaitForExit()
        [pscustomobject]@{ ExitCode = $process.ExitCode; DockerUnavailable = $dockerUnavailable }
    } finally {
        $process.Dispose()
    }
}

function Clear-TestResults {
    $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
    if (Test-Path -LiteralPath $resultDirectory) {
        Remove-Item -LiteralPath $resultDirectory -Recurse -Force -ErrorAction Stop
    }
}

function Get-SafeTestCount {
    $resultDirectory = Join-Path $repositoryRoot "backend/build/test-results/test"
    if (-not (Test-Path -LiteralPath $resultDirectory -PathType Container)) { return "UNKNOWN" }
    $total = 0
    foreach ($file in Get-ChildItem -LiteralPath $resultDirectory -Filter "TEST-*.xml" -File -ErrorAction Stop) {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction Stop
        $encoded = [string]$document.testsuite.tests
        if ($encoded -notmatch '^[0-9]+$') { throw [InvalidDataException]::new("Test count is invalid") }
        $total += [int]$encoded
    }
    if ($total -eq 0) { return "UNKNOWN" }
    $total.ToString([Globalization.CultureInfo]::InvariantCulture)
}

function Test-PendingOwnerRecord {
    if (-not (Test-Path -LiteralPath $acceptancePath -PathType Leaf)) { return $false }
    $markdown = Get-Content -LiteralPath $acceptancePath -Raw -ErrorAction Stop
    $statusCount = @([Regex]::Matches($markdown, '(?m)^status: PENDING\r?$')).Count
    $ownerCount = @([Regex]::Matches($markdown, '(?m)^owner: PENDING\r?$')).Count
    $decisionCount = @([Regex]::Matches($markdown, '(?m)^decisionAt: PENDING\r?$')).Count
    $statusCount -eq 1 -and $ownerCount -eq 1 -and $decisionCount -eq 1
}

function Test-TrackedSecrets {
    $patterns = @(
        'github_pat_[A-Za-z0-9_]{20,}',
        'ghp_[A-Za-z0-9]{30,}',
        'AKIA[0-9A-Z]{16}'
    )
    foreach ($pattern in $patterns) {
        & git grep --quiet -I -E $pattern -- .
        if ($LASTEXITCODE -eq 0) { return $false }
        if ($LASTEXITCODE -ne 1) { throw [InvalidOperationException]::new("Secret scan failed") }
    }
    $true
}

function Assert-ExactProperties {
    param([object]$Value, [string[]]$Names)
    if ($null -eq $Value) { throw [EvidenceValidationException]::new("Evidence object is missing") }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    if (($actual -join "`n") -cne ($expected -join "`n")) {
        throw [EvidenceValidationException]::new("Evidence properties are invalid")
    }
}

function Assert-SafeEvidenceText {
    param([string]$Text)
    $unsafe = '(?i)(github_pat_|ghp_[A-Za-z0-9]{20,}|authorization|bearer[ :=_-]|password|(?:token|secret|api[_-]?key)\s*[=:]|jdbc:postgresql|[a-z]:\\\\|"\s*:\s*"/)'
    if ($Text -match $unsafe) { throw [EvidenceValidationException]::new("Evidence contains unsafe text") }
}

function Write-SanitizedChildEvidence {
    param([string]$Path, [object]$Document)
    $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $Document | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
        Assert-SafeEvidenceText (Get-Content -LiteralPath $temporary -Raw)
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Read-PerformanceEvidence {
    if (-not (Test-Path -LiteralPath $performancePath -PathType Leaf)) { throw "Performance evidence is missing" }
    $raw = Get-Content -LiteralPath $performancePath -Raw
    Assert-SafeEvidenceText $raw
    $document = $raw | ConvertFrom-Json
    Assert-ExactProperties $document @(
        "schemaVersion", "fixture", "samples", "start", "worker", "query", "queryCounts", "hardware", "runtime"
    )
    Assert-ExactProperties $document.fixture @("issues", "edges")
    foreach ($phase in @("start", "worker", "query")) {
        Assert-ExactProperties $document.$phase @("p50Ms", "p95Ms", "maxMs", "targetP95Ms", "hardLimitMs")
    }
    Assert-ExactProperties $document.queryCounts @("release", "header", "issues", "paths", "gaps")
    Assert-ExactProperties $document.hardware @("processors", "maxMemoryBytes")
    Assert-ExactProperties $document.runtime @("java", "os")
    if ($document.schemaVersion -ne 1 -or $document.fixture.issues -ne 20 -or $document.fixture.edges -ne 2000) {
        throw [EvidenceValidationException]::new("Performance fixture identity is invalid")
    }
    if ($document.samples -ne 3 -or $document.queryCounts.release -ne 1 -or $document.queryCounts.header -ne 1 -or
        $document.queryCounts.issues -ne 1 -or $document.queryCounts.paths -ne 1 -or $document.queryCounts.gaps -ne 1) {
        throw [EvidenceValidationException]::new("Performance sampling or query shape is invalid")
    }
    $expectedTargets = @{ start = 1000; worker = 10000; query = 1000 }
    $expectedLimits = @{ start = 30000; worker = 60000; query = 30000 }
    foreach ($phase in @("start", "worker", "query")) {
        $value = $document.$phase
        if ($value.p50Ms -lt 0 -or $value.p95Ms -lt $value.p50Ms -or $value.maxMs -lt $value.p95Ms -or
            $value.maxMs -gt $value.hardLimitMs -or $value.targetP95Ms -ne $expectedTargets[$phase] -or
            $value.hardLimitMs -ne $expectedLimits[$phase]) {
            throw [EvidenceValidationException]::new("Performance result is invalid")
        }
    }
    if ($document.hardware.processors -lt 1 -or $document.hardware.maxMemoryBytes -lt 1 -or
        $document.runtime.java -notmatch '^[A-Za-z0-9 ._()+-]{1,128}$' -or
        $document.runtime.os -notmatch '^[A-Za-z0-9 ._()+-]{1,128}$') {
        throw [EvidenceValidationException]::new("Performance runtime metadata is invalid")
    }
    $sanitized = [ordered]@{
        schemaVersion = 1
        fixture = [ordered]@{ issues = 20; edges = 2000 }
        samples = 3
        start = [ordered]@{ p50Ms = $document.start.p50Ms; p95Ms = $document.start.p95Ms; maxMs = $document.start.maxMs; targetP95Ms = 1000; hardLimitMs = 30000 }
        worker = [ordered]@{ p50Ms = $document.worker.p50Ms; p95Ms = $document.worker.p95Ms; maxMs = $document.worker.maxMs; targetP95Ms = 10000; hardLimitMs = 60000 }
        query = [ordered]@{ p50Ms = $document.query.p50Ms; p95Ms = $document.query.p95Ms; maxMs = $document.query.maxMs; targetP95Ms = 1000; hardLimitMs = 30000 }
        queryCounts = [ordered]@{ release = 1; header = 1; issues = 1; paths = 1; gaps = 1 }
        hardware = [ordered]@{ processors = $document.hardware.processors; maxMemoryBytes = $document.hardware.maxMemoryBytes }
        runtime = [ordered]@{ java = [string]$document.runtime.java; os = [string]$document.runtime.os }
    }
    Write-SanitizedChildEvidence -Path $performancePath -Document $sanitized
    [pscustomobject]$sanitized
}

function Read-RecoveryEvidence {
    if (-not (Test-Path -LiteralPath $recoveryPath -PathType Leaf)) { throw "Recovery evidence is missing" }
    $raw = Get-Content -LiteralPath $recoveryPath -Raw
    Assert-SafeEvidenceText $raw
    $document = $raw | ConvertFrom-Json
    Assert-ExactProperties $document @(
        "schemaVersion", "backupRestore", "replayDigest", "dbRestartReclaim", "deadLetter", "manualRetry"
    )
    if ($document.schemaVersion -ne 1 -or $document.backupRestore -cne "PASS" -or
        $document.dbRestartReclaim -cne "PASS" -or $document.deadLetter -cne "PASS" -or
        $document.manualRetry -cne "PASS" -or $document.replayDigest -notmatch '^sha256:[0-9a-f]{64}$') {
        throw [EvidenceValidationException]::new("Recovery evidence is invalid")
    }
    $sanitized = [ordered]@{
        schemaVersion = 1
        backupRestore = "PASS"
        replayDigest = [string]$document.replayDigest
        dbRestartReclaim = "PASS"
        deadLetter = "PASS"
        manualRetry = "PASS"
    }
    Write-SanitizedChildEvidence -Path $recoveryPath -Document $sanitized
    [pscustomobject]$sanitized
}

function Write-EvidenceSummary {
    param([string]$Commit, [object[]]$Results, [string]$Status)
    New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
    $performance = $null
    $recovery = $null
    if (Test-Path -LiteralPath $performancePath -PathType Leaf) { $performance = Read-PerformanceEvidence }
    if (Test-Path -LiteralPath $recoveryPath -PathType Leaf) { $recovery = Read-RecoveryEvidence }
    $document = [ordered]@{
        schemaVersion = 1
        exactCommit = $Commit
        migrationVersion = "V11"
        status = $Status
        checks = @($Results | ForEach-Object {
            [ordered]@{ name = $_.Name; status = $_.Status; tests = $_.Tests; diagnostic = $_.Diagnostic }
        })
        performance = $performance
        replayDigest = if ($null -eq $recovery) { $null } else { $recovery.replayDigest }
        recovery = $recovery
    }
    $temporary = "$evidencePath.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $document | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
        Assert-SafeEvidenceText (Get-Content -LiteralPath $temporary -Raw)
        Move-Item -LiteralPath $temporary -Destination $evidencePath -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Write-EvidenceDigest {
    $digest = "sha256:$((Get-FileHash -LiteralPath $evidencePath -Algorithm SHA256).Hash.ToLowerInvariant())"
    Set-Content -LiteralPath $evidenceDigestPath -Value $digest -Encoding ascii -NoNewline
    $digest
}

Push-Location $repositoryRoot
try {
    $commit = (& git rev-parse HEAD 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { $commit = "UNKNOWN" }
    Write-Output "COMMIT $commit"
    $results = [Collections.Generic.List[object]]::new()
    $firstExitCode = 1
    $firstFailureRecorded = $false

    foreach ($check in $checks) {
        $exitCode = 1
        $diagnostic = "CHECK_FAILED"
        $tests = "UNKNOWN"
        try {
            if ($InjectFailure -ceq $check.Name) {
                $exitCode = 97
                $diagnostic = "INJECTED_TEST_FAILURE"
            } else {
                switch ($check.Kind) {
                    "internal" {
                        if ($check.Name -ceq "clean-tree") {
                            $dirty = @(& git status --porcelain --untracked-files=all)
                            if ($LASTEXITCODE -ne 0) { throw "Git status failed" }
                            if ($dirty.Count -eq 0) { $exitCode = 0; $diagnostic = "NONE" }
                            else { $exitCode = 1; $diagnostic = "WORKTREE_DIRTY" }
                        } else {
                            if ($commit -eq "UNKNOWN") { $exitCode = 1; $diagnostic = "COMMIT_UNRESOLVED" }
                            elseif (-not [string]::IsNullOrWhiteSpace($env:GITHUB_SHA) -and $env:GITHUB_SHA -cne $commit) {
                                $exitCode = 1; $diagnostic = "EXACT_HEAD_MISMATCH"
                            } else { $exitCode = 0; $diagnostic = "NONE" }
                        }
                    }
                    "multi" {
                        $exitCode = 0
                        $totalTests = 0
                        foreach ($command in $check.Commands) {
                            if ([string]$command[0] -like '*gradlew*') { Clear-TestResults }
                            if (($command -join ' ') -like '*TraceabilityVerificationRecoveryTest*' -and
                                (Test-Path -LiteralPath $recoveryPath)) {
                                Remove-Item -LiteralPath $recoveryPath -Force
                            }
                            $child = Invoke-SafeChild $command
                            if ([string]$command[0] -like '*gradlew*') {
                                $count = Get-SafeTestCount
                                if ($count -ne "UNKNOWN") { $totalTests += [int]$count }
                            }
                            if ($child.ExitCode -ne 0) {
                                if ($exitCode -eq 0) {
                                    $exitCode = $child.ExitCode
                                    $diagnostic = if ($child.DockerUnavailable) { "POSTGRESQL_RUNTIME_UNAVAILABLE" } else { "CHECK_FAILED" }
                                }
                            }
                        }
                        if ($exitCode -eq 0 -and $check.Name -ceq "replay") {
                            Read-RecoveryEvidence | Out-Null
                        }
                        if ($exitCode -eq 0) { $diagnostic = "NONE" }
                        if ($totalTests -gt 0) { $tests = $totalTests.ToString([Globalization.CultureInfo]::InvariantCulture) }
                    }
                    { $_ -in @("gradle", "secret", "acceptance") } {
                        if ($check.Kind -in @("gradle", "secret")) { Clear-TestResults }
                        if ($check.Name -ceq "performance" -and (Test-Path -LiteralPath $performancePath)) {
                            Remove-Item -LiteralPath $performancePath -Force
                        }
                        $child = Invoke-SafeChild $check.Command
                        $exitCode = $child.ExitCode
                        if ($check.Kind -in @("gradle", "secret")) { $tests = Get-SafeTestCount }
                        if ($exitCode -ne 0) {
                            $diagnostic = if ($child.DockerUnavailable) { "POSTGRESQL_RUNTIME_UNAVAILABLE" } else { "CHECK_FAILED" }
                        } elseif ($check.Name -ceq "performance") {
                            Read-PerformanceEvidence | Out-Null
                            $diagnostic = "NONE"
                        } elseif ($check.Kind -ceq "secret") {
                            if (Test-TrackedSecrets) { $diagnostic = "NONE" } else { $exitCode = 1; $diagnostic = "SECRET_SCAN_FAILED" }
                        } elseif ($check.Kind -ceq "acceptance") {
                            if (Test-PendingOwnerRecord) { $diagnostic = "NONE" } else { $exitCode = 1; $diagnostic = "OWNER_DECISION_NOT_PENDING" }
                        } else { $diagnostic = "NONE" }
                    }
                    "evidence" {
                        $preStatus = if (@($results | Where-Object Status -eq "FAILED").Count -eq 0) { "PASS" } else { "FAILED" }
                        $placeholder = [pscustomobject]@{ Name = $check.Name; Status = "PASS"; Tests = "UNKNOWN"; Diagnostic = "NONE" }
                        Write-EvidenceSummary -Commit $commit -Results @($results.ToArray() + $placeholder) -Status $preStatus
                        Write-EvidenceDigest | Out-Null
                        $exitCode = 0
                        $diagnostic = "NONE"
                    }
                }
            }
        } catch {
            $exitCode = 1
            if ($_.Exception -is [EvidenceValidationException]) {
                $diagnostic = "EVIDENCE_INVALID"
                foreach ($invalidEvidencePath in @($performancePath, $recoveryPath)) {
                    if (Test-Path -LiteralPath $invalidEvidencePath) {
                        Remove-Item -LiteralPath $invalidEvidencePath -Force
                    }
                }
            } else {
                $diagnostic = "CHECK_FAILED"
            }
            $tests = "UNKNOWN"
        }

        $status = if ($exitCode -eq 0) { "PASS" } else { "FAILED" }
        $result = [pscustomobject]@{ Name = $check.Name; Status = $status; Tests = $tests; Diagnostic = $diagnostic; ExitCode = $exitCode }
        $results.Add($result)
        if ($exitCode -ne 0 -and -not $firstFailureRecorded) {
            $firstExitCode = $exitCode
            $firstFailureRecorded = $true
        }
        Write-Output "CHECK $($check.Name) $status tests=$tests diagnostic=$diagnostic"
    }

    $failed = @($results | Where-Object Status -eq "FAILED")
    $finalStatus = if ($failed.Count -eq 0) { "PASS" } else { "FAILED" }
    Write-EvidenceSummary -Commit $commit -Results $results.ToArray() -Status $finalStatus
    try { Write-EvidenceDigest | Out-Null } catch { }
    Write-Output "SUMMARY total=$($results.Count) passed=$($results.Count - $failed.Count) failed=$($failed.Count)"
    Write-Output "STATUS $finalStatus"
    foreach ($failure in $failed) { Write-Output "FAILED $($failure.Name) diagnostic=$($failure.Diagnostic)" }
    if ($failed.Count -gt 0) { exit $firstExitCode }
    exit 0
} finally {
    Pop-Location
}
