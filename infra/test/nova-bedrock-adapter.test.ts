import { GetAccountDataRetentionCommand } from '@aws-sdk/client-bedrock';
import { ConverseCommand, type ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import {
  BedrockNovaTransport,
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
    ['TypeError', 'CLIENT_RUNTIME'],
    ['Error', 'CLIENT_RUNTIME'],
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
