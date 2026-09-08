# Local Payload Verification Implementation Record

- Record date: 2026-09-08; Task 1 engineering record only, not Owner acceptance.
- Design and scope: [TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md), [implementation-plan Task 1](../superpowers/plans/2026-09-08-local-m1-demonstration.md).
- Pre-implementation baseline: Chinese 4876d8a1a3cb75fa0f1f6236fe3965b2ac64acd1; English a4a3ff1c457b66bd9ef77125b39f69b0d635c108.

## Execution Authority

Following design delivery, the Owner issued the next-step instruction below. The original Chinese is preserved using Unicode escapes; it confirms Task 1 implementation, without accepting the complete demonstration or extending authorization to Tasks 2/3 or Company infrastructure.

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

## Changes and Compatibility

ArtifactPayloadVerifier is an application interface; ordinary Backend default configuration retains the original INCOMPLETE, ARTIFACT_CHECKSUM_NOT_VERIFIED, message, and validator version. ValidateManifest invokes the interface only after existing consistency checks pass, and reports retain the existing registration transaction. LocalArtifactPayloadVerifier is an explicitly constructed ordinary class, not an automatically activated production Bean.

The local implementation actually reads digest-named files, limited to 1 MiB per file and 16 files per invocation, using m1-local-payload/1. Matching files yield VALID, missing or unreadable files yield INCOMPLETE, and invalid digests, links, nonregular files, exceeded limits, or mismatches yield FAILED. FAILED takes precedence for mixed results, with violations preserving input positions. Results exclude local paths and raw exceptions.

No API, Schema, Migration, Lock trust list, or historical validate-read logic changed; no dependencies, launcher, environment, or real Provider were added. Changes to files after registration do not rewrite history; this implementation provides neither continuous monitoring nor administrator-resistant modification guarantees.

## Verification Record

RED: new target tests failed compilation because the interface, local implementation, and configuration were absent, with exit code 1; implementation and GREEN followed. Using the existing JDK 21, the command below exited 0: 33 tests, 30 PASS, 3 SKIPPED, 0 failures; bootJar succeeded. ArchitectureTest has 6 cases, ArtifactPayloadEvaluationTest 2, ArtifactPayloadVerifierTest 13 (3 skipped), ManifestContractTest 9, ManifestIdempotencyScopeTest 1, and ManifestSemanticValidationTest 2; XML is under backend/build/test-results/test. Contract and acceptance-record validation passed. Insufficient Windows file-permission or symbolic-link capabilities are explicitly recorded as SKIPPED, not PASS; PostgreSQL integration tests require the Linux CI container environment.

```text
./backend/gradlew.bat -p backend test --tests '*ArtifactPayload*Test' --tests '*ManifestSemanticValidationTest' --tests '*ManifestContractTest' --tests '*ManifestIdempotencyScopeTest' --tests '*ArchitectureTest' bootJar --console=plain
```

Independent read-only review: APPROVE, with no important issues requiring fixes; reviewed task scope, dependency direction, default compatibility, local verification paths, and test XML. The reviewer did not rerun the build; database and Linux-specific tests remain subject to CI.

This commit retains M1 clean test bootJar and M2 gates; new unit tests automatically enter existing CI. Remote results must use actual workflow outcomes for this pair of fixed commits; unfinished runs must not be recorded as passing.

## Next Execution Plan

Current result: Task 1 implementation, local checks, and independent review are complete; remote regression awaits CI. Git state: this record accompanies the paired implementation commits. Next action: execute Task 2 isolated demonstration launcher after Task 1 checks finish. Prerequisite: next-step execution instruction; actual database scenarios require an existing container environment. Acceptance target: real HTTP/JWT completes synthetic M1 positive/negative scenarios, and production packaging excludes demonstration classes; this record does not substitute for Owner acceptance.
