import type { APIGatewayProxyEvent } from 'aws-lambda';
import { BedrockClient } from '@aws-sdk/client-bedrock';
import { BedrockRuntimeClient } from '@aws-sdk/client-bedrock-runtime';
import { AnalysisProvider, createAnalysisHandler } from './analysis-handler';
import { DynamoIdempotencyStore } from './dynamo-idempotency-store';
import { DynamoQuotaStore } from './dynamo-quota-store';
import { EmfAnalysisTelemetry } from './analysis-telemetry';
import { BedrockNovaTransport, loadAccountDataRetentionMode } from './nova-bedrock-adapter';
import { createNovaProvider } from './nova-provider';

const bedrockRegion = process.env.BEDROCK_REGION ?? '';
const modelId = process.env.BEDROCK_MODEL_ID ?? '';
const allowedModes = (process.env.BEDROCK_MODEL_ALLOWED_MODES ?? '').split(',').filter(Boolean);
const retentionClient = new BedrockClient({ region: bedrockRegion || 'ap-northeast-1' });
// 初期化フェーズで検証を開始し、失敗は未処理rejectionにせずproviderをfail closedにする。
const retentionModePromise = loadAccountDataRetentionMode(retentionClient).catch(() => undefined);
const transport = new BedrockNovaTransport(new BedrockRuntimeClient({ region: bedrockRegion || 'ap-northeast-1' }));
const telemetry = new EmfAnalysisTelemetry('FridgeManager/Analysis', process.env.ENVIRONMENT ?? 'unknown');
const provider: AnalysisProvider = {
  async analyze(request) {
    const dataRetentionMode = await retentionModePromise;
    return createNovaProvider(
      { region: bedrockRegion, modelId, allowedModes, dataRetentionMode: dataRetentionMode ?? '' },
      transport,
      (usage) => telemetry.recordProviderUsage(usage),
    ).analyze(request);
  },
};
const tableName = process.env.IDEMPOTENCY_TABLE_NAME;
const controlTableName = process.env.CONTROL_TABLE_NAME;
if (!tableName || !controlTableName) throw new Error('DynamoDB table environment is required');
const limits = {
  minute: Number(process.env.QUOTA_SHORT_LIMIT ?? '2'),
  daily: Number(process.env.QUOTA_DAILY_LIMIT ?? '5'),
  monthly: Number(process.env.QUOTA_MONTHLY_LIMIT ?? '30'),
  global: Number(process.env.QUOTA_GLOBAL_LIMIT ?? '8000'),
};
const handler = createAnalysisHandler({
  provider, idempotencyStore: new DynamoIdempotencyStore(tableName), quotaStore: new DynamoQuotaStore(tableName, limits, undefined, undefined, controlTableName),
  telemetry,
});
export async function main(event: APIGatewayProxyEvent) { return handler(event); }
