import type { APIGatewayProxyEvent } from 'aws-lambda';
import { AnalysisError, AnalysisProvider, createAnalysisHandler } from './analysis-handler';
import { DynamoIdempotencyStore } from './dynamo-idempotency-store';

const provider: AnalysisProvider = {
  async analyze() { throw new AnalysisError('PROVIDER_UNAVAILABLE', 503); },
};
const tableName = process.env.IDEMPOTENCY_TABLE_NAME;
if (!tableName) throw new Error('IDEMPOTENCY_TABLE_NAME is required');
const handler = createAnalysisHandler({ provider, idempotencyStore: new DynamoIdempotencyStore(tableName) });
export async function main(event: APIGatewayProxyEvent) { return handler(event); }
