# Minimal quality evaluation Task 2: strict parsing and canonical encoding

Date: 2026-09-17. References: [implementation plan Task 2](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), [probe amendment](2026-09-15-quality-evaluation-preflight.md).

## Authorization and scope

The Owner replied “execute the next step” after Task 2 was named as next, authorizing this parsing/encoding segment without recording TDR Accepted, rule publication or product acceptance. All six CI runs on Task 1 fix commits 7f7ca0e / b4904ef were verified successful; the original English failure remains recorded.

This segment provides only a bounded YAML syntax tree, explicit failure codes and typed canonical bytes. Rule AST, fact catalog binding and evaluation belong to Task 3; publication, database, Jobs, actual inputs and replay belong to later segments. Existing Core, catalog v1 and module digests remain unchanged.

## Dependency and compatibility boundaries

Before changes, dependencyInsight already resolved org.yaml:snakeyaml:2.5: Jackson YAML 2.21.4 requests 2.5, while the existing graph upgrades Spring Boot 3.5.16's soft 2.4 constraint. Explicitly pin the currently resolved version without upgrading Spring Boot/Jackson or describing existing successful Gradle resolution as a new dependency conflict; stop on newly unresolvable conflicts. This record is not a dependency vulnerability assessment.

## Boundary interpretation

Rule input is limited to 64 KiB of original UTF-8 bytes; root depth is 1 and mapping keys also count toward the 4096-node budget. Duplicate keys compare decoded strings. Parsing yields a syntax value tree only; success does not establish Rule Schema, catalog path or operator validity.

Encoding retains the typed tree format with the same version identifier as Task 1; object keys sort by Unicode scalar value and arrays retain caller-supplied semantic order, without guessing catalog order in the encoder. The 4 MiB output limit includes type wrappers, escapes and UTF-8 overhead.

The 4096 limits on numeric tokens, precision, integer digits and absolute scale apply together; accepted numbers therefore expand to at most 4099 characters (sign, 0. and 4096 fractional digits), making the 8192 expansion guard additional protection. Do not weaken other limits to fabricate a valid 8192 boundary test; test reachable boundaries and expanding inputs rejected earlier.

## Verification record

- TDD: the initial 15 assertions failed; after adding YAML/TAG directive and implicit-empty boundaries, 2 of 18 tests failed before fixes. Logs remain locally in this plan's .superpowers/sdd directory.
- Independent review found P2: the underlying Resolver treated 08/09 and legacy numbers longer than 1024 characters as strings. After regression RED, implicit classification was removed in favor of explicit bounded scalar grammar and linear sexagesimal checking; tests cover 1024/1025, long legacy numbers, the 64 KiB boundary, quoted retention and digit-leading business text. Independent JDK 21 probes confirmed closure with no remaining blockers.
- Final Chinese regression passed 27/27: StrictRuleYamlTest 11, QualityCanonicalEncoderTest 8, ArchitectureTest 6, ApplicationContextTest 2; English targeted tests passed 19/19. All had zero failures, errors and skips; assemble succeeded in both worktrees, and new test classes use a 60-second timeout.
- Node contract tests passed 7/7; the full validator reported schemas=7, positive=20, negative=9, operations=36. Existing machine contracts and v1 files were unchanged.
- Golden bytes cover all value types, controls, Unicode scalar ordering, no normalization, exact large integers, negative zero and trailing zeros; a directly constructed 20000-level tree avoids recursive stack overflow, and the exact 4 MiB boundary includes byte/escape/key overhead.
- Post-change dependencyInsight confirmed successful strictly 2.5 resolution with unchanged platform versions. YAML/TAG directives are explicitly rejected; unknown directives without semantic effect are ignored by the underlying parser, without claiming rejection of every directive.
- This segment does not establish passing Operator Matrix, actual inputs, database recovery, three quality replays or Owner acceptance. Final Pair Gate, push and CI are established by fixed delivery commits, without claiming remote success in advance.

## Next execution plan

Current result: established by actual verification in this record. Git status: paired bilingual commits identified by Git history. Next action: implement Task 3 restricted rule evaluation after Task 2 engineering verification. Prerequisites: passing tests, review and fixed-commit CI for this segment. Acceptance target: operator matrices, error propagation, resource limits and golden tests for the two demo rules pass, without automatic rule publication.
