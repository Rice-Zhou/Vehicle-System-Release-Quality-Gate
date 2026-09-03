# Traceability v1 Consumer Inventory

## Reproducible command

```powershell
rg -n "TraceabilityFactBatch|TraceabilityFactInput|facts:ingest" . --glob '!docs/superpowers/**'
```

## Result

The command was run in this worktree on 2026-09-03. Matches are limited to these permitted categories:

- The OpenAPI contract, compatibility baseline, and `M2ApiContractTest`; the `TraceabilityFactInput` assertion proves that v1 is superseded and is not a caller.
- Existing written design, governance acceptance records, TDR-017, and this inventory record; these preserve the history and migration decision for the v1 pre-release draft.
- Design documentation that references the frozen `/api/v1/traceability/facts:ingest` endpoint; the endpoint remains unchanged and is not evidence of a v1 request-body consumer.

No Controller, Application, Adapter, fixture, script, or workflow real v1 consumer matched. Therefore the unimplemented and unused `TraceabilityFactBatch`/`TraceabilityFactInput` draft is superseded in the same contract subject by `BuildProvenanceEnvelope schemaVersion: 2`; the Path, Method, Permission, `Idempotency-Key`, and `serviceOauth` remain unchanged.

If a future run finds any real v1 consumer, supersession must stop, v1 must be retained, and the TDR-017 compatibility review must be reopened.
