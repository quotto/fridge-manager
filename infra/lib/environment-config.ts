import { RemovalPolicy } from 'aws-cdk-lib';
import { RetentionDays } from 'aws-cdk-lib/aws-logs';

export const supportedEnvironments = ['dev', 'stg', 'prod'] as const;

export type DeploymentEnvironment = (typeof supportedEnvironments)[number];

export interface EnvironmentConfig {
  readonly environment: DeploymentEnvironment;
  readonly stackName: string;
  readonly accountEnvVar: string;
  readonly region: 'ap-northeast-1';
  readonly logRetention: RetentionDays;
  readonly logRetentionDays: 14 | 30 | 90;
  readonly removalPolicy: RemovalPolicy;
  readonly retainData: boolean;
  readonly terminationProtection: boolean;
}

const environmentConfigs: Readonly<Record<DeploymentEnvironment, EnvironmentConfig>> = {
  dev: {
    environment: 'dev',
    stackName: 'FridgeManagerDevFoundation',
    accountEnvVar: 'FRIDGE_MANAGER_DEV_AWS_ACCOUNT',
    region: 'ap-northeast-1',
    logRetention: RetentionDays.TWO_WEEKS,
    logRetentionDays: 14,
    removalPolicy: RemovalPolicy.DESTROY,
    retainData: false,
    terminationProtection: false,
  },
  stg: {
    environment: 'stg',
    stackName: 'FridgeManagerStgFoundation',
    accountEnvVar: 'FRIDGE_MANAGER_STG_AWS_ACCOUNT',
    region: 'ap-northeast-1',
    logRetention: RetentionDays.ONE_MONTH,
    logRetentionDays: 30,
    removalPolicy: RemovalPolicy.DESTROY,
    retainData: false,
    terminationProtection: false,
  },
  prod: {
    environment: 'prod',
    stackName: 'FridgeManagerProdFoundation',
    accountEnvVar: 'FRIDGE_MANAGER_PROD_AWS_ACCOUNT',
    region: 'ap-northeast-1',
    logRetention: RetentionDays.THREE_MONTHS,
    logRetentionDays: 90,
    removalPolicy: RemovalPolicy.RETAIN,
    retainData: true,
    terminationProtection: true,
  },
};

export function getEnvironmentConfig(environment: string): EnvironmentConfig {
  if (!supportedEnvironments.includes(environment as DeploymentEnvironment)) {
    throw new Error(`未対応の環境です: ${environment}`);
  }

  return environmentConfigs[environment as DeploymentEnvironment];
}
