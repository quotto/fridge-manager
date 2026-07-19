import { randomUUID } from 'node:crypto';
import { DynamoDBClient, TransactWriteItemsCommand } from '@aws-sdk/client-dynamodb';
import type { SNSEvent } from 'aws-lambda';

export interface ControlChange { readonly enabled: boolean; readonly reason: string; }
export function createAiControlHandler(tableName: string, client = new DynamoDBClient({}), now = () => new Date()) {
  if (!tableName) throw new Error('Control table is required');
  return async (event: SNSEvent | ControlChange): Promise<void> => {
    const fromBudget = 'Records' in event;
    const change: ControlChange = fromBudget ? { enabled: false, reason: 'MONTHLY_BUDGET_100_PERCENT' } : event;
    if (typeof change.enabled !== 'boolean' || !change.reason.trim() || change.reason.length > 200) throw new Error('Control change is invalid');
    // 手動操作の実主体は自己申告値ではなくCloudTrailのLambda Invoke principalを監査正本とする。
    const actor = fromBudget ? 'AWS_BUDGETS' : 'MANUAL_OPERATOR';
    const timestamp = now(); const epoch = timestamp.toISOString();
    await client.send(new TransactWriteItemsCommand({ TransactItems: [
      { Update: { TableName: tableName, Key: { controlId: { S: 'CONTROL#AI' } },
        UpdateExpression: 'SET enabled = :enabled, updatedAt = :updatedAt, actor = :actor, reason = :reason',
        ExpressionAttributeValues: { ':enabled': { BOOL: change.enabled }, ':updatedAt': { S: epoch }, ':actor': { S: actor }, ':reason': { S: change.reason } } } },
      { Put: { TableName: tableName, Item: { controlId: { S: `AUDIT#${epoch}#${randomUUID()}` }, enabled: { BOOL: change.enabled }, updatedAt: { S: epoch }, actor: { S: actor }, reason: { S: change.reason }, expiresAt: { N: String(Math.floor(timestamp.getTime() / 1000) + 400 * 24 * 60 * 60) } } } },
    ] }));
  };
}
