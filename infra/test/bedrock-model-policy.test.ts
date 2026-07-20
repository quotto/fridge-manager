import { loadBedrockModelPolicy } from '../lib/bedrock-model-policy';

describe('Bedrock model policy evidence', () => {
  it('公式証跡の有効期間内はJP Geoとnoneだけを許可する', () => {
    expect(loadBedrockModelPolicy(new Date('2026-07-20T00:00:00Z'))).toMatchObject({
      region: 'ap-northeast-1', modelId: 'jp.amazon.nova-2-lite-v1:0',
      destinationRegions: ['ap-northeast-1', 'ap-northeast-3'], allowedModes: ['none'],
    });
  });

  it('90日を過ぎた証跡ではデプロイ用synthを拒否する', () => {
    expect(() => loadBedrockModelPolicy(new Date('2026-10-19T00:00:01Z'))).toThrow('expired');
  });
});
