import { readFileSync, readdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { App } from 'aws-cdk-lib';
import { AnalysisApiStack } from '../lib/analysis-api-stack';
import { getEnvironmentConfig } from '../lib/environment-config';

const read = (path: string) => readFileSync(path, 'utf8');

describe('cloud deployment workflow', () => {
  const workflow = read('.github/workflows/deploy.yml');

  it('すべての運用shell scriptがbash構文として有効である', () => {
    for (const file of readdirSync('.github/scripts').filter((name) => name.endsWith('.sh'))) {
      expect(() => execFileSync('bash', ['-n', `.github/scripts/${file}`])).not.toThrow();
    }
  });

  it('CI成功済みmain commitをstgからprodへ同一SHAで昇格する', () => {
    expect(workflow).toContain('workflow_run:');
    expect(workflow).toContain('workflows: [CI]');
    expect(workflow).not.toContain('workflow_dispatch:');
    expect(workflow).toContain("github.event.workflow_run.head_branch == 'main'");
    expect(workflow).toContain("github.event.workflow_run.event == 'push'");
    expect(workflow).toContain('github.event.workflow_run.head_repository.full_name == github.repository');
    expect(workflow).toContain('ref: ${{ github.event.workflow_run.head_sha || github.sha }}');
    expect(workflow).toContain('environment: staging');
    expect(workflow).toContain('environment: production');
    expect(workflow).toContain('needs: production-plan');
    expect(workflow).toContain('cdk-promotion-${{ github.event.workflow_run.head_sha || github.sha }}');
    expect(workflow).toContain('bash .github/scripts/verify-promotion.sh');
    expect(workflow).toContain('git rev-parse origin/main');
    expect(workflow).toContain('Display staging diff');
  });

  it('OIDCの短期credentialだけを使いstg smoke後にprod deployする', () => {
    expect(workflow).toContain('id-token: write');
    expect(workflow).toContain('aws-actions/configure-aws-credentials@');
    expect(workflow).not.toContain('AWS_ACCESS_KEY_ID');
    expect(workflow).toContain('bash .github/scripts/smoke-cloud.sh stg');
    expect(workflow).toContain('bash .github/scripts/smoke-cloud.sh prod');
    expect(workflow).toContain('bash .github/scripts/deploy-cloud.sh stg');
    expect(workflow).toContain('bash .github/scripts/deploy-cloud.sh prod');
  });

  it('staging smokeはdeploy roleではなく最小権限operations roleで実行する', () => {
    expect(workflow).toContain('Configure staging operations credentials');
    expect(workflow).toContain('role-to-assume: ${{ vars.AWS_OPERATIONS_ROLE_ARN }}');
    expect(workflow.indexOf('Configure staging operations credentials')).toBeLessThan(workflow.indexOf('Smoke test staging'));
  });

  it('deploy scriptは自動rollbackを無効化できない', () => {
    const script = read('.github/scripts/deploy-cloud.sh');
    expect(script).toContain('--rollback');
    expect(script).not.toContain('--no-rollback');
    expect(script).toContain('--require-approval never');
    expect(script).toContain('bash .github/scripts/verify-aws-account.sh');
    const accountGuard = read('.github/scripts/verify-aws-account.sh');
    expect(accountGuard).toContain('aws sts get-caller-identity');
    expect(accountGuard).toContain('actual_account" == "$AWS_ACCOUNT_ID');
    expect(workflow).toContain('Verify production plan AWS account');
    expect(read('.github/workflows/rollback-release.yml')).toContain('bash .github/scripts/verify-aws-account.sh');
  });

  it('stgの失敗済みAPI stackだけを再deploy前に削除する', () => {
    const script = read('.github/scripts/deploy-cloud.sh');
    expect(script).toContain('ROLLBACK_COMPLETE');
    expect(script).toContain('aws cloudformation delete-stack --stack-name "$api_stack"');
    expect(script).toContain('aws cloudformation wait stack-delete-complete --stack-name "$api_stack"');
    expect(script).toContain('[[ "$environment" == stg ]]');
    expect(script).not.toContain('delete-stack --stack-name "$foundation"');
  });

  it('既存AWS予算通知先を運用通知secretとして再利用する', () => {
    const deployScript = read('.github/scripts/deploy-cloud.sh');
    const rollbackWorkflow = read('.github/workflows/rollback-release.yml');
    expect(workflow).not.toContain('BUDGET_NOTIFICATION_EMAIL');
    expect(rollbackWorkflow).not.toContain('BUDGET_NOTIFICATION_EMAIL');
    expect(deployScript).not.toContain('BUDGET_NOTIFICATION_EMAIL');
    expect(workflow).toContain('OPERATIONS_NOTIFICATION_EMAIL: ${{ secrets.OPERATIONS_NOTIFICATION_EMAIL }}');
    expect(rollbackWorkflow).toContain('OPERATIONS_NOTIFICATION_EMAIL: ${{ secrets.OPERATIONS_NOTIFICATION_EMAIL }}');
    expect(deployScript).toContain('OperationsNotificationEmail=${OPERATIONS_NOTIFICATION_EMAIL}');
  });

  it('promotion assemblyにstg/prod両stackを要求する', () => {
    const script = read('.github/scripts/verify-promotion.sh');
    for (const stack of ['FridgeManagerStgFoundation', 'FridgeManagerStgFoundationAnalysisApi', 'FridgeManagerProdFoundation', 'FridgeManagerProdFoundationAnalysisApi']) {
      expect(script).toContain(stack);
    }
  });

  it('AI停止復旧drillは停止と復旧の双方を検証する', () => {
    const script = read('.github/scripts/drill-ai-control.sh');
    expect(script).toContain('change_and_verify false False');
    expect(script).toContain('change_and_verify true True');
    expect(script).toContain('"{\\"enabled\\":${enabled}');
    expect(script).toContain('aws lambda invoke');
    expect(script).toContain('aws dynamodb get-item');
  });

  it('runbookは主要alarmとrollback・AI復旧を案内する', () => {
    const runbook = read('docs/runbooks/cloud-operations.md');
    for (const alarm of ['CoreAvailabilityAlarm', 'ProviderFailureAlarm', 'LatencyP95Alarm', 'AnalysisApi5xxAlarm', 'AuthorizerLambdaErrorAlarm', 'AnalysisLambdaErrorAlarm', 'AnalysisLambdaThrottleAlarm', 'BudgetStopDlqAlarm']) {
      expect(runbook).toContain(alarm);
    }
    expect(runbook).toContain('CloudFormation');
    expect(runbook).toContain('drill-ai-control.sh');
    expect(read('.github/workflows/rollback-release.yml')).toContain('bash .github/scripts/fetch-release.sh');
  });

  it('production API stackだけtermination protectionを有効化する', () => {
    expect(new AnalysisApiStack(new App(), 'DevProtection', { config: getEnvironmentConfig('dev') }).terminationProtection).toBe(false);
    expect(new AnalysisApiStack(new App(), 'StgProtection', { config: getEnvironmentConfig('stg') }).terminationProtection).toBe(false);
    expect(new AnalysisApiStack(new App(), 'ProdProtection', { config: getEnvironmentConfig('prod') }).terminationProtection).toBe(true);
  });
});
