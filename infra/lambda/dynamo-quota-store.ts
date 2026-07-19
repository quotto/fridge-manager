import { createHash } from 'node:crypto';
import { AttributeValue, BatchGetItemCommand, DynamoDBClient, GetItemCommand, TransactWriteItemsCommand, TransactionCanceledException, UpdateItemCommand } from '@aws-sdk/client-dynamodb';
import { QuotaLimitType, QuotaReservation, QuotaStore } from './quota-store';

interface Period { readonly key: string; readonly retryAt: string; readonly expiresAt: number; }
interface Periods { readonly daily: Period; readonly monthly: Period; }
const HASH = /^[0-9a-f]{64}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function operationToken(operation: 'R' | 'L', key: string): string {
  return `${operation}-${createHash('sha256').update(key).digest('hex').slice(0, 32)}`;
}

function jstPeriods(now: Date): Periods {
  const epoch = now.getTime();
  const jst = new Date(epoch + 9 * 60 * 60 * 1000);
  const year = jst.getUTCFullYear(); const month = jst.getUTCMonth(); const day = jst.getUTCDate();
  const dayEnd = Date.UTC(year, month, day + 1) - 9 * 60 * 60 * 1000;
  const monthEnd = Date.UTC(year, month + 1, 1) - 9 * 60 * 60 * 1000;
  const period = (key: string, end: number): Period => ({ key, retryAt: new Date(end).toISOString(), expiresAt: Math.floor(end / 1000) + 2 * 24 * 60 * 60 });
  return {
    daily: period(`${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`, dayEnd),
    monthly: period(`${year}-${String(month + 1).padStart(2, '0')}`, monthEnd),
  };
}

export class DynamoQuotaStore implements QuotaStore {
  public constructor(
    private readonly tableName: string,
    private readonly limits: { readonly minute: number; readonly daily: number; readonly monthly: number; readonly global?: number },
    private readonly client = new DynamoDBClient({}),
    private readonly now = () => new Date(),
    private readonly controlTableName?: string,
  ) {
    if (!tableName || Object.values(limits).some((limit) => limit !== undefined && (!Number.isSafeInteger(limit) || limit < 1))) throw new Error('Quota configuration is invalid');
  }

  public async reserve(userHash: string, requestId: string): Promise<QuotaReservation> {
    if (!HASH.test(userHash) || !UUID.test(requestId)) throw new Error('Quota key is invalid');
    const currentTime = this.now();
    if (this.controlTableName) {
      const control = await this.client.send(new GetItemCommand({ TableName: this.controlTableName, Key: { controlId: { S: 'CONTROL#AI' } }, ConsistentRead: true }));
      if (control.Item?.enabled?.BOOL !== true) return { kind: 'stopped' };
    }
    const periods = jstPeriods(currentTime);
    const reservationKey = `QR#${userHash}#${requestId}`;
    const keys = {
      minute: `Q#${userHash}#SHORT`,
      daily: `Q#${userHash}#DAILY#${periods.daily.key}`,
      monthly: `Q#${userHash}#MONTHLY#${periods.monthly.key}`,
      global: `Q#GLOBAL#MONTHLY#${periods.monthly.key}`,
    };
    const counter = (key: string, type: QuotaLimitType, period: Period, limit: number) => ({ Update: {
      TableName: this.tableName, Key: { requestId: { S: key } },
      UpdateExpression: 'SET expiresAt = :ttl, #kind = :kind ADD #count :one',
      ConditionExpression: 'attribute_not_exists(#count) OR #count < :limit',
      ExpressionAttributeNames: { '#count': 'count', '#kind': 'kind' },
      ExpressionAttributeValues: { ':ttl': { N: String(period.expiresAt) }, ':kind': { S: type }, ':one': { N: '1' }, ':limit': { N: String(limit) } },
    } });
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const short = await this.client.send(new GetItemCommand({ TableName: this.tableName, Key: { requestId: { S: keys.minute } }, ConsistentRead: true }));
      const previousTokens = Number(short.Item?.tokens?.N ?? this.limits.minute);
      const previousUpdatedAt = Number(short.Item?.updatedAt?.N ?? currentTime.getTime());
      if (!Number.isFinite(previousTokens) || previousTokens < 0 || previousTokens > this.limits.minute ||
          !Number.isSafeInteger(previousUpdatedAt) || previousUpdatedAt < 1 || previousUpdatedAt > currentTime.getTime()) throw new Error('Quota counter is corrupted');
      const refillPerMillisecond = this.limits.minute / 60_000;
      const available = Math.min(this.limits.minute, previousTokens + Math.max(0, currentTime.getTime() - previousUpdatedAt) * refillPerMillisecond);
      if (available < 1) {
        const retry = currentTime.getTime() + Math.ceil((1 - available) / refillPerMillisecond);
        let pending = [{ requestId: { S: keys.daily } }, { requestId: { S: keys.monthly } }, { requestId: { S: keys.global } }];
        const items: Array<Record<string, AttributeValue>> = [];
        for (let readAttempt = 0; readAttempt < 3 && pending.length > 0; readAttempt += 1) {
          const counters = await this.client.send(new BatchGetItemCommand({ RequestItems: { [this.tableName]: { Keys: pending, ConsistentRead: true } } }));
          items.push(...(counters.Responses?.[this.tableName] ?? []));
          pending = counters.UnprocessedKeys?.[this.tableName]?.Keys as typeof pending ?? [];
        }
        if (pending.length > 0) throw new Error('Quota counter read was incomplete');
        const count = (key: string) => {
          const value = Number(items.find((item) => item.requestId?.S === key)?.count?.N ?? 0);
          if (!Number.isSafeInteger(value) || value < 0) throw new Error('Quota counter is corrupted');
          return value;
        };
        if (count(keys.global) >= (this.limits.global ?? 8000)) return { kind: 'exceeded', limitType: 'GLOBAL', retryAt: periods.monthly.retryAt };
        if (count(keys.monthly) >= this.limits.monthly) return { kind: 'exceeded', limitType: 'MONTHLY', retryAt: periods.monthly.retryAt };
        if (count(keys.daily) >= this.limits.daily) return { kind: 'exceeded', limitType: 'DAILY', retryAt: periods.daily.retryAt };
        return { kind: 'exceeded', limitType: 'SHORT', retryAt: new Date(retry).toISOString() };
      }
      const shortUpdate = { Update: {
        TableName: this.tableName, Key: { requestId: { S: keys.minute } },
        UpdateExpression: 'SET tokens = :tokens, updatedAt = :now, expiresAt = :ttl, #kind = :kind',
        ConditionExpression: short.Item ? 'tokens = :previousTokens AND updatedAt = :previousUpdatedAt' : 'attribute_not_exists(requestId)',
        ExpressionAttributeNames: { '#kind': 'kind' },
        ExpressionAttributeValues: {
          ':tokens': { N: String(available - 1) }, ':now': { N: String(currentTime.getTime()) },
          ':ttl': { N: String(Math.floor(currentTime.getTime() / 1000) + 2 * 24 * 60 * 60) }, ':kind': { S: 'SHORT' },
          ...(short.Item ? { ':previousTokens': { N: String(previousTokens) }, ':previousUpdatedAt': { N: String(previousUpdatedAt) } } : {}),
        },
      } };
      try {
      await this.client.send(new TransactWriteItemsCommand({ ClientRequestToken: operationToken('R', `${reservationKey}#${previousUpdatedAt}#${previousTokens}`), TransactItems: [
        { Put: { TableName: this.tableName, Item: {
          requestId: { S: reservationKey }, status: { S: 'RESERVED' }, expiresAt: { N: String(Math.floor(currentTime.getTime() / 1000) + 24 * 60 * 60) },
          minuteKey: { S: keys.minute }, dailyKey: { S: keys.daily }, monthlyKey: { S: keys.monthly }, globalKey: { S: keys.global },
        }, ConditionExpression: 'attribute_not_exists(requestId)' } },
        shortUpdate,
        counter(keys.daily, 'DAILY', periods.daily, this.limits.daily),
        counter(keys.monthly, 'MONTHLY', periods.monthly, this.limits.monthly),
        counter(keys.global, 'GLOBAL', periods.monthly, this.limits.global ?? 8000),
      ] }));
      return { kind: 'reserved', reservationKey };
      } catch (error) {
        if (!(error instanceof TransactionCanceledException)) throw error;
        if (error.CancellationReasons?.[1]?.Code === 'ConditionalCheckFailed') continue;
      const candidates: Array<[number, QuotaLimitType, Period]> = [
        [4, 'GLOBAL', periods.monthly], [3, 'MONTHLY', periods.monthly], [2, 'DAILY', periods.daily],
      ];
      const failed = candidates.filter(([index]) => error.CancellationReasons?.[index]?.Code === 'ConditionalCheckFailed');
      if (failed.length === 0) throw error;
      const limitType = failed[0]?.[1] ?? 'DAILY';
      const retryAt = failed.map(([, , period]) => period.retryAt).sort().at(-1) ?? periods.daily.retryAt;
      return { kind: 'exceeded', limitType, retryAt };
      }
    }
    throw new Error('Quota reservation contention exceeded retry limit');
  }

  public async succeed(reservationKey: string): Promise<void> {
    await this.client.send(new UpdateItemCommand({ TableName: this.tableName, Key: { requestId: { S: reservationKey } },
      UpdateExpression: 'SET #status = :succeeded', ConditionExpression: 'attribute_exists(requestId) AND (#status = :reserved OR #status = :succeeded)',
      ExpressionAttributeNames: { '#status': 'status' }, ExpressionAttributeValues: { ':succeeded': { S: 'SUCCEEDED' }, ':reserved': { S: 'RESERVED' } },
    }));
  }

  public async release(reservationKey: string): Promise<void> {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const existing = await this.client.send(new GetItemCommand({ TableName: this.tableName, Key: { requestId: { S: reservationKey } }, ConsistentRead: true }));
      if (existing.Item?.status?.S === 'RELEASED') return;
      if (existing.Item?.status?.S !== 'RESERVED') throw new Error('Quota reservation cannot be released');
      const keys = [existing.Item.minuteKey?.S, existing.Item.dailyKey?.S, existing.Item.monthlyKey?.S, existing.Item.globalKey?.S];
      if (keys.some((key) => !key?.startsWith('Q#')) || keys.some((key) => key?.includes('undefined'))) throw new Error('Quota reservation is corrupted');
      const short = await this.client.send(new GetItemCommand({ TableName: this.tableName, Key: { requestId: { S: keys[0] as string } }, ConsistentRead: true }));
      const previousTokens = Number(short.Item?.tokens?.N); const previousUpdatedAt = Number(short.Item?.updatedAt?.N);
      const currentTime = this.now().getTime();
      if (!Number.isFinite(previousTokens) || previousTokens < 0 || previousTokens > this.limits.minute ||
          !Number.isSafeInteger(previousUpdatedAt) || previousUpdatedAt < 1 || previousUpdatedAt > currentTime) throw new Error('Quota counter is corrupted');
      const refill = this.limits.minute / 60_000;
      const available = Math.min(this.limits.minute, previousTokens + Math.max(0, currentTime - previousUpdatedAt) * refill);
      const refunded = Math.min(this.limits.minute, available + 1);
      try {
      await this.client.send(new TransactWriteItemsCommand({ ClientRequestToken: operationToken('L', `${reservationKey}#${previousUpdatedAt}#${previousTokens}`), TransactItems: [
        { Update: { TableName: this.tableName, Key: { requestId: { S: reservationKey } }, UpdateExpression: 'SET #status = :released', ConditionExpression: '#status = :reserved', ExpressionAttributeNames: { '#status': 'status' }, ExpressionAttributeValues: { ':released': { S: 'RELEASED' }, ':reserved': { S: 'RESERVED' } } } },
        { Update: { TableName: this.tableName, Key: { requestId: { S: keys[0] as string } }, UpdateExpression: 'SET tokens = :tokens, updatedAt = :now', ConditionExpression: 'tokens = :previousTokens AND updatedAt = :previousUpdatedAt', ExpressionAttributeValues: { ':tokens': { N: String(refunded) }, ':now': { N: String(currentTime) }, ':previousTokens': { N: String(previousTokens) }, ':previousUpdatedAt': { N: String(previousUpdatedAt) } } } },
        ...keys.slice(1).map((key) => ({ Update: { TableName: this.tableName, Key: { requestId: { S: key as string } }, UpdateExpression: 'ADD #count :minusOne', ConditionExpression: '#count > :zero', ExpressionAttributeNames: { '#count': 'count' }, ExpressionAttributeValues: { ':minusOne': { N: '-1' }, ':zero': { N: '0' } } } })),
      ] }));
        return;
      } catch (error) {
        if (error instanceof TransactionCanceledException && error.CancellationReasons?.[0]?.Code === 'ConditionalCheckFailed') return;
        if (error instanceof TransactionCanceledException && error.CancellationReasons?.[1]?.Code === 'ConditionalCheckFailed') continue;
        throw error;
      }
    }
    throw new Error('Quota release contention exceeded retry limit');
  }
}
