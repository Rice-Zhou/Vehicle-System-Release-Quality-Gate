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

function withEvidence() {
  const report = sample();
  report.evaluation.inputSnapshot.evidenceRefs = [{
    evidenceId: 'evidence-1', runId: 'run-1', attemptId: 'attempt-1',
    type: 'LOG', digest, sizeBytes: 5
  }];
  report.evaluation.qualityResult.ruleResults[0].evidenceRefs = ['evidence-1'];
  report.testRunResponse.attempts[0].result.evidenceIds = ['evidence-1'];
  return report;
}

test('rejects conflicting pinned Case and Evidence references with or without source responses', async t => {
  const cases = [
    ['policy Case ID', report => { report.evaluation.inputSnapshot.selectedCaseRefs[0].caseId = 'other'; }],
    ['policy Case version', report => { report.evaluation.inputSnapshot.selectedCaseRefs[0].version = 2; }],
    ['Evidence Run', report => { report.evaluation.inputSnapshot.evidenceRefs[0].runId = 'other'; }],
    ['Evidence Attempt', report => { report.evaluation.inputSnapshot.evidenceRefs[0].attemptId = 'other'; }],
    ['duplicate Evidence ID', report => { report.evaluation.inputSnapshot.evidenceRefs.push({
      ...report.evaluation.inputSnapshot.evidenceRefs[0], type: 'SCREENSHOT'
    }); }],
    ['unknown rule Evidence', report => { report.evaluation.qualityResult.ruleResults[0].evidenceRefs = ['other']; }],
    ['omitted rule Evidence', report => { report.evaluation.qualityResult.ruleResults[0].evidenceRefs = []; }]
  ];
  for (const [name, mutate] of cases) {
    for (const queried of [true, false]) {
      await t.test(`${name}; source queried=${queried}`, () => {
        const report = withEvidence();
        if (!queried) { delete report.traceabilityResponse; delete report.testRunResponse; }
        mutate(report);
        assert.throws(() => validate(report), /QUALITY_REPORT_INPUT_MISMATCH/);
      });
    }
  }
});

test('rejects pinned Evidence absent from the queried selected Result', () => {
  const report = withEvidence();
  report.testRunResponse.attempts[0].result.evidenceIds = [];
  assert.throws(() => validate(report), /QUALITY_REPORT_INPUT_MISMATCH/);
});

test('pre-pin ERROR cannot reference Evidence without pinned input', async t => {
  for (const status of ['ERROR', 'NOT_APPLICABLE']) {
    await t.test(status, () => {
      const report = sample(error);
      report.evaluation.ruleResults = [{
        ...structuredClone(evaluation.qualityResult.ruleResults[0]), status, evidenceRefs: ['nonexistent-evidence']
      }];
      assert.throws(() => validate(report), /QUALITY_REPORT_INPUT_MISMATCH/);
      report.evaluation.ruleResults[0].evidenceRefs = [];
      assert.equal(validate(report).evaluation.state, 'ERROR');
    });
  }
});

test('rule Evidence uses the pinned set for applicable outcomes and is empty otherwise', async t => {
  const report = withEvidence();
  report.evaluation.inputSnapshot.evidenceRefs.push({
    evidenceId: 'evidence-2', runId: 'run-1', attemptId: 'attempt-1', type: 'SCREENSHOT', digest, sizeBytes: 8
  });
  report.testRunResponse.attempts[0].result.evidenceIds.push('evidence-2');
  report.evaluation.qualityResult.ruleResults[0].evidenceRefs = ['evidence-2', 'evidence-1'];
  assert.equal(validate(report).evaluation.qualityResult.action, 'PASS');
  for (const status of ['ERROR', 'NOT_APPLICABLE']) {
    await t.test(`${status} cannot retain Evidence references`, () => {
      const failed = sample({ ...error, inputSnapshot: report.evaluation.inputSnapshot,
        ruleResults: [{ ...report.evaluation.qualityResult.ruleResults[0], status }] });
      failed.testRunResponse.attempts[0].result.evidenceIds = ['evidence-1', 'evidence-2'];
      assert.throws(() => validate(failed), /QUALITY_REPORT_INPUT_MISMATCH/);
      failed.evaluation.ruleResults[0].evidenceRefs = [];
      assert.equal(validate(failed).evaluation.state, 'ERROR');
    });
  }
});

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
