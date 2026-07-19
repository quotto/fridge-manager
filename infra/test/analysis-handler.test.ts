import type { APIGatewayProxyEventV2 } from 'aws-lambda';
import { encode } from 'jpeg-js';
import {
  AnalysisError,
  AnalysisProvider,
  AnalysisProviderResult,
  IdempotencyClaim,
  IdempotencyStore,
  createAnalysisHandler,
} from '../lambda/analysis-handler';
import { QuotaStore } from '../lambda/quota-store';

class MemoryStore implements IdempotencyStore {
  private readonly records = new Map<string, { hash: string; status: 'IN_PROGRESS' | 'COMPLETED' }>();

  public async claim(key: string, hash: string): Promise<IdempotencyClaim> {
    const record = this.records.get(key);
    if (record) return record.hash === hash ? { kind: 'duplicate', status: record.status } : { kind: 'conflict' };
    this.records.set(key, { hash, status: 'IN_PROGRESS' });
    return { kind: 'claimed' };
  }

  public async complete(key: string): Promise<void> {
    const record = this.records.get(key);
    if (record) this.records.set(key, { ...record, status: 'COMPLETED' });
  }
  public async abandon(key: string, hash: string): Promise<void> {
    const record = this.records.get(key);
    if (record?.hash === hash && record.status === 'IN_PROGRESS') this.records.delete(key);
  }
}

const quotaStore: QuotaStore = {
  reserve: async (_userHash, requestId) => ({ kind: 'reserved', reservationKey: `quota#${requestId}` }),
  succeed: async () => undefined,
  release: async () => undefined,
};

function event(body: unknown, overrides: Partial<APIGatewayProxyEventV2> = {}): APIGatewayProxyEventV2 {
  return {
    version: '2.0', routeKey: 'POST /v1/analysis', rawPath: '/v1/analysis', rawQueryString: '',
    headers: { 'content-type': 'application/json' }, requestContext: {
      authorizer: { lambda: { firebaseVerified: true, appCheckVerified: true, userId: 'anonymous-user' } },
    } as never,
    isBase64Encoded: false, body: JSON.stringify(body), ...overrides,
  };
}

const jpeg = encode({ data: Buffer.from([255, 0, 0, 255]), width: 1, height: 1 }, 85).data.toString('base64');
const oversizedDimensionJpeg = (() => {
  const bytes = Buffer.from(jpeg, 'base64');
  const sof = bytes.indexOf(Buffer.from([0xff, 0xc0]));
  if (sof < 0) throw new Error('テストJPEGにSOF0がありません');
  bytes.writeUInt16BE(2049, sof + 7);
  return bytes.toString('base64');
})();
const oversizedPixelCountJpeg = (() => {
  const bytes = Buffer.from(jpeg, 'base64');
  const sof = bytes.indexOf(Buffer.from([0xff, 0xc0]));
  if (sof < 0) throw new Error('テストJPEGにSOF0がありません');
  bytes.writeUInt16BE(2000, sof + 5);
  bytes.writeUInt16BE(2001, sof + 7);
  return bytes.toString('base64');
})();
const validRequest = { requestId: '018f47a0-90c0-7d54-b92d-4285f7fb3312', mode: 'new', image: { mediaType: 'image/jpeg', base64: jpeg } };

describe('AI解析Lambda', () => {
  it.each(['new', 'update'] as const)('%sモードを処理しrequestIdを応答へ引き継ぐ', async (mode) => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockResolvedValue({
      requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [],
    }) };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore });

    const response = await handler(event({ ...validRequest, mode, ...(mode === 'update' ? { currentItems: [{ name: '牛乳', quantity: '1', unit: '本' }] } : {}) }));

    expect(response.statusCode).toBe(200);
    expect(response.headers).toMatchObject({ 'x-request-id': validRequest.requestId });
    expect(JSON.parse(response.body ?? '{}')).toMatchObject({ requestId: validRequest.requestId, status: 'succeeded' });
  });

  it('同じrequestIdとpayloadの再送ではproviderを再実行せず重複応答を返す', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockResolvedValue({
      requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [],
    }) };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore });

    const first = await handler(event(validRequest));
    const retry = await handler(event(validRequest));

    expect(provider.analyze).toHaveBeenCalledTimes(1);
    expect(first.statusCode).toBe(200);
    expect(retry.statusCode).toBe(409);
    expect(JSON.parse(retry.body ?? '{}').error.code).toBe('DUPLICATE_REQUEST');
  });

  it('同一requestIdの並行呼出しを一方だけproviderへ到達させる', async () => {
    let resolveProvider!: (result: AnalysisProviderResult) => void;
    const provider: AnalysisProvider = { analyze: jest.fn().mockReturnValue(new Promise((resolve) => { resolveProvider = resolve; })) };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore });
    const first = handler(event(validRequest));
    const second = await handler(event(validRequest));
    resolveProvider({ requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [] });
    await first;
    expect(second.statusCode).toBe(409);
    expect(provider.analyze).toHaveBeenCalledTimes(1);
  });

  it('同じrequestIdで異なるpayloadを再送すると競合として拒否する', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockResolvedValue({
      requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [],
    }) };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore });
    await handler(event(validRequest));

    const response = await handler(event({ ...validRequest, mode: 'update', currentItems: [{ name: '牛乳', quantity: '1', unit: '本' }] }));

    expect(response.statusCode).toBe(409);
    expect(JSON.parse(response.body ?? '{}').error.code).toBe('IDEMPOTENCY_CONFLICT');
    expect(provider.analyze).toHaveBeenCalledTimes(1);
  });

  it('認証検証済みcontextがない場合はdeny-by-defaultで拒否する', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore })(
      event(validRequest, { requestContext: {} as never }),
    );
    expect(response.statusCode).toBe(401);
    expect(JSON.parse(response.body ?? '{}').error.code).toBe('UNAUTHORIZED');
    expect(provider.analyze).not.toHaveBeenCalled();
  });

  it.each([
    [new AnalysisError('TIMEOUT', 504), 504, 'TIMEOUT'],
    [new AnalysisError('PROVIDER_UNAVAILABLE', 503), 503, 'PROVIDER_UNAVAILABLE'],
    [new AnalysisError('QUOTA_EXCEEDED', 429, '2026-07-20T00:00:00+09:00'), 429, 'QUOTA_EXCEEDED'],
    [new AnalysisError('UNANALYZABLE_IMAGE', 422), 422, 'UNANALYZABLE_IMAGE'],
    [new Error('secret provider detail'), 500, 'INTERNAL_ERROR'],
  ])('障害を構造化エラーへ分類する', async (error, statusCode, code) => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockRejectedValue(error) };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore })(event(validRequest));
    const body = JSON.parse(response.body ?? '{}');

    expect(response.statusCode).toBe(statusCode);
    expect(body).toMatchObject({ requestId: validRequest.requestId, status: 'failed', error: { code } });
    expect(response.body).not.toContain('secret provider detail');
  });

  it.each([
    ['JSON不正', '{', 'INVALID_REQUEST'],
    ['requestId不正', { ...validRequest, requestId: 'bad' }, 'INVALID_REQUEST'],
    ['更新時の対象なし', { ...validRequest, mode: 'update' }, 'INVALID_REQUEST'],
    ['magic bytes不正', { ...validRequest, image: { mediaType: 'image/jpeg', base64: Buffer.from('not jpeg').toString('base64') } }, 'INVALID_IMAGE'],
    ['JPEG外形だけを偽装したデコード不能画像', { ...validRequest, image: { mediaType: 'image/jpeg', base64: Buffer.from([0xff, 0xd8, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xd9]).toString('base64') } }, 'INVALID_IMAGE'],
    ['長辺2048px超過', { ...validRequest, image: { mediaType: 'image/jpeg', base64: oversizedDimensionJpeg } }, 'INVALID_IMAGE'],
    ['400万画素超過', { ...validRequest, image: { mediaType: 'image/jpeg', base64: oversizedPixelCountJpeg } }, 'INVALID_IMAGE'],
    ['Base64不正', { ...validRequest, image: { mediaType: 'image/jpeg', base64: 'not+base64$' } }, 'INVALID_IMAGE'],
    ['mediaType偽装', { ...validRequest, image: { mediaType: 'image/png', base64: jpeg } }, 'INVALID_IMAGE'],
    ['サイズ超過', { ...validRequest, image: { mediaType: 'image/jpeg', base64: Buffer.alloc(3 * 1024 * 1024 + 1, 0xff).toString('base64') } }, 'INVALID_IMAGE'],
  ])('%sをprovider呼出前に拒否する', async (_name, body, code) => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore });
    const response = await handler(typeof body === 'string' ? event({}, { body }) : event(body));

    expect(response.statusCode).toBe(400);
    expect(JSON.parse(response.body ?? '{}').error.code).toBe(code);
    expect(provider.analyze).not.toHaveBeenCalled();
  });

  it('100を超える10進数量を拒否する', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore })(event({
      ...validRequest, mode: 'update', currentItems: [{ name: '牛乳', quantity: '100.01', unit: '本' }],
    }));
    expect(response.statusCode).toBe(400);
    expect(provider.analyze).not.toHaveBeenCalled();
  });

  it('一時的なprovider失敗後は同じrequestIdを再試行できる', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn()
      .mockRejectedValueOnce(new AnalysisError('PROVIDER_UNAVAILABLE', 503))
      .mockResolvedValueOnce({ requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [] }) };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore });
    expect((await handler(event(validRequest))).statusCode).toBe(503);
    expect((await handler(event(validRequest))).statusCode).toBe(200);
    expect(provider.analyze).toHaveBeenCalledTimes(2);
  });

  it('application/json以外のContent-Typeを拒否する', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore })(
      event(validRequest, { headers: { 'content-type': 'text/plain' } }),
    );
    expect(response.statusCode).toBe(415);
    expect(provider.analyze).not.toHaveBeenCalled();
  });

  it('providerが異なるrequestIdを返した場合は不正出力として拒否する', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockResolvedValue({
      requestId: '550e8400-e29b-41d4-a716-446655440000', status: 'succeeded', candidates: [], warnings: [],
    }) };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore })(event(validRequest));
    expect(response.statusCode).toBe(422);
    expect(JSON.parse(response.body ?? '{}').error.code).toBe('UNANALYZABLE_IMAGE');
  });

  it('日次上限時は種別とretryAtを返しproviderを呼ばない', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const limited: QuotaStore = {
      reserve: jest.fn().mockResolvedValue({ kind: 'exceeded', limitType: 'DAILY', retryAt: '2026-07-20T15:00:00.000Z' }),
      succeed: jest.fn(), release: jest.fn(),
    };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore: limited })(event(validRequest));
    expect(response.statusCode).toBe(429);
    expect(JSON.parse(response.body ?? '{}')).toMatchObject({ error: { code: 'QUOTA_EXCEEDED', quotaType: 'DAILY', retryAt: '2026-07-20T15:00:00.000Z' } });
    expect(provider.analyze).not.toHaveBeenCalled();
  });

  it('quota store障害は503でfail-closedにしproviderを呼ばない', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const unavailable: QuotaStore = { reserve: jest.fn().mockRejectedValue(new Error('dynamo')), succeed: jest.fn(), release: jest.fn() };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore: unavailable })(event(validRequest));
    expect(response.statusCode).toBe(503);
    expect(JSON.parse(response.body ?? '{}').error.code).toBe('QUOTA_UNAVAILABLE');
    expect(provider.analyze).not.toHaveBeenCalled();
  });

  it('AI基盤障害時だけ予約を一度返却する', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockRejectedValue(new AnalysisError('PROVIDER_UNAVAILABLE', 503)) };
    const reserved: QuotaStore = { reserve: jest.fn().mockResolvedValue({ kind: 'reserved', reservationKey: 'reservation' }), succeed: jest.fn(), release: jest.fn() };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore: reserved })(event(validRequest));
    expect(response.statusCode).toBe(503);
    expect(reserved.release).toHaveBeenCalledTimes(1);
  });

  it('有効なAI結果後のquota完了障害では返却せず再送もproviderへ進めない', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockResolvedValue({ requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [] }) };
    const reserved: QuotaStore = { reserve: jest.fn().mockResolvedValue({ kind: 'reserved', reservationKey: 'reservation' }), succeed: jest.fn().mockRejectedValue(new Error('dynamo')), release: jest.fn() };
    const handler = createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore: reserved });
    expect((await handler(event(validRequest))).statusCode).toBe(500);
    expect((await handler(event(validRequest))).statusCode).toBe(409);
    expect(provider.analyze).toHaveBeenCalledTimes(1);
    expect(reserved.release).not.toHaveBeenCalled();
  });

  it('入力不正はquotaへ到達せず非計上にする', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const untouched: QuotaStore = { reserve: jest.fn(), succeed: jest.fn(), release: jest.fn() };
    await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore: untouched })(event({ ...validRequest, requestId: 'bad' }));
    expect(untouched.reserve).not.toHaveBeenCalled();
  });

  it('予算緊急停止中はretryAtなし503でproviderを呼ばない', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn() };
    const stopped: QuotaStore = { reserve: jest.fn().mockResolvedValue({ kind: 'stopped' }), succeed: jest.fn(), release: jest.fn() };
    const idempotencyStore = new MemoryStore(); const abandon = jest.spyOn(idempotencyStore, 'abandon');
    const response = await createAnalysisHandler({ provider, idempotencyStore, quotaStore: stopped })(event(validRequest));
    expect(response.statusCode).toBe(503);
    expect(JSON.parse(response.body ?? '{}')).toMatchObject({ error: { code: 'SERVICE_STOPPED' } });
    expect(JSON.parse(response.body ?? '{}').error.retryAt).toBeUndefined();
    expect(provider.analyze).not.toHaveBeenCalled();
    expect(abandon).toHaveBeenCalledTimes(1);
  });

  it('telemetry障害でも成功応答を失わない', async () => {
    const provider: AnalysisProvider = { analyze: jest.fn().mockResolvedValue({ requestId: validRequest.requestId, status: 'succeeded', candidates: [], warnings: [] }) };
    const response = await createAnalysisHandler({ provider, idempotencyStore: new MemoryStore(), quotaStore,
      telemetry: { record() { throw new Error('observability unavailable'); } }, now: () => 100 })(event(validRequest));
    expect(response.statusCode).toBe(200);
  });
});
