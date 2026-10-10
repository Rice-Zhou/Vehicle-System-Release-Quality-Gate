import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { validateQualityReportExport, MAX_QUALITY_REPORT_BYTES } from '../demo/quality-report-export.mjs';

const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const evaluation = read('contracts/examples/v0.2/quality-evaluation/completed.json');
const error = read('contracts/examples/v0.2/quality-evaluation/error.json');
const digest = `sha256:${'a'.repeat(64)}`;

function sample(value = evaluation) {
  const input = value.inputSnapshot;
  const report = { formatVersion: 1, provenance: { classification: 'UNKNOWN' }, evaluationId: value.evaluationId, evaluation: structuredClone(value) };
  if (!input) return report;
  report.traceabilityResponse = {
    snapshot: {
      snapshotId: input.traceabilitySnapshot.id, releaseId: value.releaseId,
      version: input.traceabilitySnapshot.version, issueSnapshotId: input.issueSnapshot.id,
      manifestRevisionId: input.manifest.id, manifestDigest: input.manifest.digest,
      policyVersion: 'v1', validatorVersion: 'v1', inputDigest: digest,
      contentDigest: input.traceabilitySnapshot.digest, createdAt: value.createdAt
    }, issues: [], edges: []
  };
  const selected = input.selections[0];
  report.testRunResponse = {
    runId: selected.runId, releaseId: value.releaseId, manifestId: input.manifest.id,
    manifestDigest: input.manifest.digest, inputDigest: digest,
    plan: { planId: 'smoke', version: 1 },
    environment: { bootSessionId: 'test-boot-01', buildId: 'synthetic-build', buildFingerprint: 'synthetic/device/demo:1/test' },
    status: 'COMPLETED', attempts: [{ attemptId: selected.selectedAttempt.attemptId,
      status: 'COMPLETED', evidenceRequirements: [], result: {
        attemptId: selected.selectedAttempt.attemptId, testRunId: selected.runId,
        releaseId: value.releaseId, origin: 'AGENT', status: 'PASS', resultDigest: selected.resultDigest,
        caseId: selected.case.caseId, caseVersion: selected.case.version,
        attemptNo: selected.selectedAttempt.attemptNo, agentId: 'agent-1', deviceId: 'device-1',
        startedAt: value.createdAt, finishedAt: value.createdAt, durationMs: 0,
        evidenceIds: [], evidenceRequirements: []
      }
    }]
  };
  return report;
}

const validate = value => validateQualityReportExport(Buffer.from(JSON.stringify(value)));

test('accepts fixed completed and pre/post-fix ERROR without inventing a result', () => {
  assert.deepEqual(validate(sample()).evaluation.qualityResult.action, 'PASS');
  for (const action of ['WARNING', 'BLOCK']) {
    const report = sample();
    report.evaluation.qualityResult.action = action;
    report.evaluation.qualityResult.ruleResults[0].status = action;
    assert.equal(validate(report).evaluation.qualityResult.action, action);
  }
  assert.equal(validate(sample(error)).evaluation.releaseQualityState, 'NOT_EVALUATED');
  const afterFix = sample({ ...error, inputSnapshot: evaluation.inputSnapshot });
  assert.equal(validate(afterFix).evaluation.state, 'ERROR');
});

test('rejects unknown fields, credentials, mismatched selection and source identities', () => {
  const cases = [
    report => { report.token = 'secret'; },
    report => { report.provenance.fixtureId = 'unproved'; },
    report => { report.evaluationId = 'different'; },
    report => { report.traceabilityResponse.snapshot.releaseId = 'other'; },
    report => { report.traceabilityResponse.snapshot.issueSnapshotId = 'other'; },
    report => { report.traceabilityResponse.snapshot.manifestDigest = `sha256:${'b'.repeat(64)}`; },
    report => { report.traceabilityResponse.snapshot.manifestRevisionId = 'other'; },
    report => { report.testRunResponse.runId = 'other'; },
    report => { report.testRunResponse.releaseId = 'other'; },
    report => { report.testRunResponse.attempts[0].result.resultDigest = `sha256:${'b'.repeat(64)}`; },
    report => { report.testRunResponse.attempts[0].result.attemptId = 'other'; },
    report => { report.testRunResponse.attempts[0].result.caseVersion = 2; },
    report => { report.testRunResponse.attempts[0].result.status = 'FAIL'; },
    report => { report.evaluation.inputSnapshot.releaseId = 'other'; },
    report => { report.evaluation.qualityResult.action = 'ERROR'; }
  ];
  for (const mutate of cases) {
    const report = sample(); mutate(report);
    assert.throws(() => validate(report), /QUALITY_REPORT_INPUT_INVALID|QUALITY_REPORT_INPUT_MISMATCH/);
  }
  const preFix = sample(error);
  preFix.testRunResponse = sample().testRunResponse;
  assert.throws(() => validate(preFix), /QUALITY_REPORT_INPUT_INVALID/);
  const pending = sample(error);
  pending.evaluation = {
    evaluationId: error.evaluationId, project: error.project, releaseId: error.releaseId,
    ruleSet: error.ruleSet, createdAt: error.createdAt, state: 'QUEUED', jobId: 'job-1'
  };
  assert.throws(() => validate(pending), /QUALITY_REPORT_INPUT_INVALID/);
});

test('preserves explicit provenance and exact byte boundary', () => {
  const proven = sample();
  proven.provenance = { classification: 'SYNTHETIC_FIXTURE', fixtureId: 'controlled-ci-1' };
  assert.equal(validate(proven).provenance.classification, 'SYNTHETIC_FIXTURE');
  const raw = JSON.stringify(sample());
  const exact = Buffer.from(raw + ' '.repeat(MAX_QUALITY_REPORT_BYTES - Buffer.byteLength(raw)));
  assert.equal(validateQualityReportExport(exact).evaluationId, evaluation.evaluationId);
  assert.throws(() => validateQualityReportExport(Buffer.concat([exact, Buffer.from(' ')])), /QUALITY_REPORT_INPUT_TOO_LARGE/);
  assert.throws(() => validateQualityReportExport(Buffer.from([0xff])), /QUALITY_REPORT_INPUT_INVALID/);
});
