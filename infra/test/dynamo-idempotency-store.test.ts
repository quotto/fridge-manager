import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoIdempotencyStore } from '../lambda/dynamo-idempotency-store';

describe('DynamoIdempotencyStore', () => {
  it('条件付きPutItemで処理権を獲得し結果本文を保存しない', async () => {
    const client = { send: jest.fn().mockResolvedValue({}) };
    const store = new DynamoIdempotencyStore('table', client as never);
    expect(await store.claim('user#request', 'hash')).toEqual({ kind: 'claimed' });
    const input = client.send.mock.calls[0]?.[0].input;
    expect(input).toMatchObject({ TableName: 'table', ConditionExpression: 'attribute_not_exists(requestId)' });
    expect(JSON.stringify(input)).not.toMatch(/image|candidate|currentItems/);
  });

  it.each([
    ['同一hashの処理中', { requestHash: { S: 'hash' }, status: { S: 'IN_PROGRESS' } }, { kind: 'duplicate', status: 'IN_PROGRESS' }],
    ['同一hashの完了済み', { requestHash: { S: 'hash' }, status: { S: 'COMPLETED' } }, { kind: 'duplicate', status: 'COMPLETED' }],
    ['異なるhash', { requestHash: { S: 'other' }, status: { S: 'COMPLETED' } }, { kind: 'conflict' }],
  ])('%sを強整合readで判定する', async (_name, item, expected) => {
    const conflict = new ConditionalCheckFailedException({ $metadata: {}, message: 'exists' });
    const client = { send: jest.fn().mockRejectedValueOnce(conflict).mockResolvedValueOnce({ Item: item }) };
    const store = new DynamoIdempotencyStore('table', client as never);
    expect(await store.claim('user#request', 'hash')).toEqual(expected);
    expect(client.send.mock.calls[1]?.[0].input).toMatchObject({ ConsistentRead: true });
  });

  it('完了更新と失敗時の条件付き削除を行う', async () => {
    const client = { send: jest.fn().mockResolvedValue({}) };
    const store = new DynamoIdempotencyStore('table', client as never);
    await store.complete('user#request');
    await store.abandon('user#request', 'hash');
    expect(client.send.mock.calls[0]?.[0].input).toMatchObject({ ConditionExpression: 'attribute_exists(requestId)' });
    expect(client.send.mock.calls[1]?.[0].input).toMatchObject({ ConditionExpression: 'requestHash = :hash AND #status = :inProgress' });
  });

  it('DynamoDBの予期しない障害は隠さず上位へ返す', async () => {
    const client = { send: jest.fn().mockRejectedValue(new Error('network')) };
    await expect(new DynamoIdempotencyStore('table', client as never).claim('key', 'hash')).rejects.toThrow('network');
  });
});
