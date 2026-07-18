import { AnalysisProviderResult, CANDIDATE_LIMIT_WARNING, validateProviderResult } from '../lambda/provider-boundary';

const requestId = '018f47a0-90c0-7d54-b92d-4285f7fb3312';
const validCandidate = {
  name: '牛乳', quantity: '1', unit: '本', evidence: 'VISIBLE_COUNT', requiresReview: false,
} as const;

function result(candidates: readonly unknown[]): AnalysisProviderResult {
  return { requestId, status: 'succeeded', candidates, warnings: [] };
}

describe('AI provider信頼境界', () => {
  it('31件目以降を混入させず上限超過warningを付ける', () => {
    const validated = validateProviderResult(result(Array.from({ length: 31 }, (_, index) => ({ ...validCandidate, name: `食材${index}` }))), requestId);
    expect(validated?.candidates).toHaveLength(30);
    expect(validated?.warnings).toContain(CANDIDATE_LIMIT_WARNING);
    expect(validated?.candidates.some((candidate) => candidate.name === '食材30')).toBe(false);
  });

  it('31件目の不正値と既存warning上限は先頭30件の候補を妨げない', () => {
    const candidates = [...Array.from({ length: 30 }, () => validCandidate), { ...validCandidate, unit: 'ケース' }];
    const raw = { ...result(candidates), warnings: Array.from({ length: 30 }, (_, index) => `WARN_${index}`) };
    const validated = validateProviderResult(raw, requestId);
    expect(validated?.candidates).toHaveLength(30);
    expect(validated?.warnings).toHaveLength(30);
    expect(validated?.warnings).toContain(CANDIDATE_LIMIT_WARNING);
  });

  it.each([
    ['空白だけの名称', { ...validCandidate, name: '  ' }],
    ['前後空白付き名称', { ...validCandidate, name: ' 牛乳' }],
    ['制御文字付き名称', { ...validCandidate, name: '牛\n乳' }],
    ['31文字の名称', { ...validCandidate, name: 'あ'.repeat(31) }],
    ['範囲外数量', { ...validCandidate, quantity: '100.01' }],
    ['未定義単位', { ...validCandidate, unit: 'ケース' }],
    ['数量だけ不明', { ...validCandidate, quantity: null }],
    ['単位だけ不明', { ...validCandidate, unit: null }],
    ['不明値なのに未確認扱い', { ...validCandidate, quantity: null, unit: null, requiresReview: false }],
    ['根拠不明なのに未確認扱い', { ...validCandidate, evidence: 'UNKNOWN', requiresReview: false }],
    ['余分なプロパティ', { ...validCandidate, confidence: 0.99 }],
  ])('%sをクライアントへ通さない', (_name, candidate) => {
    expect(validateProviderResult(result([candidate]), requestId)).toBeUndefined();
  });

  it('名称・数量・単位が不明でも要確認なら候補として通す', () => {
    const candidate = { name: null, quantity: null, unit: null, evidence: 'UNKNOWN', requiresReview: true };
    expect(validateProviderResult(result([candidate]), requestId)?.candidates).toEqual([candidate]);
  });

  it('requestIdが異なる出力を拒否する', () => {
    expect(validateProviderResult(result([validCandidate]), '550e8400-e29b-41d4-a716-446655440000')).toBeUndefined();
  });
});
