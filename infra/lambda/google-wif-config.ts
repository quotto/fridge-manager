export interface GoogleWifConfig {
  readonly type: 'external_account';
  readonly audience: string;
  readonly subject_token_type: 'urn:ietf:params:aws:token-type:aws4_request';
  readonly token_url: 'https://sts.googleapis.com/v1/token';
  readonly service_account_impersonation_url: string;
  readonly credential_source: {
    readonly environment_id: 'aws1';
    readonly region_url: string;
    readonly url: string;
    readonly regional_cred_verification_url: string;
  };
}

export function createGoogleWifConfig(audience: string, serviceAccountEmail: string): GoogleWifConfig {
  if (!/^\/\/iam\.googleapis\.com\/projects\/[1-9][0-9]+\/locations\/global\/workloadIdentityPools\/[A-Za-z0-9_-]+\/providers\/[A-Za-z0-9_-]+$/.test(audience) ||
      !/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com$/.test(serviceAccountEmail)) {
    throw new Error('Google workload identity configuration is invalid');
  }
  return {
    type: 'external_account', audience,
    subject_token_type: 'urn:ietf:params:aws:token-type:aws4_request',
    token_url: 'https://sts.googleapis.com/v1/token',
    service_account_impersonation_url: `https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${encodeURIComponent(serviceAccountEmail)}:generateAccessToken`,
    credential_source: {
      environment_id: 'aws1',
      region_url: 'http://169.254.169.254/latest/meta-data/placement/availability-zone',
      url: 'http://169.254.169.254/latest/meta-data/iam/security-credentials',
      regional_cred_verification_url: 'https://sts.{region}.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15',
    },
  };
}
