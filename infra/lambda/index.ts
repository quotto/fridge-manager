import type { APIGatewayProxyEvent } from 'aws-lambda';
import { AnalysisError, AnalysisProvider, createAnalysisHandler } from './analysis-handler';
import { DynamoIdempotencyStore } from './dynamo-idempotency-store';
import { DynamoQuotaStore } from './dynamo-quota-store';

const provider: AnalysisProvider = {
  async analyze() { throw new AnalysisError('PROVIDER_UNAVAILABLE', 503); },
};
const tableName = process.env.IDEMPOTENCY_TABLE_NAME;
if (!tableName) throw new Error('IDEMPOTENCY_TABLE_NAME is required');
const limits = {
  minute: Number(process.env.QUOTA_SHORT_LIMIT ?? '2'),
  daily: Number(process.env.QUOTA_DAILY_LIMIT ?? '5'),
  monthly: Number(process.env.QUOTA_MONTHLY_LIMIT ?? '30'),
};
const handler = createAnalysisHandler({
  provider, idempotencyStore: new DynamoIdempotencyStore(tableName), quotaStore: new DynamoQuotaStore(tableName, limits),
});
export async function main(event: APIGatewayProxyEvent) { return handler(event); }
