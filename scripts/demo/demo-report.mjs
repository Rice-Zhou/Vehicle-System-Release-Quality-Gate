const M1_SCENARIOS = ['validFileLockExport', 'corruptFileRejected', 'unauthenticatedRejected', 'viewerWriteRejected', 'idempotentReplay', 'historicalExportStable'];
const M2_SCENARIOS = ['mappingProfile', 'issueSync', 'issueSnapshot', 'buildIngestion', 'snapshotA', 'snapshotB', 'sameKeyReplay', 'userIngestionRejected', 'invalidFactsRejected', 'historyStable'];
const STATUSES = new Set(['PASS', 'FAILED', 'NOT_RUN', 'RUNNING']);
const EDGE_TYPES = new Set(['ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE']);
const GAP_CODES = new Set(['ISSUE_COMMIT_MISSING', 'COMMIT_BUILD_MISSING', 'BUILD_ARTIFACT_MISSING', 'ARTIFACT_RELEASE_MISSING', 'TEST_RESULT_EVIDENCE_MISSING']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const COMMIT = /^[0-9a-f]{40}$/;
const DIGEST = /^sha256:[0-9a-f]{64}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const CODE = /^[A-Z0-9_]{1,80}$/;
const ID = /^[A-Za-z0-9_-]{1,128}$/;

const COPY = {
  en: {
    title: 'Offline Demo Report', synthetic: 'Synthetic demo — read-only presentation of recorded facts',
    source: 'Source identity', status: 'Recorded status', runId: 'Run ID', commit: 'Code commit', dirty: 'Working tree dirty',
    m1: 'M1 recorded result', m2: 'M2 recorded result', notProvided: 'Not provided', notProduced: 'Not produced',
    outOfScope: 'M2 is outside this report scope', release: 'Release and Manifest', artifacts: 'Artifacts', issues: 'Issue traceability',
    history: 'History check', scenarios: 'Scenario, HTTP, and error details', field: 'Field', value: 'Value',
    artifactId: 'Artifact ID', type: 'Type', name: 'Name', version: 'Version', required: 'Required', checksum: 'Checksum',
    snapshot: 'Snapshot', issue: 'Issue', fixed: 'Fixed', included: 'Included', verified: 'Verified', path: 'Path', gaps: 'Gaps',
    negative: 'A negative scenario marked PASS means the expected rejection occurred; it is not a Release PASS.', boundary: 'Synthetic fixture only. Verified remains false; this is not proof from a real vehicle or a final Release quality decision.', errors: 'Error codes',
    historyNote: 'snapshotABytesStable is the recorded run check; this report did not recompute history.',
    noArtifacts: 'No artifacts produced', noIssues: 'No issue results produced', none: 'None'
  },
  zh: {
    title: '离线演示报告', synthetic: '合成演示——已记录事实的只读呈现', source: '来源标识', status: '记录状态', runId: '运行 ID',
    commit: '代码提交', dirty: '工作树脏状态', m1: 'M1 原始结果', m2: 'M2 原始结果', notProvided: '未提供', notProduced: '未产生',
    outOfScope: 'M2 不在本报告范围内', release: 'Release 与 Manifest', artifacts: 'Artifact', issues: 'Issue 可追溯性',
    history: '历史检查', scenarios: '场景、HTTP 与错误明细', field: '字段', value: '值', artifactId: 'Artifact ID', type: '类型',
    name: '名称', version: '版本', required: '必需', checksum: '校验和', snapshot: '快照', issue: 'Issue', fixed: 'Fixed',
    included: 'Included', verified: 'Verified', path: '路径', gaps: 'Gap',
    negative: '负向场景的 PASS 表示按预期拒绝，不是 Release PASS。', boundary: '仅使用合成夹具。Verified 保持 false；这不是真实车辆证明，也不是最终 Release 质量决定。', errors: '错误码',
    historyNote: 'snapshotABytesStable 是既有运行检查结果；本报告未重新验证历史。', noArtifacts: '未产生 Artifact', noIssues: '未产生 Issue 结果', none: '无'
  }
};

export class ReportInputError extends Error {
  constructor(code = 'REPORT_INPUT_INVALID') { super(code); this.name = 'ReportInputError'; this.code = code; }
}

const invalid = () => { throw new ReportInputError('REPORT_INPUT_INVALID'); };
const mismatch = () => { throw new ReportInputError('REPORT_INPUT_MISMATCH'); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const present = (source, key) => Object.prototype.hasOwnProperty.call(source, key);
const requireObject = value => { if (!object(value)) invalid(); return value; };
const requireString = (value, pattern = ID) => { if (typeof value !== 'string' || !pattern.test(value)) invalid(); return value; };
const optionalString = (source, key, pattern = ID) => present(source, key) && source[key] !== null ? requireString(source[key], pattern) : null;
const optionalBoolean = (source, key) => { if (!present(source, key) || source[key] === null) return null; if (typeof source[key] !== 'boolean') invalid(); return source[key]; };
const requireBoolean = value => { if (typeof value !== 'boolean') invalid(); return value; };
const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const display = (value, copy) => value === null || value === undefined ? copy.notProvided : Array.isArray(value) ? (value.length ? value.join(', ') : copy.none) : String(value);
const cell = (key, value, copy) => `<td data-field="${key}">${escapeHtml(display(value, copy))}</td>`;

function validateCodes(value) {
  if (!Array.isArray(value) || value.some(code => typeof code !== 'string' || !CODE.test(code))) invalid();
  return [...value];
}

function validateScenarios(value, requiredNames, required) {
  if (value === undefined && !required) return null;
  const source = requireObject(value);
  if (required && requiredNames.some(name => !present(source, name))) invalid();
  const result = {};
  for (const name of requiredNames) if (present(source, name)) {
    if (!STATUSES.has(source[name])) invalid();
    result[name] = source[name];
  }
  return result;
}

function validateHttp(value, requiredNames, required) {
  if (value === undefined && !required) return null;
  const source = requireObject(value);
  if (required && requiredNames.some(name => !present(source, name))) invalid();
  const result = {};
  for (const name of requiredNames) if (present(source, name)) {
    const statuses = source[name];
    if (!Array.isArray(statuses) || statuses.some(status => !Number.isInteger(status) || status < 100 || status > 599)) invalid();
    result[name] = [...statuses];
  }
  return result;
}

function projectSummary(value) {
  const source = requireObject(value);
  if (source.classification !== 'SYNTHETIC_DEMO' || !['PASS', 'FAILED'].includes(source.status)) invalid();
  const required = source.status === 'PASS';
  const result = {
    classification: source.classification, status: source.status, runId: requireString(source.runId, UUID),
    codeCommit: optionalString(source, 'codeCommit', COMMIT), workingTreeDirty: optionalBoolean(source, 'workingTreeDirty'),
    scenarioStatuses: validateScenarios(source.scenarioStatuses, M1_SCENARIOS, required), httpStatuses: validateHttp(source.httpStatuses, M1_SCENARIOS, required),
    releaseId: optionalString(source, 'releaseId'), manifestId: optionalString(source, 'manifestId'),
    corruptReleaseId: optionalString(source, 'corruptReleaseId'), corruptManifestId: optionalString(source, 'corruptManifestId'),
    contentDigest: optionalString(source, 'contentDigest', DIGEST), payloadSha256: optionalString(source, 'payloadSha256', SHA256),
    errorCodes: present(source, 'errorCodes') ? validateCodes(source.errorCodes) : [], apiErrorCodes: present(source, 'apiErrorCodes') ? validateCodes(source.apiErrorCodes) : []
  };
  if (required && [result.codeCommit, result.workingTreeDirty, result.releaseId, result.manifestId, result.contentDigest, result.payloadSha256].some(x => x === null)) invalid();
  return result;
}

function projectManifest(value) {
  if (value === null || value === undefined) return null;
  const source = requireObject(value);
  if (!Array.isArray(source.artifacts) || source.artifacts.length !== 1) invalid();
  const artifacts = source.artifacts.map(item => {
    const artifact = requireObject(item); const checksum = requireObject(artifact.checksum);
    if (checksum.algorithm !== 'SHA-256') invalid();
    return { artifactId: requireString(artifact.artifactId), type: requireString(artifact.type), name: requireString(artifact.name, /^[\s\S]+$/), version: requireString(artifact.version, /^[\s\S]+$/), required: requireBoolean(artifact.required), checksum: requireString(checksum.value, SHA256) };
  });
  return { releaseId: requireString(source.releaseId), manifestVersion: requireString(source.manifestVersion, /^[\s\S]+$/), vehicle: requireString(source.vehicle, /^[\s\S]+$/), platform: requireString(source.platform, /^[\s\S]+$/), systemVersion: requireString(source.systemVersion, /^[\s\S]+$/), buildId: requireString(source.buildId), artifacts };
}

function projectIssue(value, required) {
  const source = requireObject(value);
  if (required && ['fixed', 'included', 'verified', 'path', 'gaps'].some(key => !present(source, key))) invalid();
  const fixed = present(source, 'fixed') ? requireBoolean(source.fixed) : null;
  const included = present(source, 'included') ? requireBoolean(source.included) : null;
  const verified = present(source, 'verified') ? requireBoolean(source.verified) : null;
  if (verified !== null && verified !== false) invalid();
  const hasPath = present(source, 'path');
  const hasGaps = present(source, 'gaps');
  if ((hasPath && !Array.isArray(source.path)) || (hasGaps && !Array.isArray(source.gaps))) invalid();
  const path = hasPath ? source.path : null;
  const gaps = hasGaps ? source.gaps : null;
  return {
    fixed, included, verified,
    path: path?.map(raw => { const edge = requireObject(raw); if (!EDGE_TYPES.has(edge.edgeType)) invalid(); return { edgeType: edge.edgeType, fromId: requireString(edge.fromId), toId: requireString(edge.toId) }; }) ?? null,
    gaps: gaps?.map(raw => { const gap = requireObject(raw); if (!GAP_CODES.has(gap.diagnosticCode)) invalid(); return gap.diagnosticCode; }) ?? null
  };
}

function projectPair(value, mapper, required) {
  if (value === undefined && !required) return null;
  const source = requireObject(value); const result = {};
  for (const key of ['A', 'B']) {
    if (!present(source, key)) { if (required) invalid(); continue; }
    result[key] = mapper(source[key]);
  }
  return result;
}

function projectM2(value) {
  if (value === null || value === undefined) return null;
  const source = requireObject(value);
  if (source.classification !== 'SYNTHETIC_DEMO' || source.proofKind !== 'SYNTHETIC_FIXTURE' || !['PASS', 'FAILED'].includes(source.status)) invalid();
  const required = source.status === 'PASS';
  const pairIds = raw => requireString(raw);
  const issues = projectPair(source.issues, raw => {
    const issueMap = requireObject(raw); const result = {};
    for (const issueId of ['DEMO-1', 'DEMO-2']) {
      if (!present(issueMap, issueId)) { if (required) invalid(); continue; }
      result[issueId] = projectIssue(issueMap[issueId], required);
    }
    return result;
  }, required);
  let history = null;
  if (source.history !== undefined) {
    const h = requireObject(source.history);
    if (required && (!present(h, 'latestSnapshotId') || !present(h, 'snapshotABytesStable'))) invalid();
    history = {
      latestSnapshotId: present(h, 'latestSnapshotId') ? requireString(h.latestSnapshotId) : null,
      snapshotABytesStable: present(h, 'snapshotABytesStable') ? requireBoolean(h.snapshotABytesStable) : null
    };
  }
  else if (required) invalid();
  const result = {
    classification: source.classification, proofKind: source.proofKind, status: source.status, runId: requireString(source.runId, UUID),
    codeCommit: optionalString(source, 'codeCommit', COMMIT), workingTreeDirty: optionalBoolean(source, 'workingTreeDirty'),
    scenarioStatuses: validateScenarios(source.scenarioStatuses, M2_SCENARIOS, required), httpStatuses: validateHttp(source.httpStatuses, M2_SCENARIOS, required),
    releaseId: optionalString(source, 'releaseId'), manifestId: optionalString(source, 'manifestId'), syncRunId: optionalString(source, 'syncRunId'), issueSnapshotId: optionalString(source, 'issueSnapshotId'),
    verificationRunIds: projectPair(source.verificationRunIds, pairIds, required), traceabilitySnapshotIds: projectPair(source.traceabilitySnapshotIds, pairIds, required),
    contentDigests: projectPair(source.contentDigests, raw => requireString(raw, DIGEST), required), issues, history,
    errorCodes: present(source, 'errorCodes') ? validateCodes(source.errorCodes) : []
  };
  if (required && [result.codeCommit, result.workingTreeDirty, result.releaseId, result.manifestId, result.syncRunId, result.issueSnapshotId].some(x => x === null)) invalid();
  return result;
}

function checkRelations(summary, manifest, m2, scope) {
  if (summary.status === 'PASS' && !manifest) invalid();
  if (manifest) {
    if (summary.releaseId && manifest.releaseId !== summary.releaseId) mismatch();
    if (summary.payloadSha256 && manifest.artifacts[0].checksum !== summary.payloadSha256) mismatch();
  }
  if (scope === 'm2') {
    if (summary.status === 'PASS' && !m2) invalid();
    if (m2) {
      if (m2.runId !== summary.runId || m2.codeCommit !== summary.codeCommit || m2.workingTreeDirty !== summary.workingTreeDirty) mismatch();
      if (summary.releaseId && m2.releaseId && summary.releaseId !== m2.releaseId) mismatch();
      if (summary.manifestId && m2.manifestId && summary.manifestId !== m2.manifestId) mismatch();
      if (m2.history?.latestSnapshotId && m2.traceabilitySnapshotIds?.B && m2.history.latestSnapshotId !== m2.traceabilitySnapshotIds.B) mismatch();
    }
  }
}

function rows(entries, copy) {
  return entries.map(([key, label, value]) => `<tr><th scope="row">${escapeHtml(label)}</th>${cell(key, value, copy)}</tr>`).join('');
}

function resultTable(title, prefix, result, copy) {
  if (!result) return `<section><h2>${escapeHtml(title)}</h2><p class="missing">${escapeHtml(copy.notProduced)}</p></section>`;
  return `<section><h2>${escapeHtml(title)}</h2><div class="table-wrap"><table><tbody>${rows([
    [`${prefix}.status`, copy.status, result.status], [`${prefix}.runId`, copy.runId, result.runId], [`${prefix}.codeCommit`, copy.commit, result.codeCommit], [`${prefix}.workingTreeDirty`, copy.dirty, result.workingTreeDirty]
  ], copy)}</tbody></table></div></section>`;
}

function scenarioDetails(title, prefix, result, names, copy) {
  if (!result) return '';
  const body = names.filter(name => present(result.scenarioStatuses ?? {}, name) || present(result.httpStatuses ?? {}, name)).map(name => `<tr><th scope="row">${escapeHtml(name)}</th>${cell(`${prefix}.scenarioStatuses.${name}`, result.scenarioStatuses?.[name] ?? null, copy)}${cell(`${prefix}.httpStatuses.${name}`, result.httpStatuses?.[name] ?? null, copy)}</tr>`).join('');
  const errors = [...result.errorCodes, ...(result.apiErrorCodes ?? [])];
  return `<details><summary>${escapeHtml(title)}</summary><div class="table-wrap"><table><thead><tr><th>${escapeHtml(copy.field)}</th><th>Status</th><th>HTTP</th></tr></thead><tbody>${body}</tbody></table></div><p data-field="${prefix}.errorCodes"><strong>${escapeHtml(copy.errors)}:</strong> ${escapeHtml(display(errors, copy))}</p></details>`;
}

export function renderDemoReport(input, options) {
  const opts = requireObject(options); if (!['m1', 'm2'].includes(opts.scope) || !['zh', 'en'].includes(opts.language)) invalid();
  const root = requireObject(input); const copy = COPY[opts.language];
  const summary = projectSummary(root.summary);
  const manifest = projectManifest(root.manifest);
  const m2 = opts.scope === 'm2' ? projectM2(root.m2) : null;
  checkRelations(summary, manifest, m2, opts.scope);
  const artifactRows = manifest?.artifacts.map((a, index) => `<tr>${cell(`manifest.artifacts.${index}.artifactId`, a.artifactId, copy)}${cell(`manifest.artifacts.${index}.type`, a.type, copy)}${cell(`manifest.artifacts.${index}.name`, a.name, copy)}${cell(`manifest.artifacts.${index}.version`, a.version, copy)}${cell(`manifest.artifacts.${index}.required`, a.required, copy)}${cell(`manifest.artifacts.${index}.checksum`, a.checksum, copy)}</tr>`).join('') ?? '';
  const issueRows = m2?.issues ? ['A', 'B'].flatMap(snapshot => Object.entries(m2.issues[snapshot] ?? {}).map(([issueId, issue]) => {
    const path = issue.path?.map(edge => `${edge.edgeType}: ${edge.fromId} → ${edge.toId}`).join('\n') ?? null;
    return `<tr>${cell(`m2.issues.${snapshot}.${issueId}.snapshot`, snapshot, copy)}${cell(`m2.issues.${snapshot}.${issueId}.issueId`, issueId, copy)}${cell(`m2.issues.${snapshot}.${issueId}.fixed`, issue.fixed, copy)}${cell(`m2.issues.${snapshot}.${issueId}.included`, issue.included, copy)}${cell(`m2.issues.${snapshot}.${issueId}.verified`, issue.verified, copy)}${cell(`m2.issues.${snapshot}.${issueId}.path`, path === '' ? copy.none : path, copy)}${cell(`m2.issues.${snapshot}.${issueId}.gaps`, issue.gaps, copy)}</tr>`;
  })).join('') : '';
  const releaseRows = rows([['summary.releaseId', 'summary.releaseId', summary.releaseId], ['manifest.releaseId', 'manifest.releaseId', manifest?.releaseId], ['summary.manifestId', 'manifestId', summary.manifestId], ['summary.contentDigest', 'contentDigest', summary.contentDigest], ['summary.payloadSha256', 'payloadSha256', summary.payloadSha256], ['manifest.manifestVersion', 'manifestVersion', manifest?.manifestVersion], ['manifest.vehicle', 'vehicle', manifest?.vehicle], ['manifest.platform', 'platform', manifest?.platform], ['manifest.systemVersion', 'systemVersion', manifest?.systemVersion], ['manifest.buildId', 'buildId', manifest?.buildId]], copy);
  const m2Extra = m2 ? rows([['m2.proofKind', 'proofKind', m2.proofKind], ['m2.releaseId', 'releaseId', m2.releaseId], ['m2.manifestId', 'manifestId', m2.manifestId], ['m2.syncRunId', 'syncRunId', m2.syncRunId], ['m2.issueSnapshotId', 'issueSnapshotId', m2.issueSnapshotId], ['m2.verificationRunIds.A', 'verificationRunId A', m2.verificationRunIds?.A], ['m2.verificationRunIds.B', 'verificationRunId B', m2.verificationRunIds?.B], ['m2.traceabilitySnapshotIds.A', 'traceabilitySnapshotId A', m2.traceabilitySnapshotIds?.A], ['m2.traceabilitySnapshotIds.B', 'traceabilitySnapshotId B', m2.traceabilitySnapshotIds?.B], ['m2.contentDigests.A', 'contentDigest A', m2.contentDigests?.A], ['m2.contentDigests.B', 'contentDigest B', m2.contentDigests?.B]], copy) : '';
  return `<!doctype html>\n<html lang="${opts.language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; connect-src 'none'; script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><title>${escapeHtml(copy.title)}</title><style>
  :root{color-scheme:light;--ink:#172033;--muted:#5e6879;--line:#d9dee8;--paper:#fff;--wash:#f4f7fb;--accent:#2457a7}*{box-sizing:border-box}body{margin:0;background:var(--wash);color:var(--ink);font:15px/1.55 system-ui,sans-serif}main{max-width:1240px;margin:auto;padding:32px 20px 64px}header,section,details{background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:20px;margin:16px 0;box-shadow:0 3px 14px #14213d0d}h1{margin:0 0 6px;font-size:clamp(1.8rem,4vw,2.7rem)}h2{font-size:1.25rem;margin:0 0 14px;color:var(--accent)}p{margin:.5rem 0}.eyebrow{font-weight:700;color:var(--accent)}.missing{color:var(--muted);font-style:italic}.table-wrap{overflow-x:auto}table{border-collapse:collapse;width:100%;min-width:560px}.artifact-table{min-width:760px}.issue-table{min-width:1080px}.artifact-table th:nth-child(-n+2),.artifact-table td:nth-child(-n+2),.artifact-table th:nth-child(4),.artifact-table td:nth-child(4),.artifact-table th:nth-child(5),.artifact-table td:nth-child(5),.issue-table th:nth-child(-n+5),.issue-table td:nth-child(-n+5){white-space:nowrap}th,td{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:9px 11px;overflow-wrap:anywhere;white-space:pre-line}th{background:#f8faff;font-weight:650}summary{cursor:pointer;font-weight:700;color:var(--accent)}.notice{border-left:4px solid var(--accent);padding-left:12px}@media(max-width:620px){main{padding:16px 10px 40px}header,section,details{padding:14px;border-radius:8px}}
  </style></head><body><main><header><p class="eyebrow">SYNTHETIC_DEMO</p><h1>${escapeHtml(copy.title)}</h1><p>${escapeHtml(copy.synthetic)}</p><p class="notice">${escapeHtml(copy.boundary)}</p><p>${escapeHtml(copy.negative)}</p></header>
  ${resultTable(copy.m1, 'summary', summary, copy)}
  ${opts.scope === 'm1' ? `<section><h2>${escapeHtml(copy.m2)}</h2><p>${escapeHtml(copy.outOfScope)}</p></section>` : resultTable(copy.m2, 'm2', m2, copy)}
  <section><h2>${escapeHtml(copy.release)}</h2><div class="table-wrap"><table><tbody>${releaseRows}${m2Extra}</tbody></table></div></section>
  <section><h2>${escapeHtml(copy.artifacts)}</h2>${artifactRows ? `<div class="table-wrap"><table class="artifact-table"><thead><tr><th>${escapeHtml(copy.artifactId)}</th><th>${escapeHtml(copy.type)}</th><th>${escapeHtml(copy.name)}</th><th>${escapeHtml(copy.version)}</th><th>${escapeHtml(copy.required)}</th><th>${escapeHtml(copy.checksum)}</th></tr></thead><tbody>${artifactRows}</tbody></table></div>` : `<p class="missing">${escapeHtml(copy.noArtifacts)}</p>`}</section>
  ${opts.scope === 'm2' ? `<section><h2>${escapeHtml(copy.issues)}</h2>${issueRows ? `<div class="table-wrap"><table class="issue-table"><thead><tr><th>${escapeHtml(copy.snapshot)}</th><th>${escapeHtml(copy.issue)}</th><th>${escapeHtml(copy.fixed)}</th><th>${escapeHtml(copy.included)}</th><th>${escapeHtml(copy.verified)}</th><th>${escapeHtml(copy.path)}</th><th>${escapeHtml(copy.gaps)}</th></tr></thead><tbody>${issueRows}</tbody></table></div>` : `<p class="missing">${escapeHtml(copy.noIssues)}</p>`}</section><section><h2>${escapeHtml(copy.history)}</h2><p>${escapeHtml(copy.historyNote)}</p><div class="table-wrap"><table><tbody>${rows([['m2.history.latestSnapshotId','latestSnapshotId',m2?.history?.latestSnapshotId],['m2.history.snapshotABytesStable','snapshotABytesStable',m2?.history?.snapshotABytesStable]],copy)}</tbody></table></div></section>` : ''}
  <section><h2>${escapeHtml(copy.scenarios)}</h2>${scenarioDetails(copy.m1, 'summary', summary, M1_SCENARIOS, copy)}${opts.scope === 'm2' ? scenarioDetails(copy.m2, 'm2', m2, M2_SCENARIOS, copy) : ''}</section>
  </main></body></html>\n`;
}
