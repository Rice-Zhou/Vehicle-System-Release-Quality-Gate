import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { runQualityDemo, readQualityDemoConfig } from '../demo/quality-demo.mjs';

const completed = JSON.parse(await readFile('contracts/examples/v0.2/quality-evaluation/completed.json'));
const missing = JSON.parse(await readFile('contracts/examples/v0.2/quality-evaluation/error.json'));
const names = ['smokePass', 'smokeFail', 'requiredIssueBlock', 'missingEvidence'];
const token = 'PRIVATE_TOKEN_MUST_NOT_APPEAR';

function evaluation(name, runId, id) {
  if (name === 'missingEvidence') return { ...structuredClone(missing), evaluationId: id,
    error: { code: 'QUALITY_REQUIRED_EVIDENCE_MISSING', parameters: {} } };
  const value = structuredClone(completed);
  value.evaluationId = id;
  value.qualityResult.action = name === 'smokePass' ? 'PASS' : 'BLOCK';
  const input = value.inputSnapshot;
  input.facts.testResults[0].status = name === 'smokeFail' ? 'FAIL' : 'PASS';
  if (name === 'requiredIssueBlock') {
    const issue = input.facts.issues[0];
    issue.required = true;
    issue.verified = false;
    input.requiredIssueRefs = [{ source: issue.source, sourceIssueId: issue.sourceIssueId }];
  }
  input.selections[0].runId = runId;
  input.facts.testResults[0].runId = runId;
  const attemptId = input.selections[0].selectedAttempt.attemptId;
  input.evidenceRefs = [{ evidenceId: 'evidence-' + runId, runId, attemptId,
    type: 'LOG', digest: 'sha256:' + 'd'.repeat(64), sizeBytes: 5 }];
  value.qualityResult.ruleResults.forEach(rule => {
    rule.status = value.qualityResult.action;
    rule.evidenceRefs = ['ERROR', 'NOT_APPLICABLE'].includes(rule.status) ? [] : [input.evidenceRefs[0].evidenceId];
  });
  return value;
}

function sourceResponses(value) {
  const input = value.inputSnapshot;
  if (!input) return {};
  const selected = input.selections[0], fact = input.facts.testResults[0];
  return {
    trace: { snapshot: { snapshotId: input.traceabilitySnapshot.id, releaseId: value.releaseId,
      version: input.traceabilitySnapshot.version, issueSnapshotId: input.issueSnapshot.id,
      manifestRevisionId: input.manifest.id, manifestDigest: input.manifest.digest,
      policyVersion: 'v1', validatorVersion: 'v1', inputDigest: input.inputDigest,
      contentDigest: input.traceabilitySnapshot.digest, createdAt: value.createdAt }, issues: [], edges: [] },
    run: { runId: selected.runId, releaseId: value.releaseId, manifestId: input.manifest.id,
      manifestDigest: input.manifest.digest, inputDigest: input.inputDigest,
      plan: { planId: 'smoke', version: 1 },
      environment: { bootSessionId: 'boot', buildId: 'build', buildFingerprint: 'device/build' },
      status: 'COMPLETED', attempts: [{ attemptId: selected.selectedAttempt.attemptId, status: 'COMPLETED',
        evidenceRequirements: [], result: { caseId: selected.case.caseId, caseVersion: selected.case.version,
          testRunId: selected.runId, releaseId: value.releaseId, attemptId: selected.selectedAttempt.attemptId,
          attemptNo: selected.selectedAttempt.attemptNo, resultDigest: selected.resultDigest, status: fact.status,
          origin: 'AGENT', agentId: 'agent', deviceId: 'device', startedAt: value.createdAt,
          finishedAt: value.createdAt, durationMs: 0, evidenceIds: input.evidenceRefs.map(ref => ref.evidenceId),
          evidenceRequirements: [] } }] }
  };
}

async function fixture(t, mode = '') {
  const calls = [], records = new Map(), runNames = new Map();
  let created = 0, queued = 0;
  const server = createServer(async (req, res) => {
    const body = [];
    for await (const chunk of req) body.push(chunk);
    const request = body.length ? JSON.parse(Buffer.concat(body)) : null;
    calls.push({ method: req.method, path: req.url, body: request,
      key: req.headers['idempotency-key'], authorization: req.headers.authorization });
    const send = (status, value) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(value)); };
    if (mode === 'redirect') { res.writeHead(302, { location: 'http://127.0.0.1:1/secret' }); res.end(); return; }
    if (req.method === 'POST' && req.url === '/api/v1/test-runs') {
      const name = names[created++], id = 'run-' + created;
      runNames.set(id, name);
      return send(201, { testRunId: id, releaseId: request.releaseId, state: 'WAITING_FOR_AGENT' });
    }
    if (req.method === 'POST' && req.url.endsWith('/quality-evaluations')) {
      const runId = request.testRunIds[0], name = runNames.get(runId), id = 'evaluation-' + ++queued;
      const value = evaluation(name, runId, id);
      if (mode === 'wrong-decision') value.qualityResult.action = 'BLOCK';
      if (mode === 'pass-masquerading-as-fail' && name === 'smokeFail') value.inputSnapshot.facts.testResults[0].status = 'PASS';
      if (mode === 'fail-masquerading-as-issue' && name === 'requiredIssueBlock') value.inputSnapshot.facts.testResults[0].status = 'FAIL';
      if (mode === 'empty-required-issues' && name === 'requiredIssueBlock') {
        value.inputSnapshot.requiredIssueRefs = [];
        value.inputSnapshot.facts.issues.forEach(issue => { issue.required = false; });
      }
      if (mode === 'required-issues-in-normal-smoke' && name === 'smokePass') {
        const issue = value.inputSnapshot.facts.issues[0];
        issue.required = true;
        issue.verified = true;
        value.inputSnapshot.requiredIssueRefs = [{ source: issue.source, sourceIssueId: issue.sourceIssueId }];
      }
      records.set(id, value);
      return send(202, { evaluationId: id, jobId: 'job-' + queued });
    }
    if (req.url.includes('/quality-results')) {
      const values = [...records.values()];
      if (mode === 'evaluation-timeout') return send(200, { items: values.map(x => ({
        evaluationId: x.evaluationId, releaseId: x.releaseId, state: 'RUNNING'
      })), nextCursor: null });
      // The exporter must pass an unrelated historical success and follow the exact cursor.
      if (!req.url.includes('cursor=')) return send(200, { items: [{ ...completed, evaluationId: 'historical-success' }], nextCursor: 'next page' });
      return send(200, { items: values, nextCursor: null });
    }
    if (req.url.startsWith('/api/v1/test-runs/')) {
      const id = req.url.split('/')[4];
      if (mode === 'run-timeout') return send(200, { runId: id, releaseId: completed.releaseId, status: 'RUNNING' });
      const value = [...records.values()].find(x => x.inputSnapshot?.selections[0].runId === id);
      return send(200, value ? sourceResponses(value).run : {
        runId: id, releaseId: completed.releaseId, status: 'COMPLETED'
      });
    }
    if (req.url.includes('/traceability?')) {
      return send(200, sourceResponses([...records.values()].find(x => x.inputSnapshot)).trace);
    }
    if (req.url.startsWith('/api/v1/evidence/')) {
      if (mode === 'metadata-denied') return send(403, { code: 'FORBIDDEN', detail: token });
      const id = decodeURIComponent(req.url.split('/').at(-1));
      const ref = [...records.values()].flatMap(x => x.inputSnapshot?.evidenceRefs ?? []).find(x => x.evidenceId === id);
      return send(200, { evidenceId: id, testRunId: ref.runId, attemptId: ref.attemptId,
        type: ref.type, payloadChecksum: mode === 'metadata-conflict' ? 'sha256:' + '0'.repeat(64) : ref.digest,
        sizeBytes: ref.sizeBytes, state: 'AVAILABLE', integrity: 'VERIFIED' });
    }
    send(404, {});
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const root = await mkdtemp(join(tmpdir(), 'vsrqg-quality-cli-'));
  const config = {
    origin: 'http://127.0.0.1:' + server.address().port,
    tokenEnvironment: 'VSRQG_QUALITY_DEMO_TOKEN', outputRoot: join(root, 'output'),
    pollIntervalMs: 10, timeoutSeconds: 1, verifyEvidenceMetadata: true,
    scenarios: Object.fromEntries(names.map(name => [name, {
      releaseId: completed.releaseId, ruleSet: structuredClone(completed.ruleSet),
      traceabilitySnapshotId: completed.inputSnapshot.traceabilitySnapshot.id,
      testPlan: { planId: 'single-device-smoke', version: name === 'smokeFail' ? 2 : 1 },
      deviceSelector: { vehicle: 'synthetic', requiredCapabilities: ['LOG', 'SCREENSHOT'] }
    }]))
  };
  return { config, calls, root };
}

test('controlled entry creates four new Runs and exports only returned Evaluation IDs', { timeout: 60000 }, async t => {
  const { config, calls } = await fixture(t);
  const result = await runQualityDemo(config, { token });
  assert.equal(result.status, 'SUCCEEDED');
  assert.equal(result.classification, 'UNKNOWN');
  assert.equal(new Set(calls.filter(x => x.method === 'POST').map(x => x.key)).size, 8);
  assert.equal(calls.filter(x => x.path === '/api/v1/test-runs').length, 4);
  assert.equal(calls.some(x => /publish|payload|download|:cancel/.test(x.path)), false);
  assert.ok(calls.every(x => x.authorization === 'Bearer ' + token));
  for (const [index, name] of names.entries()) {
    const entry = result.scenarios[name];
    assert.equal(entry.runId, 'run-' + (index + 1));
    assert.equal(entry.evaluationId, 'evaluation-' + (index + 1));
    const dir = join(config.outputRoot, name);
    const report = JSON.parse(await readFile(join(dir, 'quality-report-export.json')));
    assert.equal(report.evaluationId, entry.evaluationId);
    assert.equal(report.provenance.classification, 'UNKNOWN');
    for (const language of ['zh', 'en']) {
      assert.ok((await readFile(join(dir, 'report.' + language + '.html'), 'utf8')).length > 1000);
    }
    if (name === 'missingEvidence') {
      assert.equal(report.evaluation.releaseQualityState, 'NOT_EVALUATED');
      assert.equal('qualityResult' in report.evaluation, false);
      assert.equal('traceabilityResponse' in report, false);
      assert.equal('testRunResponse' in report, false);
    } else {
      const access = JSON.parse(await readFile(join(dir, 'evidence-access.json')));
      assert.equal(access.items[0].integrity, 'VERIFIED');
      assert.equal(access.payloadDownloaded, false);
      assert.equal(report.evaluation.inputSnapshot.selections[0].runId, entry.runId);
    }
    assert.equal(JSON.stringify(report).includes(token), false);
  }
  const session = await readFile(join(config.outputRoot, 'session.json'), 'utf8');
  assert.equal(session.includes(token), false);
  assert.equal(calls.filter(x => x.path.includes('/traceability?')).length, 3);
});

for (const [mode, expected] of [
  ['run-timeout', 'QUALITY_DEMO_TIMEOUT'], ['evaluation-timeout', 'QUALITY_DEMO_TIMEOUT'],
  ['wrong-decision', 'QUALITY_DEMO_DECISION_MISMATCH'], ['metadata-denied', 'QUALITY_DEMO_HTTP_403'],
  ['metadata-conflict', 'QUALITY_FIXTURE_EVIDENCE_MISMATCH'], ['redirect', 'QUALITY_DEMO_HTTP_302'],
  ['pass-masquerading-as-fail', 'QUALITY_DEMO_SCENARIO_MISMATCH'],
  ['fail-masquerading-as-issue', 'QUALITY_DEMO_SCENARIO_MISMATCH'],
  ['empty-required-issues', 'QUALITY_DEMO_SCENARIO_MISMATCH'],
  ['required-issues-in-normal-smoke', 'QUALITY_DEMO_SCENARIO_MISMATCH']
]) test('visible failure preserves progress: ' + mode, { timeout: 60000 }, async t => {
  const { config } = await fixture(t, mode);
  await assert.rejects(runQualityDemo(config, { token }), new RegExp(expected));
  const session = JSON.parse(await readFile(join(config.outputRoot, 'session.json')));
  assert.equal(session.status, 'FAILED');
  assert.equal(session.errorCode, expected);
  assert.equal(JSON.stringify(session).includes(token), false);
  if (mode === 'run-timeout') assert.equal(session.scenarios.smokePass.runId, 'run-1');
  if (mode === 'evaluation-timeout') assert.equal(session.scenarios.smokePass.evaluationId, 'evaluation-1');
});

test('invalid configuration and reused output fail before any API call', async t => {
  const { config, calls, root } = await fixture(t);
  for (const change of [
    { origin: 'https://example.com' }, { origin: 'http://user:pass@127.0.0.1:1234' },
    { origin: config.origin + '/path' }, { token: 'secret' }, { timeoutSeconds: 0 },
    { scenarios: { smokePass: config.scenarios.smokePass } }, { outputRoot: 'relative' }
  ]) await assert.rejects(runQualityDemo({ ...config, ...change }, { token }), /QUALITY_DEMO_CONFIG_INVALID/);
  await assert.rejects(runQualityDemo(config, { token: '' }), /QUALITY_DEMO_TOKEN_REQUIRED/);
  await mkdir(config.outputRoot);
  await assert.rejects(runQualityDemo(config, { token }), /QUALITY_DEMO_OUTPUT_EXISTS/);
  assert.equal(calls.length, 0);
  const configPath = join(root, 'config.json');
  await writeFile(configPath, JSON.stringify(config).replace('"origin":', '"origin":"http://127.0.0.1:1","origin":'));
  await assert.rejects(readQualityDemoConfig(configPath), /QUALITY_DEMO_CONFIG_INVALID/);
});

test('PowerShell entry handles config paths with spaces and marks unchecked metadata explicitly', { timeout: 60000 }, async t => {
  const { config, root } = await fixture(t);
  config.verifyEvidenceMetadata = false;
  config.timeoutSeconds = 5;
  const path = join(root, 'config with spaces.json');
  await writeFile(path, JSON.stringify(config));
  const result = await promisify(execFile)('pwsh', ['-NoProfile', '-File', 'scripts/demo/run-quality.ps1', '-Config', path], {
    timeout: 60000, env: { ...process.env, VSRQG_QUALITY_DEMO_TOKEN: token }
  });
  assert.match(result.stdout, /QUALITY_DEMO_REPORTS_READY/);
  assert.equal((result.stdout + result.stderr).includes(token), false);
  const session = JSON.parse(await readFile(join(config.outputRoot, 'session.json')));
  assert.equal(session.scenarios.smokePass.evidenceAccess, 'NOT_REQUESTED');
  assert.equal(session.scenarios.missingEvidence.evidenceAccess, 'NOT_PINNED');
  await assert.rejects(promisify(execFile)('pwsh', ['-NoProfile', '-File', 'scripts/demo/run-quality.ps1', '-Config', path], {
    timeout: 60000, env: { ...process.env, VSRQG_QUALITY_DEMO_TOKEN: '' }
  }), error => error.code === 1 && error.stderr.includes('QUALITY_DEMO_TOKEN_REQUIRED') && !error.stderr.includes(token));
});
