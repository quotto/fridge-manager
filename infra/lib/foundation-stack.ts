import { Aspects, CfnOutput, Stack, StackProps, Tags } from 'aws-cdk-lib';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';
import { EnvironmentConfig } from './environment-config';
import { LeastPrivilegeIamAspect } from './least-privilege-iam-aspect';

export interface FoundationStackProps extends StackProps {
  readonly config: EnvironmentConfig;
}

export class FoundationStack extends Stack {
  public constructor(scope: Construct, id: string, props: FoundationStackProps) {
    super(scope, id, {
      ...props,
      terminationProtection: props.config.terminationProtection,
    });

    const { config } = props;
    this.applyRequiredTags(config);

    const encryptionKey = new kms.Key(this, 'FoundationEncryptionKey', {
      alias: `alias/fridge-manager/${config.environment}/foundation`,
      description: `fridge-manager ${config.environment} foundation encryption key`,
      enableKeyRotation: true,
      removalPolicy: config.removalPolicy,
    });

    const logGroup = new logs.LogGroup(this, 'FoundationLogGroup', {
      logGroupName: `/fridge-manager/${config.environment}/foundation`,
      encryptionKey,
      retention: config.logRetention,
      removalPolicy: config.removalPolicy,
    });

    // 明示的な依存により、ログ削除前に暗号鍵が削除されることを防ぐ。
    logGroup.node.addDependency(encryptionKey);

    if (config.environment !== 'dev') {
      const truststore = new s3.Bucket(this, 'AopTruststore', {
        // truststore は公開CA chainだけを置く。API Gateway の読取り互換性を
        // 保ちつつ、S3のサーバー側暗号化を強制する。
        encryption: s3.BucketEncryption.S3_MANAGED,
        blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
        enforceSSL: true,
        versioned: true,
        removalPolicy: config.removalPolicy,
        autoDeleteObjects: false,
      });
      truststore.node.addDependency(encryptionKey);
      new CfnOutput(this, 'AopTruststoreBucketName', {
        value: truststore.bucketName,
        description: 'Cloudflare AOP public CA truststore bucket name',
        exportName: `fridge-manager-${config.environment}-aop-truststore-bucket`,
      });
    }
    Aspects.of(this).add(new LeastPrivilegeIamAspect());
  }

  private applyRequiredTags(config: EnvironmentConfig): void {
    Tags.of(this).add('Application', 'fridge-manager');
    Tags.of(this).add('Environment', config.environment);
    Tags.of(this).add('ManagedBy', 'aws-cdk');
    Tags.of(this).add('DataClassification', 'sensitive');
  }
}
