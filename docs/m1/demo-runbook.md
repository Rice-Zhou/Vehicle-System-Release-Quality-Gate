# Synthetic M1 Demonstration Runbook

This entry demonstrates the actual-file → Release → Manifest verification → Lock → export mechanical flow, including corruption, unauthenticated access, permission rejection, and historical replay checks. It is not a complete MVP, real-vehicle test, or Company deployment.

## Prerequisites

From the repository root, use existing PowerShell 7, Git, JDK 21, and a working local Docker Compose environment. JAVA_HOME should point to the existing JDK 21; the entry does not install software or provision cloud resources. Port 55432 is reserved for the separate demonstration database.

The existing repository Gradle Wrapper is reused. The first build may download the configured Gradle distribution and build dependencies, requiring normal access to dependency repositories.

Both first and reused runs require a repository-external demonstration database password. Keep it in your own password manager and supply the same value when reusing retained data; never put it in the repository, scripts, logs, or commit messages. The script neither generates and discards a password nor deletes data when passwords mismatch.

Supply the password through hidden input in the current PowerShell session:

```powershell
$demoCredential = [pscredential]::new('vsrqg_demo', (Read-Host 'Demo database password' -AsSecureString))
$env:VSRQG_DEMO_DATABASE_PASSWORD = $demoCredential.GetNetworkCredential().Password
```

This environment variable is used only by child processes launched from the current session. The entry fixes Compose project vsrqg-m1-demo, database/user vsrqg_demo, and loopback port 55432, without using Company or real-Provider configuration.

## Single-Command Execution

```powershell
pwsh -NoProfile -File scripts/demo/run-m1.ps1
```

Success exits 0 and failure exits nonzero; file or directory existence alone does not prove success. Read the most recent report with:

```powershell
Get-ChildItem backend/build/demo/m1 -Filter summary.json -Recurse |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 |
    Get-Content
```

## Interpreting Results

Each independent run writes under backend/build/demo/m1/<runId>/. summary.json includes SYNTHETIC_DEMO, run ID, code commit, workingTreeDirty, actual scenario statuses, HTTP statuses, Release/Manifest IDs, digests, and fixed error codes. manifest.json is the Manifest actually submitted in the normal scenario. Reports exclude tokens, private keys, passwords, raw identities, and connection information.

The normal overall result is PASS. PASS for corruption or permission-negative scenarios means the system rejected requests as expected; their actual HTTP statuses remain 422/409, 401, or 403. FAILED means the run did not complete its expected flow. Before JVM startup, the report contains only minimal failure metadata, never fabricated business IDs or successful scenarios.

workingTreeDirty=true indicates relevant uncommitted changes; codeCommit then identifies HEAD without proving those changed bytes. The fixed source sample demo/m1/sample-config.txt remains unchanged; actual corruption affects the current run's working copy. File changes after registration do not rewrite existing historical reports.

## Services, Data, and Reuse

The entry stops only demonstration services it started from a stopped state, retaining the volume and reports. Services already running before execution remain running. Repeated execution creates new synthetic projects, Releases, and reports without overwriting prior records. Database or password errors exit nonzero; changing passwords, deleting volumes, or silent retries must not manufacture success.

Inspect containers belonging to the separate project:

```powershell
docker ps -a --filter label=com.docker.compose.project=vsrqg-m1-demo --format '{{.ID}} {{.Status}}'
```

To stop a preexisting service manually, verify its container ID above and use docker stop; stopping does not delete its volume. Do not delete a volume to bypass an unknown password; first recover the original password from your own repository-external storage.

## Verification and Boundaries

Container-free script tests run through scripts/tests/m1-demo.tests.ps1. Existing CI additionally runs scripts/tests/m1-demo-ci.tests.ps1 to verify initial execution, retained-volume reuse, preservation of preexisting running services, wrong-password failure, and source-file preservation, uploading summary.json/manifest.json.

[TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md) defines implementation boundaries; final results and commit evidence are in the [Task 3 implementation record](2026-09-08-reproducible-m1-walkthrough.md). This runbook does not substitute for Owner acceptance.
