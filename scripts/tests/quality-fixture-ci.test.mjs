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
const bundle = { classification: 'SYNTHETIC_FIXTURE', fixtureId: `quality-decision-${commit}`, responses: { completed, error } };

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
