import Ajv2020 from 'ajv/dist/2020';
import candidateSchema from '../api/schemas/food-candidate.schema.json';

export const CANDIDATE_LIMIT_WARNING = 'CANDIDATE_LIMIT_EXCEEDED';
export const FOOD_UNITS = ['g', 'kg', 'ml', 'L', '個', '本', '枚', '袋', 'パック', '箱', '缶', '瓶', '束', '株', '玉', '丁', '尾', '切れ', '房', '合', '食'] as const;
export type FoodUnit = typeof FOOD_UNITS[number];
export type CandidateEvidence = 'VISIBLE_COUNT' | 'PACKAGE_LABEL' | 'VISUAL_ESTIMATE' | 'UNKNOWN';
export interface FoodCandidate {
  readonly name: string | null;
  readonly quantity: string | null;
  readonly unit: FoodUnit | null;
  readonly evidence: CandidateEvidence;
  readonly requiresReview: boolean;
}
export interface AnalysisProviderResult {
  readonly requestId: string;
  readonly status: 'succeeded';
  readonly candidates: readonly unknown[];
  readonly warnings: readonly string[];
}
export interface ValidatedAnalysisResult extends Omit<AnalysisProviderResult, 'candidates'> {
  readonly candidates: readonly FoodCandidate[];
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
const validateCandidateSchema = ajv.compile(candidateSchema);

function containsControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f));
  });
}

function isValidCandidate(value: unknown): value is FoodCandidate {
  if (!validateCandidateSchema(value) || !value || typeof value !== 'object') return false;
  const candidate = value as unknown as FoodCandidate;
  if (candidate.name !== null && (candidate.name !== candidate.name.trim() || [...candidate.name].length < 1 || [...candidate.name].length > 30 || containsControlCharacter(candidate.name))) return false;
  if ((candidate.quantity === null) !== (candidate.unit === null)) return false;
  if ((candidate.name === null || candidate.quantity === null || candidate.unit === null || candidate.evidence === 'UNKNOWN') && !candidate.requiresReview) return false;
  return true;
}

/** AI出力を信頼境界で検証し、最大件数だけは要件どおり決定的に切り詰める。 */
export function validateProviderResult(value: unknown, expectedRequestId: string): ValidatedAnalysisResult | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (Object.keys(raw).some((key) => !['requestId', 'status', 'candidates', 'warnings'].includes(key)) ||
      raw.requestId !== expectedRequestId || raw.status !== 'succeeded' || !Array.isArray(raw.candidates) || !Array.isArray(raw.warnings)) return undefined;
  const exceeded = raw.candidates.length > 30;
  const candidates = raw.candidates.slice(0, 30);
  if (!candidates.every(isValidCandidate)) return undefined;
  const warningLimit = exceeded ? 29 : 30;
  const sourceWarnings = raw.warnings.slice(0, warningLimit);
  if (sourceWarnings.some((warning) => typeof warning !== 'string' || [...warning].length > 100)) return undefined;
  const warnings = exceeded
    ? [...sourceWarnings.filter((warning) => warning !== CANDIDATE_LIMIT_WARNING), CANDIDATE_LIMIT_WARNING].slice(-30)
    : sourceWarnings;
  return { requestId: raw.requestId, status: 'succeeded', candidates, warnings };
}
