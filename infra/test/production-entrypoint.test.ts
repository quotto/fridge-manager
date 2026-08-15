import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createProductionProvider } from '../lambda/production-provider';
import { NOVA_MODEL_ID, NOVA_REGION, NovaTransport } from '../lambda/nova-provider';
import { RetentionCheckResult } from '../lambda/nova-bedrock-adapter';

const request = {
  requestId: '123e4567-e89b-42d3-a456-426614174000',
  mode: 'new' as const,
  image: { mediaType: 'image/jpeg' as const, base64: '/9j/2Q==' },
};

describe('production analysis entrypoint wiring', () => {
  it('verifies retention and reaches the Nova transport', async () => {
    const converse = jest.fn().mockResolvedValue({
      output: { message: { content: [{ toolUse: {
        name: 'report_food_candidates',
        input: { candidates: [], warnings: [] },
      } }] } },
    });
    const transport: NovaTransport = { converse };
    const recordUsage = jest.fn();
    const provider = createProductionProvider(
      { region: NOVA_REGION, modelId: NOVA_MODEL_ID, allowedModes: ['none'] },
      Promise.resolve<RetentionCheckResult>({ kind: 'verified', mode: 'none' }),
      transport,
      recordUsage,
      jest.fn(),
    );

    await expect(provider.analyze(request)).resolves.toMatchObject({ status: 'succeeded' });
    expect(converse).toHaveBeenCalledTimes(1);
    expect(recordUsage).toHaveBeenCalledWith(expect.objectContaining({ requestId: request.requestId, attempts: 1 }));
  });

  it('fails closed without invoking Nova when retention cannot be verified', async () => {
    const converse = jest.fn();
    const provider = createProductionProvider(
      { region: NOVA_REGION, modelId: NOVA_MODEL_ID, allowedModes: ['none'] },
      Promise.resolve<RetentionCheckResult>({ kind: 'failed', reason: 'ACCESS_DENIED' }),
      { converse },
      jest.fn(),
      jest.fn(),
    );

    await expect(provider.analyze(request)).rejects.toMatchObject({ code: 'PROVIDER_UNAVAILABLE' });
    expect(converse).not.toHaveBeenCalled();
  });

  it('保持確認失敗は固定理由とrequestIdだけを専用callbackへ渡す', async () => {
    const recordPreflightFailure = jest.fn();
    const provider = createProductionProvider(
      { region: NOVA_REGION, modelId: NOVA_MODEL_ID, allowedModes: ['none'] },
      Promise.resolve<RetentionCheckResult>({ kind: 'failed', reason: 'THROTTLED' }),
      { converse: jest.fn() },
      jest.fn(),
      recordPreflightFailure,
    );

    await expect(provider.analyze(request)).rejects.toMatchObject({ code: 'PROVIDER_UNAVAILABLE', providerCalled: false });
    expect(recordPreflightFailure).toHaveBeenCalledWith({ requestId: request.requestId, reason: 'THROTTLED' });
  });

  it('許可外の保持modeはConverse前に専用preflight失敗とする', async () => {
    const converse = jest.fn();
    const recordUsage = jest.fn();
    const recordPreflightFailure = jest.fn();
    const provider = createProductionProvider(
      { region: NOVA_REGION, modelId: NOVA_MODEL_ID, allowedModes: ['none'] },
      Promise.resolve<RetentionCheckResult>({ kind: 'verified', mode: 'inherit' }),
      { converse }, recordUsage, recordPreflightFailure,
    );
    await expect(provider.analyze(request)).rejects.toMatchObject({ code: 'PROVIDER_UNAVAILABLE', providerCalled: false });
    expect(converse).not.toHaveBeenCalled();
    expect(recordUsage).not.toHaveBeenCalled();
    expect(recordPreflightFailure).toHaveBeenCalledWith({ requestId: request.requestId, reason: 'MODE_NOT_ALLOWED' });
  });

  it('connects the factory result to the production handler', () => {
    const source = readFileSync(resolve('infra/lambda/index.ts'), 'utf8');
    expect(source).toMatch(/const provider = createProductionProvider\([\s\S]*?\);/);
    expect(source).toMatch(/createAnalysisHandler\(\{\s*provider,/);
    expect(source).not.toContain("throw new AnalysisError('PROVIDER_UNAVAILABLE'");
  });
});
