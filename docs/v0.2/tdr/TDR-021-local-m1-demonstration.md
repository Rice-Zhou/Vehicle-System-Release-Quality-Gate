# TDR-021 — Minimum Local Synthetic M1 Demonstration

- Date: 2026-09-08; status: Accepted (Tasks 1/2/3 implementation scope). Following Task 2 delivery, the Owner again instructed execution of the next step, confirming Task 3 single-command entry, sample, and result output implementation; the complete demonstration has passed actual verification and awaits Owner review, without claiming Owner acceptance.
- Authority: [stage goal](../reviews/2026-09-08-demonstrable-product-priority.md), [gap inventory](../reviews/2026-09-08-demonstrable-product-gap-inventory.md).
- Baseline: Chinese a2ebf7d079a9e7f5f506bd8e66bb5f27b3493724; English af2ab1c4448268cbbd866c412ce46650ae3a92ee.

## Goal and Choice

Deliver a repeatable local flow: start existing Backend → initialize synthetic project/identities → create Release over HTTP → register Manifest with actual file digests → validate → Lock → export. Execute corrupted-file and permission-denied scenarios with readable results and sanitized JSON. This proves the M1 mechanical flow, not a real vehicle Release or complete MVP.

| Option | Trade-off |
|---|---|
| Separate demo source-set launcher reusing Backend and JWT/RBAC | Recommended; no new service, actual HTTP/database execution, demonstration code excluded from production packaging |
| New OIDC service and complete frontend | Additional environment and UI unnecessary for this flow; defer |
| Test mocks or prewritten VALID/LOCKED | Bypasses verification and permission paths being demonstrated; not a deliverable approach |

## File Verification Integration

Add ArtifactPayloadVerifier accepting a Manifest-ordered list of lowercase SHA-256 values; PayloadVerification reuses ValidationStatus and ManifestViolation and carries validatorVersion. ValidateManifest.evaluate performs existing consistency checks before invoking the verifier when structural errors are absent. RegisterManifest retains the same transaction, insertValidation, and existing tables. No new HTTP API, Schema, or Migration.

The ordinary Backend default verifier retains INCOMPLETE, ARTIFACT_CHECKSUM_NOT_VERIFIED, and the existing validator version. The launcher explicitly injects the local implementation and trusts m1-local-payload/1 only within that process, without changing global allowlists.

The local implementation only reads digest-named files under the launcher-selected synthetic directory, reading actual bytes to recompute SHA-256. Requests cannot supply paths or URLs. Reject empty lists, invalid digests, symlinks, nonregular files, escape, read errors, mismatches, and inputs above 1 MiB per file or 16 files per invocation. These are local-adapter limits, not global Schema changes. Return only fixed violation codes and Manifest-relative positions, never local paths or raw exceptions.

Verification occurs at registration; the validate API continues reading persisted reports without rewriting history. Corruption changes one byte of a separate working copy before registration with a new Release/Idempotency-Key; after repair create another Release without overwriting failure records. Later file changes do not retroactively change reports; this design promises neither continuous monitoring nor administrator-resistant modification. Preserve original synthetic files unchanged.

## Identity and Runtime Boundaries

Add an independent demo source set, m1Demo JavaExec task, and com.ricezhou.vsrqg.demo package, reusing main runtimeClasspath and the same VsrqgApplication. Production bootJar excludes all demo classes; tests may depend on demo output, but main does not depend on demo.

Bind to 127.0.0.1 on a random port, force PILOT and archive provider NONE, disable Issue/Traceability Workers, and do not load Company/real-Provider configuration. Permit only loopback access to dedicated vsrqg_demo, rejecting other hosts/database names. Reuse deploy/dev/compose.yml with separate Compose project vsrqg-m1-demo, port 55432, and an independent volume without adding a service type. Missing Docker or unavailable JDK 21 causes explicit exit, never automatic installation.

Using existing Spring Security/Nimbus, generate a one-time RSA key in memory and issue 10-minute JWTs. A real JwtDecoder verifies signature, issuer, audience, and exp/nbf. Use issuer http://localhost/vsrqg-demo and audience vsrqg-m1-demo without remote discovery. Reuse SecurityConfig, JwtPrincipalMapper, JdbcProjectAuthorizer, and Permission; no permitAll, fake decoder, or duplicate role rules.

Through a parameterized transaction, initialize only new per-run project, principal, and project_assignment records, including RELEASE_MANAGER and VIEWER. Fail on identifier collisions without overwriting existing rows. Create all Release/Manifest/validation/locked states through actual HTTP, without test seeders. The same JVM's client holds tokens; provide no token endpoint and write no keys, tokens, or passwords into Git, logs, or result files.

The existing registration API returns no failed-version Manifest ID in its 422 response, and no revision-list API exists. For the corrupted-file scenario only, allow a parameterized read-only lookup of the unique REJECTED revision using the Release ID returned by this run’s HTTP request and the synthetic Project ID, solely to obtain the ID required for the Lock request; zero or multiple rows fail. All business-state changes still use real HTTP; never correct state after lookup, guess IDs, or add a production API. The launcher injects this lookup into the scenario; it does not become a business authority.

## Samples and Lifecycle

Use an explicitly SYNTHETIC_DEMO UTF-8 CONFIG file, not a purported flashable image. Fill Manifest fields with actual created ID, project, buildId, and measured file digests, never placeholder contract-example digests.

The PowerShell entry supplies child-process demonstration database configuration, starts Compose, runs m1Demo, and propagates its exit code without changing the user's environment. Stop only services started by this invocation, retaining volume and output without deleting data. Do not stop preexisting demonstration services; fail on password or connection mismatch. Service reuse requires an explicitly supplied matching repository-external demonstration password; never use a newly generated password for an existing volume.

Both first and reused runs explicitly supply VSRQG_DEMO_DATABASE_PASSWORD; the script never automatically generates, prints, or stores it, allowing retained volumes to reuse the same repository-external password. The script accepts only local Docker endpoints. The entry generates the runId, code commit, fixed sample path, and output directory and passes them to the JVM through VSRQG_DEMO_RUN_ID, VSRQG_DEMO_CODE_COMMIT, VSRQG_DEMO_SAMPLE_FILE, and VSRQG_DEMO_OUTPUT_DIRECTORY. Reports also include workingTreeDirty through VSRQG_DEMO_WORKING_TREE_DIRTY; when uncommitted changes exist, the code commit identifies HEAD without proving those changed bytes. Before the JVM starts, record only minimal FAILED metadata; the same summary subsequently carries actual business results, and stopping failures must also make the overall result FAILED.

Write per-run backend/build/demo/m1/<runId>/summary.json and manifest.json with only SYNTHETIC_DEMO, code commit, scenario states, Release/Manifest IDs, digests, error codes, and actual HTTP statuses. Exclude private keys, tokens, raw identities, and connection information. Close the launcher's own application context on exit; failures remain nonzero without retries manufacturing success.

## Verification, Compatibility, and Rollback

Verify valid file→VALID→Lock→export; single-byte corruption before registration cannot Lock; unauthenticated access returns 401; VIEWER with write scopes is still rejected by RBAC; invalid signature, expiration, or wrong audience/issuer is rejected; duplicate keys return the same result; historical exports stay stable; production packaging excludes demo classes; ordinary Backend remains INCOMPLETE and Company controls remain unaffected.

Local verifier unit tests need no Docker. Actual HTTP/database scenarios use PostgreSQL 17.11 and JDK 21, without substituting MockMvc jwt injection. Backend unit tests default to a 60-second timeout; add target tests to existing CI while preserving M1/M2 regression and bilingual Pair Gate. This host has no Docker command, and explicit entry failure was verified. The complete demonstration and data reuse passed in existing bilingual CI; see the Task 3 record.

To roll back, stop using m1Demo while retaining reports and database; ordinary Backend uses its default verifier. No database migration or deployment. Reassess remote files, real identities, larger file workloads, or historical revalidation; frozen-semantic changes require an ADR. This file-verification interface does not replace TDR-004 large-Evidence storage.

## Implementation and Next Step

The [implementation plan](../../superpowers/plans/2026-09-08-local-m1-demonstration.md) has three tasks: file verification, isolated launcher, and single-command flow. Self-review covers both P0 gaps without prewritten conclusions, mock JWT, production demonstration identities, historical rewrites, or Company dependencies; this is design self-review, not independent review or Owner acceptance.

Current result: all three tasks are complete; independent review, paired M1/M2 CI, real demonstrations, and Artifact checks passed. See the [Task 3 record](../../m1/2026-09-08-reproducible-m1-walkthrough.md) and [runbook](../../m1/demo-runbook.md). Git state: bilingual implementation and result records are versioned and pushed. Next action: Owner review of the M1 synthetic demonstration delivery. Preconditions: Owner review; local repetition needs an existing container environment and demonstration password, without Company resources. Acceptance target: explicitly record whether the current demonstration goal is met; no substitute for Owner acceptance and no automatic next milestone.
