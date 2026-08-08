import { GetAccountDataRetentionCommand } from '@aws-sdk/client-bedrock';
import { ConverseCommand, type ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { Readable } from 'node:stream';
import { SignatureV4 } from '@smithy/signature-v4';
import { HttpRequest } from '@smithy/core/protocols';
import {
  BedrockNovaTransport,
  NodeSha256,
  RETENTION_HTTP_TIMEOUTS,
  SignedBedrockRetentionClient,
  loadAccountDataRetentionMode,
} from '../lambda/nova-bedrock-adapter';

describe('Bedrock Nova SDK adapter', () => {
  it('ConverseCommandをBedrockRuntimeClientへ送信する', async () => {
    const response = { output: { message: { content: [] } } };
    const runtimeClient = { send: jest.fn().mockResolvedValue(response) };
    const transport = new BedrockNovaTransport(runtimeClient);
    const input: ConverseCommandInput = {
      modelId: 'jp.amazon.nova-2-lite-v1:0',
      messages: [{ role: 'user', content: [{ text: 'test' }] }],
    };

    await expect(transport.converse(input)).resolves.toBe(response);

    expect(runtimeClient.send).toHaveBeenCalledTimes(1);
    const command = runtimeClient.send.mock.calls[0]?.[0];
    expect(command).toBeInstanceOf(ConverseCommand);
    expect(command.input).toEqual(input);
  });
});

describe('Bedrock account data retention起動検証', () => {
  it('許可リージョン以外のcontrol-plane endpointを構築しない', () => {
    expect(() => new SignedBedrockRetentionClient('us-east-1')).toThrow(TypeError);
  });

  it('SigV4署名したcontrol-plane GETからmodeだけを返す', async () => {
    const signer = new SignatureV4({
      credentials: { accessKeyId: 'AKIDEXAMPLE', secretAccessKey: 'test-secret-key', sessionToken: 'test-session-token' },
      region: 'ap-northeast-1', service: 'bedrock', sha256: NodeSha256,
    });
    const requestSigner = { sign: (request: HttpRequest) => signer.sign(request) as Promise<HttpRequest> };
    const handler = { handle: jest.fn().mockResolvedValue({
      response: { statusCode: 200, body: Readable.from([JSON.stringify({ mode: 'none', updatedAt: 'unsupported-date' })]) },
    }) };
    const client = new SignedBedrockRetentionClient('ap-northeast-1', requestSigner, handler);

    await expect(client.send(new GetAccountDataRetentionCommand({}))).resolves.toEqual({ mode: 'none' });
    const sent = handler.handle.mock.calls[0]?.[0];
    expect(sent).toMatchObject({ protocol: 'https:', hostname: 'bedrock.ap-northeast-1.amazonaws.com', method: 'GET', path: '/data-retention' });
    expect(sent?.headers.authorization).toMatch(/^AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE\//);
    expect(sent?.headers['x-amz-date']).toMatch(/^\d{8}T\d{6}Z$/);
    expect(sent?.headers['x-amz-security-token']).toBe('test-session-token');
  });

  it('control-plane通信の全timeoutを明示している', () => {
    const handler = { handle: jest.fn() };
    let observed: typeof RETENTION_HTTP_TIMEOUTS | undefined;
    const factory = jest.fn((options: typeof RETENTION_HTTP_TIMEOUTS) => { observed = options; return handler; });
    const signer = { sign: jest.fn() };
    new SignedBedrockRetentionClient('ap-northeast-1', signer, undefined, factory);
    expect(factory).toHaveBeenCalledWith({
      connectionTimeout: 3_000, requestTimeout: 5_000, socketTimeout: 5_000, throwOnRequestTimeout: true,
    });
    expect(RETENTION_HTTP_TIMEOUTS).toEqual(observed);
  });

  it('control-planeの非200をstatusだけ持つ固定分類可能な例外にする', async () => {
    const signer = { sign: jest.fn(async (request) => request) };
    const handler = { handle: jest.fn().mockResolvedValue({ response: { statusCode: 403, body: Readable.from(['secret']) } }) };
    const client = new SignedBedrockRetentionClient('ap-northeast-1', signer, handler);
    await expect(loadAccountDataRetentionMode(client)).resolves.toEqual({ kind: 'failed', reason: 'ACCESS_DENIED' });
  });

  it('control-plane応答が上限を超える場合はfail closedにする', async () => {
    const signer = { sign: jest.fn(async (request) => request) };
    const handler = { handle: jest.fn().mockResolvedValue({ response: { statusCode: 200, body: Readable.from(['x'.repeat(16_385)]) } }) };
    const client = new SignedBedrockRetentionClient('ap-northeast-1', signer, handler);
    await expect(loadAccountDataRetentionMode(client)).resolves.toEqual({ kind: 'failed', reason: 'RETENTION_BODY' });
  });

  it.each([
    ['署名', { sign: jest.fn().mockRejectedValue(new Error('secret')) }, { handle: jest.fn() }, 'RETENTION_SIGNING'],
    ['通信', { sign: jest.fn(async (request) => request) }, { handle: jest.fn().mockRejectedValue(new Error('secret')) }, 'RETENTION_TRANSPORT'],
    ['JSON', { sign: jest.fn(async (request) => request) }, { handle: jest.fn().mockResolvedValue({ response: { statusCode: 200, body: Readable.from(['not-json']) } }) }, 'RETENTION_JSON'],
  ])('%s段階の失敗を固定分類する', async (_name, signer, handler, reason) => {
    const client = new SignedBedrockRetentionClient('ap-northeast-1', signer, handler);
    await expect(loadAccountDataRetentionMode(client)).resolves.toEqual({ kind: 'failed', reason });
  });

  it('GetAccountDataRetentionCommandを送り現在のmodeを返す', async () => {
    const controlClient = { send: jest.fn().mockResolvedValue({ mode: 'none' }) };

    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'verified', mode: 'none' });

    expect(controlClient.send).toHaveBeenCalledTimes(1);
    const command = controlClient.send.mock.calls[0]?.[0];
    expect(command).toBeInstanceOf(GetAccountDataRetentionCommand);
    expect(command.input).toEqual({});
  });

  it('保持modeを取得できない場合はfail closedにする', async () => {
    const controlClient = { send: jest.fn().mockResolvedValue({ mode: undefined }) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason: 'INVALID_RESPONSE' });
  });

  it.each([
    ['AccessDeniedException', 'ACCESS_DENIED'],
    ['AccessDenied', 'ACCESS_DENIED'],
    ['UnauthorizedException', 'ACCESS_DENIED'],
    ['UnrecognizedClientException', 'ACCESS_DENIED'],
    ['CredentialsProviderError', 'CREDENTIALS'],
    ['TimeoutError', 'NETWORK'],
    ['TypeError', 'CLIENT_TYPE_ERROR'],
    ['Error', 'CLIENT_ERROR'],
    ['ThrottlingException', 'THROTTLED'],
    ['ServiceUnavailableException', 'SERVICE_UNAVAILABLE'],
    ['UnexpectedSdkException', 'UNKNOWN'],
  ])('保持mode取得APIの%sを固定理由へ分類してfail closedにする', async (name, reason) => {
    const controlClient = { send: jest.fn().mockRejectedValue(Object.assign(new Error('secret detail'), { name })) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason });
  });

  it('例外nameの参照自体が失敗してもUNKNOWNへ閉じる', async () => {
    const hostile = Object.defineProperty({}, 'name', { get() { throw new Error('secret getter'); } });
    const controlClient = { send: jest.fn().mockRejectedValue(hostile) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason: 'UNKNOWN' });
  });

  it('名前のない例外を固定カテゴリへ分類する', async () => {
    const controlClient = { send: jest.fn().mockRejectedValue({ message: 'secret detail' }) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason: 'NAME_MISSING' });
  });

  it('statusなしSDK metadataを固定カテゴリへ分類する', async () => {
    const controlClient = { send: jest.fn().mockRejectedValue({ name: 'OddSdkError', $metadata: {} }) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason: 'SDK_METADATA_UNKNOWN' });
  });

  it.each([
    [{ name: 'Error', code: 'ENOTFOUND', message: 'secret host' }, 'NETWORK'],
    [{ name: 'TypeError', message: 'fetch failed: secret endpoint' }, 'NETWORK'],
    [{ name: 'TypeError', message: 'Cannot read properties of undefined (secret)' }, 'SDK_DESERIALIZATION'],
    [{ name: 'TypeError', message: 'Invalid URL: secret endpoint' }, 'CLIENT_CONFIGURATION'],
    [{ name: 'TypeError', message: 'Invalid RFC-3339 date-time value: secret' }, 'SDK_DATE_DESERIALIZATION'],
    [{ name: 'TypeError', message: 'Invalid month: secret' }, 'SDK_DATE_DESERIALIZATION'],
    [{ name: 'TypeError', message: 'Expected string, got object: secret' }, 'SDK_SHAPE_DESERIALIZATION'],
    [{ name: 'TypeError', message: "Cannot load config 'secret'. Expected number" }, 'CLIENT_CONFIGURATION'],
    [{ name: 'TypeError', message: 'The "input" argument must be ArrayBuffer. secret' }, 'SDK_BUFFER'],
  ])('runtime例外を本文を出さず固定カテゴリへ分類する', async (error, reason) => {
    const controlClient = { send: jest.fn().mockRejectedValue(error) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason });
  });

  it.each([
    [403, 'ACCESS_DENIED'],
    [400, 'VALIDATION'],
    [429, 'THROTTLED'],
    [500, 'SERVICE_UNAVAILABLE'],
  ])('未知のSDK例外でもHTTP %iを固定理由%sへ分類する', async (httpStatusCode, reason) => {
    const error = { name: 'UnknownSdkError', $metadata: { httpStatusCode }, message: 'secret detail' };
    const controlClient = { send: jest.fn().mockRejectedValue(error) };
    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toEqual({ kind: 'failed', reason });
  });
});
