import { App } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { AnalysisApiStack } from '../lib/analysis-api-stack';
import { getEnvironmentConfig } from '../lib/environment-config';

describe('AnalysisApiStack', () => {
  const template = Template.fromStack(new AnalysisApiStack(new App(), 'AnalysisApi', {
    config: getEnvironmentConfig('dev'),
  }));

  it('POST /v1/analysisをLambdaへ統合し本文を検証する', () => {
    template.hasResourceProperties('AWS::ApiGateway::RestApi', { Body: Match.objectLike({
      paths: Match.objectLike({ '/v1/analysis': Match.objectLike({ post: Match.anyValue() }) }),
    }) });
    template.resourceCountIs('AWS::Lambda::Function', 4);
    const rendered = JSON.stringify(template.toJSON());
    expect(rendered).toContain('4194304');
    expect(rendered).toContain('additionalProperties');
    expect(rendered).not.toContain('./schemas/analysis-request.schema.json');
  });

  it('cacheなしREQUEST authorizerで双方のtoken検証前に解析Lambdaへ到達させない', () => {
    const rendered = JSON.stringify(template.toJSON());
    expect(rendered).toContain('method.request.header.Authorization,method.request.header.X-Firebase-AppCheck');
    expect(rendered).toContain('authorizerResultTtlInSeconds');
    expect(rendered).toContain('FirebaseAuthorizer');
    expect(rendered).toContain('authorizers/*');
    template.hasResourceProperties('AWS::Lambda::Function', { Environment: { Variables: Match.objectLike({
      FIREBASE_PROJECT_ID: { Ref: 'FirebaseProjectId' },
      FIREBASE_PROJECT_NUMBER: { Ref: 'FirebaseProjectNumber' },
      GOOGLE_WIF_AUDIENCE: { Ref: 'GoogleWifAudience' },
      GOOGLE_SERVICE_ACCOUNT_EMAIL: { Ref: 'GoogleServiceAccountEmail' },
    }) } });
  });

  it('requestId単位の冪等性ストアをTTL・暗号化付きで作る', () => {
    template.hasResourceProperties('AWS::DynamoDB::Table', {
      AttributeDefinitions: [{ AttributeName: 'requestId', AttributeType: 'S' }],
      KeySchema: [{ AttributeName: 'requestId', KeyType: 'HASH' }],
      TimeToLiveSpecification: { AttributeName: 'expiresAt', Enabled: true },
      SSESpecification: { SSEEnabled: true },
    });
  });

  it('ユーザー別クォータ既定値2/分・5/日・30/月をデプロイ時に変更できる', () => {
    expect(template.toJSON().Parameters).toMatchObject({
      ShortQuotaLimit: { Default: 2, MinValue: 1 }, DailyQuotaLimit: { Default: 5, MinValue: 1 }, MonthlyQuotaLimit: { Default: 30, MinValue: 1 },
    });
    template.hasResourceProperties('AWS::Lambda::Function', { Environment: { Variables: Match.objectLike({
      QUOTA_SHORT_LIMIT: { Ref: 'ShortQuotaLimit' }, QUOTA_DAILY_LIMIT: { Ref: 'DailyQuotaLimit' }, QUOTA_MONTHLY_LIMIT: { Ref: 'MonthlyQuotaLimit' },
    }) } });
  });

  it('Lambdaを58秒、Regional REST統合を60秒にする', () => {
    template.hasResourceProperties('AWS::Lambda::Function', { Timeout: 58, ReservedConcurrentExecutions: 5 });
    expect(JSON.stringify(template.toJSON())).toContain('60000');
  });

  it('全体8000回、WAF 10回/分、50 USD予算と50/80/100通知を構成する', () => {
    expect(template.toJSON().Parameters).toMatchObject({ GlobalQuotaLimit: { Default: 8000 }, AnomalyThresholdUsd: { Default: 5 } });
    template.hasResourceProperties('AWS::WAFv2::WebACL', { Scope: 'REGIONAL', Rules: [Match.objectLike({ Statement: { RateBasedStatement: Match.objectLike({ Limit: 10, EvaluationWindowSec: 60, AggregateKeyType: 'IP' }) }, VisibilityConfig: Match.objectLike({ SampledRequestsEnabled: false }) })] });
    template.hasResourceProperties('AWS::Budgets::Budget', { Budget: { BudgetLimit: { Amount: 50, Unit: 'USD' }, TimeUnit: 'MONTHLY' } });
    const rendered = JSON.stringify(template.toJSON());
    for (const threshold of [50, 80, 100]) expect(rendered).toContain(`"Threshold":${threshold}`);
    expect(rendered).toContain('AWS::CE::AnomalySubscription');
    expect(rendered).toContain('CONTROL#AI');
    expect(rendered).toContain('budgets.amazonaws.com');
    expect(rendered).toContain('costalerts.amazonaws.com');
    expect(rendered).toContain('AWS:SourceAccount');
    expect(rendered).toContain('AWS:SourceArn');
    expect(rendered).toContain('BudgetStopDlq');
  });

  it('解析・制御LambdaのDynamoDB権限を実使用APIへ限定する', () => {
    const policies = template.findResources('AWS::IAM::Policy');
    const rendered = JSON.stringify(policies);
    for (const action of ['dynamodb:GetItem', 'dynamodb:BatchGetItem', 'dynamodb:PutItem', 'dynamodb:UpdateItem', 'dynamodb:DeleteItem', 'dynamodb:TransactWriteItems']) {
      expect(rendered).toContain(action);
    }
    expect(rendered).not.toContain('dynamodb:Scan');
    expect(rendered).not.toContain('dynamodb:Query');
    expect(rendered).not.toContain('dynamodb:BatchWriteItem');
  });

  it('Budget通知SNSと停止DLQをローテーション有効な用途別CMKで暗号化する', () => {
    template.resourceCountIs('AWS::KMS::Key', 3);
    template.allResourcesProperties('AWS::KMS::Key', { EnableKeyRotation: true });
    const topics = template.findResources('AWS::SNS::Topic');
    expect(Object.values(topics)).toHaveLength(2);
    for (const topic of Object.values(topics)) expect(topic.Properties).toHaveProperty('KmsMasterKeyId');
    template.hasResourceProperties('AWS::SQS::Queue', { KmsMasterKeyId: Match.anyValue(), MessageRetentionPeriod: 1209600 });
    const rendered = JSON.stringify(template.toJSON());
    expect(rendered).not.toContain('alias/aws/sqs');
    for (const principal of ['budgets.amazonaws.com', 'costalerts.amazonaws.com', 'sns.amazonaws.com']) expect(rendered).toContain(principal);
    expect(rendered).toContain('kms:GenerateDataKey*');
    expect(rendered).toContain('aws:SourceAccount');
  });

  it('暗号化・環境別保持のlog groupとSLO dashboard/alarmを作る', () => {
    template.resourceCountIs('AWS::Logs::LogGroup', 3);
    template.allResourcesProperties('AWS::Logs::LogGroup', { KmsKeyId: Match.anyValue(), RetentionInDays: 14 });
    template.resourceCountIs('AWS::CloudWatch::Dashboard', 1);
    const rendered = JSON.stringify(template.toJSON());
    for (const value of ['CoreAvailabilityAlarm', 'ProviderFailureAlarm', 'LatencyP95Alarm', 'AnalysisLambdaErrorAlarm', 'AnalysisLambdaThrottleAlarm', 'AnalysisApi5xxAlarm', 'AuthorizerLambdaErrorAlarm', '30-day availability', '30-day rolling', 'ProviderCalls']) expect(rendered).toContain(value);
    expect(rendered).toContain('FridgeManager/Analysis');
  });

  it('各アプリLambdaのログ権限を対応LogGroupへのstream書込だけに限定する', () => {
    const rendered = JSON.stringify(template.findResources('AWS::IAM::Policy'));
    expect(rendered).toContain('logs:CreateLogStream');
    expect(rendered).toContain('logs:PutLogEvents');
    expect(rendered).toContain('AuthorizerLogs');
    expect(rendered).toContain('AnalysisLogs');
    expect(rendered).toContain('ControlLogs');
  });
});
