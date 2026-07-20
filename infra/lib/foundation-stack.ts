import { Aspects, Stack, StackProps, Tags } from 'aws-cdk-lib';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
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
    const logGroupName = `/fridge-manager/${config.environment}/foundation`;
    encryptionKey.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal(`logs.${this.region}.amazonaws.com`)],
      actions: ['kms:Encrypt', 'kms:Decrypt', 'kms:ReEncrypt*', 'kms:GenerateDataKey*', 'kms:DescribeKey'],
      resources: ['*'],
      conditions: {
        ArnEquals: {
          'kms:EncryptionContext:aws:logs:arn': `arn:${this.partition}:logs:${this.region}:${this.account}:log-group:${logGroupName}`,
        },
      },
    }));

    const logGroup = new logs.LogGroup(this, 'FoundationLogGroup', {
      logGroupName,
      encryptionKey,
      retention: config.logRetention,
      removalPolicy: config.removalPolicy,
    });

    // 明示的な依存により、ログ削除前に暗号鍵が削除されることを防ぐ。
    logGroup.node.addDependency(encryptionKey);
    Aspects.of(this).add(new LeastPrivilegeIamAspect());
  }

  private applyRequiredTags(config: EnvironmentConfig): void {
    Tags.of(this).add('Application', 'fridge-manager');
    Tags.of(this).add('Environment', config.environment);
    Tags.of(this).add('ManagedBy', 'aws-cdk');
    Tags.of(this).add('DataClassification', 'sensitive');
  }
}
