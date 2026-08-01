import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const sourceRoot = 'app/build/outputs/androidTest-results/connected';
const outputFile = '.compatibility-evidence/summary.json';

function xmlFiles(directory) {
  let entries;
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries.flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? xmlFiles(path) : entry.name.endsWith('.xml') ? [path] : [];
  });
}

function attribute(text, name) {
  return new RegExp(`${name}="([^"]*)"`).exec(text)?.[1] ?? null;
}

const suites = xmlFiles(sourceRoot).flatMap((path) => {
  const xml = readFileSync(path, 'utf8');
  const suiteTag = /<testsuite\b[^>]*>/.exec(xml)?.[0];
  if (!suiteTag) return [];
  const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)]
    .map(([, attributes, body = '']) => ({
      className: attribute(attributes, 'classname'),
      name: attribute(attributes, 'name'),
      status: body.includes('<failure')
        ? 'failed'
        : body.includes('<error')
          ? 'error'
          : body.includes('<skipped')
            ? 'skipped'
            : 'passed',
    }));
  return [{
    name: attribute(suiteTag, 'name'),
    tests: Number(attribute(suiteTag, 'tests') ?? 0),
    failures: Number(attribute(suiteTag, 'failures') ?? 0),
    errors: Number(attribute(suiteTag, 'errors') ?? 0),
    skipped: Number(attribute(suiteTag, 'skipped') ?? 0),
    cases,
  }];
});

if (suites.length === 0) {
  console.error('Android instrumentation XML results were not generated');
  process.exit(1);
}

mkdirSync('.compatibility-evidence', { recursive: true });
writeFileSync(outputFile, `${JSON.stringify({ schemaVersion: 1, suites }, null, 2)}\n`, {
  encoding: 'utf8',
  mode: 0o600,
});
console.log(`Wrote ${suites.length} privacy-safe suite summaries`);
