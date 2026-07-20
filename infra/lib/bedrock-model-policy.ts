import policyDocument from '../config/bedrock-model-policy.json';

export interface BedrockModelPolicy {
  readonly region: string;
  readonly modelId: string;
  readonly foundationModelId: string;
  readonly destinationRegions: readonly string[];
  readonly allowedModes: readonly string[];
  readonly verifiedAt: string;
  readonly validDays: number;
}

/** 直接metadata APIを持たないbedrock-runtimeモデルの公式証跡をデプロイ時に期限付き検証する。 */
export function loadBedrockModelPolicy(now: Date = new Date()): BedrockModelPolicy {
  const verifiedAt = new Date(`${policyDocument.verifiedAt}T00:00:00Z`);
  const ageMs = now.getTime() - verifiedAt.getTime();
  const validMs = policyDocument.validDays * 24 * 60 * 60 * 1000;
  const safe = policyDocument.region === 'ap-northeast-1' &&
    policyDocument.modelId === 'jp.amazon.nova-2-lite-v1:0' &&
    policyDocument.foundationModelId === 'amazon.nova-2-lite-v1:0' &&
    policyDocument.destinationRegions.length === 2 &&
    policyDocument.destinationRegions.includes('ap-northeast-1') &&
    policyDocument.destinationRegions.includes('ap-northeast-3') &&
    policyDocument.allowedModes.length === 1 && policyDocument.allowedModes[0] === 'none' && policyDocument.validDays === 90 &&
    policyDocument.evidence.length >= 2 && policyDocument.evidence.every((item) => item.url.startsWith('https://docs.aws.amazon.com/')) &&
    Number.isFinite(verifiedAt.getTime()) && ageMs >= 0 && ageMs <= validMs;
  if (!safe) throw new Error('Bedrock model policy evidence is invalid or expired');
  return policyDocument;
}
