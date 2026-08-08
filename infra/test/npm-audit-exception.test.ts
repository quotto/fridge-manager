import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const checker = join(process.cwd(), '.github/scripts/check-npm-audit.mjs');

function run(report: unknown) {
  return spawnSync(process.execPath, [checker], {
    input: JSON.stringify(report),
    encoding: 'utf8',
  });
}

function report(vulnerabilities: Record<string, unknown>) {
  return { auditReportVersion: 2, vulnerabilities };
}

describe('npm auditの期限付き例外', () => {
  it('aws-cdk-lib同梱の対象GHSAだけを許容する', () => {
    const result = run(report({
      'brace-expansion': {
        name: 'brace-expansion', severity: 'high',
        via: [{ url: 'https://github.com/advisories/GHSA-mh99-v99m-4gvg', severity: 'high' }],
        nodes: ['node_modules/aws-cdk-lib/node_modules/brace-expansion'],
      },
    }));

    expect(result.status).toBe(0);
    expect(result.stdout).toContain('GHSA-mh99-v99m-4gvg');
    expect(result.stdout).toContain('2026-08-15失効');
  });

  it('同じGHSAでもaws-cdk-lib外の依存経路は拒否する', () => {
    const result = run(report({
      'brace-expansion': {
        name: 'brace-expansion', severity: 'high',
        via: [{ url: 'https://github.com/advisories/GHSA-mh99-v99m-4gvg', severity: 'high' }],
        nodes: ['node_modules/brace-expansion'],
      },
    }));

    expect(result.status).toBe(1);
  });

  it.each([
    ['空のvia', []],
    ['文字列だけのvia', ['brace-expansion']],
  ])('root advisoryの%sをfail closedで拒否する', (_label, via) => {
    const result = run(report({
      'brace-expansion': {
        name: 'brace-expansion', severity: 'high', via,
        nodes: ['node_modules/aws-cdk-lib/node_modules/brace-expansion'],
      },
    }));

    expect(result.status).toBe(1);
  });

  it('npm auditが同じroot advisoryから派生表示する親packageを許容する', () => {
    const result = run(report({
      'brace-expansion': {
        name: 'brace-expansion', severity: 'high',
        via: [{ url: 'https://github.com/advisories/GHSA-mh99-v99m-4gvg', severity: 'high' }],
        nodes: ['node_modules/aws-cdk-lib/node_modules/brace-expansion'],
      },
      minimatch: {
        name: 'minimatch', severity: 'high', via: ['brace-expansion'], nodes: ['node_modules/minimatch'],
      },
      'aws-cdk-lib': {
        name: 'aws-cdk-lib', severity: 'high', via: ['minimatch'], nodes: ['node_modules/aws-cdk-lib'],
      },
    }));

    expect(result.status).toBe(0);
  });

  it('別のHighまたはCritical脆弱性があれば拒否する', () => {
    const result = run(report({
      lodash: {
        name: 'lodash', severity: 'critical',
        via: [{ url: 'https://github.com/advisories/GHSA-other', severity: 'critical' }],
        nodes: ['node_modules/lodash'],
      },
    }));

    expect(result.status).toBe(1);
  });

  it('low/moderateだけの脆弱性はHigh/Criticalゲートの対象外とする', () => {
    const result = run(report({
      example: {
        name: 'example', severity: 'moderate',
        via: [{ url: 'https://github.com/advisories/GHSA-moderate', severity: 'moderate' }],
        nodes: ['node_modules/example'],
      },
    }));

    expect(result.status).toBe(0);
  });

  it('対象GHSAでも複数の依存経路があれば拒否する', () => {
    const result = run(report({
      'brace-expansion': {
        name: 'brace-expansion', severity: 'high',
        via: [{ url: 'https://github.com/advisories/GHSA-mh99-v99m-4gvg', severity: 'high' }],
        nodes: [
          'node_modules/aws-cdk-lib/node_modules/brace-expansion',
          'node_modules/brace-expansion',
        ],
      },
    }));

    expect(result.status).toBe(1);
  });

  it('脆弱性がなければ成功する', () => {
    expect(run(report({})).status).toBe(0);
  });

  it('Security workflowは監査JSONを限定checkerへ渡す', () => {
    const workflow = readFileSync('.github/workflows/security.yml', 'utf8');
    expect(workflow).toContain('npm audit --audit-level=high --ignore-scripts --json');
    expect(workflow).toContain('node .github/scripts/check-npm-audit.mjs');
  });

  it('例外checkerの変更はCODEOWNERレビュー対象とする', () => {
    const codeowners = readFileSync('.github/CODEOWNERS', 'utf8');
    expect(codeowners).toContain('/.github/scripts/check-npm-audit.mjs @quotto');
    expect(codeowners).toContain('/.github/workflows/security.yml @quotto');
  });

  it('production昇格は明示的な有効化なしでは開始しない', () => {
    const workflow = readFileSync('.github/workflows/deploy.yml', 'utf8');
    expect(workflow.match(/if: vars\.PRODUCTION_DEPLOY_ENABLED == 'true'/g)).toHaveLength(2);
    expect(workflow).toContain('Reject every production high-severity vulnerability');
    expect(workflow).toContain('npm audit --audit-level=high --ignore-scripts');
  });
});
