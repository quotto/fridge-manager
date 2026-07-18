import type { APIGatewayRequestAuthorizerEvent } from 'aws-lambda';
import { AuthFailureAuditor, createFirebaseAuthorizer, FirebaseTokenVerifier } from '../lambda/firebase-authorizer';

const now = 1_800_000_000;
const projectId = 'fridge-manager-prod';
const projectNumber = '123456789012';
const appId = '1:123456789012:android:abc123';
const validId = { uid: 'anonymous-user', aud: projectId, iss: `https://securetoken.google.com/${projectId}`, exp: now + 60, iat: now - 30, authTime: now - 60 };
const validApp = { appId, aud: [`projects/${projectId}`], iss: `https://firebaseappcheck.googleapis.com/${projectNumber}`, exp: now + 60, iat: now - 30 };

function event(headers: Record<string, string> = { authorization: 'Bearer id.token.value', 'x-firebase-appcheck': 'app.token.value' }): APIGatewayRequestAuthorizerEvent {
  return { type: 'REQUEST', methodArn: 'arn:aws:execute-api:ap-northeast-1:123456789012:api/dev/POST/v1/analysis', resource: '/v1/analysis', path: '/v1/analysis', httpMethod: 'POST', headers, multiValueHeaders: {}, pathParameters: null, queryStringParameters: null, multiValueQueryStringParameters: null, stageVariables: null, requestContext: { requestId: 'request-correlation-id' } as never };
}

function fixture(overrides: Partial<FirebaseTokenVerifier> = {}) {
  const verifier: FirebaseTokenVerifier = {
    verifyIdToken: jest.fn().mockResolvedValue(validId),
    verifyAndConsumeAppCheckToken: jest.fn().mockResolvedValue(validApp),
    ...overrides,
  };
  const auditor: AuthFailureAuditor = { record: jest.fn() };
  return { verifier, auditor, authorizer: createFirebaseAuthorizer({ projectId, projectNumber, allowedAppIds: new Set([appId]), verifier, auditor, now: () => now }) };
}

describe('Firebase Lambda authorizer', () => {
  it('双方のtoken検証とlimited-use token消費後のみ最小contextを許可する', async () => {
    const { authorizer, verifier } = fixture();
    const result = await authorizer(event());
    expect(verifier.verifyIdToken).toHaveBeenCalledWith('id.token.value');
    expect(verifier.verifyAndConsumeAppCheckToken).toHaveBeenCalledWith('app.token.value');
    expect(result).toMatchObject({ principalId: 'anonymous-user', policyDocument: { Statement: [{ Effect: 'Allow' }] }, context: { firebaseVerified: true, appCheckVerified: true, userId: 'anonymous-user' } });
  });

  it.each([
    ['audience改ざんID token', { ...validId, aud: 'other-project' }],
    ['issuer改ざんID token', { ...validId, iss: 'https://attacker.invalid' }],
    ['期限切れID token', { ...validId, exp: now }],
    ['未来発行ID token', { ...validId, iat: now + 1 }],
    ['未来認証ID token', { ...validId, authTime: now + 1 }],
  ])('%sをApp Check消費前に拒否する', async (_name, decoded) => {
    const { authorizer, verifier, auditor } = fixture({ verifyIdToken: jest.fn().mockResolvedValue(decoded) });
    await expect(authorizer(event())).rejects.toThrow('Unauthorized');
    expect(verifier.verifyAndConsumeAppCheckToken).not.toHaveBeenCalled();
    expect(auditor.record).toHaveBeenCalledWith({ code: 'INVALID_ID_TOKEN', requestId: 'request-correlation-id' });
  });

  it.each([
    ['audience改ざんApp Check', { ...validApp, aud: ['projects/999999'] }, 'INVALID_APP_CHECK_TOKEN'],
    ['issuer改ざんApp Check', { ...validApp, iss: 'https://attacker.invalid' }, 'INVALID_APP_CHECK_TOKEN'],
    ['期限切れApp Check', { ...validApp, exp: now }, 'INVALID_APP_CHECK_TOKEN'],
    ['未来発行App Check', { ...validApp, iat: now + 1 }, 'INVALID_APP_CHECK_TOKEN'],
    ['許可外app ID', { ...validApp, appId: '1:123456789012:android:other' }, 'INVALID_APP_CHECK_TOKEN'],
    ['再利用limited-use token', { ...validApp, alreadyConsumed: true }, 'REPLAYED_APP_CHECK_TOKEN'],
  ])('%sを拒否し分類監査する', async (_name, decoded, code) => {
    const { authorizer, auditor } = fixture({ verifyAndConsumeAppCheckToken: jest.fn().mockResolvedValue(decoded) });
    await expect(authorizer(event())).rejects.toThrow('Unauthorized');
    expect(auditor.record).toHaveBeenCalledWith({ code, requestId: 'request-correlation-id' });
  });

  it('署名検証失敗を拒否しtoken本文を監査へ渡さない', async () => {
    const { authorizer, auditor } = fixture({ verifyIdToken: jest.fn().mockRejectedValue(new Error('bad signature id.token.value')) });
    await expect(authorizer(event())).rejects.toThrow('Unauthorized');
    expect(auditor.record).toHaveBeenCalledWith({ code: 'INVALID_ID_TOKEN', requestId: 'request-correlation-id' });
    expect(JSON.stringify((auditor.record as jest.Mock).mock.calls)).not.toContain('id.token.value');
  });

  it('同じlimited-use tokenの原子的消費結果に従い2回目を拒否する', async () => {
    const consume = jest.fn().mockResolvedValueOnce(validApp).mockResolvedValueOnce({ ...validApp, alreadyConsumed: true });
    const { authorizer, auditor } = fixture({ verifyAndConsumeAppCheckToken: consume });
    await expect(authorizer(event())).resolves.toMatchObject({ principalId: 'anonymous-user' });
    await expect(authorizer(event())).rejects.toThrow('Unauthorized');
    expect(consume).toHaveBeenCalledTimes(2);
    expect(auditor.record).toHaveBeenCalledWith({ code: 'REPLAYED_APP_CHECK_TOKEN', requestId: 'request-correlation-id' });
  });

  it('同じlimited-use tokenの並行消費競合では一方だけを許可する', async () => {
    const consume = jest.fn().mockResolvedValueOnce(validApp).mockResolvedValueOnce({ ...validApp, alreadyConsumed: true });
    const { authorizer } = fixture({ verifyAndConsumeAppCheckToken: consume });

    const results = await Promise.allSettled([authorizer(event()), authorizer(event())]);

    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    expect(results.filter((result) => result.status === 'rejected')).toHaveLength(1);
    expect(consume).toHaveBeenCalledTimes(2);
  });

  it('App Check consume APIまたはJWKSの障害時はtoken情報を残さずfail-closedにする', async () => {
    const { authorizer, auditor } = fixture({
      verifyAndConsumeAppCheckToken: jest.fn().mockRejectedValue(new Error('network app.token.value')),
    });

    await expect(authorizer(event())).rejects.toThrow('Unauthorized');

    expect(auditor.record).toHaveBeenCalledWith({ code: 'INVALID_APP_CHECK_TOKEN', requestId: 'request-correlation-id' });
    expect(JSON.stringify((auditor.record as jest.Mock).mock.calls)).not.toContain('app.token.value');
  });

  it.each([
    ['token不足', {}, 'MISSING_TOKEN'],
    ['Bearer形式不正', { authorization: 'Basic secret', 'x-firebase-appcheck': 'app.token.value' }, 'MALFORMED_TOKEN'],
  ])('%sを検証器へ渡さない', async (_name, headers, code) => {
    const { authorizer, verifier, auditor } = fixture();
    await expect(authorizer(event(headers))).rejects.toThrow('Unauthorized');
    expect(verifier.verifyIdToken).not.toHaveBeenCalled();
    expect(auditor.record).toHaveBeenCalledWith({ code, requestId: 'request-correlation-id' });
  });
});
