import { TransactionCanceledException } from '@aws-sdk/client-dynamodb';
import { DynamoQuotaStore } from '../lambda/dynamo-quota-store';

const userHash = 'a'.repeat(64);
const requestId = '018f47a0-90c0-7d54-b92d-4285f7fb3312';
const now = new Date('2026-07-19T14:59:45.000Z'); // JST 23:59:45

describe('DynamoQuotaStore', () => {
  it('短期token・日次5・月次30と予約を1 transactionで原子的に確保する', async () => {
    const client = { send: jest.fn().mockResolvedValueOnce({}).mockResolvedValueOnce({}) };
    const result = await new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId);
    expect(result).toMatchObject({ kind: 'reserved' });
    const input = client.send.mock.calls[1]?.[0].input;
    expect(input.TransactItems).toHaveLength(4);
    expect(input.ClientRequestToken).toMatch(/^R-[0-9a-f]{32}$/);
    expect(JSON.stringify(input)).toContain('"N":"5"');
    expect(JSON.stringify(input)).toContain('"N":"30"');
    expect(JSON.stringify(input)).not.toContain('anonymous-user');
  });

  it('token bucket容量2を使い切ると30秒後をretryAtにする', async () => {
    const client = { send: jest.fn().mockResolvedValueOnce({ Item: { tokens: { N: '0' }, updatedAt: { N: String(now.getTime()) } } }).mockResolvedValueOnce({ Responses: { table: [] } }) };
    const result = await new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId);
    expect(result).toEqual({ kind: 'exceeded', limitType: 'SHORT', retryAt: '2026-07-19T15:00:15.000Z' });
    expect(client.send).toHaveBeenCalledTimes(2);
  });

  it('短期・日次・月次が同時超過なら最も遅いMONTHLYを返す', async () => {
    const client = { send: jest.fn()
      .mockResolvedValueOnce({ Item: { tokens: { N: '0' }, updatedAt: { N: String(now.getTime()) } } })
      .mockResolvedValueOnce({ Responses: { table: [
        { requestId: { S: `Q#${userHash}#DAILY#2026-07-19` }, count: { N: '5' } },
        { requestId: { S: `Q#${userHash}#MONTHLY#2026-07` }, count: { N: '30' } },
      ] } }) };
    const result = await new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId);
    expect(result).toEqual({ kind: 'exceeded', limitType: 'MONTHLY', retryAt: '2026-07-31T15:00:00.000Z' });
  });

  it('日次・月次が同時超過ならMONTHLYと最も遅いJST月初を返す', async () => {
    const canceled = new TransactionCanceledException({ $metadata: {}, message: 'limit', CancellationReasons: [
      { Code: 'None' }, { Code: 'None' }, { Code: 'ConditionalCheckFailed' }, { Code: 'ConditionalCheckFailed' },
    ] });
    const client = { send: jest.fn().mockResolvedValueOnce({}).mockRejectedValueOnce(canceled) };
    const result = await new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId);
    expect(result).toEqual({ kind: 'exceeded', limitType: 'MONTHLY', retryAt: '2026-07-31T15:00:00.000Z' });
  });

  it('失敗返却は予約状態・短期token・日次・月次を同じtransactionで一度だけ戻す', async () => {
    const reservationKey = `QR#${userHash}#${requestId}`;
    const reservation = { Item: { status: { S: 'RESERVED' }, minuteKey: { S: `Q#${userHash}#SHORT` }, dailyKey: { S: `Q#${userHash}#DAILY#2026-07-19` }, monthlyKey: { S: `Q#${userHash}#MONTHLY#2026-07` } } };
    const short = { Item: { tokens: { N: '0' }, updatedAt: { N: String(now.getTime()) } } };
    const client = { send: jest.fn().mockResolvedValueOnce(reservation).mockResolvedValueOnce(short).mockResolvedValueOnce({}) };
    await new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).release(reservationKey);
    const input = client.send.mock.calls[2]?.[0].input;
    expect(input.TransactItems).toHaveLength(4);
    expect(input.ClientRequestToken).toMatch(/^L-[0-9a-f]{32}$/);
    expect(input.TransactItems[0].Update.ConditionExpression).toBe('#status = :reserved');
  });

  it('不正設定・生UID・不正requestIdを拒否する', async () => {
    expect(() => new DynamoQuotaStore('table', { minute: 0, daily: 5, monthly: 30 })).toThrow();
    await expect(new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }).reserve('anonymous-user', requestId)).rejects.toThrow('Quota key is invalid');
  });

  it('SHORTのCAS競合時は最新値を再読してから予約する', async () => {
    const conflict = new TransactionCanceledException({ $metadata: {}, message: 'race', CancellationReasons: [
      { Code: 'None' }, { Code: 'ConditionalCheckFailed' }, { Code: 'None' }, { Code: 'None' },
    ] });
    const client = { send: jest.fn()
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(conflict)
      .mockResolvedValueOnce({ Item: { tokens: { N: '1' }, updatedAt: { N: String(now.getTime()) } } })
      .mockResolvedValueOnce({}) };
    await expect(new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId))
      .resolves.toMatchObject({ kind: 'reserved' });
    expect(client.send).toHaveBeenCalledTimes(4);
  });

  it('BatchGetの未処理keyが解消しなければfail-closedにする', async () => {
    const unprocessed = { UnprocessedKeys: { table: { Keys: [{ requestId: { S: `Q#${userHash}#DAILY#2026-07-19` } }] } } };
    const client = { send: jest.fn()
      .mockResolvedValueOnce({ Item: { tokens: { N: '0' }, updatedAt: { N: String(now.getTime()) } } })
      .mockResolvedValue(unprocessed) };
    await expect(new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId))
      .rejects.toThrow('Quota counter read was incomplete');
    expect(client.send).toHaveBeenCalledTimes(4);
  });

  it('自然補充後の返却をcapacity 2に丸め、二重返却しない', async () => {
    const reservationKey = `QR#${userHash}#${requestId}`;
    const reservation = { Item: { status: { S: 'RESERVED' }, minuteKey: { S: `Q#${userHash}#SHORT` }, dailyKey: { S: `Q#${userHash}#DAILY#2026-07-19` }, monthlyKey: { S: `Q#${userHash}#MONTHLY#2026-07` } } };
    const client = { send: jest.fn()
      .mockResolvedValueOnce(reservation)
      .mockResolvedValueOnce({ Item: { tokens: { N: '1.9' }, updatedAt: { N: String(now.getTime() - 30_000) } } })
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce({ Item: { status: { S: 'RELEASED' } } }) };
    const store = new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now);
    await store.release(reservationKey);
    await store.release(reservationKey);
    expect(client.send.mock.calls[2]?.[0].input.TransactItems[1].Update.ExpressionAttributeValues[':tokens']).toEqual({ N: '2' });
    expect(client.send.mock.calls.filter(([command]) => command.input.TransactItems)).toHaveLength(1);
  });

  it('JSTの日付・月境界で日次・月次counter keyを切り替える', async () => {
    let clock = new Date('2026-07-31T14:59:59.000Z');
    const client = { send: jest.fn().mockResolvedValue({}) };
    const store = new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => clock);
    await store.reserve(userHash, requestId);
    clock = new Date('2026-07-31T15:00:00.000Z');
    await store.reserve(userHash, '018f47a0-90c0-7d54-b92d-4285f7fb3313');
    const first = JSON.stringify(client.send.mock.calls[1]?.[0].input);
    const second = JSON.stringify(client.send.mock.calls[3]?.[0].input);
    expect(first).toContain('DAILY#2026-07-31'); expect(first).toContain('MONTHLY#2026-07');
    expect(second).toContain('DAILY#2026-08-01'); expect(second).toContain('MONTHLY#2026-08');
  });

  it('同じ予約keyの条件失敗は上限扱いにせず二重加算を防ぐ', async () => {
    const duplicate = new TransactionCanceledException({ $metadata: {}, message: 'duplicate', CancellationReasons: [
      { Code: 'ConditionalCheckFailed' }, { Code: 'None' }, { Code: 'None' }, { Code: 'None' },
    ] });
    const client = { send: jest.fn().mockResolvedValueOnce({}).mockRejectedValueOnce(duplicate) };
    await expect(new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).reserve(userHash, requestId))
      .rejects.toBe(duplicate);
  });

  it('成功状態の再適用は安全だが予約なしは拒否する条件付き更新にする', async () => {
    const client = { send: jest.fn().mockResolvedValue({}) };
    await new DynamoQuotaStore('table', { minute: 2, daily: 5, monthly: 30 }, client as never, () => now).succeed(`QR#${userHash}#${requestId}`);
    expect(client.send.mock.calls[0]?.[0].input.ConditionExpression).toContain('#status = :reserved OR #status = :succeeded');
    expect(client.send.mock.calls[0]?.[0].input.ConditionExpression).toContain('attribute_exists');
  });
});
