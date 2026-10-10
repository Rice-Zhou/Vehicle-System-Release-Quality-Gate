import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import fs from 'node:fs';
import { renderQualityReport } from '../demo/quality-report.mjs';
import { generateReport } from '../demo/render-report.mjs';

const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const completed = read('contracts/examples/v0.2/quality-evaluation/completed.json');
const error = read('contracts/examples/v0.2/quality-evaluation/error.json');
const exportOf = evaluation => ({ formatVersion: 1, provenance: { classification: 'UNKNOWN' }, evaluationId: evaluation.evaluationId, evaluation });

test('bilingual quality projection preserves fixed facts and escapes dynamic text', () => {
  const report = exportOf(structuredClone(completed));
  report.evaluation.inputSnapshot.evidenceRefs = [{
    evidenceId: 'evidence-1', attemptId: 'attempt-1', runId: 'run-1', type: 'LOG',
    digest: `sha256:${'a'.repeat(64)}`, sizeBytes: 5
  }];
  report.evaluation.qualityResult.ruleResults[0].explanation.code = '<img src=x onerror=alert(1)> &';
  const zh = renderQualityReport(report, { language: 'zh' });
  const en = renderQualityReport(report, { language: 'en' });
  for (const html of [zh, en]) {
    for (const fact of ['evaluation-1', 'release-1', 'quality-input-1', 'REQUIRED_ISSUE_VERIFIED', 'evidence.crashes[]']) assert.ok(html.includes(fact));
    assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt; &amp;/);
    assert.doesNotMatch(html, /<img|<script|https?:\/\/|<a\s/i);
    assert.match(html, /Content-Security-Policy/);
    assert.match(html, /UNKNOWN/);
    assert.match(html, /evidence-1; LOG; run-1; attempt-1/);
    assert.doesNotMatch(html, /grantId|\/payload|<a\s/i);
  }
  assert.match(zh, /质量判定/);
  assert.match(en, /Quality Evaluation/);
});

test('pre-pin ERROR stays NOT_EVALUATED without a fabricated result or source', () => {
  const html = renderQualityReport(exportOf(error), { language: 'en' });
  assert.match(html, /NOT_EVALUATED/);
  assert.match(html, /REQUIRED_EVIDENCE_MISSING/);
  assert.match(html, /not pinned/i);
  assert.doesNotMatch(html, /resultDigest/);
});

test('quality scope reads only its export and creates a new output', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'vsrqg-quality-report-'));
  const input = join(dir, 'quality-report-export.json');
  const output = join(dir, 'report.en.html');
  const bytes = JSON.stringify(exportOf(completed));
  await writeFile(input, bytes);
  await writeFile(join(dir, 'summary.json'), '{invalid');
  await generateReport({ runDirectory: dir, outputFile: output, scope: 'quality', language: 'en' });
  assert.match(await readFile(output, 'utf8'), /Quality Evaluation/);
  assert.equal(await readFile(input, 'utf8'), bytes);
  await assert.rejects(generateReport({ runDirectory: dir, outputFile: output, scope: 'quality', language: 'en' }), /REPORT_OUTPUT_FAILED/);
  await assert.rejects(generateReport({ runDirectory: dir, outputFile: input, scope: 'quality', language: 'en' }), /REPORT_OUTPUT_FAILED/);
  const badDir = await mkdtemp(join(tmpdir(), 'vsrqg-quality-report-'));
  try { await symlink(input, join(badDir, 'quality-report-export.json'), 'file'); }
  catch (failure) { if (failure.code === 'EPERM') { t.diagnostic('symlink creation unavailable on this Windows host'); return; } throw failure; }
  await assert.rejects(generateReport({ runDirectory: badDir, outputFile: join(badDir, 'out.html'), scope: 'quality', language: 'en' }), /REPORT_INPUT_INVALID/);
});
