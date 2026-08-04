import type { APIGatewayRequestAuthorizerEvent, APIGatewayAuthorizerResult } from 'aws-lambda';

export interface DecodedIdToken {
  readonly uid: string;
  readonly aud: string;
  readonly iss: string;
  readonly exp: number;
  readonly iat: number;
  readonly authTime: number;
}
export interface DecodedAppCheckToken {
  readonly appId: string;
  readonly aud: readonly string[];
  readonly iss: string;
  readonly exp: number;
  readonly iat: number;
  readonly alreadyConsumed?: boolean;
}
export interface FirebaseTokenVerifier {
  verifyIdToken(token: string): Promise<DecodedIdToken>;
  verifyAndConsumeAppCheckToken(token: string): Promise<DecodedAppCheckToken>;
}
export type AuthFailureCode = 'MISSING_TOKEN' | 'MALFORMED_TOKEN' | 'INVALID_ID_TOKEN' |
  'INVALID_APP_CHECK_TOKEN' | 'REPLAYED_APP_CHECK_TOKEN';
export interface AuthFailureAuditor {
  record(event: { readonly code: AuthFailureCode; readonly requestId?: string }): void;
}

const PROJECT_NUMBER = /^[1-9][0-9]{5,19}$/;
const PROJECT_ID = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;

function header(headers: Record<string, string | undefined> | null, name: string): string | undefined {
  return Object.entries(headers ?? {}).find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1];
}

function allow(principalId: string, resource: string): APIGatewayAuthorizerResult {
  return {
    principalId,
    policyDocument: { Version: '2012-10-17', Statement: [{ Action: 'execute-api:Invoke', Effect: 'Allow', Resource: resource }] },
    context: { firebaseVerified: true, appCheckVerified: true, userId: principalId },
  };
}

function validTime(value: number, now: number): boolean {
  return Number.isSafeInteger(value) && value > 0 && value <= now;
}

export function createFirebaseAuthorizer(deps: {
  readonly projectId: string;
  readonly projectNumber: string;
  readonly allowedAppIds: ReadonlySet<string>;
  readonly verifier: FirebaseTokenVerifier;
  readonly auditor: AuthFailureAuditor;
  readonly now?: () => number;
}) {
  if (!PROJECT_ID.test(deps.projectId) || !PROJECT_NUMBER.test(deps.projectNumber) || deps.allowedAppIds.size === 0) {
    throw new Error('Firebase authorizer configuration is invalid');
  }
  const now = deps.now ?? (() => Math.floor(Date.now() / 1000));
  return async (event: APIGatewayRequestAuthorizerEvent): Promise<APIGatewayAuthorizerResult> => {
    const requestId = event.requestContext?.requestId;
    const authorization = header(event.headers, 'authorization');
    const appCheckToken = header(event.headers, 'x-firebase-appcheck');
    if (!authorization || !appCheckToken) {
      deps.auditor.record({ code: 'MISSING_TOKEN', ...(requestId ? { requestId } : {}) });
      throw new Error('Unauthorized');
    }
    const match = /^Bearer ([A-Za-z0-9._~-]+)$/.exec(authorization);
    if (!match?.[1]) {
      deps.auditor.record({ code: 'MALFORMED_TOKEN', ...(requestId ? { requestId } : {}) });
      throw new Error('Unauthorized');
    }
    let idToken: DecodedIdToken;
    try {
      idToken = await deps.verifier.verifyIdToken(match[1]);
      const currentTime = now();
      if (!idToken.uid || idToken.aud !== deps.projectId || idToken.iss !== `https://securetoken.google.com/${deps.projectId}` ||
          !Number.isSafeInteger(idToken.exp) || idToken.exp <= currentTime || !validTime(idToken.iat, currentTime) || !validTime(idToken.authTime, currentTime)) throw new Error('invalid claims');
    } catch {
      deps.auditor.record({ code: 'INVALID_ID_TOKEN', ...(requestId ? { requestId } : {}) });
      throw new Error('Unauthorized');
    }
    try {
      const appCheck = await deps.verifier.verifyAndConsumeAppCheckToken(appCheckToken);
      const currentTime = now();
      const validAudience = appCheck.aud.includes(`projects/${deps.projectId}`);
      if (!appCheck.appId || !deps.allowedAppIds.has(appCheck.appId) || !validAudience ||
          appCheck.iss !== `https://firebaseappcheck.googleapis.com/${deps.projectNumber}` || !Number.isSafeInteger(appCheck.exp) ||
          appCheck.exp <= currentTime || !validTime(appCheck.iat, currentTime)) throw new Error('invalid claims');
      if (appCheck.alreadyConsumed === true) {
        deps.auditor.record({ code: 'REPLAYED_APP_CHECK_TOKEN', ...(requestId ? { requestId } : {}) });
        throw new Error('replayed');
      }
    } catch (error) {
      console.error(error.message);
      console.error(error.stack?.split('\n'));
      if (error instanceof Error && error.message === 'replayed') throw new Error('Unauthorized');
      deps.auditor.record({ code: 'INVALID_APP_CHECK_TOKEN', ...(requestId ? { requestId } : {}) });
      throw new Error('Unauthorized');
    }
    return allow(idToken.uid, event.methodArn);
  };
}
