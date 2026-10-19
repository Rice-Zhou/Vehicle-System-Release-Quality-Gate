# BC01–BC03 Private Read-only Bootstrap Isolation Test Engineering Record

- Date: 2026-10-19; scope: isolated tests and minimal demo assembly, not an actual read-only startup, G4 execution, or Owner acceptance.
- Basis: [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md) and the offline BR01/BR02 and BC01–BC03 contracts and reviews. Original descriptions and missing references remain unchanged.

## Implementation

Add `PrivateReadOnlyBackendBootstrap.kt` within the existing demo source set. One 44-key contract generates the final phase properties and checks values, sources, absence, and redaction. No configuration file, dependency, Main, or JavaExec is added. Production modules, database migrations, and existing demonstration flows remain unchanged.

P35 uses scalar resolution and the existing `ManifestValidationTrustPolicy` as the consumer comparison. Binder-only indexed representations, empty CSV items, and duplicate items are rejected. Profiles receive a T0 record before Boot preparation; the final check compares both properties and actual active/default sets. A late overlay cannot retroactively establish the earlier choice.

`InspectionContext` retains immutable registrations and preparation evidence for this Environment. Sources are matched by reference identity; inventory precedes the overlay so same-name replacement cannot erase an invalid source. The Boot transparent view is captured by controlled attachment, and legitimate framework sources are registered at their preparation stages. The factory uses the existing YAML loader beforehand to capture fixed classpath resource evidence solely for comparison with later source content and Origin, without adding another Environment source. Replaced or copied sources after preparation are rejected.

`ResolvedTlsInput` requires an externally supplied immutable independent reference record associated with the existing helper paths and eight properties. Test records use synthetic materials and do not prove actual R1 approval. Inspection does not read TLS files, create a Decoder, obtain connections, or generate approval. Refresh is delegated only after final validation and successful evidence recording; an already instantiated DataSource or a failing sink prevents continuation.

## Tests and review

Write `PrivateReadOnlyBackendBootstrapTest` first. The initial missing-class compilation is retained separately and does not count as behavioral failure. On the minimum compilable scaffold, 14 of 15 tests failed assertions because the new gates were absent; this was not a legacy production regression. A lower scalar initially interfered with the indexed counterexample; the fixture for that same property was corrected and failure reconfirmed, with other inputs remaining legal.

The initial implementation passed all 15 tests. Independent review then found acceptance of a foreign Boot view, rejection of the legitimate ApplicationInfo source, and acceptance of a forged classpath Origin. New tests, together with empty/duplicate CSV cases, produced four actual failures and passed after repair. The subsequent independent review found no remaining blocking issue. This engineering review does not replace independent reviewer approval of project rules.

Final Chinese target tests passed 21/21 and existing packaging regressions passed 7/7, with no failures, errors, or skips. Tests have a 60-second timeout. Coverage includes frozen source contents, late identical resource copies, independent profile counterexamples, missing/disconnected TLS reference evidence, no connection acquisition, and proof preceding substitute refresh. TLS files contain temporary placeholder bytes, not valid certificates or an actual handshake. Lifecycle tests invoke only configuration preparation methods and a Context substitute that does not truly refresh; they do not run the application.

The complete local backend suite was also attempted: 1,253 tests, 350 failures, and 11 skips. The first cause was Testcontainers finding no usable Docker; related classes then failed initialization. M1/M2 demo integration tests likewise reported that Docker initialization could not be retried. Full logs, per-class failures, and XML are retained; existing tests were neither modified nor silently skipped. Final English target tests, compilation, packaging, exact-commit M1/M2/M3 CI, and Pair Gate results belong to the delivery receipt and are not claimed in advance.

## Boundaries and next step

Evidence directory: `D:/VSRQG-local-smoke/g4-bc01-bc03-implementation-20261019`. Actual TLS/Decoder/credentials/source registration, read-only sessions, and endpoint proof remain missing. Identity binding remains `DEFERRED_BY_OWNER`; published versions, four scenarios' new Run/Attempt/Evaluation IDs, and formal business receipts are unfilled. No actual configuration is enabled; no user isolation Backend/Agent, business requests, business Run, rule publication, permission grant, real Provider, or Company resource is activated. Temporary fixtures in existing automated tests do not become user environment facts.

No merge, Tag, release, or deployment occurs. Plan checkboxes and acceptance remain unchanged. TDR-026 remains Proposed / REVIEW_REQUIRED; Evidence windows and the two completion-evidence layers are unchanged.

Current result: BC01–BC03 isolation boundaries have implementation and test evidence. Git status: paired commits, pushes, and CI are identified in the delivery receipt. Next action: read-only reconciliation of the pure assembly caller and missing R1 material preparation. Preconditions: actual material access and startup require separate authorization; the existing deferred identity decision remains effective. Acceptance target: source records, TLS/Decoder, and private targets can be located individually, with missing values explicit; these tests authorize no actual startup and do not complete G4 or Owner acceptance.
