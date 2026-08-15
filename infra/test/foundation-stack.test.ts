import { App, Aspects, Stack, Tags } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import * as iam from 'aws-cdk-lib/aws-iam';
import { FoundationStack } from '../lib/foundation-stack';
import { getEnvironmentConfig, supportedEnvironments } from '../lib/environment-config';
import { LeastPrivilegeIamAspect } from '../lib/least-privilege-iam-aspect';

describe('FoundationStack', () => {
  it.each(supportedEnvironments)('%s を synth でき、必須タグを付与する', (environment) => {
    const app = new App();
    const config = getEnvironmentConfig(environment);
    const stack = new FoundationStack(app, config.stackName, { config });
    const template = Template.fromStack(stack);

    template.hasResourceProperties('AWS::Logs::LogGroup', {
      RetentionInDays: config.logRetentionDays,
      KmsKeyId: Match.anyValue(),
      Tags: Match.arrayWith([
        { Key: 'Application', Value: 'fridge-manager' },
        { Key: 'DataClassification', Value: 'sensitive' },
        { Key: 'Environment', Value: environment },
        { Key: 'ManagedBy', Value: 'aws-cdk' },
      ]),
    });
    template.hasResourceProperties('AWS::KMS::Key', {
      EnableKeyRotation: true,
      Tags: Match.arrayWith([
        { Key: 'Application', Value: 'fridge-manager' },
        { Key: 'DataClassification', Value: 'sensitive' },
        { Key: 'Environment', Value: environment },
        { Key: 'ManagedBy', Value: 'aws-cdk' },
      ]),
    });
    expect(stack.terminationProtection).toBe(config.terminationProtection);
  });

  it.each(supportedEnvironments)('%s のリソース名を他環境から分離する', (environment) => {
    const app = new App();
    const config = getEnvironmentConfig(environment);
    const template = Template.fromStack(new FoundationStack(app, config.stackName, { config }));

    template.hasResourceProperties('AWS::Logs::LogGroup', {
      LogGroupName: `/fridge-manager/${environment}/foundation`,
    });
    template.hasResourceProperties('AWS::KMS::Alias', {
      AliasName: `alias/fridge-manager/${environment}/foundation`,
    });
  });

  it.each(supportedEnvironments)('%s のログ暗号鍵を対象ロググループだけに許可する', (environment) => {
    const app = new App();
    const config = getEnvironmentConfig(environment);
    const template = Template.fromStack(new FoundationStack(app, config.stackName, {
      config,
      env: { account: '123456789012', region: 'ap-northeast-1' },
    }));

    const key = Object.values(template.findResources('AWS::KMS::Key'))[0]!;
    const statement = key.Properties.KeyPolicy.Statement.find((candidate: Record<string, unknown>) =>
      (candidate.Principal as { Service?: string })?.Service === 'logs.ap-northeast-1.amazonaws.com');
    expect(statement).toMatchObject({
      Effect: 'Allow',
      Action: ['kms:Encrypt', 'kms:Decrypt', 'kms:ReEncrypt*', 'kms:GenerateDataKey*', 'kms:DescribeKey'],
      Resource: '*',
    });
    const encryptionContext = JSON.stringify(statement.Condition.ArnEquals['kms:EncryptionContext:aws:logs:arn']);
    expect(encryptionContext).toContain('123456789012');
    expect(encryptionContext).toContain(`log-group:/fridge-manager/${environment}/foundation`);
    expect(encryptionContext).not.toContain(`${environment}/foundation*`);
  });

  it.each(['dev', 'stg'] as const)('%s の一時基盤は削除可能にする', (environment) => {
    const app = new App();
    const config = getEnvironmentConfig(environment);
    const template = Template.fromStack(new FoundationStack(app, config.stackName, { config }));

    for (const resource of Object.values(template.findResources('AWS::KMS::Key'))) {
      expect(resource).toMatchObject({ DeletionPolicy: 'Delete', UpdateReplacePolicy: 'Delete' });
    }
    for (const resource of Object.values(template.findResources('AWS::Logs::LogGroup'))) {
      expect(resource).toMatchObject({ DeletionPolicy: 'Delete', UpdateReplacePolicy: 'Delete' });
    }
  });

  it('prod の鍵とログを削除から保護する', () => {
    const app = new App();
    const config = getEnvironmentConfig('prod');
    const template = Template.fromStack(new FoundationStack(app, config.stackName, { config }));

    for (const resource of Object.values(template.findResources('AWS::KMS::Key'))) {
      expect(resource).toMatchObject({ DeletionPolicy: 'Retain', UpdateReplacePolicy: 'Retain' });
    }
    for (const resource of Object.values(template.findResources('AWS::Logs::LogGroup'))) {
      expect(resource).toMatchObject({ DeletionPolicy: 'Retain', UpdateReplacePolicy: 'Retain' });
    }
  });

  it.each(['stg', 'prod'] as const)('%s のAOP truststoreを暗号化・versioning・公開遮断付きで用意する', (environment) => {
    const app = new App();
    const config = getEnvironmentConfig(environment);
    const stack = new FoundationStack(app, config.stackName, { config });
    const template = Template.fromStack(stack);

    template.hasResourceProperties('AWS::S3::Bucket', {
      BucketEncryption: Match.objectLike({ ServerSideEncryptionConfiguration: Match.arrayWith([
        Match.objectLike({ ServerSideEncryptionByDefault: { SSEAlgorithm: 'aws:kms', KMSMasterKeyID: Match.anyValue() } }),
      ]) }),
      PublicAccessBlockConfiguration: {
        BlockPublicAcls: true,
        BlockPublicPolicy: true,
        IgnorePublicAcls: true,
        RestrictPublicBuckets: true,
      },
      VersioningConfiguration: { Status: 'Enabled' },
    });
    template.hasResourceProperties('AWS::S3::BucketPolicy', {
      PolicyDocument: Match.objectLike({ Statement: Match.arrayWith([
        Match.objectLike({
          Effect: 'Deny',
          Condition: { Bool: { 'aws:SecureTransport': 'false' } },
        }),
        Match.objectLike({
          Sid: 'AllowApiGatewayReadMtlsTruststore',
          Principal: { Service: 'apigateway.amazonaws.com' },
          Action: ['s3:GetObject', 's3:GetObjectVersion'],
          Resource: Match.anyValue(),
        }),
      ]) }),
    });
    template.hasResourceProperties('AWS::KMS::Key', {
      KeyPolicy: Match.objectLike({ Statement: Match.arrayWith([
        Match.objectLike({
          Sid: 'AllowApiGatewayDecryptMtlsTruststore',
          Principal: { Service: 'apigateway.amazonaws.com' },
          Action: 'kms:Decrypt',
        }),
      ]) }),
    });
    template.hasOutput('AopTruststoreBucketName', { Value: Match.anyValue() });
    template.hasOutput('AopTruststoreBucketName', { Export: { Name: `fridge-manager-${environment}-aop-truststore-bucket` } });
  });

  it('dev には公開AOP truststoreを作らない', () => {
    const app = new App();
    const config = getEnvironmentConfig('dev');
    const template = Template.fromStack(new FoundationStack(app, config.stackName, { config }));

    template.resourceCountIs('AWS::S3::Bucket', 0);
  });
});

describe('LeastPrivilegeIamAspect', () => {
  it('Action または Resource がワイルドカードの IAM ポリシーを拒否する', () => {
    const app = new App();
    const stack = new Stack(app, 'BroadPolicy');
    Tags.of(stack).add('Environment', 'dev');
    const role = new iam.Role(stack, 'Role', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
    });
    role.addToPolicy(new iam.PolicyStatement({ actions: ['*'], resources: ['*'] }));
    Aspects.of(stack).add(new LeastPrivilegeIamAspect());

    expect(() => app.synth()).toThrow(/最小権限違反/);
  });

  it('対象リソースと操作を限定した IAM ポリシーを許可する', () => {
    const app = new App();
    const stack = new Stack(app, 'ScopedPolicy');
    const role = new iam.Role(stack, 'Role', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
    });
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['logs:CreateLogStream', 'logs:PutLogEvents'],
      resources: ['arn:aws:logs:ap-northeast-1:123456789012:log-group:/aws/lambda/example:*'],
    }));
    Aspects.of(stack).add(new LeastPrivilegeIamAspect());

    expect(() => app.synth()).not.toThrow();
  });
});
