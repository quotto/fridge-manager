import { GetAccountDataRetentionCommand } from '@aws-sdk/client-bedrock';
import { ConverseCommand, type ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { AnalysisError } from '../lambda/analysis-handler';
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

    await expect(loadAccountDataRetentionMode(controlClient)).resolves.toBe('none');

    expect(controlClient.send).toHaveBeenCalledTimes(1);
    const command = controlClient.send.mock.calls[0]?.[0];
    expect(command).toBeInstanceOf(GetAccountDataRetentionCommand);
    expect(command.input).toEqual({});
  });

  it('保持modeを取得できない場合はfail closedにする', async () => {
    const controlClient = { send: jest.fn().mockResolvedValue({ mode: undefined }) };

    await expect(loadAccountDataRetentionMode(controlClient)).rejects.toMatchObject<Partial<AnalysisError>>({
      code: 'PROVIDER_UNAVAILABLE',
    });
  });

  it('保持mode取得APIが失敗した場合はfail closedにする', async () => {
    const controlClient = { send: jest.fn().mockRejectedValue(new Error('Bedrock unavailable')) };

    await expect(loadAccountDataRetentionMode(controlClient)).rejects.toMatchObject<Partial<AnalysisError>>({
      code: 'PROVIDER_UNAVAILABLE',
    });
  });
});
