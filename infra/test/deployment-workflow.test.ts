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
    expect(script).toContain('ACM_CERTIFICATE_ARN');
    expect(script).toContain('AopTruststoreBucketName');
    expect(script).toContain('AopTruststoreVersion');
    expect(script).toContain('npx cdk deploy "$foundation"');
    expect(script.indexOf('if [[ "$environment" == stg ]]')).toBeLessThan(script.indexOf('npx cdk deploy "$api_stack"'));
    expect(script).toContain('--parameters "${api_stack}:AcmCertificateArn=${ACM_CERTIFICATE_ARN}"');
    expect(script).toContain('--parameters "${api_stack}:AopTruststoreVersion=${aop_truststore_version}"');
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

  it('AOP証明書の生成workflowはGitHub secretを表示せず環境別に実行する', () => {
    const workflow = read('.github/workflows/provision-cloudflare-aop.yml');
    expect(workflow).toContain('workflow_dispatch:');
    expect(workflow).toContain('options: [stg, prod]');
    expect(workflow).toContain("inputs.target == 'prod' && 'production' || 'staging'");
    expect(workflow).toContain('CLOUDFLARE_AOP_TOKEN: ${{ secrets.CLOUDFLARE_AOP_TOKEN }}');
    expect(workflow).toContain('contents: read');
    expect(workflow).toContain('id-token: write');
    expect(workflow).toContain('AWS_ACCOUNT_ID: ${{ vars.AWS_ACCOUNT_ID }}');
    expect(workflow).not.toContain('set -x');
    expect(workflow).toContain('bash .github/scripts/provision-cloudflare-aop.sh');
    const script = read('.github/scripts/provision-cloudflare-aop.sh');
    expect(script).toContain('openssl rand -hex 12');
    expect(script).toContain('ca_common_name=');
    expect(script).toContain('bash .github/scripts/verify-aws-account.sh');
    expect(script).toContain('openssl x509 -req -sha256 -days 89');
    expect(script).toContain("-addext 'basicConstraints=critical,CA:TRUE,pathlen:0'");
    expect(script).not.toContain('ca-ext.cnf');
    expect(script).toContain('Cloudflare AOP certificate upload failed:');
    expect(script).toContain("jq -c '{success, errors: (.errors | map({code, message}))}'");
    expect(script).toContain('if ! certificate_id="$(jq -er');
    expect(script).not.toContain('origin_tls_client_auth/hostnames"');
    expect(script).not.toContain('--sse AES256');
  });

  it('AOP有効化はAPI GatewayのmTLS配備確認後にのみ実行する', () => {
    const workflow = read('.github/workflows/activate-cloudflare-aop.yml');
    const script = read('.github/scripts/activate-cloudflare-aop.sh');
    expect(workflow).toContain('workflow_dispatch:');
    expect(workflow).toContain("inputs.target == 'prod' && 'production' || 'staging'");
    expect(workflow).toContain('CLOUDFLARE_AOP_TOKEN: ${{ secrets.CLOUDFLARE_AOP_TOKEN }}');
    expect(script).toContain('aws apigateway get-domain-name');
    expect(script).toContain('truststoreVersion');
    expect(script).toContain('pending-manifest.json');
    expect(script).toContain('active-manifest.json');
    expect(script).toContain('cert_status == "active"');
    expect(script).toContain('Cloudflare AOP certificate did not become active before timeout');
    expect(script).toContain('origin_tls_client_auth/hostnames');
    expect(script).toContain('select(.success == true)');
  });

  it('Cloudflare設定はプロキシDNSと二つのhostnameを分離した単一Rate Limiting ruleを適用する', () => {
    const workflow = read('.github/workflows/configure-cloudflare-api.yml');
    const script = read('.github/scripts/configure-cloudflare-api.sh');
    expect(workflow).toContain('CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}');
    expect(workflow).toContain('CLOUDFLARE_AOP_TOKEN: ${{ secrets.CLOUDFLARE_AOP_TOKEN }}');
    expect(workflow).toContain("inputs.target == 'prod' && 'production' || 'staging'");
    expect(script).toContain('proxied:true');
    expect(script).toContain('http_ratelimit');
    expect(script).toContain('requests_per_period: 10');
    expect(script).toContain('characteristics: ["ip.src", "http.host", "cf.colo.id"]');
    expect(script).toContain('action: "block"');
    expect(script).toContain('action_parameters');
    expect(script).toContain('/rulesets/${ruleset_id}/rules');
    expect(script).toContain('rules: $rule}');
    expect(script).toContain('hostname AOP is not active');
    expect(script).toContain('/settings/ssl');
    expect(script).toContain('result.value == "strict"');
    expect(script).toContain('fridge-manager-stg.wackwack.net');
    expect(script).toContain('fridge-manager.wackwack.net');
    expect(script).toContain('Cloudflare configuration failed near script line');
  });

  it('初回bootstrapはFoundation、AOP truststore、API Gateway custom domainの順に配備する', () => {
    const workflow = read('.github/workflows/bootstrap-cloudflare-api.yml');
    expect(workflow).toContain('workflow_dispatch:');
    expect(workflow).toContain('npx cdk deploy "$FOUNDATION_STACK"');
    expect(workflow).toContain('bash .github/scripts/provision-cloudflare-aop.sh');
    expect(workflow).toContain('bash .github/scripts/deploy-cloud.sh "${{ inputs.target }}" cdk.out');
    expect(workflow).toContain('AOP_TRUSTSTORE_PHASE: pending');
    expect(workflow).toContain('bash .github/scripts/activate-cloudflare-aop.sh');
    expect(workflow).toContain('bash .github/scripts/configure-cloudflare-api.sh');
    expect(workflow).toContain('bash .github/scripts/smoke-cloud.sh "${{ inputs.target }}"');
    expect(workflow).toContain('ACM_CERTIFICATE_ARN: ${{ vars.ACM_CERTIFICATE_ARN }}');
  });
});
