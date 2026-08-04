import { readFileSync } from 'node:fs';

const allowedAdvisories = ['https://github.com/advisories/GHSA-mh99-v99m-4gvg','https://github.com/advisories/GHSA-rgw5-rvv9-x895'];
const allowedNode = 'node_modules/aws-cdk-lib/node_modules/brace-expansion';
const exceptionExpiresAt = Date.parse('2026-08-15T00:00:00Z');

function fail(message) {
  console.error(`npm audit rejected: ${message}`);
  process.exit(1);
}

let report;
try {
  report = JSON.parse(readFileSync(0, 'utf8'));
} catch {
  fail('監査結果が有効なJSONではありません');
}

if (report?.auditReportVersion !== 2 || typeof report?.vulnerabilities !== 'object' || report.vulnerabilities === null) {
  fail('npm audit v2形式ではありません');
}

const vulnerabilities = Object.values(report.vulnerabilities).filter(
  (vulnerability) => vulnerability?.severity === 'high' || vulnerability?.severity === 'critical',
);
if (vulnerabilities.length === 0) {
  console.log('npm audit: High/Critical脆弱性なし');
  process.exit(0);
}

if (vulnerabilities.length !== 1) {
  fail(`期限付き例外以外の脆弱性を検出しました（${vulnerabilities.length}件）`);
}

const vulnerability = vulnerabilities[0];
const advisories = Array.isArray(vulnerability.via)
  ? vulnerability.via.filter((entry) => typeof entry === 'object' && entry !== null)
  : [];
const nodes = Array.isArray(vulnerability.nodes) ? vulnerability.nodes : [];

const isAllowed =
  vulnerability.name === 'brace-expansion' &&
  vulnerability.severity === 'high' &&
  advisories.every(advisory=>allowedAdvisories.includes(advisory.url)) &&
  advisories.every(advisory=>advisory.severity === 'high') &&
  nodes.length === 1 &&
  nodes[0] === allowedNode;

if (!isAllowed) {
  fail('期限付き例外のGHSAまたは依存経路と一致しません');
}

if (Date.now() >= exceptionExpiresAt) {
  fail('Issue #88の期限付き例外が2026-08-15に失効しました');
}

console.log(`期限付きdev/stg例外（2026-08-15失効）: ${allowedAdvisory} via ${allowedNode}`);
