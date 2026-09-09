# Offline Demonstration Report Runbook

Generate bilingual HTML from one explicitly selected existing M1/M2 run directory, presenting Release, Manifest, Issues, Gaps and history together in a browser. It presents recorded results without rerunning verification or making Release quality decisions.

## Generate

Use existing Node (local verification environment v20.14.0, CI Node 24) without installing dependencies or starting Backend, database or Docker. Prepare the output parent directory first and choose a nonexistent filename; existing output is never overwritten.

Run these commands at the repository root. demo/report/sample contains the preserved [same-run synthetic CI sample](../../demo/report/sample/README.md); backend/build must already exist. If a report exists, choose another new filename with --output.

```powershell
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.zh.html --scope m2 --language zh
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.en.html --scope m2 --language en
```

Alternatively point --run-dir at an explicitly chosen backend/build/demo/m1/<runId> or one run directory in an extracted CI Artifact. Never combine files from different runs. --scope m2 requires same-run M1/M2; --scope m1 displays M1 only and never reads M2. No latest-run selection or implicit scope changes; provide all four arguments explicitly.

## Open and Interpret

Open the generated HTML in an existing browser without Node, database or network. Language files contain identical business values with translated explanations; original IDs, enums, HTTP statuses and error codes stay unchanged. Expand details and scroll narrow tables horizontally.

REPORT_RENDERED and exit 0 mean only successful report generation; source M1/M2 status can remain FAILED. They do not mean the entire demonstration, Owner acceptance or Release Gate passed. Negative-scenario PASS means expected rejection; NOT_RUN/RUNNING and unavailable data stay visible rather than defaulting to success.

SYNTHETIC_DEMO / SYNTHETIC_FIXTURE and Verified=false identify synthetic traceability. manifest.json is registration input; Lock/export outcomes come from source summary scenarios. history.snapshotABytesStable cites a source-run check rather than revalidation. Preserve source runId/codeCommit/workingTreeDirty; dirty=true or an unavailable commit means the commit cannot prove all input bytes.

## Failure and File Preservation

| Condition | Result |
|---|---|
| Minimal or partial FAILED source | Can generate a failed report, explicitly showing business IDs/files not yet produced without guessing a cause |
| PASS source missing required files, invalid JSON/types, over 1 MiB per file, symlinks or nonregular files | REPORT_INPUT_INVALID, nonzero, no HTML |
| Run/code/dirty/Release/Manifest mismatch | REPORT_INPUT_MISMATCH, nonzero, no combined data |
| Missing/repeated/unknown arguments or invalid scope/language | REPORT_ARGUMENT_INVALID, nonzero |
| Existing output, absent parent or output write failure | REPORT_OUTPUT_FAILED, nonzero, no overwrite |

Source JSON stays unchanged. Correct file selection or identify input problems after failure; never delete database/history to manufacture success. Diagnostics contain fixed codes only, without raw inputs, credentials, exceptions or absolute paths. Render only allowlisted fields; unknown extensions are not displayed.

## CI and Provenance

Existing M1 CI generates zh/en for every summary directory after the actual demonstration, selecting m2 only where its controlled sample directory contains m2-summary.json, otherwise m1. Generation errors fail the step without overriding the original demo gate. HTML joins original JSON in the existing m1-demo Artifact with 30-day retention.

Git-preserved samples do not depend on permanent availability of the original Artifact; preserve needed synthetic materials from other runs through existing GitHub practices or mark them unavailable. No new Company or cloud archive environment is required.

See [TDR-023](../v0.2/tdr/TDR-023-offline-demo-report.md) for technical boundaries and the [verification record](2026-09-09-offline-report-verification.md) for actual engineering results. This entry does not authorize merge, Tag, release, deployment or real Providers.
