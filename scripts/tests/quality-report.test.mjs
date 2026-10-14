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
  report.evaluation.qualityResult.ruleResults[0].evidenceRefs = ['evidence-1'];
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

const assertRow = (html, field, value) => assert.ok(
  html.includes(`<th scope="row">${field}</th><td>${value}</td>`), `missing ${field}=${value}`
);

test('bilingual report exposes result identity and the complete pinned selection', () => {
  const report = exportOf(structuredClone(completed));
  report.evaluation.qualityResult.resultId = 'result-locator-17';
  for (const language of ['zh', 'en']) {
    const html = renderQualityReport(report, { language });
    assertRow(html, 'resultId', 'result-locator-17');
    assertRow(html, 'ruleSetId', 'minimal-smoke');
    assertRow(html, 'ruleSetVersion', '1');
    assertRow(html, 'selections[0].case.caseId', 'smoke');
    assertRow(html, 'selections[0].case.version', '1');
    assertRow(html, 'selections[0].selectedAttempt.attemptNo', '1');
    assertRow(html, 'selections[0].otherAttempts', '[]');
  }
});

test('bilingual rule diagnostics retain values, numeric types, null and escaping', () => {
  const report = exportOf(structuredClone(completed));
  const rule = report.evaluation.qualityResult.ruleResults[0];
  rule.matchedFacts = [
    { path: 'issues[].verified', value: false },
    { path: 'diagnostic.null', value: null },
    { path: 'diagnostic.large', value: ['INTEGER', '9007199254740993'] },
    { path: 'diagnostic.decimal', value: ['DECIMAL', '1.250000000000000001'] },
    { path: 'diagnostic.text', value: '<script>&"\'' }
  ];
  rule.explanation.parameters = { verified: false, absent: null,
    count: ['INTEGER', '9007199254740993'], detail: '<img>&' };
  const before = JSON.stringify(report);
  for (const language of ['zh', 'en']) {
    const html = renderQualityReport(report, { language });
    assertRow(html, 'ruleResults[0].matchedFacts[0].path', 'issues[].verified');
    assertRow(html, 'ruleResults[0].matchedFacts[0].value', 'false');
    assertRow(html, 'ruleResults[0].matchedFacts[1].value', 'null');
    assertRow(html, 'ruleResults[0].matchedFacts[2].value', '[&quot;INTEGER&quot;,&quot;9007199254740993&quot;]');
    assertRow(html, 'ruleResults[0].matchedFacts[3].value', '[&quot;DECIMAL&quot;,&quot;1.250000000000000001&quot;]');
    assertRow(html, 'ruleResults[0].explanation.parameters.verified', 'false');
    assertRow(html, 'ruleResults[0].explanation.parameters.absent', 'null');
    assertRow(html, 'ruleResults[0].explanation.parameters.count', '[&quot;INTEGER&quot;,&quot;9007199254740993&quot;]');
    assert.match(html, /&lt;script&gt;&amp;/);
    assert.match(html, /&lt;img&gt;&amp;/);
    assert.doesNotMatch(html, /<script|<img/);
  }
  assert.equal(JSON.stringify(report), before);
});

test('pre-pin ERROR retains Rule Set identity and original diagnostic parameters', () => {
  const failed = structuredClone(error);
  failed.ruleSet = { ruleSetId: 'failed-rule-set', version: 7 };
  failed.error.parameters = { missing: 'SCREENSHOT', count: ['INTEGER', '1'], optional: null };
  for (const language of ['zh', 'en']) {
    const html = renderQualityReport(exportOf(failed), { language });
    assertRow(html, 'ruleSetId', 'failed-rule-set');
    assertRow(html, 'ruleSetVersion', '7');
    assertRow(html, 'error.parameters.missing', '&quot;SCREENSHOT&quot;');
    assertRow(html, 'error.parameters.count', '[&quot;INTEGER&quot;,&quot;1&quot;]');
    assertRow(html, 'error.parameters.optional', 'null');
    assert.match(html, /NOT_EVALUATED/);
    assert.doesNotMatch(html, /resultId|resultDigest/);
  }
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
