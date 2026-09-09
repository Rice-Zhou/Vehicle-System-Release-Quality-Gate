import { lstat, open, unlink } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderDemoReport, ReportInputError } from './demo-report.mjs';

const MAX_INPUT_BYTES = 1024 * 1024;

class ReportBoundaryError extends Error {
  constructor(code) { super(code); this.code = code; }
}

const inputError = () => { throw new ReportBoundaryError('REPORT_INPUT_INVALID'); };

async function readJsonObject(path, required) {
  let pathStat;
  try { pathStat = await lstat(path); }
  catch (error) { if (!required && error.code === 'ENOENT') return null; inputError(); }
  if (!pathStat.isFile() || pathStat.isSymbolicLink() || pathStat.size > MAX_INPUT_BYTES) inputError();
  let handle; let bytes;
  try {
    handle = await open(path, 'r');
    const fileStat = await handle.stat();
    if (!fileStat.isFile() || fileStat.size > MAX_INPUT_BYTES) inputError();
    const buffer = Buffer.alloc(MAX_INPUT_BYTES + 1);
    let length = 0;
    while (length < buffer.length) {
      const { bytesRead } = await handle.read(buffer, length, buffer.length - length, null);
      if (bytesRead === 0) break;
      length += bytesRead;
    }
    if (length > MAX_INPUT_BYTES) inputError();
    bytes = buffer.subarray(0, length);
  } catch { inputError(); }
  finally { if (handle) { try { await handle.close(); } catch { inputError(); } } }
  let text;
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { inputError(); }
  let value;
  try { value = JSON.parse(text); } catch { inputError(); }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) inputError();
  return value;
}

export async function generateReport({ runDirectory, outputFile, scope, language }) {
  if (typeof runDirectory !== 'string' || typeof outputFile !== 'string' || !['m1', 'm2'].includes(scope) || !['zh', 'en'].includes(language)) inputError();
  const directory = resolve(runDirectory); const output = resolve(outputFile);
  const inputPaths = ['summary.json', 'manifest.json', ...(scope === 'm2' ? ['m2-summary.json'] : [])].map(name => resolve(directory, name));
  if (inputPaths.includes(output)) throw new ReportBoundaryError('REPORT_OUTPUT_FAILED');
  const summary = await readJsonObject(inputPaths[0], true);
  const manifest = await readJsonObject(inputPaths[1], false);
  const m2 = scope === 'm2' ? await readJsonObject(inputPaths[2], false) : null;
  const html = renderDemoReport({ summary, manifest, m2 }, { scope, language });
  let handle;
  try {
    const parent = await lstat(dirname(output));
    if (!parent.isDirectory() || parent.isSymbolicLink()) throw new Error('parent');
    handle = await open(output, 'wx');
    await handle.writeFile(html, 'utf8');
    await handle.sync();
    await handle.close(); handle = null;
  } catch {
    if (handle) { try { await handle.close(); } catch {} try { await unlink(output); } catch {} }
    throw new ReportBoundaryError('REPORT_OUTPUT_FAILED');
  }
}

function parseArguments(args) {
  const allowed = new Set(['--run-dir', '--output', '--scope', '--language']);
  if (args.length !== 8) throw new ReportBoundaryError('REPORT_ARGUMENT_INVALID');
  const parsed = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index]; const value = args[index + 1];
    if (!allowed.has(key) || typeof value !== 'string' || value.length === 0 || value.startsWith('--') || present(parsed, key)) throw new ReportBoundaryError('REPORT_ARGUMENT_INVALID');
    parsed[key] = value;
  }
  if (Object.keys(parsed).length !== 4 || !['m1', 'm2'].includes(parsed['--scope']) || !['zh', 'en'].includes(parsed['--language'])) throw new ReportBoundaryError('REPORT_ARGUMENT_INVALID');
  return { runDirectory: parsed['--run-dir'], outputFile: parsed['--output'], scope: parsed['--scope'], language: parsed['--language'] };
}

const present = (source, key) => Object.prototype.hasOwnProperty.call(source, key);
const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  try { await generateReport(parseArguments(process.argv.slice(2))); process.stdout.write('REPORT_RENDERED\n'); }
  catch (error) {
    const code = error instanceof ReportInputError || error instanceof ReportBoundaryError ? error.code : 'REPORT_OUTPUT_FAILED';
    process.stderr.write(`${code}\n`); process.exitCode = 1;
  }
}
