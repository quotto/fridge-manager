import { AnalysisProvider, AnalysisRequest, ProviderPreflightError } from './analysis-handler';
import { RetentionCheckResult, RetentionFailureReason } from './nova-bedrock-adapter';
import { NovaProviderUsage, NovaTransport, createNovaProvider } from './nova-provider';

export interface ProductionProviderConfig {
  readonly region: string;
  readonly modelId: string;
  readonly allowedModes: readonly string[];
}

/** Production entrypointの配線を副作用から分離し、保持確認後だけNovaを呼ぶ。 */
export function createProductionProvider(
  config: ProductionProviderConfig,
  retentionCheck: Promise<RetentionCheckResult>,
  transport: NovaTransport,
  recordUsage: (usage: NovaProviderUsage) => void,
  recordPreflightFailure: (event: { readonly requestId: string; readonly reason: RetentionFailureReason }) => void,
): AnalysisProvider {
  return {
    async analyze(request: AnalysisRequest) {
      const result = await retentionCheck;
      if (result.kind === 'failed') {
        try { recordPreflightFailure({ requestId: request.requestId, reason: result.reason }); }
        catch { /* 観測障害でfail-closed応答を失敗させない。 */ }
        throw new ProviderPreflightError();
      }
      if (!config.allowedModes.includes(result.mode)) {
        try { recordPreflightFailure({ requestId: request.requestId, reason: 'MODE_NOT_ALLOWED' }); }
        catch { /* 観測障害でfail-closed応答を失敗させない。 */ }
        throw new ProviderPreflightError();
      }
      return createNovaProvider(
        { ...config, dataRetentionMode: result.mode },
        transport,
        recordUsage,
      ).analyze(request);
    },
  };
}
