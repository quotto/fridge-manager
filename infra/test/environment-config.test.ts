import { getEnvironmentConfig, supportedEnvironments } from '../lib/environment-config';

describe('環境設定', () => {
  it('dev・stg・prod を同一ソースから生成できる', () => {
    expect(supportedEnvironments).toEqual(['dev', 'stg', 'prod']);

    const configs = supportedEnvironments.map(getEnvironmentConfig);
    expect(new Set(configs.map(({ stackName }) => stackName)).size).toBe(3);
    expect(new Set(configs.map(({ accountEnvVar }) => accountEnvVar)).size).toBe(3);
    expect(configs.every(({ region }) => region === 'ap-northeast-1')).toBe(true);
  });

  it('環境ごとにログ保持と削除保護を強化する', () => {
    expect(getEnvironmentConfig('dev')).toMatchObject({
      logRetentionDays: 14,
      apiIntegrationTimeoutMillis: 29_000,
      retainData: false,
      terminationProtection: false,
    });
    expect(getEnvironmentConfig('stg')).toMatchObject({
      logRetentionDays: 30,
      apiIntegrationTimeoutMillis: 29_000,
      retainData: false,
      terminationProtection: false,
    });
    expect(getEnvironmentConfig('prod')).toMatchObject({
      logRetentionDays: 90,
      apiIntegrationTimeoutMillis: 60_000,
      retainData: true,
      terminationProtection: true,
    });
  });

  it('未定義環境を拒否する', () => {
    expect(() => getEnvironmentConfig('qa')).toThrow('未対応の環境です: qa');
  });
});
