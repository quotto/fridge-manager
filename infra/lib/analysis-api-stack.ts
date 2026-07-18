import { Duration, Stack, StackProps } from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as nodejs from 'aws-cdk-lib/aws-lambda-nodejs';
import * as iam from 'aws-cdk-lib/aws-iam';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Construct } from 'constructs';
import { EnvironmentConfig } from './environment-config';

export interface AnalysisApiStackProps extends StackProps { readonly config: EnvironmentConfig; }

function apiGatewaySchema(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(apiGatewaySchema);
  if (!value || typeof value !== 'object') return value;
  const result: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    if (['$schema', '$id', '$defs', 'if', 'then'].includes(key)) continue;
    if (key === 'const') { result.enum = [child]; continue; }
    if (key === '$ref' && typeof child === 'string') { result.$ref = child.replace('#/$defs/', '#/definitions/'); continue; }
    if (key === 'type' && Array.isArray(child)) {
      const nonNull = child.filter((item) => item !== 'null');
      result.type = nonNull.length === 1 ? nonNull[0] : nonNull;
      if (child.includes('null')) result.nullable = true;
      continue;
    }
    result[key] = apiGatewaySchema(child);
  }
  const definitions = (value as Record<string, unknown>).$defs;
  if (definitions) result.definitions = apiGatewaySchema(definitions);
  return result;
}

export class AnalysisApiStack extends Stack {
  public constructor(scope: Construct, id: string, props: AnalysisApiStackProps) {
    super(scope, id, props);
    const table = new dynamodb.Table(this, 'AnalysisIdempotency', {
      partitionKey: { name: 'requestId', type: dynamodb.AttributeType.STRING },
      timeToLiveAttribute: 'expiresAt', encryption: dynamodb.TableEncryption.AWS_MANAGED,
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST, removalPolicy: props.config.removalPolicy,
    });
    const fn = new nodejs.NodejsFunction(this, 'AnalysisHandler', {
      entry: resolve('infra/lambda/index.ts'), handler: 'main', runtime: lambda.Runtime.NODEJS_22_X,
      timeout: Duration.seconds(58), memorySize: 1024,
      reservedConcurrentExecutions: 5,
      environment: { IDEMPOTENCY_TABLE_NAME: table.tableName },
      bundling: { minify: true, sourceMap: true },
    });
    table.grantReadWriteData(fn);

    const specification = JSON.parse(readFileSync(resolve('infra/api/openapi.json'), 'utf8')) as Record<string, unknown>;
    const components = (specification.components as { schemas: Record<string, unknown> }).schemas;
    components.AnalysisRequest = apiGatewaySchema(JSON.parse(readFileSync(resolve('infra/api/schemas/analysis-request.schema.json'), 'utf8')) as unknown);
    components.AnalysisResponse = apiGatewaySchema(JSON.parse(readFileSync(resolve('infra/api/schemas/analysis-response.schema.json'), 'utf8')) as unknown);
    const paths = specification.paths as Record<string, Record<string, Record<string, unknown>>>;
    const post = paths['/v1/analysis']?.post;
    if (!post) throw new Error('OpenAPIにPOST /v1/analysisがありません');
    post['x-amazon-apigateway-integration'] = {
      type: 'aws_proxy', httpMethod: 'POST', timeoutInMillis: 60000,
      uri: `arn:${this.partition}:apigateway:${this.region}:lambda:path/2015-03-31/functions/${fn.functionArn}/invocations`,
    };
    const api = new apigateway.SpecRestApi(this, 'AnalysisApi', {
      apiDefinition: apigateway.ApiDefinition.fromInline(specification),
      endpointTypes: [apigateway.EndpointType.REGIONAL], deployOptions: {
        dataTraceEnabled: false, tracingEnabled: true, throttlingBurstLimit: 2, throttlingRateLimit: 10,
      },
    });
    fn.addPermission('AllowApiGatewayInvoke', { principal: new iam.ServicePrincipal('apigateway.amazonaws.com'), sourceArn: api.arnForExecuteApi('POST', '/v1/analysis') });
  }
}
