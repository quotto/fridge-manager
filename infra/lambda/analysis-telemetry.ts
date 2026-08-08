import { NOVA_MODEL_ID } from './nova-model';

export type TelemetryOutcome = 'SUCCESS' | 'CLIENT_REJECT' | 'QUOTA_REJECT' | 'PROVIDER_FAILURE' | 'SERVICE_FAILURE' | 'SERVICE_STOPPED';
export interface AnalysisTelemetryEvent {
  readonly outcome: TelemetryOutcome;
  readonly latencyMs: number;
  readonly statusCode: number;
  readonly errorCode?: string;
  readonly requestId?: string;
  readonly providerCalled: boolean;
  readonly sloEligible: boolean;
}
export interface AnalysisTelemetry { record(event: AnalysisTelemetryEvent): void; }
export interface ProviderUsageTelemetryEvent {
  readonly modelId: string;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly attempts: number;
  readonly requestId?: string;
  readonly failureReason?: string;
}

const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/;
const SAFE_CODE = /^[A-Z_]{1,40}$/;
const ALLOWED_MODEL_IDS = new Set([NOVA_MODEL_ID]);
const PROVIDER_FAILURE_REASONS = new Set(['ACCESS_DENIED', 'VALIDATION', 'NOT_FOUND', 'THROTTLED', 'SERVICE_UNAVAILABLE', 'UNKNOWN']);
const PROVIDER_PREFLIGHT_FAILURE_REASONS = new Set(['ACCESS_DENIED', 'VALIDATION', 'THROTTLED', 'SERVICE_UNAVAILABLE', 'CREDENTIALS', 'NETWORK', 'CLIENT_CONFIGURATION', 'SDK_DESERIALIZATION', 'SDK_DATE_DESERIALIZATION', 'SDK_SHAPE_DESERIALIZATION', 'SDK_BUFFER', 'CLIENT_TYPE_ERROR', 'CLIENT_ERROR', 'NAME_MISSING', 'SDK_METADATA_UNKNOWN', 'INVALID_RESPONSE', 'MODE_NOT_ALLOWED', 'UNKNOWN']);
const MAX_TOKEN_COUNT = 10_000_000;
const safeCount = (value: number, maximum = MAX_TOKEN_COUNT): boolean =>
  Number.isSafeInteger(value) && value >= 0 && value <= maximum;
export class EmfAnalysisTelemetry implements AnalysisTelemetry {
  public constructor(private readonly namespace: string, private readonly environment: string, private readonly write = (line: string) => console.log(line)) {}
  public record(event: AnalysisTelemetryEvent): void {
    const safeRequestId = event.requestId && SAFE_ID.test(event.requestId) ? event.requestId : undefined;
    const safeCode = event.errorCode && SAFE_CODE.test(event.errorCode) ? event.errorCode : undefined;
    const metrics = [
      { Name: 'Requests', Unit: 'Count' }, { Name: 'Latency', Unit: 'Milliseconds' },
      ...(event.providerCalled ? [{ Name: 'ProviderCalls', Unit: 'Count' }] : []),
      ...(event.sloEligible ? [{ Name: 'SloEligible', Unit: 'Count' }, { Name: 'SloSuccess', Unit: 'Count' }] : []),
    ];
    this.write(JSON.stringify({
      _aws: { Timestamp: Date.now(), CloudWatchMetrics: [{ Namespace: this.namespace, Dimensions: [['Environment'], ['Environment', 'Outcome']], Metrics: metrics }] },
      Environment: this.environment, Outcome: event.outcome, Requests: 1, Latency: Math.max(0, Math.round(event.latencyMs)),
      ...(event.providerCalled ? { ProviderCalls: 1 } : {}),
      ...(event.sloEligible ? { SloEligible: 1, SloSuccess: event.outcome === 'SUCCESS' ? 1 : 0 } : {}),
      statusCode: event.statusCode, ...(safeCode ? { errorCode: safeCode } : {}), ...(safeRequestId ? { requestId: safeRequestId } : {}),
    }));
  }

  public recordProviderUsage(event: ProviderUsageTelemetryEvent): void {
    if (!ALLOWED_MODEL_IDS.has(event.modelId) ||
        !safeCount(event.inputTokens) || !safeCount(event.outputTokens) ||
        !safeCount(event.attempts, 2) || event.attempts < 1) return;
    const safeRequestId = event.requestId && SAFE_ID.test(event.requestId) ? event.requestId : undefined;
    const safeFailureReason = event.failureReason && PROVIDER_FAILURE_REASONS.has(event.failureReason) ? event.failureReason : undefined;
    this.write(JSON.stringify({
      _aws: {
        Timestamp: Date.now(),
        CloudWatchMetrics: [{
          Namespace: this.namespace,
          Dimensions: [['Environment', 'ModelId']],
          Metrics: [
            { Name: 'InputTokens', Unit: 'Count' },
            { Name: 'OutputTokens', Unit: 'Count' },
            { Name: 'ProviderCalls', Unit: 'Count' },
          ],
        }],
      },
      Environment: this.environment,
      ModelId: event.modelId,
      InputTokens: event.inputTokens,
      OutputTokens: event.outputTokens,
      ProviderCalls: event.attempts,
      ...(safeFailureReason ? { ProviderFailureReason: safeFailureReason } : {}),
      ...(safeRequestId ? { requestId: safeRequestId } : {}),
    }));
  }

  /** 保持条件の起動検証失敗を、実モデル呼び出しmetricと分離して記録する。 */
  public recordProviderPreflightFailure(event: { readonly requestId: string; readonly reason: string }): void {
    if (!PROVIDER_PREFLIGHT_FAILURE_REASONS.has(event.reason)) return;
    const safeRequestId = SAFE_ID.test(event.requestId) ? event.requestId : undefined;
    this.write(JSON.stringify({
      Environment: this.environment,
      ProviderPreflightStage: 'DATA_RETENTION',
      ProviderPreflightFailureReason: event.reason,
      ...(safeRequestId ? { requestId: safeRequestId } : {}),
    }));
  }
}
