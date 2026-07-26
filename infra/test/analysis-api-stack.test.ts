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

  it('nullableな候補フィールドをAPI Gateway互換schemaへ変換する', () => {
    const restApi = Object.values(template.findResources('AWS::ApiGateway::RestApi'))[0]!;
    const schemas = (restApi.Properties.Body.components as { schemas: Record<string, unknown> }).schemas;
    const visit = (value: unknown): void => {
      if (Array.isArray(value)) {
        value.forEach(visit);
        return;
      }
      if (!value || typeof value !== 'object') return;
      const record = value as Record<string, unknown>;
      if (Array.isArray(record.enum)) expect(record.enum).not.toContain(null);
      Object.values(record).forEach(visit);
    };
    visit(schemas.AnalysisResponse);
    expect(JSON.stringify(schemas.AnalysisResponse)).toContain('"nullable":true');
  });

  it('cacheなしREQUEST authorizerで双方のtoken検証前に解析Lambdaへ到達させない', () => {
    const restApi = Object.values(template.findResources('AWS::ApiGateway::RestApi'))[0]!;
    const body = restApi.Properties.Body as {
      components: { securitySchemes: Record<string, unknown> };
      paths: Record<string, { post: Record<string, unknown> }>;
    };
    expect(body.components.securitySchemes).toMatchObject({
      FirebaseAuthorizer: {
        type: 'apiKey',
        name: 'Authorization',
        in: 'header',
        'x-amazon-apigateway-authtype': 'custom',
        'x-amazon-apigateway-authorizer': {
          type: 'request',
          authorizerResultTtlInSeconds: 0,
          identitySource: 'method.request.header.Authorization,method.request.header.X-Firebase-AppCheck',
          authorizerUri: expect.anything(),
        },
      },
    });
    expect(body.paths['/v1/analysis']!.post.security).toEqual([{ FirebaseAuthorizer: [] }]);
    const rendered = JSON.stringify(template.toJSON());
    expect(JSON.stringify(body.components.securitySchemes)).toContain(':apigateway:');
    expect(rendered).toContain('authorizers/*');
    template.hasResourceProperties('AWS::Lambda::Function', { Environment: { Variables: Match.objectLike({
      FIREBASE_PROJECT_ID: { Ref: 'FirebaseProjectId' },
      FIREBASE_PROJECT_NUMBER: { Ref: 'FirebaseProjectNumber' },
      GOOGLE_WIF_AUDIENCE: { Ref: 'GoogleWifAudience' },
      GOOGLE_SERVICE_ACCOUNT_EMAIL: { Ref: 'GoogleServiceAccountEmail' },
    }) } });
  });

  it('Google WIFの主体をFirebase検証Lambdaの固定実行ロールへ限定できる', () => {
    template.hasResourceProperties('AWS::IAM::Role', {
      RoleName: 'FridgeManagerDevFoundationFirebaseAuthorizerRole',
      AssumeRolePolicyDocument: {
        Version: '2012-10-17',
        Statement: [{ Action: 'sts:AssumeRole', Effect: 'Allow', Principal: { Service: 'lambda.amazonaws.com' } }],
      },
    });
    template.hasOutput('FirebaseAuthorizerRoleArn', { Value: Match.anyValue() });
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
    template.hasResourceProperties('AWS::Lambda::Function', { Timeout: 58 });
    const analysisFunction = Object.values(template.findResources('AWS::Lambda::Function'))
      .find((resource) => resource.Properties.FunctionName === 'fridge-manager-dev-analysis')!;
    expect(analysisFunction.Properties).not.toHaveProperty('ReservedConcurrentExecutions');
    expect(JSON.stringify(template.toJSON())).toContain('60000');
  });

  it('prodの解析Lambdaだけ予約同時実行5で費用を保護する', () => {
    const prodTemplate = Template.fromStack(new AnalysisApiStack(new App(), 'ProdAnalysisApi', {
      config: getEnvironmentConfig('prod'),
    }));
    prodTemplate.hasResourceProperties('AWS::Lambda::Function', {
      FunctionName: 'fridge-manager-prod-analysis',
      ReservedConcurrentExecutions: 5,
    });
  });

  it('全体8000回、WAF 10回/分、50 USD予算と50/80/100通知を構成する', () => {
    expect(template.toJSON().Parameters).toMatchObject({ GlobalQuotaLimit: { Default: 8000 }, AnomalyThresholdUsd: { Default: 5 } });
    expect(template.toJSON().Parameters).not.toHaveProperty('BudgetNotificationEmail');
    expect(template.toJSON().Parameters).toHaveProperty('OperationsNotificationEmail');
    template.hasResourceProperties('AWS::WAFv2::WebACL', { Scope: 'REGIONAL', Rules: [Match.objectLike({ Statement: { RateBasedStatement: Match.objectLike({ Limit: 10, EvaluationWindowSec: 60, AggregateKeyType: 'IP' }) }, VisibilityConfig: Match.objectLike({ SampledRequestsEnabled: false }) })] });
    template.hasResourceProperties('AWS::Budgets::Budget', { Budget: { BudgetLimit: { Amount: 50, Unit: 'USD' }, TimeUnit: 'MONTHLY' } });
    const rendered = JSON.stringify(template.toJSON());
    for (const threshold of [50, 80, 100]) expect(rendered).toContain(`"Threshold":${threshold}`);
    expect(rendered).toContain('AWS::CE::AnomalySubscription');
    const anomalySubscription = Object.values(template.findResources('AWS::CE::AnomalySubscription'))[0]!;
    const thresholdExpression = JSON.stringify(anomalySubscription.Properties.ThresholdExpression);
    expect(thresholdExpression).toContain('ANOMALY_TOTAL_IMPACT_ABSOLUTE');
    expect(thresholdExpression).toContain('GREATER_THAN_OR_EQUAL');
    expect(thresholdExpression).toContain('AnomalyThresholdUsd');
    expect(thresholdExpression).not.toContain('And');
    expect(rendered).toContain('CONTROL#AI');
    expect(rendered).toContain('budgets.amazonaws.com');
    expect(rendered).toContain('costalerts.amazonaws.com');
    expect(rendered).toContain('AWS:SourceAccount');
    expect(rendered).toContain('AWS:SourceArn');
    expect(rendered).toContain('BudgetStopDlq');
    template.resourceCountIs('AWS::SNS::Subscription', 3);
    template.resourceCountIs('AWS::SNS::Topic', 2);
    template.hasResourceProperties('AWS::SNS::Subscription', { Protocol: 'email' });
    template.hasResourceProperties('AWS::SNS::Subscription', { Protocol: 'lambda' });
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

  it('Nova 2 Lite JP Geoだけを呼び出し、起動時に保持モードを確認できる', () => {
    const analysisFunction = Object.values(template.findResources('AWS::Lambda::Function'))
      .find((resource) => resource.Properties.FunctionName === 'fridge-manager-dev-analysis')!;
    expect(analysisFunction.Properties.Environment.Variables).toMatchObject({
      BEDROCK_REGION: 'ap-northeast-1',
      BEDROCK_MODEL_ID: 'jp.amazon.nova-2-lite-v1:0',
      BEDROCK_MODEL_ALLOWED_MODES: 'none',
    });
    const policies = JSON.stringify(template.findResources('AWS::IAM::Policy'));
    expect(policies).toContain('bedrock:InvokeModel');
    expect(policies).toContain('bedrock:GetAccountDataRetention');
    expect(policies).toContain('inference-profile/jp.amazon.nova-2-lite-v1:0');
    expect(policies).toContain('foundation-model/amazon.nova-2-lite-v1:0');
    expect(policies).toContain('ap-northeast-3');
    expect(policies).toContain('bedrock:InferenceProfileArn');
    expect(policies).not.toContain('foundation-model/*');
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
