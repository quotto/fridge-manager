import { ConditionalCheckFailedException, DeleteItemCommand, DynamoDBClient, GetItemCommand, PutItemCommand, UpdateItemCommand } from '@aws-sdk/client-dynamodb';
import { IdempotencyClaim, IdempotencyStore } from './analysis-handler';

export class DynamoIdempotencyStore implements IdempotencyStore {
  public constructor(private readonly tableName: string, private readonly client = new DynamoDBClient({})) {}

  public async claim(key: string, hash: string): Promise<IdempotencyClaim> {
    const expiresAt = Math.floor(Date.now() / 1000) + 24 * 60 * 60;
    try {
      await this.client.send(new PutItemCommand({ TableName: this.tableName, Item: {
        requestId: { S: key }, requestHash: { S: hash }, status: { S: 'IN_PROGRESS' }, expiresAt: { N: String(expiresAt) },
      }, ConditionExpression: 'attribute_not_exists(requestId)' }));
      return { kind: 'claimed' };
    } catch (error) {
      if (!(error instanceof ConditionalCheckFailedException)) throw error;
      const existing = await this.client.send(new GetItemCommand({ TableName: this.tableName, Key: { requestId: { S: key } }, ConsistentRead: true }));
      if (existing.Item?.requestHash?.S !== hash) return { kind: 'conflict' };
      return { kind: 'duplicate', status: existing.Item?.status?.S === 'COMPLETED' ? 'COMPLETED' : 'IN_PROGRESS' };
    }
  }

  public async complete(key: string): Promise<void> {
    await this.client.send(new UpdateItemCommand({ TableName: this.tableName, Key: { requestId: { S: key } },
      UpdateExpression: 'SET #status = :completed', ExpressionAttributeNames: { '#status': 'status' }, ExpressionAttributeValues: { ':completed': { S: 'COMPLETED' } },
      ConditionExpression: 'attribute_exists(requestId)' }));
  }

  public async abandon(key: string, hash: string): Promise<void> {
    await this.client.send(new DeleteItemCommand({ TableName: this.tableName, Key: { requestId: { S: key } },
      ConditionExpression: 'requestHash = :hash AND #status = :inProgress', ExpressionAttributeNames: { '#status': 'status' },
      ExpressionAttributeValues: { ':hash': { S: hash }, ':inProgress': { S: 'IN_PROGRESS' } } }));
  }
}
