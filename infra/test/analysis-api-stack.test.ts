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
    template.resourceCountIs('AWS::Lambda::Function', 1);
    const rendered = JSON.stringify(template.toJSON());
    expect(rendered).toContain('4194304');
    expect(rendered).toContain('additionalProperties');
    expect(rendered).not.toContain('./schemas/analysis-request.schema.json');
  });

  it('requestId単位の冪等性ストアをTTL・暗号化付きで作る', () => {
    template.hasResourceProperties('AWS::DynamoDB::Table', {
      AttributeDefinitions: [{ AttributeName: 'requestId', AttributeType: 'S' }],
      KeySchema: [{ AttributeName: 'requestId', KeyType: 'HASH' }],
      TimeToLiveSpecification: { AttributeName: 'expiresAt', Enabled: true },
      SSESpecification: { SSEEnabled: true },
    });
  });

  it('Lambdaを58秒、Regional REST統合を60秒にする', () => {
    template.hasResourceProperties('AWS::Lambda::Function', { Timeout: 58, ReservedConcurrentExecutions: 5 });
    expect(JSON.stringify(template.toJSON())).toContain('60000');
  });
});
