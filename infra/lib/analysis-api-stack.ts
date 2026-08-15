import { CfnOutput, CfnParameter, Duration, Fn, Stack, StackProps } from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as nodejs from 'aws-cdk-lib/aws-lambda-nodejs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as budgets from 'aws-cdk-lib/aws-budgets';
import * as ce from 'aws-cdk-lib/aws-ce';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as subscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as wafv2 from 'aws-cdk-lib/aws-wafv2';
import * as cr from 'aws-cdk-lib/custom-resources';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cwActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as logs from 'aws-cdk-lib/aws-logs';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Construct } from 'constructs';
import { EnvironmentConfig } from './environment-config';
import { loadBedrockModelPolicy } from './bedrock-model-policy';

export interface AnalysisApiStackProps extends StackProps { readonly config: EnvironmentConfig; }

const cloudflareCidrs = [
  '173.245.48.0/20', '103.21.244.0/22', '103.22.200.0/22', '103.31.4.0/22', '141.101.64.0/18',
  '108.162.192.0/18', '190.93.240.0/20', '188.114.96.0/20', '197.234.240.0/22', '198.41.128.0/17',
  '162.158.0.0/15', '104.16.0.0/13', '104.24.0.0/14', '172.64.0.0/13', '131.0.72.0/22',
  '2400:cb00::/32', '2606:4700::/32', '2803:f800::/32', '2405:b500::/32', '2405:8100::/32',
  '2a06:98c0::/29', '2c0f:f248::/32',
];

function publicApiDomain(environment: EnvironmentConfig['environment']): string | undefined {
  if (environment === 'stg') return 'fridge-manager-stg.wackwack.net';
  if (environment === 'prod') return 'fridge-manager.wackwack.net';
  return undefined;
}

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
    if (key === 'enum' && Array.isArray(child) && child.includes(null)) {
      result.enum = child.filter((item) => item !== null).map(apiGatewaySchema);
      result.nullable = true;
      continue;
    }
    result[key] = apiGatewaySchema(child);
  }
  const definitions = (value as Record<string, unknown>).$defs;
  if (definitions) result.definitions = apiGatewaySchema(definitions);
  return result;
}

function inlineSchemaReference(value: unknown, reference: string, schema: unknown): unknown {
  if (Array.isArray(value)) return value.map((child) => inlineSchemaReference(child, reference, schema));
  if (!value || typeof value !== 'object') return value;
  if ((value as Record<string, unknown>).$ref === reference) return schema;
  return Object.fromEntries(Object.entries(value as Record<string, unknown>)
    .map(([key, child]) => [key, inlineSchemaReference(child, reference, schema)]));
}

export class AnalysisApiStack extends Stack {
  public constructor(scope: Construct, id: string, props: AnalysisApiStackProps) {
    super(scope, id, { ...props, terminationProtection: props.config.terminationProtection });
    const apiDomain = publicApiDomain(props.config.environment);
    const acmCertificateArn = apiDomain
      ? new CfnParameter(this, 'AcmCertificateArn', { type: 'String', minLength: 1 })
      : undefined;
    const aopTruststoreVersion = apiDomain
      ? new CfnParameter(this, 'AopTruststoreVersion', { type: 'String', minLength: 1 })
      : undefined;
    const configuredCloudflareCidrs = apiDomain
      ? new CfnParameter(this, 'CloudflareCidrs', { type: 'CommaDelimitedList', default: cloudflareCidrs.join(',') })
      : undefined;
    // synth/deploy時に公式証跡の内容と期限を検証し、古い自己申告値でのデプロイを拒否する。
    const bedrockPolicy = loadBedrockModelPolicy();
    const table = new dynamodb.Table(this, 'AnalysisIdempotency', {
      partitionKey: { name: 'requestId', type: dynamodb.AttributeType.STRING },
      timeToLiveAttribute: 'expiresAt', encryption: dynamodb.TableEncryption.AWS_MANAGED,
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST, removalPolicy: props.config.removalPolicy,
    });
    const controlTable = new dynamodb.Table(this, 'AiControl', {
      partitionKey: { name: 'controlId', type: dynamodb.AttributeType.STRING }, timeToLiveAttribute: 'expiresAt',
      encryption: dynamodb.TableEncryption.AWS_MANAGED, billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: props.config.removalPolicy,
    });
    const bootstrap = new cr.AwsCustomResource(this, 'AiControlBootstrap', {
      installLatestAwsSdk: false,
      onCreate: { service: 'DynamoDB', action: 'putItem', parameters: { TableName: controlTable.tableName, Item: {
        controlId: { S: 'CONTROL#AI' }, enabled: { BOOL: true }, actor: { S: 'cloudformation' }, reason: { S: 'INITIAL_ENABLE' }, updatedAt: { S: new Date(0).toISOString() },
      }, ConditionExpression: 'attribute_not_exists(controlId)' }, physicalResourceId: cr.PhysicalResourceId.of('ai-control-bootstrap-v1') },
      policy: cr.AwsCustomResourcePolicy.fromSdkCalls({ resources: [controlTable.tableArn] }),
    });
    bootstrap.node.addDependency(controlTable);
    const firebaseProjectId = new CfnParameter(this, 'FirebaseProjectId', { type: 'String', allowedPattern: '^[a-z][a-z0-9-]{4,28}[a-z0-9]$' });
    const firebaseProjectNumber = new CfnParameter(this, 'FirebaseProjectNumber', { type: 'String', allowedPattern: '^[1-9][0-9]{5,19}$' });
    const firebaseAppIds = new CfnParameter(this, 'FirebaseAppIds', { type: 'String', minLength: 1, allowedPattern: '^[A-Za-z0-9:,_-]+$' });
    const googleWifAudience = new CfnParameter(this, 'GoogleWifAudience', { type: 'String', minLength: 1 });
    const googleServiceAccountEmail = new CfnParameter(this, 'GoogleServiceAccountEmail', { type: 'String', allowedPattern: '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.iam\\.gserviceaccount\\.com$' });
    const shortQuotaLimit = new CfnParameter(this, 'ShortQuotaLimit', { type: 'Number', default: 2, minValue: 1 });
    const dailyQuotaLimit = new CfnParameter(this, 'DailyQuotaLimit', { type: 'Number', default: 5, minValue: 1 });
    const monthlyQuotaLimit = new CfnParameter(this, 'MonthlyQuotaLimit', { type: 'Number', default: 30, minValue: 1 });
    const globalQuotaLimit = new CfnParameter(this, 'GlobalQuotaLimit', { type: 'Number', default: 8000, minValue: 1 });
    const operationsNotificationEmail = new CfnParameter(this, 'OperationsNotificationEmail', { type: 'String', allowedPattern: '^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$' });
    const anomalyThresholdUsd = new CfnParameter(this, 'AnomalyThresholdUsd', { type: 'Number', default: 5, minValue: 1 });
    const logKey = new kms.Key(this, 'ApplicationLogKey', {
      enableKeyRotation: true, removalPolicy: props.config.removalPolicy, alias: `alias/fridge-manager-${props.config.environment}-logs`,
    });
    logKey.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal(`logs.${this.region}.amazonaws.com`)], actions: ['kms:Encrypt*', 'kms:Decrypt*', 'kms:ReEncrypt*', 'kms:GenerateDataKey*', 'kms:Describe*'], resources: ['*'],
      conditions: { ArnLike: { 'kms:EncryptionContext:aws:logs:arn': `arn:${this.partition}:logs:${this.region}:${this.account}:log-group:/aws/lambda/fridge-manager-${props.config.environment}-*` } },
    }));
    const applicationLogGroup = (name: string) => new logs.LogGroup(this, `${name}Logs`, {
      logGroupName: `/aws/lambda/fridge-manager-${props.config.environment}-${name.toLowerCase()}`,
      encryptionKey: logKey, retention: props.config.logRetention, removalPolicy: props.config.removalPolicy,
    });
    const authorizerLogGroup = applicationLogGroup('Authorizer');
    const analysisLogGroup = applicationLogGroup('Analysis');
    const controlLogGroup = applicationLogGroup('Control');
    const lambdaRole = (name: string, logGroup: logs.ILogGroup, roleName?: string) => {
      const role = new iam.Role(this, `${name}Role`, {
        assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
        ...(roleName ? { roleName } : {}),
      });
      role.addToPolicy(new iam.PolicyStatement({ actions: ['logs:CreateLogStream', 'logs:PutLogEvents'], resources: [`${logGroup.logGroupArn}:*`] }));
      return role;
    };
    const authorizerRole = lambdaRole('Authorizer', authorizerLogGroup, `${props.config.stackName}FirebaseAuthorizerRole`);
    const authorizerFn = new nodejs.NodejsFunction(this, 'FirebaseAuthorizer', {
      functionName: `fridge-manager-${props.config.environment}-authorizer`, logGroup: authorizerLogGroup,
      role: authorizerRole,
      entry: resolve('infra/lambda/firebase-authorizer-index.ts'), handler: 'main', runtime: lambda.Runtime.NODEJS_22_X,
      timeout: Duration.seconds(10), memorySize: 256,
      environment: {
        FIREBASE_PROJECT_ID: firebaseProjectId.valueAsString,
        FIREBASE_PROJECT_NUMBER: firebaseProjectNumber.valueAsString,
        FIREBASE_APP_IDS: firebaseAppIds.valueAsString,
        GOOGLE_WIF_AUDIENCE: googleWifAudience.valueAsString,
        GOOGLE_SERVICE_ACCOUNT_EMAIL: googleServiceAccountEmail.valueAsString,
      },
      bundling: { minify: true, sourceMap: true },
    });
    const fn = new nodejs.NodejsFunction(this, 'AnalysisHandler', {
      functionName: `fridge-manager-${props.config.environment}-analysis`, logGroup: analysisLogGroup, role: lambdaRole('Analysis', analysisLogGroup),
      entry: resolve('infra/lambda/index.ts'), handler: 'main', runtime: lambda.Runtime.NODEJS_22_X,
      timeout: Duration.seconds(58), memorySize: 1024,
      ...(props.config.environment === 'prod' ? { reservedConcurrentExecutions: 5 } : {}),
      environment: {
        IDEMPOTENCY_TABLE_NAME: table.tableName,
        QUOTA_SHORT_LIMIT: shortQuotaLimit.valueAsString,
        QUOTA_DAILY_LIMIT: dailyQuotaLimit.valueAsString,
        QUOTA_MONTHLY_LIMIT: monthlyQuotaLimit.valueAsString,
        QUOTA_GLOBAL_LIMIT: globalQuotaLimit.valueAsString,
        CONTROL_TABLE_NAME: controlTable.tableName,
        ENVIRONMENT: props.config.environment,
        BEDROCK_REGION: bedrockPolicy.region,
        BEDROCK_MODEL_ID: bedrockPolicy.modelId,
        BEDROCK_MODEL_ALLOWED_MODES: bedrockPolicy.allowedModes.join(','),
      },
      bundling: { minify: true, sourceMap: true },
    });
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['dynamodb:GetItem', 'dynamodb:BatchGetItem', 'dynamodb:PutItem', 'dynamodb:UpdateItem', 'dynamodb:DeleteItem', 'dynamodb:TransactWriteItems'],
      resources: [table.tableArn],
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({ actions: ['dynamodb:GetItem'], resources: [controlTable.tableArn] }));
    const inferenceProfileArn = `arn:${this.partition}:bedrock:${bedrockPolicy.region}:${this.account}:inference-profile/${bedrockPolicy.modelId}`;
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['bedrock:InvokeModel'],
      resources: [inferenceProfileArn],
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['bedrock:InvokeModel'],
      resources: bedrockPolicy.destinationRegions.map((region) => `arn:${this.partition}:bedrock:${region}::foundation-model/${bedrockPolicy.foundationModelId}`),
      conditions: { StringEquals: { 'bedrock:InferenceProfileArn': inferenceProfileArn } },
    }));
    // GetAccountDataRetentionはresource-level permissionをサポートしない。
    fn.addToRolePolicy(new iam.PolicyStatement({ actions: ['bedrock:GetAccountDataRetention'], resources: ['*'] }));

    const controlFn = new nodejs.NodejsFunction(this, 'AiControlHandler', {
      functionName: `fridge-manager-${props.config.environment}-control`, logGroup: controlLogGroup, role: lambdaRole('Control', controlLogGroup),
      entry: resolve('infra/lambda/ai-control-index.ts'), handler: 'main', runtime: lambda.Runtime.NODEJS_22_X,
      timeout: Duration.seconds(10), memorySize: 256, environment: { CONTROL_TABLE_NAME: controlTable.tableName },
      bundling: { minify: true, sourceMap: true },
    });
    controlFn.addToRolePolicy(new iam.PolicyStatement({ actions: ['dynamodb:TransactWriteItems'], resources: [controlTable.tableArn] }));
    const notificationKey = new kms.Key(this, 'BudgetNotificationKey', {
      enableKeyRotation: true, removalPolicy: props.config.removalPolicy, alias: `alias/fridge-manager-${props.config.environment}-budget-notifications`,
    });
    notificationKey.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('budgets.amazonaws.com')], actions: ['kms:Decrypt', 'kms:GenerateDataKey*', 'kms:DescribeKey'], resources: ['*'],
      conditions: { StringEquals: { 'aws:SourceAccount': this.account }, ArnLike: { 'aws:SourceArn': `arn:${this.partition}:budgets::${this.account}:*` } },
    }));
    notificationKey.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('costalerts.amazonaws.com')], actions: ['kms:Decrypt', 'kms:GenerateDataKey*', 'kms:DescribeKey'], resources: ['*'],
      conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
    }));
    const dlqKey = new kms.Key(this, 'BudgetStopDlqKey', {
      enableKeyRotation: true, removalPolicy: props.config.removalPolicy, alias: `alias/fridge-manager-${props.config.environment}-budget-stop-dlq`,
    });
    const alertTopic = new sns.Topic(this, 'BudgetAlerts', { masterKey: notificationKey });
    const stopTopic = new sns.Topic(this, 'BudgetStop', { masterKey: notificationKey });
    dlqKey.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('sns.amazonaws.com')], actions: ['kms:Decrypt', 'kms:GenerateDataKey*', 'kms:DescribeKey'], resources: ['*'],
      conditions: { StringEquals: { 'aws:SourceAccount': this.account }, ArnLike: { 'aws:SourceArn': stopTopic.topicArn } },
    }));
    const stopDlq = new sqs.Queue(this, 'BudgetStopDlq', { encryption: sqs.QueueEncryption.KMS, encryptionMasterKey: dlqKey, retentionPeriod: Duration.days(14) });
    alertTopic.addSubscription(new subscriptions.EmailSubscription(operationsNotificationEmail.valueAsString));
    stopTopic.addSubscription(new subscriptions.EmailSubscription(operationsNotificationEmail.valueAsString));
    stopTopic.addSubscription(new subscriptions.LambdaSubscription(controlFn, { deadLetterQueue: stopDlq }));
    const budgetStopDlqAlarm = new cloudwatch.Alarm(this, 'BudgetStopDlqAlarm', {
      metric: stopDlq.metricApproximateNumberOfMessagesVisible(), threshold: 1, evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
    });
    budgetStopDlqAlarm.addAlarmAction(new cwActions.SnsAction(alertTopic));
    const budgetPolicyResults = [alertTopic, stopTopic].map((topic) => topic.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('budgets.amazonaws.com')], actions: ['sns:Publish'], resources: [topic.topicArn],
      conditions: { StringEquals: { 'AWS:SourceAccount': this.account }, ArnLike: { 'AWS:SourceArn': `arn:${this.partition}:budgets::${this.account}:*` } },
    })));
    const costPolicyResult = alertTopic.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('costalerts.amazonaws.com')], actions: ['sns:Publish'], resources: [alertTopic.topicArn],
      conditions: { StringEquals: { 'AWS:SourceAccount': this.account } },
    }));
    const budget = new budgets.CfnBudget(this, 'MonthlyAiBudget', {
      budget: { budgetType: 'COST', timeUnit: 'MONTHLY', budgetLimit: { amount: 50, unit: 'USD' }, budgetName: `fridge-manager-${props.config.environment}-ai` },
      notificationsWithSubscribers: [
        { notification: { comparisonOperator: 'GREATER_THAN', notificationType: 'ACTUAL', threshold: 50, thresholdType: 'PERCENTAGE' }, subscribers: [{ subscriptionType: 'SNS', address: alertTopic.topicArn }] },
        { notification: { comparisonOperator: 'GREATER_THAN', notificationType: 'ACTUAL', threshold: 80, thresholdType: 'PERCENTAGE' }, subscribers: [{ subscriptionType: 'SNS', address: alertTopic.topicArn }] },
        { notification: { comparisonOperator: 'GREATER_THAN', notificationType: 'ACTUAL', threshold: 100, thresholdType: 'PERCENTAGE' }, subscribers: [{ subscriptionType: 'SNS', address: stopTopic.topicArn }] },
      ],
    });
    for (const result of budgetPolicyResults) if (result.policyDependable) budget.node.addDependency(result.policyDependable);
    const anomalyMonitor = new ce.CfnAnomalyMonitor(this, 'CostAnomalyMonitor', { monitorName: `fridge-manager-${props.config.environment}`, monitorType: 'DIMENSIONAL', monitorDimension: 'SERVICE' });
    const anomalySubscription = new ce.CfnAnomalySubscription(this, 'CostAnomalySubscription', {
      subscriptionName: `fridge-manager-${props.config.environment}`, frequency: 'IMMEDIATE', monitorArnList: [anomalyMonitor.attrMonitorArn],
      subscribers: [{ type: 'SNS', address: alertTopic.topicArn }], thresholdExpression: JSON.stringify({ Dimensions: { Key: 'ANOMALY_TOTAL_IMPACT_ABSOLUTE', MatchOptions: ['GREATER_THAN_OR_EQUAL'], Values: [anomalyThresholdUsd.valueAsString] } }),
    });
    if (costPolicyResult.policyDependable) anomalySubscription.node.addDependency(costPolicyResult.policyDependable);

    const metric = (name: string, statistic = 'Sum', dimensionsMap: Record<string, string> = { Environment: props.config.environment }) =>
      new cloudwatch.Metric({ namespace: 'FridgeManager/Analysis', metricName: name, dimensionsMap, statistic, period: Duration.minutes(5) });
    const sloEligible = metric('SloEligible'); const sloSuccess = metric('SloSuccess');
    const availability = new cloudwatch.MathExpression({ expression: 'IF(eligible>0,100*success/eligible,100)', usingMetrics: { success: sloSuccess, eligible: sloEligible }, label: 'Core availability %', period: Duration.minutes(5) });
    const providerFailures = metric('Requests', 'Sum', { Environment: props.config.environment, Outcome: 'PROVIDER_FAILURE' });
    const serviceFailures = metric('Requests', 'Sum', { Environment: props.config.environment, Outcome: 'SERVICE_FAILURE' });
    const quotaRejects = metric('Requests', 'Sum', { Environment: props.config.environment, Outcome: 'QUOTA_REJECT' });
    const latencyP95 = metric('Latency', 'p95');
    const providerUsageDimensions = { Environment: props.config.environment, ModelId: bedrockPolicy.modelId };
    const inputTokens = metric('InputTokens', 'Sum', providerUsageDimensions);
    const outputTokens = metric('OutputTokens', 'Sum', providerUsageDimensions);
    const providerCallsByModel = metric('ProviderCalls', 'Sum', providerUsageDimensions);
    const coreAlarm = new cloudwatch.Alarm(this, 'CoreAvailabilityAlarm', {
      metric: availability, threshold: 99, comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      evaluationPeriods: 12, datapointsToAlarm: 6, treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const providerAlarm = new cloudwatch.Alarm(this, 'ProviderFailureAlarm', {
      metric: providerFailures, threshold: 3, evaluationPeriods: 1, treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const latencyAlarm = new cloudwatch.Alarm(this, 'LatencyP95Alarm', {
      metric: latencyP95, threshold: 55_000, evaluationPeriods: 3, comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const lambdaErrorAlarm = new cloudwatch.Alarm(this, 'AnalysisLambdaErrorAlarm', {
      metric: fn.metricErrors({ period: Duration.minutes(5) }), threshold: 1, evaluationPeriods: 1, treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const lambdaThrottleAlarm = new cloudwatch.Alarm(this, 'AnalysisLambdaThrottleAlarm', {
      metric: fn.metricThrottles({ period: Duration.minutes(5) }), threshold: 1, evaluationPeriods: 1, treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    for (const alarm of [coreAlarm, providerAlarm, latencyAlarm, lambdaErrorAlarm, lambdaThrottleAlarm]) alarm.addAlarmAction(new cwActions.SnsAction(alertTopic));
    const dashboardWidgets = [
      [new cloudwatch.GraphWidget({ title: '30-day rolling Core SLO 99.0%（暦月とは別）', left: [new cloudwatch.MathExpression({ expression: 'IF(eligible>0,100*success/eligible,100)', usingMetrics: {
        success: sloSuccess.with({ period: Duration.days(30) }), eligible: sloEligible.with({ period: Duration.days(30) }),
      }, label: '30-day availability %', period: Duration.days(30) })], leftYAxis: { min: 98, max: 100 } })],
      [new cloudwatch.GraphWidget({ title: 'Latency p95', left: [latencyP95] }), new cloudwatch.GraphWidget({ title: 'Errors / quota / provider', left: [serviceFailures, providerFailures, quotaRejects] })],
      [new cloudwatch.GraphWidget({ title: 'AI call usage', left: [metric('ProviderCalls')] }), new cloudwatch.AlarmWidget({ title: 'Cost / stop delivery', alarm: budgetStopDlqAlarm })],
      [new cloudwatch.GraphWidget({ title: 'AI token usage', left: [inputTokens, outputTokens], right: [providerCallsByModel] })],
      [new cloudwatch.GraphWidget({ title: 'Lambda Errors / Throttles', left: [fn.metricErrors(), fn.metricThrottles()] })],
      [new cloudwatch.TextWidget({ markdown: `## Cost controls / SLO definition\nMonthly Budget: 50 USD (50/80/100%) · Cost Anomaly threshold parameter · Global cap: 8,000/month JST. Dashboard availability is a 30-day rolling indicator; the calendar-month SLO is evaluated separately.`, width: 24, height: 3 })],
    ] as unknown as cloudwatch.IWidget[][];
    new cloudwatch.Dashboard(this, 'OperationsDashboard', { dashboardName: `fridge-manager-${props.config.environment}`, widgets: dashboardWidgets });

    const specification = JSON.parse(readFileSync(resolve('infra/api/openapi.json'), 'utf8')) as Record<string, unknown>;
    const components = specification.components as { schemas: Record<string, unknown>; securitySchemes?: Record<string, unknown> };
    components.schemas.AnalysisRequest = apiGatewaySchema(JSON.parse(readFileSync(resolve('infra/api/schemas/analysis-request.schema.json'), 'utf8')) as unknown);
    const candidateSchema = JSON.parse(readFileSync(resolve('infra/api/schemas/food-candidate.schema.json'), 'utf8')) as unknown;
    const responseSchema = JSON.parse(readFileSync(resolve('infra/api/schemas/analysis-response.schema.json'), 'utf8')) as unknown;
    components.schemas.AnalysisResponse = apiGatewaySchema(inlineSchemaReference(responseSchema, 'food-candidate.schema.json', candidateSchema));
    const paths = specification.paths as Record<string, Record<string, Record<string, unknown>>>;
    const post = paths['/v1/analysis']?.post;
    if (!post) throw new Error('OpenAPIにPOST /v1/analysisがありません');
    components.securitySchemes = { FirebaseAuthorizer: {
      type: 'apiKey', name: 'Authorization', in: 'header',
      'x-amazon-apigateway-authtype': 'custom',
      'x-amazon-apigateway-authorizer': {
        type: 'request', authorizerResultTtlInSeconds: 0,
        identitySource: 'method.request.header.Authorization,method.request.header.X-Firebase-AppCheck',
        authorizerUri: `arn:${this.partition}:apigateway:${this.region}:lambda:path/2015-03-31/functions/${authorizerFn.functionArn}/invocations`,
      },
    } };
    post.security = [{ FirebaseAuthorizer: [] }];
    post['x-amazon-apigateway-integration'] = {
      type: 'aws_proxy', httpMethod: 'POST', timeoutInMillis: props.config.apiIntegrationTimeoutMillis,
      uri: `arn:${this.partition}:apigateway:${this.region}:lambda:path/2015-03-31/functions/${fn.functionArn}/invocations`,
    };
    const api = new apigateway.SpecRestApi(this, 'AnalysisApi', {
      apiDefinition: apigateway.ApiDefinition.fromInline(specification),
      endpointTypes: [apigateway.EndpointType.REGIONAL], deployOptions: {
        stageName: props.config.environment, dataTraceEnabled: false, tracingEnabled: true, throttlingBurstLimit: 2, throttlingRateLimit: 10,
      },
      ...(configuredCloudflareCidrs ? {
        disableExecuteApiEndpoint: true,
        policy: new iam.PolicyDocument({ statements: [
          new iam.PolicyStatement({ effect: iam.Effect.ALLOW, principals: [new iam.AnyPrincipal()], actions: ['execute-api:Invoke'], resources: ['execute-api:/*'] }),
          new iam.PolicyStatement({
            effect: iam.Effect.DENY, principals: [new iam.AnyPrincipal()], actions: ['execute-api:Invoke'], resources: ['execute-api:/*'],
            conditions: { NotIpAddress: { 'aws:SourceIp': configuredCloudflareCidrs.valueAsList } },
          }),
        ] }),
      } : {}),
    });
    const api5xxAlarm = new cloudwatch.Alarm(this, 'AnalysisApi5xxAlarm', {
      metric: api.metricServerError({ period: Duration.minutes(5), statistic: 'Sum' }), threshold: 1, evaluationPeriods: 1,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const authorizerErrorAlarm = new cloudwatch.Alarm(this, 'AuthorizerLambdaErrorAlarm', {
      metric: authorizerFn.metricErrors({ period: Duration.minutes(5) }), threshold: 1, evaluationPeriods: 1,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    api5xxAlarm.addAlarmAction(new cwActions.SnsAction(alertTopic));
    authorizerErrorAlarm.addAlarmAction(new cwActions.SnsAction(alertTopic));
    const webAcl = new wafv2.CfnWebACL(this, 'AnalysisWebAcl', { scope: 'REGIONAL', defaultAction: { allow: {} }, visibilityConfig: {
      cloudWatchMetricsEnabled: true, metricName: `fridge-manager-${props.config.environment}-waf`, sampledRequestsEnabled: false,
    }, rules: [{ name: 'AnalysisIpRateLimit', priority: 0, action: { block: {} }, statement: { rateBasedStatement: {
      aggregateKeyType: 'IP', limit: 10, evaluationWindowSec: 60, scopeDownStatement: { andStatement: { statements: [
        { byteMatchStatement: { fieldToMatch: { method: {} }, positionalConstraint: 'EXACTLY', searchString: 'POST', textTransformations: [{ priority: 0, type: 'NONE' }] } },
        { byteMatchStatement: { fieldToMatch: { uriPath: {} }, positionalConstraint: 'EXACTLY', searchString: '/v1/analysis', textTransformations: [{ priority: 0, type: 'NONE' }] } },
      ] } },
    } }, visibilityConfig: { cloudWatchMetricsEnabled: true, metricName: 'analysis-ip-rate-limit', sampledRequestsEnabled: false } }] });
    new wafv2.CfnWebACLAssociation(this, 'AnalysisWebAclAssociation', {
      webAclArn: webAcl.attrArn, resourceArn: `arn:${this.partition}:apigateway:${this.region}::/restapis/${api.restApiId}/stages/${api.deploymentStage.stageName}`,
    });
    fn.addPermission('AllowApiGatewayInvoke', { principal: new iam.ServicePrincipal('apigateway.amazonaws.com'), sourceArn: api.arnForExecuteApi('POST', '/v1/analysis') });
    authorizerFn.addPermission('AllowApiGatewayAuthorizerInvoke', {
      principal: new iam.ServicePrincipal('apigateway.amazonaws.com'),
      sourceArn: this.formatArn({ service: 'execute-api', resource: api.restApiId, resourceName: 'authorizers/*' }),
    });
    if (apiDomain && acmCertificateArn && aopTruststoreVersion) {
      const domain = new apigateway.CfnDomainName(this, 'AnalysisApiDomainName', {
        domainName: apiDomain,
        regionalCertificateArn: acmCertificateArn.valueAsString,
        endpointConfiguration: { types: ['REGIONAL'] },
        securityPolicy: 'TLS_1_2',
        mutualTlsAuthentication: {
          truststoreUri: Fn.join('', ['s3://', Fn.importValue(`fridge-manager-${props.config.environment}-aop-truststore-bucket`), `/aop/${props.config.environment}/truststore.pem`]),
          truststoreVersion: aopTruststoreVersion.valueAsString,
        },
      });
      new apigateway.CfnBasePathMapping(this, 'AnalysisApiBasePathMapping', {
        domainName: apiDomain,
        restApiId: api.restApiId,
        stage: api.deploymentStage.stageName,
      }).addDependency(domain);
    }
    new CfnOutput(this, 'AnalysisApiUrl', { value: apiDomain ? `https://${apiDomain}/v1/analysis` : `${api.url}v1/analysis`, description: '認証必須の解析API endpoint' });
    new CfnOutput(this, 'FirebaseAuthorizerRoleArn', { value: authorizerRole.roleArn, description: 'Google WIFで許可するFirebase検証Lambda role' });
    new CfnOutput(this, 'AiControlTableName', { value: controlTable.tableName, description: 'AI停止状態の検証用table' });
    new CfnOutput(this, 'AiControlFunctionName', { value: controlFn.functionName, description: '監査付きAI停止・復旧function' });
  }
}
