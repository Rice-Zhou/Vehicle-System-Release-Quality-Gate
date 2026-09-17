import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { createHash } from 'node:crypto';

const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const catalogPath = 'contracts/facts/v0.2/fact-catalog-v2.json';
const schemaPath = 'schemas/v0.2/quality-evaluation.schema.json';

test('version 2 machine contracts exist without replacing version 1', () => {
  assert.equal(read('contracts/facts/v0.2/fact-catalog.json').version, 1);
  assert.ok(fs.existsSync(catalogPath), 'catalog v2 must be delivered');
  assert.ok(fs.existsSync(schemaPath), 'typed quality contracts must be delivered');
});

test('v1 catalog and schema retain the exact baseline contract bytes modulo checkout EOL', () => {
  for (const [file, hash] of [
    ['contracts/facts/v0.2/fact-catalog.json', 'a117866d8148dc2ab4fe7feda6363cca4ed405e95bc892b0ee4e87b29e96f08e'],
    ['schemas/v0.2/fact-catalog.schema.json', '555380b49df0c54192424d28a055cceab3897ab725b6efea59b75a5468591ee2']
  ]) {
    // Git/Windows checkout may change CRLF to LF; no other bytes may change.
    const bytes = fs.readFileSync(file, 'utf8').replaceAll('\r\n', '\n');
    assert.equal(createHash('sha256').update(bytes).digest('hex'), hash);
  }
});

test('version 2 catalog declares scoped typed aliases and enum confidence', async () => {
  assert.ok(fs.existsSync(catalogPath), 'catalog v2 must be delivered');
  const catalog = read(catalogPath);
  const { validateCatalogBindings, resolveItemBinding } = await import('../quality-contract-validation.mjs');
  assert.deepEqual(validateCatalogBindings(catalog), []);
  assert.equal(resolveItemBinding(catalog, 'testResults[]', 'item.status').path, 'testResults[].status');
  assert.equal(resolveItemBinding(catalog, 'issues[]', 'item.required').type, 'BOOLEAN');
  assert.throws(() => resolveItemBinding(catalog, 'issues[]', 'item.status'), /binding/);
  assert.throws(() => resolveItemBinding(catalog, 'testResults[]', 'item.required'), /binding/);
  const bad = structuredClone(catalog);
  bad.itemBindings[0].factPath = 'issues[].verified';
  assert.notDeepEqual(validateCatalogBindings(bad), []);
  const order = structuredClone(catalog);
  order.facts.find(f => f.path === 'issues[]').stableOrder = ['unknown'];
  assert.notDeepEqual(validateCatalogBindings(order), []);
});

test('catalog schemas reject unknown fields and ill-typed enum declarations', () => {
  const ajv = new Ajv2020({ strict: true, strictRequired: false });
  const v1 = ajv.compile(read('schemas/v0.2/fact-catalog.schema.json'));
  const v2 = ajv.compile(read('schemas/v0.2/fact-catalog-v2.schema.json'));
  const catalog = read(catalogPath);
  assert.ok(v2(catalog));
  assert.equal(v1(catalog), false);
  for (const patch of [{version:1},{itemBindings:[]},{unknown:true}]) assert.equal(v2({...catalog,...patch}),false);
  const bad = structuredClone(catalog);
  bad.facts.find(f => f.enumValues).type = 'DECIMAL';
  assert.equal(v2(bad),false);
  const snapshotSchema = read(schemaPath);
  for (const [path, dto] of [
    ['traceability.minimumConfidenceLevel', snapshotSchema.$defs.facts.properties.traceability.properties.minimumConfidenceLevel],
    ['testResults[].status', snapshotSchema.$defs.testResultFact.properties.status]
  ]) assert.deepEqual(dto.enum, catalog.facts.find(f=>f.path===path).enumValues);
  assert.ok(snapshotSchema.$defs.testResultFact.properties.status.enum.includes('TIMEOUT'));
});

test('typed schemas reject missing, unknown, invalid versions and forged inputs', () => {
  assert.ok(fs.existsSync(schemaPath), 'typed quality contracts must be delivered');
  const ajv = new Ajv2020({ strict: true, strictRequired: false, allErrors: true });
  addFormats(ajv);
  ajv.addSchema(read('schemas/v0.2/quality-rule.schema.json'));
  ajv.addSchema(read(schemaPath));
  const schema = read(schemaPath);
  const validate = name => ajv.compile({ $ref: `${schema.$id}#/$defs/${name}` });
  for (const [name, file] of [['ruleSetRequest','rule-set'],['evaluationRequest','request'],['evaluation','completed'],['evaluation','error'],['inputSnapshot','snapshot']]) {
    const value = read(`contracts/examples/v0.2/quality-evaluation/${file}.json`);
    const check = validate(name);
    assert.ok(check(value), `${file}: ${ajv.errorsText(check.errors)}`);
    for (const key of Object.keys(value)) {
      const missing = structuredClone(value); delete missing[key];
      assert.equal(check(missing), false, `${file} must require ${key}`);
    }
    assert.equal(check({ ...value, unknown: true }), false);
  }
  const rule = read('contracts/examples/v0.2/quality-evaluation/rule-set.json');
  for (const patch of [{selectedCaseRefs:[]},{requiredIssueRefs:null},{catalogVersion:1},{engineVersion:''},{project:''},{requiredIssueRefs:[{source:'demo',sourceIssueId:'a',required:true}]}]) {
    assert.equal(validate('ruleSetRequest')({...rule,...patch}), false, JSON.stringify(patch));
  }
  const request = read('contracts/examples/v0.2/quality-evaluation/request.json');
  assert.equal(validate('evaluationRequest')({...request,testRunIds:['x'.repeat(129)]}), false);
  assert.equal(validate('evaluationRequest')({...request,traceabilitySnapshotId:'x'.repeat(129)}), false);
  assert.equal(validate('ruleSetRequest')({...rule,ruleSetId:'x'.repeat(129)}), false);
  assert.equal(validate('evaluationRequest')({...request,facts:{}}), false);
  assert.equal(validate('evaluationRequest')({...request,testRunIds:['run-a','run-b']}), false);
  const snapshot = read('contracts/examples/v0.2/quality-evaluation/snapshot.json');
  snapshot.facts.traceability.minimumConfidenceLevel = 0.5;
  assert.equal(validate('inputSnapshot')(snapshot), false);
  const error = read('contracts/examples/v0.2/quality-evaluation/error.json');
  assert.equal(validate('evaluation')({...error,qualityResult:{action:'PASS'}}), false);
  const completed = read('contracts/examples/v0.2/quality-evaluation/completed.json');
  completed.qualityResult.ruleResults[0].status = 'ERROR';
  assert.equal(validate('evaluation')(completed), false, 'ERROR cannot produce Quality Result');
  const complete = read('contracts/examples/v0.2/quality-evaluation/snapshot.json');
  const visit = (value, path = []) => {
    if (!value || typeof value !== 'object') return;
    if (Array.isArray(value)) { value.forEach((item,index)=>visit(item,[...path,index])); return; }
    for (const key of Object.keys(value)) {
      const mutated = structuredClone(complete);
      delete path.reduce((node,part)=>node[part],mutated)[key];
      assert.equal(validate('inputSnapshot')(mutated), false, `required snapshot ${[...path,key].join('.')}`);
      visit(value[key],[...path,key]);
    }
    const mutated = structuredClone(complete);
    path.reduce((node,part)=>node[part],mutated).unknown = true;
    assert.equal(validate('inputSnapshot')(mutated), false, `unknown snapshot ${path.join('.')}`);
  };
  visit(complete);
});

test('API binds requests and responses to the shared quality schema', () => {
  const api = read('contracts/openapi/v0.2/openapi.json');
  assert.match(api.components.schemas.RuleSetRequest.$ref ?? '', /quality-evaluation.*ruleSetRequest$/);
  assert.match(api.components.schemas.QualityEvaluationRequest.$ref ?? '', /quality-evaluation.*evaluationRequest$/);
  assert.equal(api.paths['/api/v1/releases/{releaseId}/quality-evaluations'].post.responses['202'].$ref, '#/components/responses/QualityEvaluationAccepted');
  assert.equal(api.paths['/api/v1/releases/{releaseId}/quality-results'].get.responses['200'].$ref, '#/components/responses/QualityEvaluationHistory');
  for (const [name,definition] of [['QualityEvaluationAccepted','pendingEvaluation'],['QualityEvaluationHistory','evaluationHistory'],['QualityRuleSet','ruleSet']]) {
    const response = api.components.responses[name];
    assert.equal(response.content['application/json'].schema.$ref, `../../../schemas/v0.2/quality-evaluation.schema.json#/$defs/${definition}`);
    assert.equal(response.headers?.['X-Request-Id']?.$ref, '#/components/headers/RequestId');
  }
});

test('evaluation errors preserve executed rule outcomes and exact numeric explanations', () => {
  const ajv = new Ajv2020({ strict: true, strictRequired: false, allErrors: true });
  addFormats(ajv);
  ajv.addSchema(read('schemas/v0.2/quality-rule.schema.json'));
  const schema = read(schemaPath);
  ajv.addSchema(schema);
  const validate = ajv.compile({ $ref: `${schema.$id}#/$defs/evaluation` });
  const error = read('contracts/examples/v0.2/quality-evaluation/error.json');
  const completed = read('contracts/examples/v0.2/quality-evaluation/completed.json');
  const failedRule = { ...completed.qualityResult.ruleResults[0], status: 'ERROR' };
  const executed = { ...error, inputSnapshot: completed.inputSnapshot, ruleResults: [failedRule] };
  assert.ok(validate(executed), ajv.errorsText(validate.errors));
  assert.equal(validate({ ...executed, qualityResult: completed.qualityResult }), false);
  assert.equal(validate({ ...executed, ruleResults: Array.from({length:33},(_,i)=>({...failedRule,ruleId:`RULE_${i}`})) }),false);
  for (const value of [['INTEGER','0'], ['INTEGER','12'], ['INTEGER','-1'], ['INTEGER','9007199254740993'], ['DECIMAL','1.23'], ['DECIMAL','0'], ['DECIMAL','-0.01']]) {
    const numeric = structuredClone(executed);
    numeric.ruleResults[0].matchedFacts = [{path:'testResults[].attemptNo',value}];
    numeric.ruleResults[0].explanation.parameters.count = value;
    assert.ok(validate(numeric), `${JSON.stringify(value)}: ${ajv.errorsText(validate.errors)}`);
  }
  for (const value of [0, 12, Number.MAX_SAFE_INTEGER, 0.5, Number.MAX_SAFE_INTEGER+1, ['INTEGER','01'], ['INTEGER','-0'], ['INTEGER','1.0'], ['DECIMAL','1.0'], ['DECIMAL','1e3'], ['DECIMAL','-0'], ['DECIMAL','01.2'], ['INTEGER','1'.repeat(4097)], {type:'INTEGER',value:'1'}]) {
    const numeric = structuredClone(executed);
    numeric.ruleResults[0].matchedFacts = [{path:'testResults[].attemptNo',value}];
    assert.equal(validate(numeric), false, JSON.stringify(value));
    numeric.ruleResults[0].matchedFacts = [];
    numeric.ruleResults[0].explanation.parameters.count = value;
    assert.equal(validate(numeric), false, `parameter ${JSON.stringify(value)}`);
  }
});
