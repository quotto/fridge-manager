import { createGoogleWifConfig } from '../lambda/google-wif-config';

describe('Google Workload Identity Federation設定', () => {
  it('秘密鍵を保存せずAWS実行ロールから短期資格情報を取得する設定を作る', () => {
    const config = createGoogleWifConfig(
      '//iam.googleapis.com/projects/123456789012/locations/global/workloadIdentityPools/aws-pool/providers/lambda-provider',
      'firebase-verifier@fridge-manager-prod.iam.gserviceaccount.com',
    );
    expect(config).toMatchObject({ type: 'external_account', credential_source: { environment_id: 'aws1' } });
    expect(JSON.stringify(config)).not.toMatch(/private_key|client_secret|token_value/i);
  });

  it('不正なproviderまたはservice account識別子を拒否する', () => {
    expect(() => createGoogleWifConfig('attacker', 'firebase-verifier@example.com')).toThrow();
  });
});
