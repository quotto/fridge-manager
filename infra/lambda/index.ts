import type { APIGatewayProxyEvent } from 'aws-lambda';
import { AnalysisError, AnalysisProvider, createAnalysisHandler } from './analysis-handler';
import { DynamoIdempotencyStore } from './dynamo-idempotency-store';
import { DynamoQuotaStore } from './dynamo-quota-store';
import { EmfAnalysisTelemetry } from './analysis-telemetry';

const provider: AnalysisProvider = {
  async analyze() { throw new AnalysisError('PROVIDER_UNAVAILABLE', 503); },
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
  telemetry: new EmfAnalysisTelemetry('FridgeManager/Analysis', process.env.ENVIRONMENT ?? 'unknown'),
});
export async function main(event: APIGatewayProxyEvent) { return handler(event); }
