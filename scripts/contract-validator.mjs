import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import SwaggerParser from "@apidevtools/swagger-parser";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parseDocument } from "yaml";

const root = process.cwd();
const readJson = async (relativePath) => JSON.parse(await fs.readFile(path.join(root, relativePath), "utf8"));
const readText = async (relativePath) => fs.readFile(path.join(root, relativePath), "utf8");

const schemaPaths = {
  agent: "schemas/v0.2/agent-protocol.schema.json",
  qualityRule: "schemas/v0.2/quality-rule.schema.json",
  factCatalog: "schemas/v0.2/fact-catalog.schema.json",
  manifest: "schemas/v0.2/release-manifest.schema.json"
};

// Cross-subschema required/properties composition is valid JSON Schema 2020-12.
// Ajv's strictRequired lint cannot resolve those declarations through $ref/allOf.
const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
addFormats(ajv);

const validators = {};
for (const [name, schemaPath] of Object.entries(schemaPaths)) {
  validators[name] = ajv.compile(await readJson(schemaPath));
}

const registry = await readJson("contracts/examples/v0.2/validation-cases.json");
let positiveCount = 0;
let negativeCount = 0;

for (const testCase of registry.cases) {
  const source = await readText(testCase.document);
  let value;
  if (testCase.format === "yaml") {
    const document = parseDocument(source, { uniqueKeys: true, strict: true });
    if (document.errors.length > 0) {
      if (testCase.valid) {
        throw new Error(`${testCase.id}: YAML parse failed: ${document.errors.join("; ")}`);
      }
      negativeCount += 1;
      continue;
    }
    value = document.toJS();
  } else {
    value = JSON.parse(source);
  }

  const validate = validators[testCase.schema];
  if (!validate) {
    throw new Error(`${testCase.id}: unknown schema ${testCase.schema}`);
  }

  const accepted = validate(value);
  if (accepted !== testCase.valid) {
    throw new Error(`${testCase.id}: expected valid=${testCase.valid}, got ${accepted}; ${ajv.errorsText(validate.errors)}`);
  }

  if (testCase.valid) positiveCount += 1;
  else negativeCount += 1;
}

const catalog = await readJson("contracts/facts/v0.2/fact-catalog.json");
if (!validators.factCatalog(catalog)) {
  throw new Error(`Fact Catalog invalid: ${ajv.errorsText(validators.factCatalog.errors)}`);
}

const openapiPath = path.join(root, "contracts/openapi/v0.2/openapi.json");
const openapi = await SwaggerParser.validate(openapiPath);
const openapiSource = await readJson("contracts/openapi/v0.2/openapi.json");
if (!String(openapi.openapi).startsWith("3.1.")) {
  throw new Error(`Expected OpenAPI 3.1, got ${openapi.openapi}`);
}

const methodPattern = /^\|\s*(GET|POST|PUT|PATCH|DELETE)\s*\|\s*`([^`]+)`\s*\|/gm;
const documentedOperations = new Set();
for (const documentPath of ["docs/v0.2/03-api-design.md", "docs/v0.2/08-test-agent-protocol.md"]) {
  const text = await readText(documentPath);
  for (const match of text.matchAll(methodPattern)) {
    const fullPath = match[2].startsWith("/agent-api/") || match[2].startsWith("/api/")
      ? match[2]
      : `/api/v1${match[2]}`;
    documentedOperations.add(`${match[1].toLowerCase()} ${fullPath}`);
  }
}

const contractOperations = new Set();
for (const [contractPath, pathItem] of Object.entries(openapiSource.paths)) {
  for (const method of ["get", "post", "put", "patch", "delete"]) {
    if (pathItem[method]) contractOperations.add(`${method} ${contractPath}`);
  }
}

const missing = [...documentedOperations].filter((operation) => !contractOperations.has(operation));
const undocumented = [...contractOperations].filter((operation) => !documentedOperations.has(operation));
if (missing.length || undocumented.length) {
  throw new Error(`OpenAPI/document endpoint mismatch; missing=${missing.join(",") || "none"}; undocumented=${undocumented.join(",") || "none"}`);
}

const compatibilityBaseline = await readJson("contracts/openapi/v0.2/compatibility-baseline.json");
for (const baseline of compatibilityBaseline.operations) {
  const operation = openapiSource.paths[baseline.path]?.[baseline.method];
  if (!operation) {
    throw new Error(`Breaking OpenAPI change: removed ${baseline.method} ${baseline.path}`);
  }
  if (operation["x-permission"] !== baseline.permission) {
    throw new Error(`Breaking OpenAPI change: permission changed for ${baseline.method} ${baseline.path}`);
  }
  const idempotencyRequired = operation["x-idempotency-required"] === true;
  if (idempotencyRequired !== baseline.idempotencyRequired) {
    throw new Error(`Breaking OpenAPI change: idempotency changed for ${baseline.method} ${baseline.path}`);
  }
  const requestBodyRef = operation.requestBody?.$ref;
  if ((baseline.requestBodyRef ?? undefined) !== requestBodyRef) {
    throw new Error(`Breaking OpenAPI change: request contract changed for ${baseline.method} ${baseline.path}`);
  }
}

for (const [contractPath, pathItem] of Object.entries(openapiSource.paths)) {
  const agentPath = contractPath.startsWith("/agent-api/v1/");
  const corePath = contractPath.startsWith("/api/v1/");
  if (!agentPath && !corePath) {
    throw new Error(`Unversioned or unsupported path: ${contractPath}`);
  }
  for (const method of ["get", "post", "put", "patch", "delete"]) {
    const operation = pathItem[method];
    if (!operation) continue;
    if (!operation["x-permission"]) throw new Error(`${method} ${contractPath}: x-permission is required`);
    if (["post", "put", "patch", "delete"].includes(method) && operation["x-idempotency-required"] !== true) {
      throw new Error(`${method} ${contractPath}: x-idempotency-required must be true`);
    }
  }
}

console.log(`PASS contracts schemas=${Object.keys(schemaPaths).length} positive=${positiveCount} negative=${negativeCount} operations=${contractOperations.size}`);
