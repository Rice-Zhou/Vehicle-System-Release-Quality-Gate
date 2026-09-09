# Offline Read-Only M1/M2 Demonstration Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate bilingual offline read-only reports from existing same-run M1/M2 JSON.

**Architecture:** One pure presentation module owns field projection, association checks and HTML; a separate CLI owns arguments and file I/O only. No Backend connection, business-result recomputation or demo lifecycle changes.

**Tech Stack:** Existing Node v20.14.0 / CI Node 24, built-in node:test, HTML/CSS and existing GitHub M1 workflow; no new dependencies.

**Spec:** [TDR-023](../../v0.2/tdr/TDR-023-offline-demo-report.md), also serving as this plan's design specification; Accepted (Task 1 implementation scope), with Owner authorization to execute; final acceptance is separate.

## Global Constraints

- Require --run-dir, --output, --scope m1|m2 and --language zh|en; 1 MiB per input, fixed filenames, no overwriting old output or input.
- Present only SYNTHETIC_DEMO / SYNTHETIC_FIXTURE, Verified=false; separate JSON PASS/FAILED from REPORT_RENDERED, without Release PASS/BLOCK.
- Explicit language selection in one module; non-Markdown files match across branches.
- Default individual test timeout: 60000; the pure generator needs no JVM, Docker, service, key or network.
- Do not modify business code, Schemas, Migrations, authentication, DemoReport/M2DemoReport, run-m1.ps1 or accepted records; no merge, Tag, release, deployment or real Provider/Company activation.

## Task 1: Generator, Samples and Presentation Delivery

**Files:**
- Create: scripts/demo/demo-report.mjs (sole presentation/input projection module), scripts/demo/render-report.mjs (CLI).
- Create: scripts/tests/demo-report.test.mjs (node:test including in-memory negative variants).
- Create: demo/report/sample/summary.json, manifest.json, m2-summary.json and README.md (original sample and bilingual provenance record).
- Create: docs/demo/offline-report-runbook.md (operation/result semantics), docs/demo/2026-09-09-offline-report-verification.md (actual verification record).
- Modify: .github/workflows/m1-backend.yml (tests, generation and existing Artifact paths), docs/m2/synthetic-demo-runbook.md (offline runbook link).
- Status records: update TDR-023 and its index after implementation authorization; check completed plan steps; record final Owner decisions separately under existing acceptance governance.

**Interfaces:**
- renderDemoReport({ summary, manifest, m2 }, { scope, language }) -> string; parsed JSON inputs with null for absent optional files, returning full HTML without mutation. This function alone owns TDR field types, same-run associations, allowlisting, escaping and display rules.
- ReportInputError extends Error exposes code REPORT_INPUT_INVALID or REPORT_INPUT_MISMATCH; CLI never prints raw message/stack.
- generateReport({ runDirectory, outputFile, scope, language }) -> Promise<void> in the importable CLI file; strict argument parsing runs only on direct execution, never import. Call renderDemoReport after file checks/JSON parsing; map failures only to fixed TDR codes without duplicating business/shape rules.

- [x] **Step 1: Fix synthetic input.** Verify existing samples using the TDR Artifact ID, ZIP digest and source commit. Select all three files from one successful IncludeM2 run, preserve bytes and record source, member paths, digests and synthetic boundaries in README; use identical bytes on both branches. State unavailable sources explicitly rather than labeling handmade fixtures actual CI Evidence.
- [x] **Step 2: Write failing tests first.** Read the fixed sample in scripts/tests/demo-report.test.mjs. Add and run the key tests below, confirming failure because the module/functions do not yet exist before implementation. readSample parses only the three known files; use structuredClone for negative variants without contaminating originals.

```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderDemoReport } from '../demo/demo-report.mjs';
async function readSample() {
  const read = async name => JSON.parse(await readFile(
    new URL(`../../demo/report/sample/${name}`, import.meta.url), 'utf8'));
  return { summary: await read('summary.json'),
    manifest: await read('manifest.json'), m2: await read('m2-summary.json') };
}
test('same-run values and escaping', { timeout: 60000 }, async () => {
  const input = await readSample();
  input.manifest.artifacts[0].name = '<img src=x onerror=alert(1)>';
  const before = JSON.stringify(input);
  const html = renderDemoReport(input, { scope: 'm2', language: 'en' });
  assert.ok(html.includes(input.summary.runId));
  assert.ok(html.includes(input.m2.traceabilitySnapshotIds.A));
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'));
  assert.ok(!html.includes('<img'));
  assert.equal(JSON.stringify(input), before);
});
test('mixed run is rejected', { timeout: 60000 }, async () => {
  const input = await readSample();
  input.m2.runId = '00000000-0000-4000-8000-000000000000';
  assert.throws(() => renderDemoReport(input, { scope: 'm2', language: 'zh' }),
    error => error.code === 'REPORT_INPUT_MISMATCH');
});
```

- [x] **Step 3: Implement the sole presentation module.** Apply TDR shape, optional FAILED-field and association checks, projecting into local objects; use fixed zh/en dictionaries and one escapeHtml function for the complete document. Fixed templates use data-field identifiers for precise row/cell assertions; absent data never defaults to success. Use Artifact/A/B Issue tables, path/Gap lists and native details without scripts, graph computation or arbitrary object serialization.

```javascript
const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);
// Fixed-field example; all dynamic text passes through escapeHtml.
const cell = (key, value) => `<td data-field="${key}">${escapeHtml(value)}</td>`;
// key comes only from internal fixed field constants, never input object keys.
```

- [x] **Step 4: Implement CLI boundaries and extend tests.** Use lstat/file-type checks, bounded reading, strict UTF-8 decoding, JSON.parse, renderDemoReport and exclusive output creation. Map I/O errors to fixed codes without input/path text on stderr. Give each test a 60-second limit; add concrete result assertions for every row below.

| Test input | Required assertion |
|---|---|
| zh/en normal M2 and m1 scope | Original identifiers, digests, flags, paths/Gaps and separate statuses match; copy changes, technical values do not; m1 never reads M2 |
| Minimal summary FAILED, null commit/dirty, no business files | Renders FAILED/unavailable without fabricated PASS, Lock or M2 cause |
| Partial FAILED, NOT_RUN/RUNNING, m2 FAILED | Preserve existing fields and statuses, without combined success |
| summary PASS missing Manifest/M2, malformed JSON, invalid type or Verified=true | Nonzero, fixed INPUT_INVALID, no HTML |
| Mixed runId/commit/dirty/Release/Manifest | INPUT_MISMATCH, no HTML; never resolve inconsistency by selecting the latest directory |
| Over 1 MiB, invalid UTF-8, nonregular file/symlink | INPUT_INVALID; no writes to source files |
| Existing output, output equals any input, absent parent directory | OUTPUT_FAILED, original file bytes unchanged |
| Missing/repeated/unknown arguments, invalid scope/language | ARGUMENT_INVALID without implicit defaults |
| Special text, unknown token/locator extension fields | Special text stays text, unknown fields/values absent from HTML; no script, network resources or event attributes |

Run: node --test scripts/tests/demo-report.test.mjs. Target: all focused tests pass. File/CLI negatives use temporary directories and subprocesses to verify actual exit codes, not only pure functions.

- [x] **Step 5: Generate and inspect in a browser.** Run the commands below with an existing backend/build directory, then generate failed reports from temporary test failure samples. Open normal/failed zh/en in a browser; inspect narrow layouts, long IDs, tables, no network requests and no executable injection. Record actual environment, fixed inputs, commands, results and screenshot references in the verification document; explicitly mark unavailable checks incomplete.

```powershell
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.zh.html --scope m2 --language zh
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.en.html --scope m2 --language en
```

- [x] **Step 6: Integrate existing CI and runbook.** Run focused node:test after Node setup in the M1 workflow. After existing demo lifecycle checks, enumerate actual summary directories under this CI run's backend/build/demo/m1; explicitly pass m2 if m2-summary exists, otherwise m1, generating zh/en per directory. Fail explicitly if no summary exists; propagate every nonzero CLI exit. Add backend/build/demo/m1/*/report.*.html to existing Artifact paths, retaining always upload and retention-days: 30. Explain existing Node for generation versus no Node for browser reading, explicit directory/scope, REPORT_RENDERED versus source status, refusing overwrite and data expiry. Do not change run-m1.ps1.
- [ ] **Step 7: Verify and deliver the bilingual pair.** Run the documentation checks below, focused tests and diff review; after paired commits run Pair Gate, confirm non-Markdown parity, then push atomically. Verify existing CI, HTML/JSON Artifacts and field correspondence against exact implementation commits. Separate verification records from product implementation commits; missing/failed evidence cannot count as complete. Handle Owner acceptance under existing governance only once actual reports are reviewable.

```powershell
node --test scripts/tests/demo-report.test.mjs
node scripts/contract-validator.mjs
node scripts/acceptance-record-validator.mjs
git diff --check
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
```

Check each exit separately and stop subsequent commit/push on failure. This pure report tool does not change backend code or require installing local Docker for HTML; existing CI continues its actual demonstration flow.

## Design Self-Review and Next Step

One task covers input provenance, failure semantics, bilingual display, safety, CI and browser verification; every TDR completion criterion has explicit steps/tests. At plan authoring, no new commands had run; current task progress and actual verification are recorded below and in the verification record.

Current result: the TDR-023 Task 1 generator, samples, tests, CI integration and runbook passed local verification; three independent task-review findings were fixed and approved on re-review. Git status: implementation is versioned under bilingual governance; remote checks determine push status. Next action: verify fixed-implementation bilingual CI/Artifacts and prepare the Owner review record. Prerequisites: final review and actual CI results, without Company resources or new environments. Acceptance target: exact-commit HTML matches source JSON field by field, with automated and actual-rendering evidence ready for an Owner decision.
