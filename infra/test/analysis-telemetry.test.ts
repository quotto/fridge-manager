import { EmfAnalysisTelemetry } from '../lambda/analysis-telemetry';

describe('解析telemetry allowlist', () => {
  it('固定dimensionと数値metricだけをEMF出力しrequestIdをdimensionにしない', () => {
    const lines: string[] = [];
    new EmfAnalysisTelemetry('FridgeManager/Analysis', 'dev', (line) => lines.push(line)).record({
      outcome: 'SUCCESS', latencyMs: 123.4, statusCode: 200, requestId: '018f47a0-90c0-7d54-b92d-4285f7fb3312', providerCalled: true, sloEligible: true,
    });
    const value = JSON.parse(lines[0] ?? '{}');
    expect(value._aws.CloudWatchMetrics[0].Dimensions).toEqual([['Environment'], ['Environment', 'Outcome']]);
    expect(value).toMatchObject({ Environment: 'dev', Outcome: 'SUCCESS', Requests: 1, ProviderCalls: 1, SloEligible: 1, SloSuccess: 1, Latency: 123 });
    expect(JSON.stringify(value._aws)).not.toContain('requestId');
  });

  it('安全でない文字列を捨て、機微データ用fieldを一切生成しない', () => {
    const lines: string[] = [];
    const telemetry = new EmfAnalysisTelemetry('FridgeManager/Analysis', 'prod', (line) => lines.push(line));
    telemetry.record({ outcome: 'SERVICE_FAILURE', latencyMs: 1, statusCode: 500, requestId: 'bad\n{"image":"base64-secret"}', errorCode: 'bad token value', providerCalled: false, sloEligible: true } as never);
    const serialized = lines[0] ?? '';
    for (const forbidden of ['base64-secret', 'image', 'candidate', 'prompt', 'token value', 'userId', 'currentItems', 'stack']) expect(serialized).not.toContain(forbidden);
    expect(JSON.parse(serialized)).not.toHaveProperty('requestId');
    expect(JSON.parse(serialized)).not.toHaveProperty('errorCode');
  });

  it('許可済みJP Geoモデルのretry合計と入出力tokenをEMFへ出力する', () => {
    const lines: string[] = [];
    const telemetry = new EmfAnalysisTelemetry('FridgeManager/Analysis', 'dev', (line) => lines.push(line));

    telemetry.recordProviderUsage({
      modelId: 'jp.amazon.nova-2-lite-v1:0',
      inputTokens: 321,
      outputTokens: 87,
      attempts: 2,
      requestId: '018f47a0-90c0-7d54-b92d-4285f7fb3312',
    });

    const value = JSON.parse(lines[0] ?? '{}');
    expect(value._aws.CloudWatchMetrics[0]).toMatchObject({
      Dimensions: [['Environment', 'ModelId']],
      Metrics: [
        { Name: 'InputTokens', Unit: 'Count' },
        { Name: 'OutputTokens', Unit: 'Count' },
        { Name: 'ProviderCalls', Unit: 'Count' },
      ],
    });
    expect(value).toMatchObject({
      Environment: 'dev',
      ModelId: 'jp.amazon.nova-2-lite-v1:0',
      InputTokens: 321,
      OutputTokens: 87,
      ProviderCalls: 2,
      requestId: '018f47a0-90c0-7d54-b92d-4285f7fb3312',
    });
    expect(JSON.stringify(value._aws)).not.toContain('requestId');
  });

  it.each([
    ['未許可モデル', { modelId: 'global.amazon.nova-2-lite-v1:0', inputTokens: 1, outputTokens: 1, attempts: 1 }],
    ['負数token', { modelId: 'jp.amazon.nova-2-lite-v1:0', inputTokens: -1, outputTokens: 1, attempts: 1 }],
    ['過大retry', { modelId: 'jp.amazon.nova-2-lite-v1:0', inputTokens: 1, outputTokens: 1, attempts: 3 }],
    ['非整数token', { modelId: 'jp.amazon.nova-2-lite-v1:0', inputTokens: 1.2, outputTokens: 1, attempts: 1 }],
  ])('%sのprovider usageはログへ出力しない', (_name, usage) => {
    const lines: string[] = [];
    new EmfAnalysisTelemetry('FridgeManager/Analysis', 'prod', (line) => lines.push(line))
      .recordProviderUsage(usage);
    expect(lines).toEqual([]);
  });

  it('provider usageへ余分な機微fieldを渡してもallowlist外を出力しない', () => {
    const lines: string[] = [];
    new EmfAnalysisTelemetry('FridgeManager/Analysis', 'dev', (line) => lines.push(line))
      .recordProviderUsage({
        modelId: 'jp.amazon.nova-2-lite-v1:0', inputTokens: 1, outputTokens: 2, attempts: 1,
        prompt: 'secret prompt', image: 'base64-secret', token: 'auth-token', userId: 'uid',
      } as never);
    const serialized = lines[0] ?? '';
    for (const forbidden of ['secret prompt', 'base64-secret', 'auth-token', 'uid', 'prompt', 'image', 'userId']) {
      expect(serialized).not.toContain(forbidden);
    }
  });

  it('provider usageの危険なrequest IDは出力せずmetricだけを維持する', () => {
    const lines: string[] = [];
    new EmfAnalysisTelemetry('FridgeManager/Analysis', 'dev', (line) => lines.push(line))
      .recordProviderUsage({
        modelId: 'jp.amazon.nova-2-lite-v1:0', inputTokens: 1, outputTokens: 2, attempts: 1,
        requestId: 'bad\n{"image":"base64-secret"}',
      });
    const value = JSON.parse(lines[0] ?? '{}');
    expect(value).not.toHaveProperty('requestId');
    expect(JSON.stringify(value)).not.toContain('base64-secret');
    expect(value).toMatchObject({ InputTokens: 1, OutputTokens: 2, ProviderCalls: 1 });
  });
});
