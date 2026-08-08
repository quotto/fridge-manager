import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createProductionProvider } from '../lambda/production-provider';
import { NOVA_MODEL_ID, NOVA_REGION, NovaTransport } from '../lambda/nova-provider';

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
      Promise.resolve('none'),
      transport,
      recordUsage,
    );

    await expect(provider.analyze(request)).resolves.toMatchObject({ status: 'succeeded' });
    expect(converse).toHaveBeenCalledTimes(1);
    expect(recordUsage).toHaveBeenCalledWith(expect.objectContaining({ requestId: request.requestId, attempts: 1 }));
  });

  it('fails closed without invoking Nova when retention cannot be verified', async () => {
    const converse = jest.fn();
    const provider = createProductionProvider(
      { region: NOVA_REGION, modelId: NOVA_MODEL_ID, allowedModes: ['none'] },
      Promise.resolve(undefined),
      { converse },
      jest.fn(),
    );

    await expect(provider.analyze(request)).rejects.toMatchObject({ code: 'PROVIDER_UNAVAILABLE' });
    expect(converse).not.toHaveBeenCalled();
  });

  it('connects the factory result to the production handler', () => {
    const source = readFileSync(resolve('infra/lambda/index.ts'), 'utf8');
    expect(source).toMatch(/const provider = createProductionProvider\([\s\S]*?\);/);
    expect(source).toMatch(/createAnalysisHandler\(\{\s*provider,/);
    expect(source).not.toContain("throw new AnalysisError('PROVIDER_UNAVAILABLE'");
  });
});
