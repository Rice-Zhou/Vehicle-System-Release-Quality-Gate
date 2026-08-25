import { createHash } from "node:crypto";
import process from "node:process";
import canonicalize from "canonicalize";

const readStdin = async () => {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf8");
};

const source = process.argv[2] ?? await readStdin();
if (source.length === 0) throw new Error("JSON input is required as one argument or UTF-8 stdin");

const canonical = canonicalize(JSON.parse(source));
if (canonical === undefined) {
  throw new Error("Input is not a canonicalizable JSON value");
}
const bytes = Buffer.from(canonical, "utf8");
process.stdout.write(`sha256:${createHash("sha256").update(bytes).digest("hex")}`);
