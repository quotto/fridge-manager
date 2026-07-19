export type QuotaLimitType = 'SHORT' | 'DAILY' | 'MONTHLY';
export type QuotaReservation = { readonly kind: 'reserved'; readonly reservationKey: string } |
  { readonly kind: 'exceeded'; readonly limitType: QuotaLimitType; readonly retryAt: string };

export interface QuotaStore {
  reserve(userHash: string, requestId: string): Promise<QuotaReservation>;
  succeed(reservationKey: string): Promise<void>;
  release(reservationKey: string): Promise<void>;
}
