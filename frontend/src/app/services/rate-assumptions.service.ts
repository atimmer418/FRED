import { Injectable, signal } from '@angular/core';

/**
 * RateAssumptionsService — user-adjustable projection assumptions.
 *
 * Frontend-wide source of truth for the two rates every freedom projection
 * depends on (annual return, safe withdrawal). Defaults mirror the backend's
 * MonthlyFreedomUpdateService constants (10% / 4%) — with defaults untouched,
 * frontend numbers remain in exact parity with backend MFU math. Users who
 * customize rates diverge from the (currently mocked) monthly-update emails
 * until a backend follow-up persists per-user rates server-side.
 *
 * Persistence mirrors GoalsService discipline: per-user localStorage key with
 * schema version; corrupt/missing data silently falls back to defaults.
 */
const SCHEMA_VERSION = 1;
const KEY_PREFIX = `fred.rateAssumptions.v${SCHEMA_VERSION}.`;

export const DEFAULT_ANNUAL_RETURN = 0.10;

/**
 * The only selectable return rates (mirror the Monte Carlo scenarios:
 * Conservative / Moderate / Optimistic). Free-form customization was removed
 * 2026-09-12; reload() snaps any legacy stepper-era value to the nearest
 * preset so every surface (sheet, my-profile, tab3, MFU) agrees.
 */
export const RETURN_RATE_PRESETS: number[] = [0.08, 0.10, 0.12];
export const DEFAULT_WITHDRAWAL_RATE = 0.04;

/** Sane bounds for user-set values (fractions). */
export const ANNUAL_RETURN_BOUNDS: [number, number] = [0.04, 0.14];
export const WITHDRAWAL_RATE_BOUNDS: [number, number] = [0.025, 0.06];

@Injectable({ providedIn: 'root' })
export class RateAssumptionsService {

  /** Assumed average annual portfolio return (fraction, e.g. 0.10). */
  readonly annualReturn = signal(DEFAULT_ANNUAL_RETURN);

  /** Safe withdrawal rate (fraction, e.g. 0.04). */
  readonly withdrawalRate = signal(DEFAULT_WITHDRAWAL_RATE);

  get isDefault(): boolean {
    return this.annualReturn() === DEFAULT_ANNUAL_RETURN
      && this.withdrawalRate() === DEFAULT_WITHDRAWAL_RATE;
  }

  /** The userId the signals were last loaded for — guards cross-user leakage. */
  private loadedFor: string | null = null;

  constructor() {
    this.reload();
  }

  /**
   * Cheap idempotent guard: reload if the logged-in user changed since the
   * signals were populated (root-provided service outlives login sessions).
   * Called before reads at rate-editing surfaces and before every write.
   */
  ensureUser(): void {
    if (localStorage.getItem('userId') !== this.loadedFor) this.reload();
  }

  /** Re-read persisted rates for the current user (call after login changes). */
  reload(): void {
    const userId = localStorage.getItem('userId');
    this.loadedFor = userId;
    if (!userId) {
      this.annualReturn.set(DEFAULT_ANNUAL_RETURN);
      this.withdrawalRate.set(DEFAULT_WITHDRAWAL_RATE);
      return;
    }
    try {
      const raw = localStorage.getItem(KEY_PREFIX + userId);
      const parsed = raw ? JSON.parse(raw) : null;
      const clamped = this._clamp(parsed?.annualReturn, ANNUAL_RETURN_BOUNDS, DEFAULT_ANNUAL_RETURN);
      this.annualReturn.set(RETURN_RATE_PRESETS.reduce((best, r) =>
        Math.abs(r - clamped) < Math.abs(best - clamped) ? r : best));
      // Withdrawal customization removed 2026-09-12 (industry-standard 4%
      // only). Ignore any legacy persisted value so old customizations can't
      // silently skew projections with no UI left to change them.
      this.withdrawalRate.set(DEFAULT_WITHDRAWAL_RATE);
    } catch {
      this.annualReturn.set(DEFAULT_ANNUAL_RETURN);
      this.withdrawalRate.set(DEFAULT_WITHDRAWAL_RATE);
    }
  }

  /** Set + persist both rates (values are clamped to bounds). */
  setRates(annualReturn: number, withdrawalRate: number): void {
    this.ensureUser();
    const ar = this._clamp(annualReturn, ANNUAL_RETURN_BOUNDS, DEFAULT_ANNUAL_RETURN);
    const wr = this._clamp(withdrawalRate, WITHDRAWAL_RATE_BOUNDS, DEFAULT_WITHDRAWAL_RATE);
    this.annualReturn.set(ar);
    this.withdrawalRate.set(wr);
    const userId = localStorage.getItem('userId');
    if (!userId) return;
    try {
      localStorage.setItem(KEY_PREFIX + userId, JSON.stringify({ annualReturn: ar, withdrawalRate: wr }));
    } catch {
      // Storage unavailable — rates still apply for this session.
    }
  }

  /** Restore the standard 10% / 4% assumptions. */
  resetToDefaults(): void {
    this.setRates(DEFAULT_ANNUAL_RETURN, DEFAULT_WITHDRAWAL_RATE);
  }

  private _clamp(value: unknown, [min, max]: [number, number], fallback: number): number {
    return typeof value === 'number' && isFinite(value)
      ? Math.min(max, Math.max(min, value))
      : fallback;
  }
}
