# Minimal quality evaluation: design review and technical probe

- Date: 2026-09-15; type: Controller self-review and isolated technical probe, not independent review or Owner acceptance.
- Design baseline: ZH `348ce96` / EN `9739218`. All six exact-commit CI runs are completed/success: ZH 34968778296, 34968778151, 34968778167; EN 34968777960, 34968777984, 34968777964.
- References: [design](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md).

## 1. Conclusion and findings

The technical approach can proceed to planning, but the probe is neither a production parser nor a complete canonical encoder. Three independent JVM executions each passed 17 capability checks with identical output hashes. No software was installed, product dependencies changed or devices/services run.

| Finding | Evidence | Resolution |
|---|---|---|
| Event parsing does not automatically enforce all LoaderOptions restrictions | allowDuplicateKeys=false still yields duplicate key events; alias limit 0 exposes AliasEvent; depth limit 32 still yields 33 levels. | Explicitly reject duplicate keys, anchor/alias, tags, merge keys and multiple documents in the event stream, counting depth and nodes. Added to design; production rejection tests remain unimplemented. |
| Byte limits differ from code point limits | 22000 Chinese characters produce UTF-8 input exceeding 64 KiB. | Bound bytes before parsing instead of relying only on LoaderOptions. |
| Implicit conversion needs control before object construction | Events distinguish date lexemes, quoted booleans and explicit tags. | Disable general object construction; use restricted scalar grammar plus schema/catalog typing and explicit rejection. |
| Basic numeric encoding is feasible; full encoding still needs golden tests | Adjacent integers above 2^53 differ; high-precision decimals retain precision; trailing zeros/negative zero normalize; INTEGER and DECIMAL tags differ. | Fix typed-tree format, strings and key ordering; bound exponent/expanded length to prevent toPlainString resource expansion. |
| Catalog and rule policy still require review | Catalog v1 numeric confidence differs from categorical source facts; required comes from new Rule Set policy. | Keep TDR Proposed; probe success does not accept catalog v2, required provenance or Case actions. |

## 2. Environment and verifiable evidence

Used existing Temurin JDK 21.0.7+6 and cached SnakeYAML 2.5 through Yaml.parse events, without claiming the latest version or a completed vulnerability assessment. Pin the implementation dependency candidate to org.yaml:snakeyaml:2.5; product integration must explicitly declare it and check BOM conflicts instead of relying on incidental transitive dependencies.

The controlled local root is `D:/VSRQG-local-smoke/quality-preflight-20260915`. QualityProbe.java is a throwaway probe, not product implementation. run-1.log, run-2.log and run-3.log retain individual PASS results; these materials are local only, without guaranteed access for reproduction on another host.

| Material | SHA-256 |
|---|---|
| snakeyaml-2.5.jar | e6682acf1ace77508ef13649cbf4f8d09d2cf5457bdb61d25ffb6ac0233d78dd |
| QualityProbe.java | 43a170856bb11ceaa6d3b0e1be249dbfe61fbe632e0c85d09427bc08165a7550 |
| Each run log | 261ead5650996ce9fc98cd2f8e056c64a39edf11abb5e7b3475097bb78e9dd42 |
| UTF-8 numeric sample ["DECIMAL","1.23"] | 02ebce75b419424adaded408749ac8e5949417c22bab801dd7338be1cd7b7341 |

Coverage: single document, duplicate key visibility, anchor, alias, tag, multiple documents, plain date, quoted boolean, merge key, 33-level events, UTF-8 bytes, adjacent large integers, decimal trailing zeros, negative zero, high precision, numeric type distinction and fixed numeric bytes. These prove available interface information and basic numeric operations, not an implemented final rejection layer, full encoder, all operators or three Quality Result replays.

## 3. Exact design amendments

Check events before any general constructor. Mapping key uniqueness uses decoded scalar string keys, rejecting non-string/complex keys and merge keys; independently track collection context. Never recursively construct objects, instantiate tags or validate after construction. Quoted date-like strings may remain literal; ambiguous plain scalars are rejected, and true/false/null/numbers use only restricted JSON-style lexemes.

Fix the proposed canonical tree as ["TYPE", value]. OBJECT value is an array of [key, typedNode] entries ordered by Unicode scalar value; ARRAY retains catalog semantic order; INTEGER/DECIMAL values are canonical decimal strings, STRING retains its original string, BOOLEAN is a boolean and NULL is null. Use whitespace-free UTF-8 JSON, escaping only quotes, backslashes and control characters; encode controls as lowercase four-digit Unicode escapes, reject unpaired surrogates and perform no Unicode normalization. Every node type needs positive/negative golden bytes; the numeric sample alone cannot accept the full encoder.

Before expanding BigDecimal, require token length at most 4096, precision at most 4096, absolute scale at most 4096 and estimated expanded length at most 8192; integers are also limited to 4096 digits. Exceeding a limit produces ERROR, never truncation or floating-point fallback. These are new resource limits awaiting production rejection tests, not behavior covered by the probe.

## 4. Next deliverable

Current result: candidate parser interface and basic exact numerics are feasible, with design limits clarified; production guards, full encoding and policy acceptance remain outstanding. Git status: review and design amendments are committed bilingually, with exact commits in Git history. Next action: prepare a staged implementation plan from the amended design. Prerequisites: Owner acceptance of TDR-026 catalog integration and rule policy; frozen changes require ADR first. Acceptance target: cover A1–A8 and place event guards, full encoding golden tests, BOM checks and source fact binding in the first segment; do not claim engineering acceptance early.
