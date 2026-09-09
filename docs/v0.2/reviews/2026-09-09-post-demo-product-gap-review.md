# Product Gap Review After M1/M2 Demonstration Acceptance

## Baseline and Scope

- Chinese baseline: 290e26c2df520d10434ab1ac062160d0cccbf629; English baseline: dbec8542b3a5525b1e3e22769a7aa8854c8975ef. Both worktrees started clean and GitHub branch heads matched these baselines.
- Following the Owner's next-step instruction, this review checks existing implementation and proposes one next work package. It does not start M3/M4 or change code, Schema, frozen architecture or existing acceptance decisions.
- Sources: [roadmap](../../roadmap.md), [MVP plan](../14-mvp-implementation-plan.md), [domain model](../01-domain-model.md), [database design](../02-database-design.md), [Agent protocol](../08-test-agent-protocol.md), [Evidence design](../09-evidence-design.md). This document supersedes current-state claims in the earlier gap inventory; earlier findings remain historical records.

## Current Conclusion

The Owner has accepted the M1 startup/Manifest demonstration, M2 traceability flow and offline presentation. More page features are not the shortest current product path. In the original MVP dependency order, the next missing capability is testing the target Release on a device and producing associated, verifiable Test Result/Evidence. That provides runtime facts needed by M4 deterministic quality decisions.

The product can currently demonstrate Release/Manifest, Fixed/Included, paths and Gaps. It cannot claim complete MVP delivery, testing of a real Release or Verified Issues. Running an ordinary Smoke Case on real hardware does not automatically verify a particular Issue.

| Capability | Verifiable Current State | Remaining Gap |
|---|---|---|
| M1 demonstration | [TDR-021 acceptance](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md) is APPROVE; Subjects 917f0c7 / 4a05ce5; actual synthetic-file checks, Lock, export and identity/permission paths exist. | Demonstration gap closed; synthetic acceptance is not real system-image deployment or completed real Provider integration. |
| M2 flow | [TDR-022 acceptance](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md) is APPROVE; Subjects 8d5354d / db98f89; synthetic Issue/Build inputs, Worker, A/B Snapshots and history exist. | Demonstration gap closed; Verified remains false. |
| Offline presentation | [TDR-023 acceptance](../../governance/acceptance/records/2026-09-09-tdr-023-demo-report-review-001.md) is APPROVE; Subjects c5400fd / 1fc37d4; the [generator](../../../scripts/demo/render-report.mjs) and samples are delivered, with actual normal/failed report verification. | P2 presentation gap closed; this displays source facts and is not a Quality Engine. |
| M3 device testing and Evidence | Main-source modules are access, issue, manifest, release, shared and traceability. Controller scans found no Device/Agent/Test Run/Evidence runtime endpoints. Tracked migrations are V1-V11, without corresponding runtime tables. Agent OpenAPI, Schema and examples are contract materials. | Device/Agent, Run/Attempt/Result, Collectors, runtime Evidence and device recovery are not delivered. |
| M4 deterministic decisions | No Quality Engine/Quality Evaluation main-source module or Controller was found; the [rule specification](../11-quality-rule-specification.md) and YAML examples exist. | Fact/Input Snapshot, rule execution, Quality Result, replay and final reporting remain undelivered; existing HTML cannot replace them. |
| M5 operational acceptance | Partial CI, backup/recovery and acceptance materials exist, but the M3/M4 runtime chain is missing. | The original real-Release end-to-end acceptance package is incomplete; partial engineering checks do not imply overall completion. |

The scan covers tracked main-source code, migrations, contracts and acceptance records at the two fixed branches. Absence in this scope does not imply absence on other machines or in uncommitted work. Existing Evidence Archive tools under shared preserve project materials; they do not implement Test Run Evidence uploads and business associations.

## Single Next Work Package Proposal

**Design and plan the smallest vertical Test Run → Result/Evidence flow for one device and one Smoke Case.** This is a candidate first M3 slice. Its immediate deliverable is an executable design, not implementation of all M3. Reuse accepted domain/protocol contracts and existing Backend, database, identity and Job capabilities. Record necessary technology choices in TDRs without redefining Core Contract.

The design should consider one versioned Plan/Case, one Device/Agent, one Run/Attempt, one objective test result and required LOG/SCREENSHOT evidence. The actual Case and required Evidence depend on available hardware and the Owner's demonstration goal; fabricated data must not fill gaps in device facts. Crash/ANR Collectors and full M3 failure exits remain part of the original milestone; the first slice must not claim to complete them.

| Question the Design Must Resolve | Minimum Reviewable Outcome |
|---|---|
| Which Release is tested | Explain how the device's running version maps to the Locked Manifest, fixed Plan/Case Version and Environment Snapshot. A successful Smoke must not imply target-Release verification when identities differ. Preserve Orchestrator deployment responsibility; do not assume flashing or device writes are authorized. |
| Who executes and reports | Reuse Agent registration, heartbeat, pull/ACK/Event/Result contracts and separate identity. Describe minimal implementation order, persistence and API reuse. ADB, if applicable, is an execution mechanism rather than an Agent-protocol replacement. |
| How success and failure remain visible | Define observations and tests for normal results, unavailable devices, execution timeout, duplicate/conflicting submissions, restarts and stale leases. Preserve Attempt/terminal/recovery invariants; task completion is not PASS. |
| How data associates and verifies | Associate Test Result/Evidence with the same Run/Release and retain raw Payload, size/checksum and Collector Version. Missing or corrupt required Evidence must not become AVAILABLE or successful acceptance. An ordinary Smoke does not change M2.5 Verified=false. |
| How part-time implementation remains practical | Provide minimally scoped tasks in dependency order, distinguishing CI protocol/fixture checks from real-device acceptance. Keep unverified items visible without requiring a device pool, online management platform or complete M4 first. |

## Device and Storage Boundaries

This review did not connect to, scan or operate a device, nor verify available hardware, control host, permitted commands/deployment mechanisms or target system version. Confirm those minimum inputs during design; no real-device result can currently be promised. Without hardware, protocol design and testing can proceed with fixture evidence, which cannot replace the real-device exit. This review requires neither procurement nor Company resources.

Continue using GitHub for suitable project materials as directed by the Owner; AWS, Object Lock and Company archiving are not recommended here. Existing [TDR-020](../tdr/TDR-020-git-project-evidence-preservation.md) covers project materials, while [TDR-004](../tdr/TDR-004-s3-compatible-evidence-storage.md) and the Agent protocol still describe runtime Payload storage and direct uploads. The next design must use actual first-slice volume, sensitivity and local demonstration needs to explain the minimum storage implementation and its relationship to accepted technical contracts. If simplification changes an existing choice, explicitly reevaluate the TDR. Do not silently treat GitHub Artifacts as a runtime Evidence API or use old documents to require cloud setup. No new storage choice is made here.

Device pools, parallel scheduling, general flashing platforms, Memory Stretch, trend UI, real external-system writeback and final quality decisions are outside this package and are not prerequisites for its design. Existing M2.5 performance-reference gaps, canonical coverage limits, historical CI Artifact expiry and test limitations remain governed by their original acceptance records.

## Verification and Next Execution Plan

This review checked the three APPROVE records, their Subjects/Scopes and actual entry points; scanned main-source Controllers, modules and all migrations; and compared domain, API, Agent/Evidence designs with original MVP exits. Device, database and HTTP demonstrations were not rerun, and document review is not product acceptance. Delivery checks cover bilingual contract/acceptance validators, diff review and Pair Gate; commits contain only this review and state navigation.

Current result: the [single-device design](../../superpowers/specs/2026-09-09-single-device-smoke-design.md) and TDR-024/025 are ready for review; the Owner confirmed an Android device and a new minimal APK. Git status: bilingual design documents are versioned; remote checks determine push status. Next action: review the design and technology proposals before detailing the implementation plan. Prerequisites: explicit design review; no Company/cloud resources. Acceptance target: agreed test scope, host Agent, identity, local Payload storage and failure semantics; no implementation or M3 acceptance is implied.
