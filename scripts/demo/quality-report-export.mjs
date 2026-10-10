import fs from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

export const MAX_QUALITY_REPORT_BYTES = 16 * 1024 * 1024;
const read = path => JSON.parse(fs.readFileSync(new URL(path, import.meta.url), 'utf8'));
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
const apiUrl = 'https://vsrqg.example/contracts/openapi/v0.2/openapi.json';
const agentUrl = 'https://vsrqg.example/schemas/v0.2/agent-execution-context.schema.json';
const agent = read('../../schemas/v0.2/agent-execution-context.schema.json');
delete agent.$id;
ajv.addSchema(agent, agentUrl);
ajv.addSchema(read('../../schemas/v0.2/quality-rule.schema.json'));
ajv.addSchema(read('../../schemas/v0.2/quality-evaluation.schema.json'));
ajv.addSchema(read('../../contracts/openapi/v0.2/openapi.json'), apiUrl);
const validateSchema = ajv.compile(read('../../schemas/v0.2/quality-report-export.schema.json'));

function same(actual, expected) {
  if (actual !== expected) throw new Error('QUALITY_REPORT_INPUT_MISMATCH');
}

export function validateQualityReportExport(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length > MAX_QUALITY_REPORT_BYTES) {
    throw new Error('QUALITY_REPORT_INPUT_TOO_LARGE');
  }
  let report;
  try {
    report = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    throw new Error('QUALITY_REPORT_INPUT_INVALID');
  }
  if (!validateSchema(report)) throw new Error('QUALITY_REPORT_INPUT_INVALID');
  const { evaluation, evaluationId, traceabilityResponse, testRunResponse } = report;
  same(evaluationId, evaluation.evaluationId);
  const input = evaluation.inputSnapshot;
  if (!input) return report;
  same(input.releaseId, evaluation.releaseId);
  same(input.project, evaluation.project);
  same(input.ruleSet.ruleSetId, evaluation.ruleSet.ruleSetId);
  same(input.ruleSet.version, evaluation.ruleSet.version);
  if (traceabilityResponse) {
    const source = traceabilityResponse.snapshot;
    same(source.releaseId, evaluation.releaseId);
    same(source.snapshotId, input.traceabilitySnapshot.id);
    same(source.version, input.traceabilitySnapshot.version);
    same(source.contentDigest, input.traceabilitySnapshot.digest);
    same(source.issueSnapshotId, input.issueSnapshot.id);
    same(source.manifestRevisionId, input.manifest.id);
    same(source.manifestDigest, input.manifest.digest);
  }
  if (testRunResponse) {
    const selected = input.selections[0];
    same(testRunResponse.releaseId, evaluation.releaseId);
    same(testRunResponse.runId, selected.runId);
    same(testRunResponse.manifestId, input.manifest.id);
    same(testRunResponse.manifestDigest, input.manifest.digest);
    const matches = testRunResponse.attempts.filter(item => item.attemptId === selected.selectedAttempt.attemptId);
    same(matches.length, 1);
    const result = matches[0].result;
    if (!result) throw new Error('QUALITY_REPORT_INPUT_MISMATCH');
    same(result.attemptId, matches[0].attemptId);
    same(result.testRunId, selected.runId);
    same(result.releaseId, evaluation.releaseId);
    same(result.attemptNo, selected.selectedAttempt.attemptNo);
    same(result.caseId, selected.case.caseId);
    same(result.caseVersion, selected.case.version);
    same(result.resultDigest, selected.resultDigest);
    const fact = input.facts.testResults[0];
    same(fact.attemptId, result.attemptId);
    same(fact.runId, result.testRunId);
    same(fact.caseId, result.caseId);
    same(fact.caseVersion, result.caseVersion);
    same(fact.attemptNo, result.attemptNo);
    same(fact.resultDigest, result.resultDigest);
    same(fact.status, result.status);
  }
  return report;
}
