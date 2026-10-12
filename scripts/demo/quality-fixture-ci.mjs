import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildQualityReportExport } from './export-quality-report.mjs';
import { validateQualityReportExport } from './quality-report-export.mjs';
import { generateReport } from './render-report.mjs';

function verifyEvidenceLocator(evaluation, testRun, metadata) {
  const refs = evaluation.inputSnapshot?.evidenceRefs;
  if (!Array.isArray(refs) || refs.length === 0 || !Array.isArray(metadata) || metadata.length !== refs.length) {
    throw new Error('QUALITY_FIXTURE_SOURCES_INCOMPLETE');
  }
  const byId = new Map(metadata.map(item => [item.evidenceId, item]));
  if (byId.size !== refs.length) throw new Error('QUALITY_FIXTURE_EVIDENCE_MISMATCH');
  for (const ref of refs) {
    const source = byId.get(ref.evidenceId);
    const attempt = testRun.attempts?.find(item => item.attemptId === ref.attemptId);
    if (!source || source.evidenceId !== ref.evidenceId || source.testRunId !== ref.runId ||
        source.attemptId !== ref.attemptId || source.type !== ref.type ||
        source.payloadChecksum !== ref.digest || source.sizeBytes !== ref.sizeBytes ||
        source.state !== 'AVAILABLE' || source.integrity !== 'VERIFIED' ||
        testRun.runId !== ref.runId || !attempt?.result?.evidenceIds?.includes(ref.evidenceId)) {
      throw new Error('QUALITY_FIXTURE_EVIDENCE_MISMATCH');
    }
  }
}

export async function produceQualityFixtureReports(bundle, outputDirectory, commit) {
  if (!/^[0-9a-f]{40}$/.test(commit) || bundle?.classification !== 'SYNTHETIC_FIXTURE' ||
      ![`quality-decision-${commit}`, `quality-upload-${commit}`].includes(bundle.fixtureId) ||
      !bundle.responses?.completed || !bundle.responses?.error) {
    throw new Error('QUALITY_FIXTURE_IDENTITY_INVALID');
  }
  const { traceability, testRun, evidenceMetadata } = bundle.responses;
  if (!traceability || !testRun) {
    throw new Error('QUALITY_FIXTURE_SOURCES_INCOMPLETE');
  }
  verifyEvidenceLocator(bundle.responses.completed, testRun, evidenceMetadata);
  for (const [outcome, expectedState] of [['completed', 'COMPLETED'], ['error', 'ERROR']]) {
    const evaluation = bundle.responses[outcome];
    if (evaluation.state !== expectedState) throw new Error('QUALITY_FIXTURE_IDENTITY_INVALID');
    const bytes = await buildQualityReportExport({
      releaseId: evaluation.releaseId, evaluationId: evaluation.evaluationId,
      fetchPage: async () => ({ items: [evaluation], nextCursor: null }),
      fetchTraceability: async () => traceability,
      fetchTestRun: async () => testRun
    });
    const report = validateQualityReportExport(bytes);
    report.provenance = { classification: 'SYNTHETIC_FIXTURE', fixtureId: bundle.fixtureId };
    const finalBytes = Buffer.from(JSON.stringify(report));
    validateQualityReportExport(finalBytes);
    const directory = join(outputDirectory, outcome);
    await mkdir(directory, { recursive: true });
    await writeFile(join(directory, 'quality-report-export.json'), finalBytes, { flag: 'wx' });
    for (const language of ['zh', 'en']) {
      await generateReport({ runDirectory: directory, outputFile: join(directory, `report.${language}.html`), scope: 'quality', language });
    }
  }
}

const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  if (process.argv.length !== 3) throw new Error('QUALITY_FIXTURE_ARGUMENT_INVALID');
  const input = resolve(process.argv[2]);
  const bundle = JSON.parse(await readFile(input, 'utf8'));
  await produceQualityFixtureReports(bundle, dirname(input), process.env.GITHUB_SHA);
  process.stdout.write('QUALITY_FIXTURE_REPORTS_READY\n');
}
