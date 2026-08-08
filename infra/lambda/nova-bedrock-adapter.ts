import { GetAccountDataRetentionCommand } from '@aws-sdk/client-bedrock';
import { ConverseCommand, ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { NovaTransport } from './nova-provider';

export type RetentionFailureReason = 'ACCESS_DENIED' | 'THROTTLED' | 'SERVICE_UNAVAILABLE' | 'INVALID_RESPONSE' | 'MODE_NOT_ALLOWED' | 'UNKNOWN';
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
    const name = error && typeof error === 'object' && 'name' in error && typeof error.name === 'string'
      ? error.name
      : '';
    if (name === 'AccessDeniedException') return 'ACCESS_DENIED';
    if (name === 'ThrottlingException') return 'THROTTLED';
    if (name === 'ServiceUnavailableException' || name === 'InternalServerException') return 'SERVICE_UNAVAILABLE';
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
