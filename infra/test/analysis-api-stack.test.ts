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
    template.resourceCountIs('AWS::Lambda::Function', 2);
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
});
