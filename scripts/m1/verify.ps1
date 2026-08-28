$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$isWindowsHost = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$gradleWrapperName = if ($isWindowsHost) {
    "gradlew.bat"
} else {
    "gradlew"
}
$gradleWrapper = Join-Path $repositoryRoot "backend/$gradleWrapperName"
Push-Location $repositoryRoot
try {
    $sourceCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$') {
        throw "Unable to resolve candidate commit"
    }
    $worktreeChanges = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect candidate worktree"
    }
    if ($worktreeChanges.Count -gt 0) {
        throw "M1 verification requires a clean worktree; commit or remove changes first"
    }

    $startedAt = [DateTimeOffset]::UtcNow
    $outputDirectory = Join-Path $repositoryRoot "backend/build/m1"
    if (Test-Path -LiteralPath $outputDirectory) {
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
    $staleTestResults = Join-Path $repositoryRoot "backend/build/test-results/test"
    if (Test-Path -LiteralPath $staleTestResults) {
        Remove-Item -LiteralPath $staleTestResults -Recurse -Force
    }
    $evidenceDirectory = Join-Path $repositoryRoot "backend/build/m1/evidence/$sourceCommit"
    $gates = [System.Collections.Generic.List[object]]::new()
    $failure = $null
    $backendGateExecuted = $false

    function Invoke-M1Gate {
        param(
            [Parameter(Mandatory = $true)][string]$Name,
            [Parameter(Mandatory = $true)][string]$Command,
            [Parameter(Mandatory = $true)][scriptblock]$Action
        )
        $gateStarted = [DateTimeOffset]::UtcNow
        $exitCode = 0
        $message = $null
        try {
            & $Action
        }
        catch {
            $exitCode = if ($_.Exception.Data.Contains("ExitCode")) {
                [int]$_.Exception.Data["ExitCode"]
            } else {
                1
            }
            $message = $_.Exception.Message
        }
        $gates.Add([ordered]@{
            name = $Name
            command = $Command
            startedAt = $gateStarted.ToString("o")
            completedAt = [DateTimeOffset]::UtcNow.ToString("o")
            exitCode = $exitCode
            error = $message
        })
        if ($exitCode -ne 0) {
            throw "Gate '$Name' failed: $message"
        }
    }

    function Throw-M1NativeFailure {
        param(
            [Parameter(Mandatory = $true)][string]$Message,
            [Parameter(Mandatory = $true)][int]$ExitCode
        )
        $exception = [InvalidOperationException]::new("$Message exited with $ExitCode")
        $exception.Data["ExitCode"] = $ExitCode
        throw $exception
    }

    try {
        Invoke-M1Gate "dependencies" "pnpm install --frozen-lockfile" {
            & pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "pnpm install" $LASTEXITCODE }
        }
        Invoke-M1Gate "contract" "./scripts/tests/verify-contracts.tests.ps1" {
            & (Join-Path $repositoryRoot "scripts/tests/verify-contracts.tests.ps1")
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Contract verification" $LASTEXITCODE }
        }
        Invoke-M1Gate "acceptance-governance" "pnpm run test:acceptance && pnpm run verify:acceptance" {
            & pnpm run test:acceptance
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance record tests" $LASTEXITCODE }
            & pnpm run verify:acceptance
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance record verification" $LASTEXITCODE }
        }
        Invoke-M1Gate "evidence-archive" "pnpm run test:evidence-archive + pnpm --silent run verify:evidence-archive" {
            & pnpm run test:evidence-archive
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Evidence archive tests" $LASTEXITCODE }
            $fixtureDirectory = (Resolve-Path (Join-Path $repositoryRoot "ops/evidence-archive/fixtures/offline-test")).Path
            $fixtureWorkPackage = (Resolve-Path (Join-Path $fixtureDirectory "work-package.json")).Path
            $fixtureArchiveReport = (Resolve-Path (Join-Path $fixtureDirectory "archive-report.json")).Path
            $fixtureRecoveryReport = (Resolve-Path (Join-Path $fixtureDirectory "recovery-report.json")).Path
            & pnpm --silent run verify:evidence-archive -- --work-package $fixtureWorkPackage --archive-report $fixtureArchiveReport --recovery-report $fixtureRecoveryReport
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Evidence archive offline fixture" $LASTEXITCODE }
        }
        Invoke-M1Gate "evidence-archive-operation-args" "./scripts/tests/evidence-archive-gradle-args.tests.ps1" {
            & (Join-Path $repositoryRoot "scripts/tests/evidence-archive-gradle-args.tests.ps1")
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Evidence archive operation argument probe" $LASTEXITCODE }
        }
        $backendGateExecuted = $true
        Invoke-M1Gate "build-test-security-concurrency" "./backend/gradlew -p backend clean test bootJar" {
            & $gradleWrapper -p (Join-Path $repositoryRoot "backend") clean test bootJar
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Backend build/test" $LASTEXITCODE }
        }
        $fullTestResults = Join-Path $repositoryRoot "backend/build/test-results/test"
        if (-not (Test-Path -LiteralPath $fullTestResults -PathType Container)) {
            throw "Backend test results are missing"
        }
        New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
        Copy-Item -LiteralPath $fullTestResults -Destination (Join-Path $evidenceDirectory "full-test-results") -Recurse -Force
        Invoke-M1Gate "smoke-recovery" "./scripts/m1/acceptance-smoke.ps1" {
            & (Join-Path $PSScriptRoot "acceptance-smoke.ps1")
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Acceptance smoke" $LASTEXITCODE }
        }
        Invoke-M1Gate "schema-export" "./scripts/m1/export-schema.ps1 -UseExistingExport" {
            & (Join-Path $PSScriptRoot "export-schema.ps1") -UseExistingExport
            if ($LASTEXITCODE -ne 0) { Throw-M1NativeFailure "Schema export" $LASTEXITCODE }
        }
    }
    catch {
        $failure = $_.Exception.Message
    }

    New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
    $testResults = Join-Path $repositoryRoot "backend/build/test-results/test"
    $savedFullTestResults = Join-Path $evidenceDirectory "full-test-results"
    if ($backendGateExecuted -and (Test-Path -LiteralPath $testResults -PathType Container) -and
        -not (Test-Path -LiteralPath $savedFullTestResults -PathType Container)) {
        Copy-Item -LiteralPath $testResults -Destination (Join-Path $evidenceDirectory "failure-test-results") -Recurse -Force
    }
    if ($null -eq $failure) {
        $finalCommit = (& git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or $finalCommit -ne $sourceCommit) {
            $failure = "Candidate commit changed during verification"
        }
    }
    if ($null -eq $failure) {
        $finalChanges = @(& git status --porcelain=v1 --untracked-files=all)
        if ($LASTEXITCODE -ne 0) {
            $failure = "Unable to perform final worktree inspection"
        } elseif ($finalChanges.Count -gt 0) {
            $failure = "Verification changed tracked or unignored files"
        }
    }

    $reportFiles = @(
        Get-ChildItem -LiteralPath $evidenceDirectory -File -Recurse -ErrorAction SilentlyContinue
        Get-Item -LiteralPath (Join-Path $repositoryRoot "backend/build/m1/acceptance-smoke.json") -ErrorAction SilentlyContinue
        Get-Item -LiteralPath (Join-Path $repositoryRoot "backend/build/m1/schema.sql") -ErrorAction SilentlyContinue
        Get-Item -LiteralPath (Join-Path $repositoryRoot "backend/build/m1/schema-metadata.json") -ErrorAction SilentlyContinue
        if ($null -eq $failure) {
            Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "backend/build/libs") -Filter "*.jar" -File -ErrorAction SilentlyContinue
        }
    ) | Where-Object { $null -ne $_ } | Sort-Object FullName -Unique
    $reports = @($reportFiles | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath($repositoryRoot, $_.FullName).Replace('\', '/')
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
            bytes = $_.Length
        }
    })
    $evidence = [ordered]@{
        schemaVersion = 1
        milestone = "M1"
        status = if ($null -eq $failure) { "CANDIDATE" } else { "FAILED" }
        commit = $sourceCommit
        startedAt = $startedAt.ToString("o")
        completedAt = [DateTimeOffset]::UtcNow.ToString("o")
        gates = $gates
        reports = $reports
        failure = $failure
        ownerDecision = "PENDING"
    }
    $evidencePath = Join-Path $evidenceDirectory "evidence.json"
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM

    if ($null -ne $failure) {
        throw $failure
    }
    Write-Output "PASS M1 gates=contract,acceptance-governance,evidence-archive,build,test,security,concurrency,smoke,recovery"
    Write-Output "EVIDENCE $([IO.Path]::GetRelativePath($repositoryRoot, $evidencePath).Replace('\', '/'))"
}
finally {
    Pop-Location
}
