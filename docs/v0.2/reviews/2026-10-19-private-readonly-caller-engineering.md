# H01–H05 private read-only caller isolated-test engineering record

- Date: 2026-10-19; scope: synthetic materials, empty Contexts/doubles and minimal callable assembly, not actual material preparation, Backend startup or Owner acceptance.
- Basis: [bootstrap engineering record](2026-10-19-private-readonly-bootstrap-engineering.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md) and the confirmed external contract. Original descriptions and missing references remain unchanged.

## Implementation

PrivateReadOnlyCaller accepts ReadOnlyHandoff, a material resolver, ReadOnlyProofFile and a nonbusiness observation callback. Missing or blank values among 15 prerequisites fail before resolution; tlsIndependentRecord must then exactly match TlsReferenceEvidence.recordReference. This checks completeness and reference identity, not approval or external-record authenticity. Runtime proofs, connection observations, handshakes and shutdown receipts are not prerequisites for themselves; human accounts, published rules and new Attempts are not required.

ReadOnlyMaterialReferences resolves only explicit database/TLS descriptors and a supplied verification public key. Database descriptor parsing is extracted from the existing M3 flow for shared reuse. DemoJwtDecoder shares the original signature, issuer, audience, time and mandatory-claim validation chain with the old identity implementation. The new path creates no signing identity or Token and invokes neither full M3 configuration nor old demonstration entry points.

The factory remains the sole implementation of 44 property/source checks. The file sink persists the existing ReadOnlyStartupProof's 40 observations, entirely omitting four credential/password entries and reducing TLS paths to match markers. It uses the private-path boundary and CREATE_NEW, returning only after synchronous writing and successful close. Existing targets are never overwritten; write/close failures prevent refresh. No second property validator is added.

The caller captures ownership when its Context is created. Because Spring destruction may only log pool-close errors, inferred pool destruction is disabled on the actual merged definition. The exposed instance is captured before property population, then the pool is explicitly closed and Context close still attempted. Execution, pool and Context failures are retained. Multiple distinct pools fail assembly while all captured pools are cleaned. There is no second pool, connection probe or external process operation.

## Tests and review

Tests were written first. Missing APIs caused a separately retained compilation failure, not a behavioral failure. A compilable skeleton produced 13 failures for reference bypass, unimplemented materials/Decoder, a no-op sink and missing cleanup. A test-double callback colliding with a Spring method was corrected and failures reproduced. Only the test reflection transport unwraps checked IO exceptions; product code does not swallow them.

Independent read-only review identified two Important findings: mismatched TLS handoff records and actual pool errors lost during Spring/Boot early close. Five new counterexamples failed before fixes; additional coverage checks property-population failure and three failure levels. Final caller tests number 20, existing bootstrap tests 21 and packaging regressions 7: 48 pass per language without failures, errors or skips. Tests retain a 60-second timeout; affected demo/test compilation succeeds.

New tests use temporary synthetic descriptors, password sentinels, test signing/public keys and nonconnecting DataSources, running substitute or empty Spring Contexts rather than the caller's default start function. Temporary Web fixtures in old automated tests are not user-environment startup. Existing checks retain production-JAR/component-scan exclusion; no new Main, JavaExec, profile or dependency is added.

The full local Backend suite was also attempted, with 350 failures and 11 skips. The initial cause and subsequent initialization errors are Testcontainers having no usable Docker. Full results and per-class failures are retained; local full-suite success is not claimed. The post-fix full English result, exact-commit Chinese/English M1/M2/M3 CI and Pair Gate are recorded in the delivery receipt, with no success prefilled here.

## Guarantee boundaries

Pool ownership begins after construction exposes an instance to the controlled Spring creation chain. It does not cover unreturned resources inside arbitrary constructors, arbitrary external shutdown listeners or arbitrary custom destruction. Close failures remain visible, but normal return is not independent proof of device, thread or all-connection termination; uncontrolled extensions require separate review.

Actual materials, independent approvals, ACLs and private-root ownership remain external R1 checks. Real TLS, clone identity and pool-session read-only behavior remain unverified. WORM, fsync and filesystem/database atomicity are not promised. Fixes are verified by counterexamples and regressions, without claiming another independent review; engineering review does not replace the rule reviewer or Owner acceptance.

## Status and next action

Evidence directory: D:/VSRQG-local-smoke/g4-h01-h05-implementation-20261019. The original contract's 19 actual references, identities, published versions and new Run/Attempt references remain explicitly missing. Identity binding remains DEFERRED_BY_OWNER. Code existence does not establish an actual handoff binding.

No actual sensitive materials were read, user configuration enabled, user private Backend/Agent started, business requests sent, business Runs created, rules published, permissions granted or real Provider/Company resources enabled. No merge, Tag, publication or deployment occurred. Plan checkboxes and acceptance states are unchanged; TDR-026 remains Proposed / REVIEW_REQUIRED.

Current result: isolated counterexamples and regressions support the caller, file sink and lifecycle. Git status: paired commits/pushes and exact-commit CI are recorded in the delivery receipt. Next action: prepare and check the controlled R1 material package after separate authorization. Preconditions: explicit material-read scope, controlled sources and independent read-only database-audit authorization; actual startup requires separate authorization. Acceptance target: independent records match actual material references, the private target has current evidence and missing values are never filled by defaults; engineering tests do not advance acceptance.
