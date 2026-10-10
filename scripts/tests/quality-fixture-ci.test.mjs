import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { produceQualityFixtureReports } from '../demo/quality-fixture-ci.mjs';

const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const completed = read('contracts/examples/v0.2/quality-evaluation/completed.json');
const error = read('contracts/examples/v0.2/quality-evaluation/error.json');
const commit = 'a'.repeat(40);
const input = completed.inputSnapshot;
const traceability = {
  snapshot: { snapshotId: input.traceabilitySnapshot.id, releaseId: completed.releaseId,
    version: input.traceabilitySnapshot.version, issueSnapshotId: input.issueSnapshot.id,
    manifestRevisionId: input.manifest.id, manifestDigest: input.manifest.digest,
    policyVersion: 'v1', validatorVersion: 'v1', inputDigest: input.inputDigest,
    contentDigest: input.traceabilitySnapshot.digest, createdAt: completed.createdAt },
  issues: [], edges: []
};
const selection = input.selections[0];
const testRun = {
  runId: selection.runId, releaseId: completed.releaseId, manifestId: input.manifest.id,
  manifestDigest: input.manifest.digest, inputDigest: input.inputDigest,
  plan: { planId: 'smoke', version: 1 },
  environment: { bootSessionId: 'boot-1', buildId: 'build-1', buildFingerprint: 'device/build' },
  status: 'COMPLETED', attempts: [{ attemptId: selection.selectedAttempt.attemptId,
    status: 'COMPLETED', evidenceRequirements: [], result: {
      caseId: selection.case.caseId, caseVersion: selection.case.version,
      testRunId: selection.runId, releaseId: completed.releaseId,
      attemptId: selection.selectedAttempt.attemptId, attemptNo: selection.selectedAttempt.attemptNo,
      resultDigest: selection.resultDigest, status: input.facts.testResults[0].status,
      origin: 'AGENT', agentId: 'agent-1', deviceId: 'device-1',
      startedAt: completed.createdAt, finishedAt: completed.createdAt,
      durationMs: 0, evidenceIds: [], evidenceRequirements: []
    }
  }]
};
const bundle = { classification: 'SYNTHETIC_FIXTURE', fixtureId: `quality-decision-${commit}`,
  responses: { completed, error, traceability, testRun } };

test('controlled source GET responses are bound to the fixed evaluation and conflicts fail', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'vsrqg-quality-sources-'));
  await produceQualityFixtureReports(bundle, dir, commit);
  const report = JSON.parse(await readFile(join(dir, 'completed', 'quality-report-export.json'), 'utf8'));
  assert.deepEqual(report.traceabilityResponse, traceability);
  assert.deepEqual(report.testRunResponse, testRun);
  const conflict = structuredClone(bundle);
  conflict.responses.traceability.snapshot.releaseId = 'other-release';
  await assert.rejects(produceQualityFixtureReports(conflict, await mkdtemp(join(tmpdir(), 'vsrqg-quality-conflict-')), commit), /QUALITY_REPORT_INPUT_MISMATCH/);
  const incomplete = structuredClone(bundle);
  delete incomplete.responses.testRun;
  await assert.rejects(produceQualityFixtureReports(incomplete, await mkdtemp(join(tmpdir(), 'vsrqg-quality-incomplete-')), commit), /QUALITY_FIXTURE_SOURCES_INCOMPLETE/);
});

test('controlled HTTP response bundle produces bilingual fixed reports', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'vsrqg-quality-fixture-'));
  await produceQualityFixtureReports(bundle, dir, commit);
  for (const outcome of ['completed', 'error']) {
    const report = JSON.parse(await readFile(join(dir, outcome, 'quality-report-export.json'), 'utf8'));
    assert.equal(report.evaluationId, bundle.responses[outcome].evaluationId);
    assert.deepEqual(report.provenance, { classification: 'SYNTHETIC_FIXTURE', fixtureId: bundle.fixtureId });
    for (const language of ['zh', 'en']) {
      const html = await readFile(join(dir, outcome, `report.${language}.html`), 'utf8');
      assert.ok(html.includes(report.evaluationId));
      assert.ok(html.includes(outcome === 'completed' ? 'PASS' : 'NOT_EVALUATED'));
    }
  }
  await assert.rejects(produceQualityFixtureReports(bundle, dir, 'b'.repeat(40)), /QUALITY_FIXTURE_IDENTITY_INVALID/);
});
