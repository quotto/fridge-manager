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
});
