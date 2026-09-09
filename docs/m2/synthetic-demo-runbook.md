# M2 synthetic integrated demonstration runbook

After the same-run M1 payload verification, Lock and export, this flow executes synthetic Issue Sync, Issue Snapshot, Build Facts, Traceability and historical queries. It demonstrates composition of existing backend capabilities, not real GitHub Builds, vehicle testing or final Quality Engine decisions.

## Run

Reuse the [M1 prerequisites](../m1/demo-runbook.md): existing PowerShell 7, Git, JDK 21, local Docker Compose and an externally retained demo database password. Follow the M1 password instructions; never put it in commands, the repository or reports. No new service, Company or cloud resources are required.

Run from the repository root:

```powershell
pwsh -NoProfile -File scripts/demo/run-m1.ps1 -IncludeM2
```

Without IncludeM2, the command still runs only M1. M2 reuses the dedicated vsrqg_demo database, Compose project vsrqg-m1-demo and retained volume. Existing Workers may process residual jobs in this demo database, so never place real business data there. Each run creates a new synthetic Project, identities and records without overwriting previous results.

## Interpret results

Success exits 0; any failed stage exits nonzero. M1 summary.json PASS alone does not establish M2 success: require a successful same-run m2-summary.json and overall command exit 0.

| Snapshot | Issue | Fixed | Included | Verified | Explanation |
|---|---|---|---|---|---|
| A | DEMO-1 | true | true | false | Complete four-edge path, still TEST_RESULT_EVIDENCE_MISSING |
| A | DEMO-2 | false | false | false | No ISSUE_COMMIT; reports ISSUE_COMMIT_MISSING |
| B | DEMO-1 | true | true | false | Original complete path retained, still missing test Evidence |
| B | DEMO-2 | true | true | false | New Build facts complete the path, still missing test Evidence |

Query A again: its entire response bytes and contentDigest must remain unchanged, while latest points to B. This demonstrates existing Snapshot semantics, not administrator-proof storage or detection of arbitrary field tampering.

classification=SYNTHETIC_DEMO and proofKind=SYNTHETIC_FIXTURE identify synthetic inputs. The dedicated fixture validator's VALID/LOW means only conformity to demo samples; GitHub-shaped sample locators are neither external evidence nor accessed. Every Issue remains Verified=false. Scenario PASS means assertions matched expectations, not Release PASS/BLOCK.

## Reports and failures

The same-run output directory is backend/build/demo/m1/<runId>/. Retain M1 summary.json and manifest.json and add m2-summary.json. Find the latest M2 report with:

```powershell
Get-ChildItem backend/build/demo/m1 -Filter m2-summary.json -Recurse |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 |
    Get-Content
```

Reports retain run/code identifiers, observed HTTP statuses, scenario states, Snapshot identifiers/digests, Issue paths and Gaps. They exclude Tokens, raw identities, passwords, connection information, raw HTTP text and sample locators. When workingTreeDirty=true, codeCommit identifies HEAD only, not uncommitted changes.

HTTP failures, Worker FAILED, polling timeouts, invalid fields and mismatched results must fail explicitly rather than masquerade as successful missing-edge scenarios. A missing edge is a successfully computed business result; unavailable or unfinished computation is execution failure. Retain the report for diagnosis rather than modifying database history or deleting a volume to manufacture success.

## Services and acceptance scope

Stop only demo services started by this invocation, retaining the volume and reports; previously running services remain running. Reuse the M1 lifecycle rules. Another invocation uses the same external password and a new runId.

[TDR-022](../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md) and the [implementation plan](../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md) define scope. Engineering verification and Owner acceptance are separate. This runbook does not authorize merge, Tag, release, deployment or real Providers.

See the [implementation record](2026-09-08-synthetic-demo-walkthrough.md) for engineering results and the [Owner review record](../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md) (APPROVE) for stage demonstration acceptance.

Generate bilingual HTML from existing results using the [offline report runbook](../demo/offline-report-runbook.md); generation does not rerun the demonstration.
