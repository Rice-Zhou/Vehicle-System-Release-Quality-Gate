import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildQualityReportExport } from './export-quality-report.mjs';
import { validateQualityReportExport } from './quality-report-export.mjs';
import { generateReport } from './render-report.mjs';

function verifyEvidenceLocator(evaluation, metadata) {
  const refs = evaluation.inputSnapshot?.evidenceRefs;
  if (!Array.isArray(refs) || refs.length === 0 || !Array.isArray(metadata) || metadata.length !== refs.length) {
    throw new Error('QUALITY_FIXTURE_SOURCES_INCOMPLETE');
  }
  const byId = new Map(metadata.map(item => [item.evidenceId, item]));
  if (byId.size !== refs.length) throw new Error('QUALITY_FIXTURE_EVIDENCE_MISMATCH');
  for (const ref of refs) {
    const source = byId.get(ref.evidenceId);
    if (!source || source.evidenceId !== ref.evidenceId || source.testRunId !== ref.runId ||
        source.attemptId !== ref.attemptId || source.type !== ref.type ||
        source.payloadChecksum !== ref.digest || source.sizeBytes !== ref.sizeBytes ||
        source.state !== 'AVAILABLE' || source.integrity !== 'VERIFIED') {
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
  if (!traceability || !testRun) throw new Error('QUALITY_FIXTURE_SOURCES_INCOMPLETE');
  verifyEvidenceLocator(bundle.responses.completed, evidenceMetadata);
  const scenarios = bundle.responses.scenarios;
  const expected = scenarios ? {
    smokePass: ['COMPLETED', 'PASS'], smokeFail: ['COMPLETED', 'BLOCK'],
    requiredIssueBlock: ['COMPLETED', 'BLOCK'],
    missingEvidence: ['ERROR', 'QUALITY_REQUIRED_EVIDENCE_MISSING']
  } : { completed: ['COMPLETED', null], error: ['ERROR', null] };
  for (const [outcome, [state, decision]] of Object.entries(expected)) {
    const source = scenarios ? scenarios[outcome] : {
      evaluation: bundle.responses[outcome], traceability, testRun, evidenceMetadata
    };
    const { evaluation } = source ?? {};
    if (!evaluation || evaluation.state !== state ||
        (state === 'COMPLETED' && decision && evaluation.qualityResult?.action !== decision) ||
        (state === 'ERROR' && decision && evaluation.error?.code !== decision)) {
      throw new Error('QUALITY_FIXTURE_DECISION_MISMATCH');
    }
    if (evaluation.inputSnapshot) {
      if (!source.traceability || !source.testRun) throw new Error('QUALITY_FIXTURE_SOURCES_INCOMPLETE');
      verifyEvidenceLocator(evaluation, source.evidenceMetadata);
    }
    const bytes = await buildQualityReportExport({
      releaseId: evaluation.releaseId, evaluationId: evaluation.evaluationId,
      fetchPage: async () => ({ items: [evaluation], nextCursor: null }),
      fetchTraceability: async () => source.traceability,
      fetchTestRun: async () => source.testRun
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
