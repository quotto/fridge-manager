import { GetAccountDataRetentionCommand } from '@aws-sdk/client-bedrock';
import { ConverseCommand, ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { AnalysisError } from './analysis-handler';
import { NovaTransport } from './nova-provider';

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

/** アカウント保持モードを権威あるBedrock control-plane APIから取得する。 */
export async function loadAccountDataRetentionMode(client: CommandSender): Promise<string> {
  try {
    const response = await client.send(new GetAccountDataRetentionCommand({}));
    const mode = response && typeof response === 'object' ? (response as { mode?: unknown }).mode : undefined;
    if (typeof mode !== 'string') throw new Error('missing retention mode');
    return mode;
  } catch {
    throw new AnalysisError('PROVIDER_UNAVAILABLE', 503);
  }
}
