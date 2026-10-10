import { validateQualityReportExport } from './quality-report-export.mjs';

const fail = code => { throw new Error(code); };

export async function buildQualityReportExport({
  releaseId, evaluationId, fetchPage, fetchTraceability, fetchTestRun
}) {
  if (!releaseId || !evaluationId || typeof fetchPage !== 'function') fail('QUALITY_REPORT_ARGUMENT_INVALID');
  let cursor = null;
  let selected = null;
  const seen = new Set();
  do {
    const page = await fetchPage(cursor);
    if (!page || !Array.isArray(page.items) || !(page.nextCursor === null || typeof page.nextCursor === 'string')) {
      fail('QUALITY_REPORT_HISTORY_INVALID');
    }
    for (const item of page.items) {
      if (item?.releaseId !== releaseId) fail('QUALITY_REPORT_INPUT_MISMATCH');
      if (item?.evaluationId !== evaluationId) continue;
      if (selected) fail('QUALITY_REPORT_EVALUATION_DUPLICATE');
      selected = item;
    }
    cursor = page.nextCursor;
    if (cursor !== null) {
      if (!cursor || seen.has(cursor) || seen.size >= 1000) fail('QUALITY_REPORT_HISTORY_INVALID');
      seen.add(cursor);
    }
  } while (cursor !== null);
  if (!selected) fail('QUALITY_REPORT_EVALUATION_NOT_FOUND');
  const report = { formatVersion: 1, provenance: { classification: 'UNKNOWN' }, evaluationId, evaluation: selected };
  const input = selected.inputSnapshot;
  if (input) {
    if (typeof fetchTraceability === 'function') {
      const response = await fetchTraceability(releaseId, input.traceabilitySnapshot.id);
      if (response !== null) report.traceabilityResponse = response;
    }
    if (typeof fetchTestRun === 'function') {
      const response = await fetchTestRun(input.selections[0].runId);
      if (response !== null) report.testRunResponse = response;
    }
  }
  const bytes = Buffer.from(JSON.stringify(report));
  validateQualityReportExport(bytes);
  return bytes;
}
