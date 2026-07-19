import { createAiControlHandler } from '../lambda/ai-control';

describe('AI緊急停止control', () => {
  it('Budgets 100%通知でAIを停止し監査recordを同一transactionへ保存する', async () => {
    const client = { send: jest.fn().mockResolvedValue({}) };
    const handler = createAiControlHandler('control', client as never, () => new Date('2026-07-20T00:00:00Z'));
    await handler({ Records: [{ Sns: { Message: 'budget payload must not be persisted' } }] } as never);
    const input = client.send.mock.calls[0]?.[0].input;
    expect(input.TransactItems).toHaveLength(2);
    expect(input.TransactItems[0].Update.ExpressionAttributeValues[':enabled']).toEqual({ BOOL: false });
    expect(input.TransactItems[0].Update.ExpressionAttributeValues[':actor']).toEqual({ S: 'AWS_BUDGETS' });
    expect(JSON.stringify(input)).toContain('MONTHLY_BUDGET_100_PERCENT');
    expect(JSON.stringify(input)).not.toContain('budget payload must not be persisted');
  });

  it('権限管理された手動invokeで理由付き復旧を監査する', async () => {
    const client = { send: jest.fn().mockResolvedValue({}) };
    await createAiControlHandler('control', client as never)({ enabled: true, reason: 'budget period verified', actor: 'forged-admin' } as never);
    expect(client.send.mock.calls[0]?.[0].input.TransactItems[0].Update.ExpressionAttributeValues).toMatchObject({ ':enabled': { BOOL: true }, ':actor': { S: 'MANUAL_OPERATOR' } });
  });

  it('boolean以外や理由なしの変更を拒否する', async () => {
    await expect(createAiControlHandler('control')({ enabled: 'true', reason: '' } as never)).rejects.toThrow('Control change is invalid');
  });
});
