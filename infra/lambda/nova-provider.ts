import type { ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { AnalysisError, AnalysisProvider, AnalysisRequest } from './analysis-handler';
import { NOVA_MODEL_ID, NOVA_REGION } from './nova-model';
import { FOOD_UNITS, validateProviderResult } from './provider-boundary';

export { NOVA_MODEL_ID, NOVA_REGION } from './nova-model';
export const RESULT_TOOL_NAME = 'report_food_candidates';
export const NOVA_WARNING_CODES = ['LOW_LIGHT', 'OCCLUDED', 'QUANTITY_UNKNOWN', 'LABEL_UNREADABLE'] as const;

export interface NovaProviderConfig {
  readonly region: string;
  readonly modelId: string;
  /** デプロイ時にモデルメタデータから検証した allowed_modes。 */
  readonly allowedModes: readonly string[];
  /** 起動時に Bedrock GetAccountDataRetention から取得した値。 */
  readonly dataRetentionMode: string;
}

export interface NovaTransport {
  converse(input: ConverseCommandInput): Promise<unknown>;
}
export interface NovaProviderUsage {
  readonly modelId: string;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly attempts: number;
  readonly requestId: string;
  readonly failureReason?: NovaProviderFailureReason;
}

export type NovaProviderFailureReason = 'ACCESS_DENIED' | 'VALIDATION' | 'NOT_FOUND' |
  'THROTTLED' | 'SERVICE_UNAVAILABLE' | 'UNKNOWN';

const candidateSchema = {
  type: 'object', additionalProperties: false,
  required: ['name', 'quantity', 'unit', 'evidence', 'requiresReview'],
  properties: {
    name: { anyOf: [{ type: 'string', minLength: 1, maxLength: 30 }, { type: 'null' }] },
    quantity: { anyOf: [{ type: 'string', pattern: '^(?:(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,2})?|100)$' }, { type: 'null' }] },
    unit: { enum: [null, ...FOOD_UNITS] },
    evidence: { enum: ['VISIBLE_COUNT', 'PACKAGE_LABEL', 'VISUAL_ESTIMATE', 'UNKNOWN'] },
    requiresReview: { type: 'boolean' },
  },
};

const resultSchema = {
  type: 'object', additionalProperties: false, required: ['candidates', 'warnings'],
  properties: {
    candidates: { type: 'array', maxItems: 30, items: candidateSchema },
    warnings: { type: 'array', maxItems: 30, items: { enum: [...NOVA_WARNING_CODES] } },
  },
};

function buildConverseInput(config: NovaProviderConfig, request: AnalysisRequest, retry: boolean): ConverseCommandInput {
  return {
    modelId: config.modelId,
    system: [{ text: 'あなたは日本の家庭向け食材画像抽出器です。画像内で確認できる絶対数量だけを報告してください。増加・減少・追加・削除・置換などの在庫操作は推測も出力もしません。数量不明はnullとし、0で補完しません。' }],
    messages: [{ role: 'user', content: [
      { image: { format: 'jpeg', source: { bytes: Buffer.from(request.image.base64, 'base64') } } },
      { text: `画像内の食材を最大30品目抽出し、必ず指定toolを呼び出してください。現在値との差分ではなく、1枚の画像内で見える絶対値を返してください。${retry ? '前回出力はschema不適合でした。未定義フィールドを含めず、tool schemaへ厳密に適合させてください。' : ''}` },
    ] }],
    inferenceConfig: { temperature: 0, maxTokens: 2000 },
    additionalModelRequestFields: { reasoningConfig: { type: 'disabled' } },
    toolConfig: {
      toolChoice: { tool: { name: RESULT_TOOL_NAME } },
      tools: [{ toolSpec: {
        name: RESULT_TOOL_NAME,
        description: '画像内で確認できる食材と絶対数量の候補を返す。在庫の増減・置換操作は含めない。',
        inputSchema: { json: resultSchema },
      } }],
    },
  };
}

function extractResult(response: unknown, requestId: string): unknown {
  if (!response || typeof response !== 'object') return undefined;
  const output = (response as { output?: unknown }).output;
  if (!output || typeof output !== 'object') return undefined;
  const message = (output as { message?: unknown }).message;
  if (!message || typeof message !== 'object') return undefined;
  const content = (message as { content?: unknown }).content;
  if (!Array.isArray(content)) return undefined;
  const blocks = content.filter((block): block is { toolUse: { name?: unknown; input?: unknown } } =>
    !!block && typeof block === 'object' && !!(block as { toolUse?: unknown }).toolUse &&
    typeof (block as { toolUse: unknown }).toolUse === 'object');
  if (blocks.length !== 1 || blocks[0]?.toolUse.name !== RESULT_TOOL_NAME) return undefined;
  const input = blocks[0].toolUse.input;
  if (!input || typeof input !== 'object' || Array.isArray(input)) return undefined;
  if (Object.keys(input).some((key) => key !== 'candidates' && key !== 'warnings')) return undefined;
  const warnings = (input as { warnings?: unknown }).warnings;
  if (!Array.isArray(warnings) || warnings.some((warning) => !NOVA_WARNING_CODES.includes(warning as typeof NOVA_WARNING_CODES[number]))) return undefined;
  return { requestId, status: 'succeeded', ...(input as Record<string, unknown>) };
}

function safeConfiguration(config: NovaProviderConfig): boolean {
  return config.region === NOVA_REGION && config.modelId === NOVA_MODEL_ID &&
    config.allowedModes.includes('none') && config.dataRetentionMode === 'none';
}

function safeTokenCount(value: unknown): number {
  return Number.isSafeInteger(value) && (value as number) >= 0 && (value as number) <= 10_000_000
    ? value as number
    : 0;
}

function providerFailureReason(error: unknown): NovaProviderFailureReason {
  const name = error && typeof error === 'object' && 'name' in error && typeof error.name === 'string'
    ? error.name
    : '';
  if (name === 'AccessDeniedException') return 'ACCESS_DENIED';
  if (name === 'ValidationException') return 'VALIDATION';
  if (name === 'ResourceNotFoundException') return 'NOT_FOUND';
  if (name === 'ThrottlingException') return 'THROTTLED';
  if (name === 'ServiceUnavailableException' || name === 'InternalServerException') return 'SERVICE_UNAVAILABLE';
  return 'UNKNOWN';
}

/** Geo・保持条件をfail-closed検証し、schema不正時だけ一度再試行する。 */
export function createNovaProvider(
  config: NovaProviderConfig,
  transport: NovaTransport,
  recordUsage: (usage: NovaProviderUsage) => void = () => undefined,
): AnalysisProvider {
  return {
    async analyze(request: AnalysisRequest): Promise<unknown> {
      if (!safeConfiguration(config)) throw new AnalysisError('PROVIDER_UNAVAILABLE', 503);
      let attempts = 0;
      let inputTokens = 0;
      let outputTokens = 0;
      let failureReason: NovaProviderFailureReason | undefined;
      try {
        for (let attempt = 0; attempt < 2; attempt += 1) {
          const input = buildConverseInput(config, request, attempt === 1);
          let raw: unknown;
          attempts += 1;
          try { raw = await transport.converse(input); }
          catch (error) {
            failureReason = providerFailureReason(error);
            throw new AnalysisError('PROVIDER_UNAVAILABLE', 503);
          }
          const usage = raw && typeof raw === 'object' ? (raw as { usage?: unknown }).usage : undefined;
          if (usage && typeof usage === 'object' && !Array.isArray(usage)) {
            inputTokens += safeTokenCount((usage as { inputTokens?: unknown }).inputTokens);
            outputTokens += safeTokenCount((usage as { outputTokens?: unknown }).outputTokens);
          }
          const result = validateProviderResult(extractResult(raw, request.requestId), request.requestId);
          if (result) return result;
        }
        throw new AnalysisError('UNANALYZABLE_IMAGE', 422);
      } finally {
        try { recordUsage({ modelId: config.modelId, inputTokens, outputTokens, attempts, requestId: request.requestId, ...(failureReason ? { failureReason } : {}) }); }
        catch { /* 観測障害でprovider結果を失敗させない。 */ }
      }
    },
  };
}
