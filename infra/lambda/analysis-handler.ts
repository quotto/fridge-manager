import { createHash } from 'node:crypto';
import type { APIGatewayProxyStructuredResultV2 } from 'aws-lambda';
import { decode } from 'jpeg-js';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import requestSchema from '../api/schemas/analysis-request.schema.json';
import responseSchema from '../api/schemas/analysis-response.schema.json';
import candidateSchema from '../api/schemas/food-candidate.schema.json';
import { ValidatedAnalysisResult, validateProviderResult } from './provider-boundary';
import { QuotaLimitType, QuotaStore } from './quota-store';
import { AnalysisTelemetry, TelemetryOutcome } from './analysis-telemetry';

export type { AnalysisProviderResult } from './provider-boundary';

export type AnalysisMode = 'new' | 'update';
export interface AnalysisRequest {
  readonly requestId: string;
  readonly mode: AnalysisMode;
  readonly image: { readonly mediaType: 'image/jpeg'; readonly base64: string };
  readonly currentItems?: readonly { readonly name: string; readonly quantity: string; readonly unit: string }[];
}
export interface AnalysisProvider { analyze(request: AnalysisRequest): Promise<unknown>; }
export type IdempotencyClaim = { readonly kind: 'claimed' } | { readonly kind: 'conflict' } |
  { readonly kind: 'duplicate'; readonly status: 'IN_PROGRESS' | 'COMPLETED' };
export interface IdempotencyStore {
  claim(key: string, hash: string): Promise<IdempotencyClaim>;
  complete(key: string): Promise<void>;
  abandon(key: string, hash: string): Promise<void>;
}

type ErrorCode = 'INVALID_REQUEST' | 'INVALID_IMAGE' | 'UNAUTHORIZED' | 'DUPLICATE_REQUEST' |
  'IDEMPOTENCY_CONFLICT' | 'TIMEOUT' | 'PROVIDER_UNAVAILABLE' | 'QUOTA_EXCEEDED' |
  'QUOTA_UNAVAILABLE' | 'SERVICE_STOPPED' | 'UNANALYZABLE_IMAGE' | 'INTERNAL_ERROR';

export class AnalysisError extends Error {
  public constructor(public readonly code: ErrorCode, public readonly statusCode: number, public readonly retryAt?: string, public readonly quotaType?: QuotaLimitType) {
    super(code);
  }
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const QUANTITY = /^(?:(?:0|[1-9][0-9]?)(?:\.[0-9]{1,2})?|100)$/;
const UNITS = new Set(['g', 'kg', 'ml', 'L', '個', '本', '枚', '袋', 'パック', '箱', '缶', '瓶', '束', '株', '玉', '丁', '尾', '切れ', '房', '合', '食']);
const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
addFormats(ajv);
ajv.addSchema(candidateSchema);
const validateRequestSchema = ajv.compile(requestSchema);
const validateResponseSchema = ajv.compile(responseSchema);

function containsControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f));
  });
}

function ownKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}

function parseRequest(body: string | null | undefined): AnalysisRequest {
  if (!body || body.length > 4_300_000) throw new AnalysisError('INVALID_REQUEST', 400);
  let value: unknown;
  try { value = JSON.parse(body); } catch { throw new AnalysisError('INVALID_REQUEST', 400); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new AnalysisError('INVALID_REQUEST', 400);
  const input = value as Record<string, unknown>;
  if (!validateRequestSchema(input)) {
    const imageError = validateRequestSchema.errors?.some((error) => error.instancePath.startsWith('/image')) === true;
    throw new AnalysisError(imageError ? 'INVALID_IMAGE' : 'INVALID_REQUEST', 400);
  }
  if (!ownKeys(input, ['requestId', 'mode', 'image', 'currentItems']) || typeof input.requestId !== 'string' ||
      !UUID.test(input.requestId) || (input.mode !== 'new' && input.mode !== 'update') ||
      !input.image || typeof input.image !== 'object' || Array.isArray(input.image)) {
    throw new AnalysisError('INVALID_REQUEST', 400);
  }
  const image = input.image as Record<string, unknown>;
  if (!ownKeys(image, ['mediaType', 'base64']) || image.mediaType !== 'image/jpeg' || typeof image.base64 !== 'string') {
    throw new AnalysisError('INVALID_REQUEST', 400);
  }
  if (input.mode === 'new' && input.currentItems !== undefined) throw new AnalysisError('INVALID_REQUEST', 400);
  if (input.mode === 'update') {
    if (!Array.isArray(input.currentItems) || input.currentItems.length < 1 || input.currentItems.length > 30) throw new AnalysisError('INVALID_REQUEST', 400);
    for (const item of input.currentItems) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) throw new AnalysisError('INVALID_REQUEST', 400);
      const candidate = item as Record<string, unknown>;
      if (!ownKeys(candidate, ['name', 'quantity', 'unit']) || typeof candidate.name !== 'string' ||
          candidate.name.trim().length < 1 || [...candidate.name.trim()].length > 30 ||
          containsControlCharacter(candidate.name) ||
          typeof candidate.quantity !== 'string' || !QUANTITY.test(candidate.quantity) ||
          typeof candidate.unit !== 'string' || !UNITS.has(candidate.unit)) throw new AnalysisError('INVALID_REQUEST', 400);
    }
  }
  validateJpeg(image.base64);
  return input as unknown as AnalysisRequest;
}

function validateJpeg(base64: string): void {
  if (base64.length > 4_194_304 || !BASE64.test(base64)) throw new AnalysisError('INVALID_IMAGE', 400);
  const bytes = Buffer.from(base64, 'base64');
  if (bytes.byteLength > 3_145_728 || bytes.length < 12 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9) throw new AnalysisError('INVALID_IMAGE', 400);
  let dimensions: { width: number; height: number } | undefined;
  let offset = 2;
  while (offset + 8 < bytes.length) {
    if (bytes[offset] !== 0xff) { offset += 1; continue; }
    const marker = bytes[offset + 1];
    if (marker === 0xd9 || marker === 0xda) break;
    const length = bytes.readUInt16BE(offset + 2);
    if (length < 2 || offset + length + 2 > bytes.length) break;
    if (marker !== undefined && ((marker >= 0xc0 && marker <= 0xc3) || (marker >= 0xc5 && marker <= 0xc7) || (marker >= 0xc9 && marker <= 0xcb) || (marker >= 0xcd && marker <= 0xcf))) {
      const height = bytes.readUInt16BE(offset + 5); const width = bytes.readUInt16BE(offset + 7);
      if (width < 1 || height < 1 || width > 2048 || height > 2048 || width * height > 4_000_000) throw new AnalysisError('INVALID_IMAGE', 400);
      dimensions = { width, height };
      break;
    }
    offset += length + 2;
  }
  if (!dimensions) throw new AnalysisError('INVALID_IMAGE', 400);
  try {
    const decoded = decode(bytes, { useTArray: true, maxResolutionInMP: 4, maxMemoryUsageInMB: 64, tolerantDecoding: false });
    if (decoded.width !== dimensions.width || decoded.height !== dimensions.height) throw new Error('dimension mismatch');
  } catch { throw new AnalysisError('INVALID_IMAGE', 400); }
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.entries(value as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`;
  return JSON.stringify(value);
}

function response(statusCode: number, requestId: string | undefined, code?: ErrorCode, retryAt?: string, result?: ValidatedAnalysisResult, quotaType?: QuotaLimitType): APIGatewayProxyStructuredResultV2 {
  const headers: Record<string, string> = { 'content-type': 'application/json', 'cache-control': 'no-store' };
  if (requestId) headers['x-request-id'] = requestId;
  return { statusCode, headers, body: JSON.stringify(result ?? {
    ...(requestId ? { requestId } : {}), status: 'failed', error: { code, retryable: statusCode >= 429, ...(retryAt ? { retryAt } : {}), ...(quotaType ? { quotaType } : {}) },
  }) };
}

export function createAnalysisHandler(deps: { readonly provider: AnalysisProvider; readonly idempotencyStore: IdempotencyStore; readonly quotaStore: QuotaStore; readonly telemetry?: AnalysisTelemetry; readonly now?: () => number }) {
  return async (event: { readonly body?: string | null; readonly headers: Record<string, string | undefined>; readonly requestContext: { readonly requestId?: string; readonly authorizer?: unknown } }): Promise<APIGatewayProxyStructuredResultV2> => {
    const startedAt = (deps.now ?? Date.now)();
    let providerCalled = false;
    const execute = async (): Promise<APIGatewayProxyStructuredResultV2> => {
    const rawAuthorizer = event.requestContext.authorizer;
    const outer = rawAuthorizer && typeof rawAuthorizer === 'object' ? rawAuthorizer as Record<string, unknown> : undefined;
    const nested = outer?.lambda;
    const auth = (nested && typeof nested === 'object' ? nested : outer) as Record<string, unknown> | undefined;
    const correlationId = event.requestContext.requestId;
    if (auth?.firebaseVerified !== 'true' || auth.appCheckVerified !== 'true' || typeof auth.userId !== 'string' || auth.userId.length === 0) return response(401, correlationId, 'UNAUTHORIZED');
    const contentType = Object.entries(event.headers).find(([key]) => key.toLowerCase() === 'content-type')?.[1];
    if (contentType?.split(';')[0]?.trim().toLowerCase() !== 'application/json') return response(415, event.requestContext.requestId, 'INVALID_REQUEST');
    let request: AnalysisRequest;
    try { request = parseRequest(event.body); } catch (error) {
      const typed = error instanceof AnalysisError ? error : new AnalysisError('INVALID_REQUEST', 400);
      return response(typed.statusCode, correlationId, typed.code);
    }
    const hash = createHash('sha256').update(canonical(request)).digest('hex');
    const userHash = createHash('sha256').update(auth.userId).digest('hex');
    let claimed = false;
    let reservationKey: string | undefined;
    let providerSucceeded = false;
    try {
      const claim = await deps.idempotencyStore.claim(`${userHash}#${request.requestId}`, hash);
      if (claim.kind === 'conflict') return response(409, request.requestId, 'IDEMPOTENCY_CONFLICT');
      if (claim.kind === 'duplicate') return response(409, request.requestId, 'DUPLICATE_REQUEST');
      claimed = true;
      let quota;
      try { quota = await deps.quotaStore.reserve(userHash, request.requestId); } catch { throw new AnalysisError('QUOTA_UNAVAILABLE', 503); }
      if (quota.kind === 'stopped') {
        await deps.idempotencyStore.abandon(`${userHash}#${request.requestId}`, hash);
        claimed = false;
        return response(503, request.requestId, 'SERVICE_STOPPED');
      }
      if (quota.kind === 'exceeded') {
        await deps.idempotencyStore.abandon(`${userHash}#${request.requestId}`, hash);
        claimed = false;
        return response(429, request.requestId, 'QUOTA_EXCEEDED', quota.retryAt, undefined, quota.limitType);
      }
      reservationKey = quota.reservationKey;
      providerCalled = true;
      const rawResult = await deps.provider.analyze(request);
      const result = validateProviderResult(rawResult, request.requestId);
      if (!result || !validateResponseSchema(result)) throw new AnalysisError('UNANALYZABLE_IMAGE', 422);
      providerSucceeded = true;
      await deps.quotaStore.succeed(reservationKey);
      await deps.idempotencyStore.complete(`${userHash}#${request.requestId}`);
      return response(200, request.requestId, undefined, undefined, result);
    } catch (error) {
      if (claimed && reservationKey && !providerSucceeded) {
        try {
          await deps.quotaStore.release(reservationKey);
          await deps.idempotencyStore.abandon(`${userHash}#${request.requestId}`, hash);
          claimed = false;
        } catch { return response(500, request.requestId, 'INTERNAL_ERROR'); }
      } else if (claimed && !reservationKey && error instanceof AnalysisError && error.code === 'QUOTA_UNAVAILABLE') {
        await deps.idempotencyStore.abandon(`${userHash}#${request.requestId}`, hash).catch(() => undefined);
      }
      const typed = error instanceof AnalysisError ? error : new AnalysisError('INTERNAL_ERROR', 500);
      return response(typed.statusCode, request.requestId, typed.code, typed.retryAt, undefined, typed.quotaType);
    }
    };
    const result = await execute();
    if (deps.telemetry) {
      const body = JSON.parse(result.body ?? '{}') as { error?: { code?: string } };
      const code = body.error?.code;
      const statusCode = result.statusCode ?? 500;
      const outcome: TelemetryOutcome = statusCode === 200 ? 'SUCCESS' : statusCode === 429 ? 'QUOTA_REJECT' :
        code === 'SERVICE_STOPPED' ? 'SERVICE_STOPPED' : ['TIMEOUT', 'PROVIDER_UNAVAILABLE', 'UNANALYZABLE_IMAGE'].includes(code ?? '') ? 'PROVIDER_FAILURE' :
          statusCode >= 500 ? 'SERVICE_FAILURE' : 'CLIENT_REJECT';
      try {
        deps.telemetry.record({ outcome, latencyMs: (deps.now ?? Date.now)() - startedAt, statusCode,
          ...(code ? { errorCode: code } : {}), ...(result.headers?.['x-request-id'] ? { requestId: String(result.headers['x-request-id']) } : {}),
          providerCalled, sloEligible: outcome === 'SUCCESS' || outcome === 'SERVICE_FAILURE' });
      } catch { /* 観測障害でAPI応答を失敗させない。 */ }
    }
    return result;
  };
}
