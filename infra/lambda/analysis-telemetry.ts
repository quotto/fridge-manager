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

const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/;
const SAFE_CODE = /^[A-Z_]{1,40}$/;
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
}
