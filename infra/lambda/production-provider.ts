import { AnalysisProvider, AnalysisRequest } from './analysis-handler';
import { NovaProviderUsage, NovaTransport, createNovaProvider } from './nova-provider';

export interface ProductionProviderConfig {
  readonly region: string;
  readonly modelId: string;
  readonly allowedModes: readonly string[];
}

/** Production entrypointの配線を副作用から分離し、保持確認後だけNovaを呼ぶ。 */
export function createProductionProvider(
  config: ProductionProviderConfig,
  retentionMode: Promise<string | undefined>,
  transport: NovaTransport,
  recordUsage: (usage: NovaProviderUsage) => void,
): AnalysisProvider {
  return {
    async analyze(request: AnalysisRequest) {
      const dataRetentionMode = await retentionMode;
      return createNovaProvider(
        { ...config, dataRetentionMode: dataRetentionMode ?? '' },
        transport,
        recordUsage,
      ).analyze(request);
    },
  };
}
