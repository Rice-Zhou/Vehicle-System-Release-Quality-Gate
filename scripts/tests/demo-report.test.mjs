import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { renderDemoReport, ReportInputError } from '../demo/demo-report.mjs';
import { generateReport } from '../demo/render-report.mjs';

const TIMEOUT = 60_000;
const SAMPLE = new URL('../../demo/report/sample/', import.meta.url);
const CLI = fileURLToPath(new URL('../demo/render-report.mjs', import.meta.url));

async function readSample() {
  const read = async name => JSON.parse(await readFile(new URL(name, SAMPLE), 'utf8'));
  return { summary: await read('summary.json'), manifest: await read('manifest.json'), m2: await read('m2-summary.json') };
}

async function temporaryRun(input) {
  input ??= await readSample();
  const directory = await mkdtemp(join(tmpdir(), 'vsrqg-report-'));
  for (const [name, value] of [['summary.json', input.summary], ['manifest.json', input.manifest], ['m2-summary.json', input.m2]]) {
    if (value !== null) await writeFile(join(directory, name), JSON.stringify(value), 'utf8');
  }
  return directory;
}

function runCli(args) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', timeout: TIMEOUT });
}

test('renders same-run M2 values, escapes text, and leaves input unchanged', { timeout: TIMEOUT }, async () => {
  const input = await readSample();
  input.manifest.artifacts[0].name = '<img src=x onerror=alert(1)> & "quoted"';
  input.manifest.unknownLocator = 'SECRET_LOCATOR';
  input.m2.unknownToken = 'SECRET_TOKEN';
  const before = JSON.stringify(input);
  const html = renderDemoReport(input, { scope: 'm2', language: 'en' });
  assert.match(html, /<html lang="en">/);
  assert.ok(html.includes(input.summary.runId));
  assert.ok(html.includes(input.summary.contentDigest));
  assert.ok(html.includes(input.m2.traceabilitySnapshotIds.A));
  assert.ok(html.includes('ISSUE_COMMIT'));
  assert.ok(html.includes('TEST_RESULT_EVIDENCE_MISSING'));
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt; &amp; &quot;quoted&quot;'));
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('SECRET_LOCATOR'));
  assert.ok(!html.includes('SECRET_TOKEN'));
  assert.ok(!/<script|<[^>]+\sonerror\s*=|https?:\/\/|<link\b|<object\b/i.test(html));
  assert.equal(JSON.stringify(input), before);
});

test('renders bilingual labels while preserving technical facts', { timeout: TIMEOUT }, async () => {
  const input = await readSample();
  const zh = renderDemoReport(input, { scope: 'm2', language: 'zh' });
  const en = renderDemoReport(input, { scope: 'm2', language: 'en' });
  assert.ok(zh.includes('离线演示报告'));
  assert.ok(en.includes('Offline Demo Report'));
  for (const fact of [input.summary.runId, input.summary.releaseId, input.m2.syncRunId, 'DEMO-1', 'false']) {
    assert.ok(zh.includes(fact));
    assert.ok(en.includes(fact));
  }
});

test('M1 scope ignores M2 completely and marks it out of scope', { timeout: TIMEOUT }, async () => {
  const input = await readSample();
  input.m2 = { malformed: '<M2_SECRET>' };
  const html = renderDemoReport(input, { scope: 'm1', language: 'en' });
  assert.ok(html.includes('outside this report scope'));
  assert.ok(!html.includes('M2_SECRET'));
  assert.ok(html.includes(input.summary.manifestId));
});

test('renders a minimal FAILED summary without inventing results', { timeout: TIMEOUT }, () => {
  const html = renderDemoReport({ summary: {
    classification: 'SYNTHETIC_DEMO', status: 'FAILED',
    runId: '00000000-0000-4000-8000-000000000001', codeCommit: null,
    workingTreeDirty: null, errorCodes: ['DEMO_START_FAILED']
  }, manifest: null, m2: null }, { scope: 'm2', language: 'en' });
  assert.ok(html.includes('FAILED'));
  assert.ok(html.includes('Not provided'));
  assert.ok(html.includes('Not produced'));
  assert.ok(html.includes('DEMO_START_FAILED'));
  assert.ok(!html.includes('NOT_RUN'));
  assert.ok(!html.includes('Lock / export'));
});

test('preserves partial FAILED, NOT_RUN, and RUNNING values', { timeout: TIMEOUT }, () => {
  const input = {
    summary: { classification: 'SYNTHETIC_DEMO', status: 'FAILED', runId: '00000000-0000-4000-8000-000000000002', codeCommit: null, workingTreeDirty: true, scenarioStatuses: { validFileLockExport: 'NOT_RUN' }, httpStatuses: {}, errorCodes: ['M1_FAILED'], apiErrorCodes: [] },
    manifest: null,
    m2: { classification: 'SYNTHETIC_DEMO', proofKind: 'SYNTHETIC_FIXTURE', status: 'FAILED', runId: '00000000-0000-4000-8000-000000000002', codeCommit: null, workingTreeDirty: true, scenarioStatuses: { mappingProfile: 'RUNNING' }, httpStatuses: {}, errorCodes: ['M2_FAILED'] }
  };
  const html = renderDemoReport(input, { scope: 'm2', language: 'en' });
  for (const value of ['NOT_RUN', 'RUNNING', 'M1_FAILED', 'M2_FAILED', 'true']) assert.ok(html.includes(value));
});

test('FAILED M2 preserves partial issue and history fields without requiring PASS shape', { timeout: TIMEOUT }, () => {
  const input = {
    summary: { classification: 'SYNTHETIC_DEMO', status: 'FAILED', runId: '00000000-0000-4000-8000-000000000003', codeCommit: null, workingTreeDirty: false, errorCodes: ['M1_FAILED'] },
    manifest: null,
    m2: {
      classification: 'SYNTHETIC_DEMO', proofKind: 'SYNTHETIC_FIXTURE', status: 'FAILED',
      runId: '00000000-0000-4000-8000-000000000003', codeCommit: null, workingTreeDirty: false,
      issues: { A: { 'DEMO-1': { fixed: true } } }, history: { snapshotABytesStable: false }, errorCodes: ['M2_FAILED']
    }
  };
  const html = renderDemoReport(input, { scope: 'm2', language: 'en' });
  assert.ok(html.includes('DEMO-1'));
  assert.match(html, /data-field="m2\.issues\.A\.DEMO-1\.fixed">true</);
  assert.match(html, /data-field="m2\.issues\.A\.DEMO-1\.included">Not provided</);
  assert.match(html, /data-field="m2\.issues\.A\.DEMO-1\.verified">Not provided</);
  assert.match(html, /data-field="m2\.history\.snapshotABytesStable">false</);
  assert.match(html, /data-field="m2\.history\.latestSnapshotId">Not provided</);
});

test('explicit null Issue path and gaps are invalid for both PASS and FAILED', { timeout: TIMEOUT }, async () => {
  const sample = await readSample();
  for (const status of ['PASS', 'FAILED']) {
    for (const field of ['path', 'gaps']) {
      const input = structuredClone(sample);
      input.m2.status = status;
      input.m2.issues.A['DEMO-1'][field] = null;
      assert.throws(
        () => renderDemoReport(input, { scope: 'm2', language: 'en' }),
        error => error instanceof ReportInputError && error.code === 'REPORT_INPUT_INVALID',
        `${status} with explicit null ${field} must be rejected`
      );
    }
  }
});

test('FAILED result displays HTTP-only scenario with unavailable status', { timeout: TIMEOUT }, () => {
  const html = renderDemoReport({
    summary: {
      classification: 'SYNTHETIC_DEMO', status: 'FAILED', runId: '00000000-0000-4000-8000-000000000004',
      codeCommit: null, workingTreeDirty: false, httpStatuses: { validFileLockExport: [503] }, errorCodes: ['DEMO_HTTP_STATUS']
    }, manifest: null, m2: null
  }, { scope: 'm1', language: 'en' });
  assert.ok(html.includes('validFileLockExport'));
  assert.match(html, /data-field="summary\.scenarioStatuses\.validFileLockExport">Not provided</);
  assert.match(html, /data-field="summary\.httpStatuses\.validFileLockExport">503</);
  assert.ok(!html.includes('NOT_RUN'));
});

test('rejects invalid options and invalid input types', { timeout: TIMEOUT }, async () => {
  const input = await readSample();
  for (const [mutation, options] of [
    [x => { x.summary.status = 'SUCCESS'; }, { scope: 'm2', language: 'en' }],
    [x => { x.summary.workingTreeDirty = 'false'; }, { scope: 'm2', language: 'en' }],
    [x => { x.summary.httpStatuses.validFileLockExport = [99]; }, { scope: 'm2', language: 'en' }],
    [x => { x.summary.releaseId = 'invalid.id'; }, { scope: 'm2', language: 'en' }],
    [x => { x.m2.issues.A['DEMO-1'].verified = true; }, { scope: 'm2', language: 'en' }],
    [x => { x.m2.issues.A['DEMO-1'].path[0].edgeType = 'UNKNOWN_EDGE'; }, { scope: 'm2', language: 'en' }],
    [x => x, { scope: 'm3', language: 'en' }],
    [x => x, { scope: 'm2', language: 'fr' }]
  ]) {
    const changed = structuredClone(input); mutation(changed);
    assert.throws(() => renderDemoReport(changed, options), error => error instanceof ReportInputError && error.code === 'REPORT_INPUT_INVALID');
  }
});

test('rejects incomplete PASS inputs', { timeout: TIMEOUT }, async () => {
  const input = await readSample();
  assert.throws(() => renderDemoReport({ ...input, manifest: null }, { scope: 'm2', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
  assert.throws(() => renderDemoReport({ ...input, m2: null }, { scope: 'm2', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
  const missingScenario = structuredClone(input);
  delete missingScenario.summary.scenarioStatuses.validFileLockExport;
  assert.throws(() => renderDemoReport(missingScenario, { scope: 'm2', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
});

test('rejects mixed run, commit, dirty, release, manifest, and checksum values', { timeout: TIMEOUT }, async () => {
  const input = await readSample();
  const mutations = [
    x => { x.m2.runId = '00000000-0000-4000-8000-000000000000'; },
    x => { x.m2.codeCommit = '0000000000000000000000000000000000000000'; },
    x => { x.m2.workingTreeDirty = true; },
    x => { x.m2.releaseId = 'rel_other'; },
    x => { x.m2.manifestId = 'man_other'; },
    x => { x.manifest.releaseId = 'rel_other'; },
    x => { x.manifest.artifacts[0].checksum.value = '0'.repeat(64); }
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(input); mutate(changed);
    assert.throws(() => renderDemoReport(changed, { scope: 'm2', language: 'zh' }), error => error.code === 'REPORT_INPUT_MISMATCH');
  }
});

test('generateReport creates an exclusive complete output', { timeout: TIMEOUT }, async () => {
  const directory = await temporaryRun();
  const output = join(directory, 'report.en.html');
  await generateReport({ runDirectory: directory, outputFile: output, scope: 'm2', language: 'en' });
  const html = await readFile(output, 'utf8');
  assert.ok(html.endsWith('</html>\n'));
  assert.ok(html.includes('3b6697c8-0e88-452f-9b87-f5ee43bfd4ef'));
  await assert.rejects(generateReport({ runDirectory: directory, outputFile: output, scope: 'm2', language: 'en' }), error => error.code === 'REPORT_OUTPUT_FAILED');
});

test('M1 generateReport does not read m2-summary.json', { timeout: TIMEOUT }, async () => {
  const directory = await temporaryRun();
  await writeFile(join(directory, 'm2-summary.json'), '{broken', 'utf8');
  const output = join(directory, 'm1.html');
  await generateReport({ runDirectory: directory, outputFile: output, scope: 'm1', language: 'zh' });
  assert.ok((await readFile(output, 'utf8')).includes('不在本报告范围内'));
});

test('rejects malformed, oversized, invalid UTF-8, directory, and symlink inputs', { timeout: TIMEOUT }, async t => {
  const cases = [
    ['malformed JSON', async d => writeFile(join(d, 'summary.json'), '{broken', 'utf8')],
    ['oversized JSON', async d => writeFile(join(d, 'summary.json'), Buffer.alloc(1024 * 1024 + 1, 0x20))],
    ['invalid UTF-8', async d => writeFile(join(d, 'summary.json'), Buffer.from([0xc3, 0x28]))],
    ['directory input', async d => { await writeFile(join(d, 'summary.json'), 'x'); await mkdir(join(d, 'summary.json')); }]
  ];
  cases[3][1] = async d => { const p = join(d, 'summary.json'); const { rm } = await import('node:fs/promises'); await rm(p); await mkdir(p); };
  for (const [name, corrupt] of cases) await t.test(name, { timeout: TIMEOUT }, async () => {
    const directory = await temporaryRun(); await corrupt(directory);
    const output = join(directory, 'out.html');
    await assert.rejects(generateReport({ runDirectory: directory, outputFile: output, scope: 'm2', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
    await assert.rejects(readFile(output));
  });
  await t.test('symbolic link input', { timeout: TIMEOUT }, async () => {
    const directory = await temporaryRun();
    const target = join(directory, 'real-summary.json');
    await writeFile(target, JSON.stringify((await readSample()).summary));
    const { rm } = await import('node:fs/promises'); await rm(join(directory, 'summary.json'));
    try { await symlink(target, join(directory, 'summary.json'), 'file'); } catch (error) { if (error.code === 'EPERM') return; throw error; }
    await assert.rejects(generateReport({ runDirectory: directory, outputFile: join(directory, 'out.html'), scope: 'm2', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
  });
  await t.test('missing required summary', { timeout: TIMEOUT }, async () => {
    const directory = await mkdtemp(join(tmpdir(), 'vsrqg-report-'));
    await assert.rejects(generateReport({ runDirectory: directory, outputFile: join(directory, 'out.html'), scope: 'm1', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
  });
  await t.test('non-object JSON root', { timeout: TIMEOUT }, async () => {
    const directory = await temporaryRun();
    await writeFile(join(directory, 'summary.json'), '[]', 'utf8');
    await assert.rejects(generateReport({ runDirectory: directory, outputFile: join(directory, 'out.html'), scope: 'm2', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
  });
});

test('output failures preserve existing and source bytes', { timeout: TIMEOUT }, async () => {
  const directory = await temporaryRun();
  const summaryPath = join(directory, 'summary.json');
  const before = await readFile(summaryPath);
  await assert.rejects(generateReport({ runDirectory: directory, outputFile: summaryPath, scope: 'm2', language: 'en' }), error => error.code === 'REPORT_OUTPUT_FAILED');
  assert.deepEqual(await readFile(summaryPath), before);
  const existing = join(directory, 'existing.html');
  await writeFile(existing, 'KEEP');
  await assert.rejects(generateReport({ runDirectory: directory, outputFile: existing, scope: 'm2', language: 'en' }), error => error.code === 'REPORT_OUTPUT_FAILED');
  assert.equal(await readFile(existing, 'utf8'), 'KEEP');
  await assert.rejects(generateReport({ runDirectory: directory, outputFile: join(directory, 'missing', 'out.html'), scope: 'm2', language: 'en' }), error => error.code === 'REPORT_OUTPUT_FAILED');
});

test('input size boundary accepts exactly 1 MiB and rejects the next byte', { timeout: TIMEOUT }, async () => {
  const minimal = JSON.stringify({
    classification: 'SYNTHETIC_DEMO', status: 'FAILED', runId: '00000000-0000-4000-8000-000000000005',
    codeCommit: null, workingTreeDirty: null, errorCodes: []
  });
  const acceptedDirectory = await temporaryRun({ summary: null, manifest: null, m2: null });
  await writeFile(join(acceptedDirectory, 'summary.json'), minimal.padEnd(1024 * 1024, ' '), 'utf8');
  const acceptedOutput = join(acceptedDirectory, 'report.html');
  await generateReport({ runDirectory: acceptedDirectory, outputFile: acceptedOutput, scope: 'm1', language: 'en' });
  assert.ok((await readFile(acceptedOutput, 'utf8')).includes('FAILED'));

  const rejectedDirectory = await temporaryRun({ summary: null, manifest: null, m2: null });
  await writeFile(join(rejectedDirectory, 'summary.json'), minimal.padEnd(1024 * 1024 + 1, ' '), 'utf8');
  await assert.rejects(generateReport({ runDirectory: rejectedDirectory, outputFile: join(rejectedDirectory, 'report.html'), scope: 'm1', language: 'en' }), error => error.code === 'REPORT_INPUT_INVALID');
});

test('CLI accepts only one complete explicit argument set', { timeout: TIMEOUT }, async () => {
  const directory = await temporaryRun();
  const valid = ['--run-dir', directory, '--output', join(directory, 'cli.html'), '--scope', 'm2', '--language', 'en'];
  const ok = runCli(valid);
  assert.equal(ok.status, 0, ok.stderr);
  assert.equal(ok.stdout.trim(), 'REPORT_RENDERED');
  for (const args of [valid.slice(0, -2), [...valid, '--scope', 'm1'], [...valid, '--unknown', 'x'], [...valid.slice(0, -1), 'fr'], [...valid.slice(0, -1), '']]) {
    const result = runCli(args);
    assert.notEqual(result.status, 0);
    assert.equal(result.stderr.trim(), 'REPORT_ARGUMENT_INVALID');
    assert.equal(result.stdout, '');
  }
});

test('CLI emits fixed diagnostics without leaking paths or parser details', { timeout: TIMEOUT }, async () => {
  const directory = await temporaryRun();
  await writeFile(join(directory, 'summary.json'), '{secret broken json', 'utf8');
  const output = join(directory, 'out.html');
  const result = runCli(['--run-dir', directory, '--output', output, '--scope', 'm2', '--language', 'en']);
  assert.notEqual(result.status, 0);
  assert.equal(result.stderr.trim(), 'REPORT_INPUT_INVALID');
  assert.ok(!result.stderr.includes(directory));
  assert.ok(!result.stderr.includes('secret'));
});
