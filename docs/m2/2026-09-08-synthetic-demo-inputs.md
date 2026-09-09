# M2 synthetic input integration record

- Record date: 2026-09-09; TDR-022 task 1 engineering record, not Owner acceptance.
- Basis: [TDR-022](../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md), [implementation plan task 1](../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md).
- Pre-implementation baseline: Chinese b859e4981270fbfdd27eb1c3e67fec2389b54604; English 7d5d50f04c941ae42ca4b0fb5e82a104ffc2df2b. All four baseline CI runs were verified SUCCESS.

## Authorization and scope

After delivery of those paired proposals and the next action to implement task 1, the Owner instructed execution of the next step on 2026-09-09. Preserve the original instruction as Unicode escapes. This records task 1 implementation authorization only, not task 2, Owner acceptance or deployment authorization.

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

This change adds a demo FIXTURE factory, bounded synthetic Build validator, explicit M2 startup options, Engineer/Service identities and Source configuration bootstrap. The IncludeM2 command option, full HTTP flow and result report remain task 2; this change does not deliver a completed M2 demonstration command.

## Implementation

M2DemoInputs reuses FixtureIssueSourceAdapter for two CLOSED/HIGH synthetic Issues with the compiled mappingVersion and current startup time. The M2 descriptor implements public IssueSourceDescriptorRegistry without accessing or expanding main source set internal visibility. Preserve DefaultIssueSourceRuntimeRegistry, the Mapping codec and production default registry.

M2DemoProvenanceValidator matches only the designed Build/Issue/revision pairs, fixed sample fields, current payload SHA and proofDigest. Matching input returns VALID/LOW with dedicated m2-demo-fixture-provenance/v1; other input returns INVALID/LOW. This proves conformity to the synthetic fixture, not a real GitHub Build. It never accesses sample locators or changes canonicalization or the production validator.

M1DemoMain.start defaults includeM2 to false. Only explicit M2 startup injects the primary descriptor/validator and factory and enables existing Worker/write flags. Missing or invalid payload SHA fails explicitly. Ordinary M1 retains its enabled Snapshot default, disabled Workers, PILOT/NONE and loopback boundary.

Identity reuses actual RSA JWT/decoder, Principal resolver and database authorizer. initializeM2 uses a parameterized transaction to add only ENGINEER, SERVICE, assignments and a FIXTURE Source, with no credential reference or prewritten business facts/Snapshots. Before Mapping activation the existing runtime returns MAPPING_PROFILE_NOT_CONFIGURED; afterward it uses authoritative versions. SERVICE project claims bind project_key rather than internal Project ID.

## Local validation

RED: before the new types/startup parameter existed, target compileTestKotlin failed on unresolved M2DemoInputs, M2DemoProvenanceValidator and includeM2, exiting 1 with operational Gradle/JDK.

GREEN: run from backend with JDK 21:

```text
./gradlew test --tests '*M2DemoInputsTest' --tests '*M1DemoPackagingTest' compileDemoKotlin bootJar
./gradlew compileTestKotlin
```

Both commands exited 0. Target tests: 11/11 PASS, comprising M2DemoInputsTest 4 and M1DemoPackagingTest 7, with no skips, failures or errors. Coverage includes matching/mismatched fields, JWT claims, default M1/explicit M2 environments, invalid payload SHA, production JAR and component-scanning isolation.

The new real PostgreSQL/Spring case is in M1DemoIntegrationTest and reuses its existing container. It covers M1/M2 bean selection, minimal Bootstrap writes, runtime rejection before activation, factory open after real Mapping activation and SERVICE decoder → resolver → authorizer. It compiled; local Docker is unavailable, so it was not executed locally and does not establish integration PASS.

## Review and remote validation

Independent task re-review passed: Spec compliant, code quality Approved, with 0 Critical/Important/Minor findings. The first review package omitted the engineering record; adding it closed the finding without changing runtime code. Append implementation-commit CI results when available. Current local evidence does not replace actual PostgreSQL 17.11/Spring execution or prove the task 2 integrated demonstration.

## Next execution plan

Current result: task 1 code, local target tests and integration-test compilation are complete; independent review passed and exact-commit CI verification is pending. Git status: accompanies the paired task 1 implementation commits. Next action: complete task 1 independent review and paired CI verification. Prerequisite: existing GitHub CI, with no new environment. Acceptance target: passing real database integration tests and successful default M1 regression, with observed evidence recorded; no Owner acceptance by the agent.
