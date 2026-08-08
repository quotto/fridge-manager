import { GetAccountDataRetentionCommand } from '@aws-sdk/client-bedrock';
import { ConverseCommand, ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { NovaTransport } from './nova-provider';

export type RetentionFailureReason = 'ACCESS_DENIED' | 'VALIDATION' | 'THROTTLED' | 'SERVICE_UNAVAILABLE' | 'CREDENTIALS' | 'NETWORK' | 'CLIENT_CONFIGURATION' | 'SDK_DESERIALIZATION' | 'SDK_DATE_DESERIALIZATION' | 'SDK_SHAPE_DESERIALIZATION' | 'SDK_BUFFER' | 'CLIENT_TYPE_ERROR' | 'CLIENT_ERROR' | 'NAME_MISSING' | 'SDK_METADATA_UNKNOWN' | 'INVALID_RESPONSE' | 'MODE_NOT_ALLOWED' | 'UNKNOWN';
export type RetentionCheckResult =
  { readonly kind: 'verified'; readonly mode: string } |
  { readonly kind: 'failed'; readonly reason: RetentionFailureReason };

interface CommandSender {
  send(command: unknown): Promise<unknown>;
}

/** AWS SDKを狭いtransport境界に閉じ込め、providerを単体テスト可能にする。 */
export class BedrockNovaTransport implements NovaTransport {
  public constructor(private readonly client: CommandSender) {}

  public async converse(input: ConverseCommandInput): Promise<unknown> {
    return this.client.send(new ConverseCommand(input));
  }
}

function retentionFailureReason(error: unknown): RetentionFailureReason {
  try {
    const candidate = error && typeof error === 'object' ? error as Record<string, unknown> : undefined;
    const name = candidate && 'name' in candidate && typeof candidate.name === 'string'
      ? candidate.name
      : '';
    if (['AccessDeniedException', 'AccessDenied', 'UnauthorizedException', 'UnrecognizedClientException'].includes(name)) return 'ACCESS_DENIED';
    if (name === 'CredentialsProviderError') return 'CREDENTIALS';
    if (['TimeoutError', 'NetworkingError', 'EndpointError'].includes(name)) return 'NETWORK';
    const code = candidate?.code;
    if (typeof code === 'string' && ['ENOTFOUND', 'EAI_AGAIN', 'ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT'].includes(code)) return 'NETWORK';
    const message = candidate?.message;
    if (typeof message === 'string') {
      if (message.includes('fetch failed')) return 'NETWORK';
      if (message.includes('Invalid URL') || message.includes('Cannot load')) return 'CLIENT_CONFIGURATION';
      if (message.includes('Cannot read properties of undefined') || message.includes('Cannot read property')) return 'SDK_DESERIALIZATION';
      if (message.includes('date-time') || message.includes('timestamp') || message.includes('RFC-3339') || message.includes('RFC-7231') ||
          message.includes('RFC3339') || message.includes('RFC7231') || message.includes('Epoch') || message.includes('Invalid month') ||
          message.includes('Invalid day') || message.includes('Offset direction') || message.includes(' must be between ')) return 'SDK_DATE_DESERIALIZATION';
      if (message.startsWith('Expected ')) return 'SDK_SHAPE_DESERIALIZATION';
      if (message.includes('"input" argument') || message.includes('base64')) return 'SDK_BUFFER';
    }
    if (name === 'TypeError') return 'CLIENT_TYPE_ERROR';
    if (name === 'Error') return 'CLIENT_ERROR';
    if (name === 'ValidationException') return 'VALIDATION';
    if (name === 'ThrottlingException') return 'THROTTLED';
    if (name === 'ServiceUnavailableException' || name === 'InternalServerException') return 'SERVICE_UNAVAILABLE';
    const metadata = candidate && '$metadata' in candidate && candidate.$metadata && typeof candidate.$metadata === 'object'
      ? candidate.$metadata as { httpStatusCode?: unknown }
      : undefined;
    const status = metadata?.httpStatusCode;
    if (status === 401 || status === 403) return 'ACCESS_DENIED';
    if (status === 400) return 'VALIDATION';
    if (status === 429) return 'THROTTLED';
    if (typeof status === 'number' && status >= 500 && status <= 599) return 'SERVICE_UNAVAILABLE';
    if (metadata) return 'SDK_METADATA_UNKNOWN';
    if (!name) return 'NAME_MISSING';
  } catch { return 'UNKNOWN'; }
  return 'UNKNOWN';
}

/** アカウント保持モードを権威あるBedrock control-plane APIから取得し、失敗を固定分類する。 */
export async function loadAccountDataRetentionMode(client: CommandSender): Promise<RetentionCheckResult> {
  try {
    const response = await client.send(new GetAccountDataRetentionCommand({}));
    const mode = response && typeof response === 'object' ? (response as { mode?: unknown }).mode : undefined;
    return typeof mode === 'string' ? { kind: 'verified', mode } : { kind: 'failed', reason: 'INVALID_RESPONSE' };
  } catch (error) {
    return { kind: 'failed', reason: retentionFailureReason(error) };
  }
}
