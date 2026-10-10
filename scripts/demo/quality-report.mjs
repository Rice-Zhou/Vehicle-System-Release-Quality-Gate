import { validateQualityReportExport } from './quality-report-export.mjs';

const COPY = {
  en: {
    title: 'Quality Evaluation', note: 'Read-only projection of one recorded Evaluation. This page does not evaluate rules or authorize a Release.',
    source: 'Source classification', decision: 'Recorded decision', input: 'Pinned input and versions',
    rules: 'Rule outcomes', evidence: 'Pinned Evidence references', uncovered: 'Uncovered facts',
    notPinned: 'Input was not pinned; no source queries or Quality Result exist.',
    notQueried: 'Source response not available in this export; navigation is unverified.',
    evidenceNote: 'Rule Evidence IDs refer to the pinned input set, not to evidence caused by an individual rule. Payload access requires separate authorization.',
    field: 'Field', value: 'Recorded value', none: 'None', error: 'Original error',
    noResult: 'NOT_EVALUATED', trace: 'Traceability query', test: 'Test Run query'
  },
  zh: {
    title: '质量判定', note: '单条已记录 Evaluation 的只读投影。本页不求值规则，也不授权 Release。',
    source: '来源类型', decision: '原始决定', input: '固定输入与版本',
    rules: '规则结果', evidence: '固定 Evidence 引用', uncovered: '未覆盖事实',
    notPinned: '输入尚未固定；没有来源查询或 Quality Result。',
    notQueried: '本导出没有来源查询响应；导航尚未验证。',
    evidenceNote: '规则旁的 Evidence ID 指向固定输入全集，不代表单条规则的因果证据。Payload 访问需另行授权。',
    field: '字段', value: '记录值', none: '无', error: '原始错误',
    noResult: 'NOT_EVALUATED', trace: 'Traceability 查询', test: 'Test Run 查询'
  }
};

const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);
const display = value => value === undefined || value === null ? '—' : escapeHtml(value);
const row = (name, value) => `<tr><th scope="row">${escapeHtml(name)}</th><td>${display(value)}</td></tr>`;
const section = (title, body) => `<section><h2>${escapeHtml(title)}</h2>${body}</section>`;
const table = (items, copy) => `<div class="table-wrap"><table><thead><tr><th>${escapeHtml(copy.field)}</th><th>${escapeHtml(copy.value)}</th></tr></thead><tbody>${items.map(([name, value]) => row(name, value)).join('')}</tbody></table></div>`;

export function renderQualityReport(report, { language } = {}) {
  if (!COPY[language]) throw new Error('REPORT_INPUT_INVALID');
  validateQualityReportExport(Buffer.from(JSON.stringify(report)));
  const copy = COPY[language];
  const { evaluation, provenance, traceabilityResponse, testRunResponse } = report;
  const input = evaluation.inputSnapshot;
  const result = evaluation.qualityResult;
  const ruleResults = result?.ruleResults ?? evaluation.ruleResults ?? [];
  const decisionRows = [
    ['evaluationId', evaluation.evaluationId], ['project', evaluation.project], ['releaseId', evaluation.releaseId],
    ['state', evaluation.state], ['createdAt', evaluation.createdAt]
  ];
  if (result) decisionRows.push(['action', result.action], ['resultDigest', result.resultDigest]);
  else decisionRows.push(['releaseQualityState', evaluation.releaseQualityState], ['error.code', evaluation.error.code]);
  const decision = table(decisionRows, copy);
  const inputRows = input ? [
    ['snapshotId', input.snapshotId], ['inputDigest', input.inputDigest],
    ['manifest.id', input.manifest.id], ['manifest.version', input.manifest.version], ['manifest.digest', input.manifest.digest],
    ['issueSnapshot.id', input.issueSnapshot.id], ['issueSnapshot.version', input.issueSnapshot.version], ['issueSnapshot.digest', input.issueSnapshot.digest],
    ['traceabilitySnapshot.id', input.traceabilitySnapshot.id], ['traceabilitySnapshot.version', input.traceabilitySnapshot.version], ['traceabilitySnapshot.digest', input.traceabilitySnapshot.digest],
    ['ruleSetId', input.ruleSet.ruleSetId], ['ruleSetVersion', input.ruleSet.version], ['ruleSetDigest', input.ruleSetDigest],
    ['catalogVersion', input.versions.catalogVersion], ['engineVersion', input.versions.engineVersion],
    ['canonicalizationVersion', input.versions.canonicalizationVersion], ['selectionPolicyVersion', input.versions.selectionPolicyVersion],
    ['requiredIssueRefs', input.requiredIssueRefs.map(x => `${x.source}:${x.sourceIssueId}`).join(', ') || copy.none],
    ['selectedCaseRefs', input.selectedCaseRefs.map(x => `${x.caseId}@${x.version}`).join(', ')],
    ['runId', input.selections[0].runId], ['attemptId', input.selections[0].selectedAttempt.attemptId],
    ['selectedResultDigest', input.selections[0].resultDigest]
  ] : [];
  const ruleRows = ruleResults.map((rule, index) => [
    [`ruleResults[${index}]`, `${rule.ruleId}@${rule.version}: ${rule.status}; ${rule.explanation.code}`],
    [`ruleResults[${index}].matchedFacts`, rule.matchedFacts.map(fact => fact.path).join(', ') || copy.none],
    [`ruleResults[${index}].evidenceRefs`, rule.evidenceRefs.join(', ') || copy.none]
  ]).flat();
  const evidenceRows = input?.evidenceRefs.map((ref, index) => [
    `evidenceRefs[${index}]`, `${ref.evidenceId}; ${ref.type}; ${ref.runId}; ${ref.attemptId}; ${ref.digest}; ${ref.sizeBytes}`
  ]) ?? [];
  const sourceRows = [
    ['Traceability', traceabilityResponse ? traceabilityResponse.snapshot.snapshotId : copy.notQueried],
    ['Test Run', testRunResponse ? testRunResponse.runId : copy.notQueried]
  ];
  return `<!doctype html>\n<html lang="${language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; connect-src 'none'; script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><title>${escapeHtml(copy.title)}</title><style>
  :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#f4f7fb;color:#172033;font:15px/1.55 system-ui,sans-serif}main{max-width:1100px;margin:auto;padding:24px}header,section{background:white;border:1px solid #d9dee8;border-radius:10px;padding:18px;margin:14px 0}h1{margin:0}h2{font-size:1.2rem;margin:0 0 10px}.table-wrap{overflow-x:auto}table{border-collapse:collapse;width:100%;min-width:520px}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #d9dee8;padding:7px 10px;overflow-wrap:anywhere}th{width:35%}.notice{border-left:4px solid #2457a7;padding-left:12px}
  </style></head><body><main><header><h1>${escapeHtml(copy.title)}</h1><p class="notice">${escapeHtml(copy.note)}</p><p>${escapeHtml(copy.source)}: ${display(provenance.classification)}${provenance.fixtureId ? ` · ${display(provenance.fixtureId)}` : ''}</p></header>
  ${section(copy.decision, decision)}
  ${section(copy.input, input ? table(inputRows, copy) : `<p>${escapeHtml(copy.notPinned)}</p>`)}
  ${input ? section(copy.trace, table(sourceRows, copy)) : ''}
  ${section(copy.rules, ruleRows.length ? table(ruleRows, copy) : `<p>${escapeHtml(copy.none)}</p>`)}
  ${section(copy.uncovered, input ? table(input.uncoveredFacts.map((value, index) => [`uncoveredFacts[${index}]`, value]), copy) : `<p>${escapeHtml(copy.none)}</p>`)}
  ${section(copy.evidence, `<p>${escapeHtml(copy.evidenceNote)}</p>${evidenceRows.length ? table(evidenceRows, copy) : `<p>${escapeHtml(copy.none)}</p>`}`)}
  </main></body></html>\n`;
}
