# Task 6 read-only report technical review

- Date: 2026-10-10; type: engineering feasibility review, not TDR or Owner acceptance.
- Reviewed baseline: Chinese `1046378` and English `ccb49c9` for TDR-026/TDR-023 and the implementation plan. This record reviews report projection only; it does not implement Task 6.

## Evidence and conclusion

The quality history query returns Evaluations by Release, including completed results or queryable errors. `inputSnapshot` retains source references, versions, selections and Evidence locators. Exact Traceability lookup by `snapshotId` returns `issueSnapshotId`, Manifest ID/digest, paths and gaps. Test Run results expose the selected Attempt/Result. The existing Node report accepts only `m1|m2`, their old inputs and a 1 MiB per-file limit. Evidence metadata GET verifies integrity and writes an observation; download has separate authorization and audit.

The route is implementable with existing APIs and the Node renderer, without a second quality evaluator or service. The following three findings have been corrected in the bilingual TDR proposals and plan but still require formal TDR review:

| Finding | Correction and verification target |
| --- | --- |
| Traceability GET does not return the Issue Snapshot digest. | Cross-check only the Issue Snapshot ID; retain its digest from pinned Quality Input without claiming independent re-verification. Reject wrong-ID or Manifest-digest fixtures. |
| Evaluation ERROR before input pinning lacks `inputSnapshot`. | Show NOT_EVALUATED and the original error without querying absent sources or inventing a Quality Result. Test one ERROR before and one after pinning. |
| The quality export boundary and old report input limit were conflated. | Use separate `quality-report-export.json`, Schema and byte limit while preserving `m1|m2` behavior. Show a synthetic label only with provenance evidence, otherwise UNKNOWN. Test oversize, mixed Release, credential leakage and old-report regressions. |

Current `ruleResults[].evidenceRefs` contains every Evidence ID in that input for applicable rules that finish evaluation; it is empty for NOT_APPLICABLE/ERROR and cannot be labeled causal proof for an individual rule. The report shows only pinned IDs/digests and verifiable source relationships. Navigation acceptance remains open when Evidence routes are disabled or permissions are unavailable. This review closes no real rule publication, device data, three-new-JVM replay, independent DB+Payload restoration or Owner acceptance. TDR-026 remains Proposed / REVIEW_REQUIRED; TDR-023's original M1/M2 Accepted scope does not expand.

## Next engineering check

First fix an export Schema and positive/negative tests from formal isolated-CI query responses, then implement controlled export and pure rendering. Tests must verify exact Evaluation selection, same-Release binding, ERROR without input, HTML escaping, old-mode regressions and a report boundary without network or rule evaluation. Exact-commit CI and bilingual Pair Gate only provide engineering evidence; A1–A8 and Owner acceptance remain separate.
