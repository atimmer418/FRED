/**
 * Remembers the last safe screen an onboarded user was on, so a cold start (iOS killed the app
 * or its web view) can reopen there instead of always landing on tab 1.
 *
 * A warm resume needs none of this: the web view is still alive and the user is still on their
 * screen. This only matters when the app boots from scratch, where the router starts at '/' and
 * something has to choose a landing.
 *
 * Pure logic plus localStorage, no Angular. The record is scoped to the user id so one account
 * can never reopen on another account's screen.
 */

export interface LastScreenRecord {
  userId: string;
  /** Always a restorable, query-free path (see RESTORABLE). */
  url: string;
  /** When the user last left the app, or last changed screen. Epoch ms. */
  savedAt: number;
}

export interface LandingContext {
  now: number;
  userId: string | null;
  /** The first-time tour starts from tab 1, so an unfinished tour always lands there. */
  tourSeen: boolean;
  /** An expired subscription gates tabs; do not restore into a gated screen. */
  subExpired: boolean;
}

/**
 * The screens that may be restored, each with the pages that sit underneath it. To make another
 * page restorable, add one row. `under` is the stack rebuilt below the page so Back and the
 * edge-swipe behave exactly as if the user had navigated there by hand. My Profile is always
 * rebuilt on the Profile tab because its own Back button returns there.
 */
const RESTORABLE: ReadonlyArray<{ match: RegExp; under: readonly string[] }> = [
  { match: /^\/tabs\/(tab1|tab2|chat|tab3)$/, under: [] },
  { match: /^\/my-profile$/, under: ['/tabs/tab3'] },
  { match: /^\/freedom$/, under: ['/tabs/tab3', '/my-profile'] },
  { match: /^\/goals\/[1-9]\d*$/, under: ['/tabs/tab3', '/my-profile'] },
];

/** Editing a saved goal falls back to that goal's page. */
const GOAL_EDIT = /^(\/goals\/[1-9]\d*)\/edit(\/plan)?$/;

export class LastScreenUtils {
  static readonly HOME = '/tabs/tab1';
  static readonly MAX_AGE_MS = 4 * 60 * 60 * 1000;
  // Bump v1 -> v2 if LastScreenRecord changes shape so an old record can never be read.
  private static readonly KEY_PREFIX = 'fred.lastScreen.v1.';

  static storageKey(userId: string): string {
    return LastScreenUtils.KEY_PREFIX + userId;
  }

  /**
   * Map any URL to the screen that should be restored for it, or null when it says nothing
   * about where to reopen (money, security and onboarding pages). Query and hash are dropped:
   * their meaning belongs to the moment the page was opened.
   */
  static toRestorable(url: string | null | undefined): string | null {
    if (!url) return null;
    const path = url.split('?')[0].split('#')[0];
    if (RESTORABLE.some(row => row.match.test(path))) return path;

    const edit = GOAL_EDIT.exec(path);
    if (edit) return edit[1];

    // The new-goal quiz and its preview depend on an in-memory draft and cannot reopen cold.
    if (path.startsWith('/goals/')) return '/my-profile';

    return null;
  }

  /**
   * Record a screen change. A screen that is not restorable leaves the record alone, so the
   * saved screen stays at the last safe one the user passed through.
   */
  static record(url: string, now: number = Date.now()): void {
    const userId = LastScreenUtils.currentUserId();
    if (!userId) return;
    const restorable = LastScreenUtils.toRestorable(url);
    if (!restorable) return;
    LastScreenUtils.write({ userId, url: restorable, savedAt: now });
  }

  /**
   * Re-stamp the time on the current user's record without changing the screen. Call this only
   * when the user is genuinely leaving the app: the 4 hour window is measured from here, and a
   * stamp at any other moment (the Face ID sheet also resigns active) would revive a stale record.
   */
  static touch(now: number = Date.now()): void {
    const userId = LastScreenUtils.currentUserId();
    if (!userId) return;
    const record = LastScreenUtils.read(userId);
    if (!record) return;
    LastScreenUtils.write({ ...record, savedAt: now });
  }

  /**
   * The navigations that land an onboarded user on a cold start, in order: the tab first, then
   * each page pushed on top of it. Never empty. Anything that is not clearly a fresh, valid
   * record for this user yields the home tab.
   */
  static landingChain(ctx: LandingContext): string[] {
    const home = [LastScreenUtils.HOME];
    if (!ctx.userId || !ctx.tourSeen || ctx.subExpired) return home;

    const record = LastScreenUtils.read(ctx.userId);
    if (!record) return home;

    // A negative age means the clock moved backwards; treat it as unknown, not as fresh.
    const age = ctx.now - record.savedAt;
    if (!(age >= 0 && age < LastScreenUtils.MAX_AGE_MS)) return home;

    // Re-validate: a record written by an older build may no longer be restorable.
    if (LastScreenUtils.toRestorable(record.url) !== record.url) return home;
    const row = RESTORABLE.find(r => r.match.test(record.url));
    if (!row) return home;

    return [...row.under, record.url];
  }

  /**
   * True once Ionic has finished building the page stack inside the router outlet: every page
   * has been adopted, only the top page is showing, and it has laid out its content. Used to
   * hold the launch cover until a restored page is really on screen, so the tab underneath is
   * never seen first. An entering page is briefly invisible, or not yet adopted, while the page
   * below it is still the visible one; both read as not settled.
   */
  static isOutletSettled(outlet: Element | null): boolean {
    if (!outlet) return false;
    const pages = Array.from(outlet.children);
    if (pages.length === 0) return false;
    if (!pages.every(page => page.classList.contains('ion-page'))) return false;

    const top = pages[pages.length - 1];
    if (top.classList.contains('ion-page-invisible') || top.classList.contains('ion-page-hidden')) {
      return false;
    }
    if (!pages.slice(0, -1).every(page => page.classList.contains('ion-page-hidden'))) return false;

    const content = top.querySelector('ion-content') as HTMLElement | null;
    return !!content && content.offsetHeight > 0;
  }

  static clear(userId: string): void {
    try {
      localStorage.removeItem(LastScreenUtils.storageKey(userId));
    } catch {
      /* storage unavailable: nothing to clear */
    }
  }

  private static currentUserId(): string | null {
    try {
      return localStorage.getItem('userId');
    } catch {
      return null;
    }
  }

  private static read(userId: string): LastScreenRecord | null {
    try {
      const raw = localStorage.getItem(LastScreenUtils.storageKey(userId));
      if (!raw) return null;
      const record = JSON.parse(raw) as LastScreenRecord;
      const valid = !!record
        && record.userId === userId
        && typeof record.url === 'string'
        && typeof record.savedAt === 'number'
        && Number.isFinite(record.savedAt);
      return valid ? record : null;
    } catch {
      return null;
    }
  }

  private static write(record: LastScreenRecord): void {
    try {
      localStorage.setItem(LastScreenUtils.storageKey(record.userId), JSON.stringify(record));
    } catch {
      /* quota or private mode: remembering the screen is best-effort */
    }
  }
}
