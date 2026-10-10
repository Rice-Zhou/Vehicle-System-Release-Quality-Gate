import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { buildQualityReportExport } from '../demo/export-quality-report.mjs';
import { validateQualityReportExport } from '../demo/quality-report-export.mjs';

const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const completed = read('contracts/examples/v0.2/quality-evaluation/completed.json');
const error = read('contracts/examples/v0.2/quality-evaluation/error.json');

function sources(evaluation) {
  const calls = [];
  const pages = [
    { items: [{ ...completed, evaluationId: 'other' }], nextCursor: 'page-2' },
    { items: [evaluation], nextCursor: null }
  ];
  return {
    calls,
    fetchPage: async cursor => { calls.push(['page', cursor]); return pages[cursor ? 1 : 0]; },
    fetchTraceability: async () => { calls.push(['trace']); return null; },
    fetchTestRun: async () => { calls.push(['run']); return null; }
  };
}

test('selects only the exact Evaluation across all history pages', async () => {
  const source = sources(completed);
  const bytes = await buildQualityReportExport({
    releaseId: completed.releaseId, evaluationId: completed.evaluationId, ...source
  });
  const report = validateQualityReportExport(bytes);
  assert.equal(report.evaluation.qualityResult.action, 'PASS');
  assert.equal(report.provenance.classification, 'UNKNOWN');
  assert.deepEqual(source.calls, [['page', null], ['page', 'page-2'], ['trace'], ['run']]);
});

test('pre-pin ERROR keeps the original failure and never queries absent sources', async () => {
  const source = sources(error);
  const report = validateQualityReportExport(await buildQualityReportExport({
    releaseId: error.releaseId, evaluationId: error.evaluationId, ...source
  }));
  assert.equal(report.evaluation.releaseQualityState, 'NOT_EVALUATED');
  assert.equal(report.evaluation.error.code, 'REQUIRED_EVIDENCE_MISSING');
  assert.equal(report.evaluation.qualityResult, undefined);
  assert.deepEqual(source.calls, [['page', null], ['page', 'page-2']]);
});

test('rejects missing, duplicate, mixed Release and looping history', async () => {
  const options = { releaseId: completed.releaseId, evaluationId: completed.evaluationId };
  await assert.rejects(buildQualityReportExport({ ...options, fetchPage: async () => ({ items: [], nextCursor: null }) }), /QUALITY_REPORT_EVALUATION_NOT_FOUND/);
  await assert.rejects(buildQualityReportExport({ ...options, fetchPage: async () => ({ items: [completed, completed], nextCursor: null }) }), /QUALITY_REPORT_EVALUATION_DUPLICATE/);
  await assert.rejects(buildQualityReportExport({ ...options, fetchPage: async () => ({ items: [{ ...completed, releaseId: 'other' }], nextCursor: null }) }), /QUALITY_REPORT_INPUT_MISMATCH/);
  await assert.rejects(buildQualityReportExport({ ...options, fetchPage: async () => ({ items: [{ ...completed, evaluationId: 'other', releaseId: 'other' }, completed], nextCursor: null }) }), /QUALITY_REPORT_INPUT_MISMATCH/);
  await assert.rejects(buildQualityReportExport({ ...options, fetchPage: async () => ({ items: [], nextCursor: 'same' }) }), /QUALITY_REPORT_HISTORY_INVALID/);
});
