import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('AI解析API契約', () => {
  const openApi = JSON.parse(readFileSync(resolve('infra/api/openapi.json'), 'utf8')) as Record<string, unknown>;
  const request = JSON.parse(readFileSync(resolve('infra/api/schemas/analysis-request.schema.json'), 'utf8')) as Record<string, unknown>;
  const response = JSON.parse(readFileSync(resolve('infra/api/schemas/analysis-response.schema.json'), 'utf8')) as Record<string, unknown>;
  const candidate = JSON.parse(readFileSync(resolve('infra/api/schemas/food-candidate.schema.json'), 'utf8')) as Record<string, unknown>;

  it('OpenAPI 3.0契約で正常・主要異常レスポンスを定義する', () => {
    expect(openApi).toMatchObject({ openapi: expect.stringMatching(/^3\.0\./), paths: { '/v1/analysis': { post: {
      responses: expect.objectContaining({ '200': expect.anything(), '400': expect.anything(), '422': expect.anything(), '429': expect.anything(), '500': expect.anything(), '503': expect.anything(), '504': expect.anything() }),
    } } } });
  });

  it('OpenAPI componentsを正本JSON Schemaへ参照しGateway 4xxもno-store構造応答にする', () => {
    expect(openApi).toMatchObject({
      components: { schemas: {
        AnalysisRequest: { $ref: './schemas/analysis-request.schema.json' },
        AnalysisResponse: { $ref: './schemas/analysis-response.schema.json' },
      } },
      'x-amazon-apigateway-gateway-responses': { BAD_REQUEST_BODY: expect.objectContaining({ responseParameters: expect.objectContaining({
        'gatewayresponse.header.Cache-Control': "'no-store'",
      }) }) },
    });
  });

  it('requestId、処理種別、画像と最大30件の更新対象を制約する', () => {
    expect(request).toMatchObject({ required: expect.arrayContaining(['requestId', 'mode', 'image']), properties: {
      requestId: { format: 'uuid' }, mode: { enum: ['new', 'update'] }, currentItems: { maxItems: 30 },
    } });
  });

  it('候補最大30件と構造化エラーコードを制約する', () => {
    const serialized = JSON.stringify(response);
    expect(serialized).toContain('"maxItems":30');
    for (const code of ['TIMEOUT', 'PROVIDER_UNAVAILABLE', 'QUOTA_EXCEEDED', 'INVALID_IMAGE', 'UNANALYZABLE_IMAGE', 'INTERNAL_ERROR']) {
      expect(serialized).toContain(code);
    }
    expect(serialized).toContain('"quotaType"');
    for (const type of ['SHORT', 'DAILY', 'MONTHLY']) expect(serialized).toContain(type);
  });

  it('食材候補schemaを正本として追加プロパティ、数量、単位、不明値を制約する', () => {
    expect(response).toMatchObject({ oneOf: expect.arrayContaining([
      expect.objectContaining({ properties: expect.objectContaining({ candidates: expect.objectContaining({ items: { $ref: 'food-candidate.schema.json' } }) }) }),
    ]) });
    expect(candidate).toMatchObject({ additionalProperties: false, required: ['name', 'quantity', 'unit', 'evidence', 'requiresReview'], properties: {
      quantity: { type: ['string', 'null'] }, unit: { type: ['string', 'null'] },
      evidence: { enum: ['VISIBLE_COUNT', 'PACKAGE_LABEL', 'VISUAL_ESTIMATE', 'UNKNOWN'] },
    } });
  });
});
