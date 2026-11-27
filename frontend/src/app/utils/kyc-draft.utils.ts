/**
 * The half-finished KYC form, kept on the phone so someone who leaves does not start over.
 *
 * It lives in localStorage, which is plain text that anything running in the web view can read.
 * So one field is never allowed in it: the applicant's Social Security Number. Everything else on
 * part 1 of the form is kept. A resumed applicant retypes the number and nothing more.
 *
 * Every way in and out goes through this class, so the rule holds in one place:
 * - save() drops the number before writing;
 * - load() drops it from anything it reads, and rewrites a draft that had it;
 * - scrubSavedSsns() runs at launch and cleans drafts left by builds that used to save it,
 *   for every account on the phone, including ones nobody opens again.
 */
export class KycDraftUtils {
  private static readonly KEY_PREFIX = 'fred_kyc_step1_';

  /** Fields that must never be written to the draft. */
  private static readonly NEVER_SAVED: readonly string[] = ['taxId'];

  private static key(): string {
    return `${this.KEY_PREFIX}${localStorage.getItem('userId') || 'anon'}`;
  }

  /**
   * A copy without the fields that are never saved. Only text survives: every field on the form is
   * text, and a draft that was damaged into holding anything else must not reach the form.
   */
  private static withoutSecrets(draft: Record<string, unknown>): Record<string, unknown> {
    const safe: Record<string, unknown> = {};
    for (const [field, value] of Object.entries(draft)) {
      if (!this.NEVER_SAVED.includes(field) && typeof value === 'string') {
        safe[field] = value;
      }
    }
    return safe;
  }

  private static isPlainObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  /** Saves part 1 of the form for the signed-in user, without the Social Security Number. */
  static save(formValue: Record<string, unknown>): void {
    try {
      localStorage.setItem(this.key(), JSON.stringify(this.withoutSecrets(formValue)));
    } catch {
      // Storage full or blocked. The draft is a convenience; the form in memory still works.
    }
  }

  /** The saved draft for the signed-in user, never with a number in it. Null when there is none. */
  static load(): Record<string, unknown> | null {
    const key = this.key();
    this.scrubKey(key);
    try {
      const raw = localStorage.getItem(key);
      const parsed: unknown = raw ? JSON.parse(raw) : null;
      return this.isPlainObject(parsed) ? this.withoutSecrets(parsed) : null;
    } catch {
      return null;
    }
  }

  static clear(): void {
    try {
      localStorage.removeItem(this.key());
    } catch {
      // Nothing to do: there is no draft we can reach.
    }
  }

  /**
   * Removes the number from every KYC draft on this phone. Called once at launch. Drafts written by
   * this build never have one, so after the first launch this finds nothing to do.
   */
  static scrubSavedSsns(): void {
    try {
      const keys: string[] = [];
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && key.startsWith(this.KEY_PREFIX)) {
          keys.push(key);
        }
      }
      keys.forEach(key => this.scrubKey(key));
    } catch {
      // Storage unavailable: there is nothing saved to clean.
    }
  }

  /** Rewrites one draft without the number. A draft that cannot be read is removed whole. */
  private static scrubKey(key: string): void {
    try {
      const raw = localStorage.getItem(key);
      if (raw === null) {
        return;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(raw);
      } catch {
        localStorage.removeItem(key);
        return;
      }
      if (!this.isPlainObject(parsed)) {
        localStorage.removeItem(key);
        return;
      }
      if (this.NEVER_SAVED.some(field => field in parsed)) {
        localStorage.setItem(key, JSON.stringify(this.withoutSecrets(parsed)));
      }
    } catch {
      // Could not rewrite it. Removing is the safe fallback.
      try { localStorage.removeItem(key); } catch { /* storage unavailable */ }
    }
  }
}
