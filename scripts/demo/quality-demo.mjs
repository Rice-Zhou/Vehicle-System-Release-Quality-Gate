import fs from 'node:fs';
import { lstat, mkdir, open, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import Ajv2020 from 'ajv/dist/2020.js';
import { parseDocument } from 'yaml';
import { buildQualityReportExport, findQualityEvaluation } from './export-quality-report.mjs';
import { MAX_QUALITY_REPORT_BYTES, validateQualityReportExport, verifyEvidenceLocator } from './quality-report-export.mjs';
import { generateReport } from './render-report.mjs';

const scenarios = { smokePass: 'PASS', smokeFail: 'BLOCK', requiredIssueBlock: 'BLOCK',
  missingEvidence: 'QUALITY_REQUIRED_EVIDENCE_MISSING' };
const api = JSON.parse(fs.readFileSync(new URL('../../contracts/openapi/v0.2/openapi.json', import.meta.url)));
const validateRun = new Ajv2020({ strict: false }).compile({
  ...api.components.schemas.CreateTestRunRequest, components: api.components
});
const fail = code => { throw new Error(code); };
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));

export function validateQualityDemoConfig(config) {
  const invalid = () => fail('QUALITY_DEMO_CONFIG_INVALID');
  if (!exact(config, ['origin', 'tokenEnvironment', 'outputRoot', 'pollIntervalMs', 'timeoutSeconds',
    'verifyEvidenceMetadata', 'scenarios'])) invalid();
  let origin;
  try { origin = new URL(config.origin); } catch { invalid(); }
  if (!['http:', 'https:'].includes(origin.protocol) || !['127.0.0.1', '[::1]'].includes(origin.hostname) ||
      origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) invalid();
  if (typeof config.tokenEnvironment !== 'string' || !/^[A-Z][A-Z0-9_]{0,127}$/.test(config.tokenEnvironment) ||
      typeof config.outputRoot !== 'string' || !isAbsolute(config.outputRoot) ||
      !Number.isInteger(config.pollIntervalMs) || config.pollIntervalMs < 10 || config.pollIntervalMs > 5000 ||
      !Number.isInteger(config.timeoutSeconds) || config.timeoutSeconds < 1 || config.timeoutSeconds > 600 ||
      typeof config.verifyEvidenceMetadata !== 'boolean' || !exact(config.scenarios, Object.keys(scenarios))) invalid();
  for (const value of Object.values(config.scenarios)) {
    if (!exact(value, ['releaseId', 'ruleSet', 'traceabilitySnapshotId', 'testPlan', 'deviceSelector']) ||
        !id(value.releaseId) || !id(value.traceabilitySnapshotId) ||
        !exact(value.ruleSet, ['ruleSetId', 'version']) || !id(value.ruleSet.ruleSetId) ||
        !Number.isSafeInteger(value.ruleSet.version) || value.ruleSet.version < 1 ||
        !validateRun({ releaseId: value.releaseId, testPlan: value.testPlan, deviceSelector: value.deviceSelector })) invalid();
  }
  if (new Set(Object.values(config.scenarios).map(value => value.releaseId)).size !== 1) invalid();
  return config;
}

async function requireRealParents(path) {
  for (let parent = path; ; parent = dirname(parent)) {
    const stat = await lstat(parent);
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail('QUALITY_DEMO_PATH_INVALID');
    if (parent === dirname(parent)) return;
  }
}

export async function readQualityDemoConfig(path) {
  try {
    if (!isAbsolute(path)) fail('QUALITY_DEMO_CONFIG_INVALID');
    await requireRealParents(dirname(path));
    const stat = await lstat(path);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 65536) fail('QUALITY_DEMO_CONFIG_INVALID');
    const handle = await open(path, 'r');
    let bytes;
    try {
      bytes = Buffer.alloc(65537);
      const result = await handle.read(bytes, 0, bytes.length, 0);
      bytes = bytes.subarray(0, result.bytesRead);
      if (bytes.length > 65536) fail('QUALITY_DEMO_CONFIG_INVALID');
    } finally { await handle.close(); }
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    const value = JSON.parse(text);
    if (parseDocument(text, { uniqueKeys: true }).errors.length) fail('QUALITY_DEMO_CONFIG_INVALID');
    return validateQualityDemoConfig(value);
  } catch { fail('QUALITY_DEMO_CONFIG_INVALID'); }
}

async function requestJson(origin, token, path, { body, status = 200, key, deadline }) {
  const remaining = deadline - Date.now();
  if (remaining <= 0) fail('QUALITY_DEMO_TIMEOUT');
  let response;
  try {
    response = await fetch(new URL(path, origin), { method: body ? 'POST' : 'GET', redirect: 'manual',
      signal: AbortSignal.timeout(Math.min(remaining, 10000)),
      headers: { Authorization: 'Bearer ' + token, Accept: 'application/json',
        ...(body ? { 'Content-Type': 'application/json', 'Idempotency-Key': key } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
  } catch (error) {
    fail(error.name === 'TimeoutError' ? 'QUALITY_DEMO_TIMEOUT' : 'QUALITY_DEMO_NETWORK_FAILED');
  }
  if (response.status !== status) {
    await response.body?.cancel();
    fail('QUALITY_DEMO_HTTP_' + response.status);
  }
  const chunks = [];
  let length = 0;
  try {
    for await (const chunk of response.body) {
      length += chunk.length;
      if (length > MAX_QUALITY_REPORT_BYTES) fail('QUALITY_DEMO_RESPONSE_LIMIT');
      chunks.push(chunk);
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
  } catch (error) {
    if (error.message === 'QUALITY_DEMO_RESPONSE_LIMIT') throw error;
    fail(error.name === 'TimeoutError' ? 'QUALITY_DEMO_TIMEOUT' : 'QUALITY_DEMO_RESPONSE_INVALID');
  }
}

async function poll(read, ready, interval, deadline) {
  while (true) {
    const value = await read();
    if (ready(value)) return value;
    const remaining = deadline - Date.now();
    if (remaining <= 0) fail('QUALITY_DEMO_TIMEOUT');
    await delay(Math.min(interval, remaining));
  }
}

function verifyScenarioInput(name, evaluation) {
  if (name === 'missingEvidence') return;
  const input = evaluation.inputSnapshot;
  const expectedStatus = name === 'smokeFail' ? 'FAIL' : 'PASS';
  if (input?.facts?.testResults?.length !== 1 || input.facts.testResults[0].status !== expectedStatus) {
    fail('QUALITY_DEMO_SCENARIO_MISMATCH');
  }
  if (name === 'smokePass' && input.requiredIssueRefs.length !== 0) fail('QUALITY_DEMO_SCENARIO_MISMATCH');
  if (name === 'requiredIssueBlock' && !input.requiredIssueRefs.some(ref =>
    input.facts.issues?.some(issue => issue.source === ref.source && issue.sourceIssueId === ref.sourceIssueId &&
      issue.required === true && issue.verified === false))) fail('QUALITY_DEMO_SCENARIO_MISMATCH');
}

export async function runQualityDemo(settings, { token } = {}) {
  const config = structuredClone(validateQualityDemoConfig(settings));
  if (typeof token !== 'string' || !token.trim() || /[\r\n]/.test(token)) fail('QUALITY_DEMO_TOKEN_REQUIRED');
  const root = resolve(config.outputRoot);
  await requireRealParents(dirname(root));
  try { await mkdir(root); } catch (error) {
    fail(error.code === 'EEXIST' ? 'QUALITY_DEMO_OUTPUT_EXISTS' : 'QUALITY_DEMO_OUTPUT_FAILED');
  }
  const session = { formatVersion: 1, classification: 'UNKNOWN', status: 'RUNNING',
    startedAt: new Date().toISOString(), scenarios: {} };
  const sessionPath = resolve(root, 'session.json');
  const save = () => writeFile(sessionPath, JSON.stringify(session, null, 2), { mode: 0o600 });
  const seenRuns = new Set(), seenEvaluations = new Set();
  try {
    await save();
    for (const [name, expected] of Object.entries(scenarios)) {
      const selected = config.scenarios[name];
      const directory = resolve(root, name);
      await mkdir(directory);
      const entry = session.scenarios[name] = { runKey: randomUUID(), evaluationKey: randomUUID(), status: 'STARTING' };
      await save();
      const deadline = Date.now() + config.timeoutSeconds * 1000;
      const request = (path, options = {}) => requestJson(config.origin, token, path, { ...options, deadline });
      const created = await request('/api/v1/test-runs', { status: 201, key: entry.runKey, body: {
        releaseId: selected.releaseId, testPlan: selected.testPlan, deviceSelector: selected.deviceSelector
      } });
      if (!id(created.testRunId) || created.releaseId !== selected.releaseId || seenRuns.has(created.testRunId)) {
        fail('QUALITY_DEMO_RUN_MISMATCH');
      }
      entry.runId = created.testRunId; entry.status = 'RUN_CREATED'; seenRuns.add(entry.runId); await save();
      const runPath = '/api/v1/test-runs/' + encodeURIComponent(entry.runId) + '/results';
      await poll(() => request(runPath), run => {
        if (run.runId !== entry.runId || run.releaseId !== selected.releaseId) fail('QUALITY_DEMO_RUN_MISMATCH');
        if (!['CREATED', 'WAITING_FOR_AGENT', 'RUNNING', 'COMPLETED', 'ERROR', 'TIMEOUT', 'CANCELLED'].includes(run.status)) fail('QUALITY_DEMO_RESPONSE_INVALID');
        return ['COMPLETED', 'ERROR', 'TIMEOUT', 'CANCELLED'].includes(run.status);
      }, config.pollIntervalMs, deadline);
      const prefix = '/api/v1/releases/' + encodeURIComponent(selected.releaseId);
      const queued = await request(prefix + '/quality-evaluations', { status: 202, key: entry.evaluationKey,
        body: { ruleSet: selected.ruleSet, testRunIds: [entry.runId], traceabilitySnapshotId: selected.traceabilitySnapshotId } });
      if (!id(queued.evaluationId) || seenEvaluations.has(queued.evaluationId)) fail('QUALITY_DEMO_EVALUATION_MISMATCH');
      entry.evaluationId = queued.evaluationId; entry.status = 'EVALUATION_QUEUED';
      seenEvaluations.add(entry.evaluationId); await save();
      const fetchPage = cursor => request(prefix + '/quality-results' + (cursor === null ? '' : '?cursor=' + encodeURIComponent(cursor)));
      const identity = { releaseId: selected.releaseId, evaluationId: entry.evaluationId, fetchPage };
      await poll(() => findQualityEvaluation(identity), value => {
        if (value && !['QUEUED', 'RUNNING', 'COMPLETED', 'ERROR'].includes(value.state)) fail('QUALITY_DEMO_RESPONSE_INVALID');
        return value && ['COMPLETED', 'ERROR'].includes(value.state);
      }, config.pollIntervalMs, deadline);
      const bytes = await buildQualityReportExport({ ...identity,
        fetchTraceability: (release, snapshot) => request(prefix + '/traceability?snapshotId=' + encodeURIComponent(snapshot)),
        fetchTestRun: run => request('/api/v1/test-runs/' + encodeURIComponent(run) + '/results')
      });
      const report = validateQualityReportExport(bytes), value = report.evaluation;
      if (value.ruleSet.ruleSetId !== selected.ruleSet.ruleSetId || value.ruleSet.version !== selected.ruleSet.version ||
          (name === 'missingEvidence' ? value.state !== 'ERROR' || value.error.code !== expected :
            value.state !== 'COMPLETED' || value.qualityResult.action !== expected)) fail('QUALITY_DEMO_DECISION_MISMATCH');
      if (value.inputSnapshot && (value.inputSnapshot.selections[0].runId !== entry.runId ||
          value.inputSnapshot.traceabilitySnapshot.id !== selected.traceabilitySnapshotId)) fail('QUALITY_DEMO_EVALUATION_MISMATCH');
      verifyScenarioInput(name, value);
      await writeFile(resolve(directory, 'quality-report-export.json'), bytes, { flag: 'wx' });
      entry.evidenceAccess = !value.inputSnapshot ? 'NOT_PINNED' :
        config.verifyEvidenceMetadata ? 'PENDING' : 'NOT_REQUESTED';
      if (config.verifyEvidenceMetadata && value.inputSnapshot) {
        const metadata = [];
        for (const ref of value.inputSnapshot.evidenceRefs) metadata.push(await request('/api/v1/evidence/' + encodeURIComponent(ref.evidenceId)));
        verifyEvidenceLocator(value, metadata);
        await writeFile(resolve(directory, 'evidence-access.json'), JSON.stringify({
          evaluationId: entry.evaluationId, checkedAt: new Date().toISOString(), payloadDownloaded: false,
          items: metadata.map(item => ({ evidenceId: item.evidenceId, integrity: item.integrity }))
        }), { flag: 'wx' });
        entry.evidenceAccess = 'VERIFIED';
      }
      for (const language of ['zh', 'en']) await generateReport({
        runDirectory: directory, outputFile: resolve(directory, 'report.' + language + '.html'), scope: 'quality', language
      });
      entry.status = 'EXPORTED'; await save();
    }
    session.status = 'SUCCEEDED'; session.completedAt = new Date().toISOString(); await save();
    return session;
  } catch (error) {
    const code = /^QUALITY_[A-Z0-9_]+$/.test(error.message) ? error.message : 'QUALITY_DEMO_IO_FAILED';
    session.status = 'FAILED'; session.errorCode = code; await save();
    fail(code);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 3) fail('QUALITY_DEMO_ARGUMENT_INVALID');
    const config = await readQualityDemoConfig(resolve(process.argv[2]));
    await runQualityDemo(config, { token: process.env[config.tokenEnvironment] });
    process.stdout.write('QUALITY_DEMO_REPORTS_READY\n');
  } catch (error) {
    process.stderr.write((/^QUALITY_[A-Z0-9_]+$/.test(error.message) ? error.message : 'QUALITY_DEMO_FAILED') + '\n');
    process.exitCode = 1;
  }
}
