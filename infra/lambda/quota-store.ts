export type QuotaLimitType = 'SHORT' | 'DAILY' | 'MONTHLY' | 'GLOBAL' | 'BUDGET';
export type QuotaReservation = { readonly kind: 'reserved'; readonly reservationKey: string } |
  { readonly kind: 'exceeded'; readonly limitType: QuotaLimitType; readonly retryAt: string } |
  { readonly kind: 'stopped' };

export interface QuotaStore {
  reserve(userHash: string, requestId: string): Promise<QuotaReservation>;
  succeed(reservationKey: string): Promise<void>;
  release(reservationKey: string): Promise<void>;
}
