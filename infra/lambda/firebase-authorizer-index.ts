import { getAppCheck } from 'firebase-admin/app-check';
import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { writeFileSync } from 'node:fs';
import { createFirebaseAuthorizer, DecodedAppCheckToken, FirebaseTokenVerifier } from './firebase-authorizer';
import { createGoogleWifConfig } from './google-wif-config';

const projectId = process.env.FIREBASE_PROJECT_ID;
const projectNumber = process.env.FIREBASE_PROJECT_NUMBER;
const appIds = process.env.FIREBASE_APP_IDS;
const wifAudience = process.env.GOOGLE_WIF_AUDIENCE;
const serviceAccountEmail = process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL;
if (!projectId || !projectNumber || !appIds || !wifAudience || !serviceAccountEmail) throw new Error('Firebase authorizer environment is required');

const credentialPath = '/tmp/google-wif-config.json';
writeFileSync(credentialPath, JSON.stringify(createGoogleWifConfig(wifAudience, serviceAccountEmail)), { mode: 0o600 });
process.env.GOOGLE_APPLICATION_CREDENTIALS = credentialPath;

const app = initializeApp({ projectId });
const verifier: FirebaseTokenVerifier = {
  async verifyIdToken(token) {
    const decoded = await getAuth(app).verifyIdToken(token);
    return { uid: decoded.uid, aud: decoded.aud, iss: decoded.iss, exp: decoded.exp, iat: decoded.iat, authTime: decoded.auth_time };
  },
  async verifyAndConsumeAppCheckToken(token) {
    const decoded = await getAppCheck(app).verifyToken(token, { consume: true });
    return {
      appId: decoded.appId, aud: decoded.token.aud, iss: decoded.token.iss, exp: decoded.token.exp, iat: decoded.token.iat,
      ...(decoded.alreadyConsumed === undefined ? {} : { alreadyConsumed: decoded.alreadyConsumed }),
    } as DecodedAppCheckToken;
  },
};

export const main = createFirebaseAuthorizer({
  projectId, projectNumber, allowedAppIds: new Set(appIds.split(',').map((value) => value.trim()).filter(Boolean)), verifier,
  auditor: { record(event) { console.warn(JSON.stringify({ event: 'firebase_authorization_failed', ...event })); } },
});
