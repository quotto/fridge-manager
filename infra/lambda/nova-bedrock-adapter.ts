import { defaultProvider } from '@aws-sdk/credential-provider-node';
import { ConverseCommand, ConverseCommandInput } from '@aws-sdk/client-bedrock-runtime';
import { createHash, createHmac, Hash, Hmac } from 'node:crypto';
import { HttpRequest } from '@smithy/core/protocols';
import { NodeHttpHandler } from '@smithy/node-http-handler';
import { SignatureV4 } from '@smithy/signature-v4';
import { NovaTransport } from './nova-provider';

export type RetentionFailureReason = 'ACCESS_DENIED' | 'VALIDATION' | 'THROTTLED' | 'SERVICE_UNAVAILABLE' | 'CREDENTIALS' | 'NETWORK' | 'RETENTION_SIGNING' | 'RETENTION_TRANSPORT' | 'RETENTION_BODY' | 'RETENTION_JSON' | 'CLIENT_CONFIGURATION' | 'SDK_DESERIALIZATION' | 'SDK_DATE_DESERIALIZATION' | 'SDK_SHAPE_DESERIALIZATION' | 'SDK_BUFFER' | 'CLIENT_TYPE_ERROR' | 'CLIENT_ERROR' | 'NAME_MISSING' | 'SDK_METADATA_UNKNOWN' | 'INVALID_RESPONSE' | 'MODE_NOT_ALLOWED' | 'UNKNOWN';
export type RetentionCheckResult =
  { readonly kind: 'verified'; readonly mode: string } |
  { readonly kind: 'failed'; readonly reason: RetentionFailureReason };

interface CommandSender {
  send(command: unknown): Promise<unknown>;
}
interface RetentionClient { getAccountDataRetention(): Promise<unknown>; }

interface RequestSigner { sign(request: HttpRequest): Promise<HttpRequest>; }
interface RequestHandler { handle(request: HttpRequest): Promise<{ response: { statusCode: number; body?: unknown } }>; }
type RequestHandlerFactory = (options: typeof RETENTION_HTTP_TIMEOUTS) => RequestHandler;

export class NodeSha256 {
  private readonly hash: Hash | Hmac;
  public constructor(secret?: string | ArrayBuffer | ArrayBufferView) {
    this.hash = secret === undefined ? createHash('sha256') : createHmac('sha256', Buffer.from(secret as never));
  }
  public update(data: Uint8Array | string): void { this.hash.update(data); }
  public async digest(): Promise<Uint8Array> { return this.hash.digest(); }
}

export const RETENTION_HTTP_TIMEOUTS = {
  connectionTimeout: 3_000,
  requestTimeout: 5_000,
  socketTimeout: 5_000,
  throwOnRequestTimeout: true,
} as const;

async function readBody(body: unknown): Promise<string> {
  if (!body || typeof body !== 'object' || !(Symbol.asyncIterator in body)) throw new TypeError('response body is not async iterable');
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of body as AsyncIterable<Uint8Array | string>) {
    const buffer = Buffer.from(chunk);
    total += buffer.byteLength;
    if (total > 16_384) throw new TypeError('response body exceeds limit');
    chunks.push(buffer);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/** SDKの日時デシリアライズを避け、署名済み応答から保持modeだけを厳格に読む。 */
export class SignedBedrockRetentionClient implements RetentionClient {
  private readonly signer: RequestSigner;
  private readonly handler: RequestHandler;
  public constructor(
    private readonly region: string,
    signer?: RequestSigner,
    handler?: RequestHandler,
    handlerFactory: RequestHandlerFactory = (options) => new NodeHttpHandler(options),
  ) {
    if (region !== 'ap-northeast-1') throw new TypeError('unsupported Bedrock retention region');
    const signature = signer ?? new SignatureV4({
      credentials: defaultProvider(), region, service: 'bedrock', sha256: NodeSha256,
    });
    this.signer = { sign: (request) => signature.sign(request) as Promise<HttpRequest> };
    this.handler = handler ?? handlerFactory(RETENTION_HTTP_TIMEOUTS);
  }

  public async getAccountDataRetention(): Promise<unknown> {
    const hostname = `bedrock.${this.region}.amazonaws.com`;
    let request: HttpRequest;
    try {
      request = await this.signer.sign(new HttpRequest({
        protocol: 'https:', hostname, method: 'GET', path: '/data-retention', headers: { host: hostname },
      }));
    } catch { throw Object.assign(new Error('retention signing failed'), { name: 'RetentionSigningError' }); }
    let response: { statusCode: number; body?: unknown };
    try { ({ response } = await this.handler.handle(request)); }
    catch { throw Object.assign(new Error('retention transport failed'), { name: 'RetentionTransportError' }); }
    if (response.statusCode !== 200) {
      throw Object.assign(new Error('Bedrock control-plane request failed'), { $metadata: { httpStatusCode: response.statusCode } });
    }
    let body: string;
    try { body = await readBody(response.body); }
    catch { throw Object.assign(new Error('retention body failed'), { name: 'RetentionBodyError' }); }
    let parsed: unknown;
    try { parsed = JSON.parse(body); }
    catch { throw Object.assign(new Error('retention JSON failed'), { name: 'RetentionJsonError' }); }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const mode = (parsed as { mode?: unknown }).mode;
    return typeof mode === 'string' ? { mode } : {};
  }
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
    if (name === 'RetentionSigningError') return 'RETENTION_SIGNING';
    if (name === 'RetentionTransportError') return 'RETENTION_TRANSPORT';
    if (name === 'RetentionBodyError') return 'RETENTION_BODY';
    if (name === 'RetentionJsonError') return 'RETENTION_JSON';
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
    if (name === 'TypeError') return 'CLIENT_TYPE_ERROR';
    if (name === 'Error') return 'CLIENT_ERROR';
    if (!name) return 'NAME_MISSING';
  } catch { return 'UNKNOWN'; }
  return 'UNKNOWN';
}

/** アカウント保持モードを権威あるBedrock control-plane APIから取得し、失敗を固定分類する。 */
export async function loadAccountDataRetentionMode(client: RetentionClient): Promise<RetentionCheckResult> {
  try {
    const response = await client.getAccountDataRetention();
    const mode = response && typeof response === 'object' ? (response as { mode?: unknown }).mode : undefined;
    return typeof mode === 'string' ? { kind: 'verified', mode } : { kind: 'failed', reason: 'INVALID_RESPONSE' };
  } catch (error) {
    return { kind: 'failed', reason: retentionFailureReason(error) };
  }
}
