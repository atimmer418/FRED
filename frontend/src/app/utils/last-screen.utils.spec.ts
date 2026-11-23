import { LandingContext, LastScreenUtils } from './last-screen.utils';

const USER = 'user-42';
const KEY = LastScreenUtils.storageKey(USER);
const NOW = 1_800_000_000_000;
const HOUR = 60 * 60 * 1000;

function seed(url: string, savedAt: number, userId = USER): void {
  localStorage.setItem(LastScreenUtils.storageKey(userId), JSON.stringify({ userId, url, savedAt }));
}

function stored(): { userId: string; url: string; savedAt: number } | null {
  const raw = localStorage.getItem(KEY);
  return raw ? JSON.parse(raw) : null;
}

function ctx(overrides: Partial<LandingContext> = {}): LandingContext {
  return { now: NOW, userId: USER, tourSeen: true, subExpired: false, ...overrides };
}

describe('LastScreenUtils', () => {
  beforeEach(() => {
    localStorage.removeItem(KEY);
    localStorage.removeItem(LastScreenUtils.storageKey('someone-else'));
    localStorage.setItem('userId', USER);
  });

  afterEach(() => {
    localStorage.removeItem(KEY);
    localStorage.removeItem(LastScreenUtils.storageKey('someone-else'));
    localStorage.removeItem('userId');
  });

  describe('toRestorable', () => {
    it('keeps each of the four tabs', () => {
      for (const tab of ['tab1', 'tab2', 'chat', 'tab3']) {
        expect(LastScreenUtils.toRestorable(`/tabs/${tab}`)).toBe(`/tabs/${tab}`);
      }
    });

    it('rejects an unknown tab and the bare tabs path', () => {
      expect(LastScreenUtils.toRestorable('/tabs/tab9')).toBeNull();
      expect(LastScreenUtils.toRestorable('/tabs')).toBeNull();
    });

    it('keeps My Profile, the Freedom Plan and a saved goal', () => {
      expect(LastScreenUtils.toRestorable('/my-profile')).toBe('/my-profile');
      expect(LastScreenUtils.toRestorable('/freedom')).toBe('/freedom');
      expect(LastScreenUtils.toRestorable('/goals/12')).toBe('/goals/12');
    });

    it('drops the query string and hash', () => {
      expect(LastScreenUtils.toRestorable('/goals/12?from=card#top')).toBe('/goals/12');
      expect(LastScreenUtils.toRestorable('/my-profile?x=1')).toBe('/my-profile');
    });

    it('sends goal editing back to that goal', () => {
      expect(LastScreenUtils.toRestorable('/goals/12/edit')).toBe('/goals/12');
      expect(LastScreenUtils.toRestorable('/goals/12/edit/plan')).toBe('/goals/12');
    });

    it('sends the new-goal quiz and preview back to My Profile', () => {
      expect(LastScreenUtils.toRestorable('/goals/new/house')).toBe('/my-profile');
      expect(LastScreenUtils.toRestorable('/goals/new/house/plan')).toBe('/my-profile');
    });

    it('sends a malformed goal id back to My Profile', () => {
      expect(LastScreenUtils.toRestorable('/goals/0')).toBe('/my-profile');
      expect(LastScreenUtils.toRestorable('/goals/abc')).toBe('/my-profile');
    });

    it('never restores money, security or onboarding pages', () => {
      const unsafe = [
        '/sell-withdraw', '/change-bank-account', '/security-settings', '/beneficiaries',
        '/beneficiaries/edit/3', '/document-upload', '/recurring-investments', '/portfolio-customize',
        '/get-started', '/survey-initial', '/fi-plan-results', '/auth-finalize', '/kyc-verification',
        '/link-bank', '/investment-schedule', '/investment-confirmation', '/recovery', '/', '',
      ];
      for (const url of unsafe) {
        expect(LastScreenUtils.toRestorable(url)).withContext(url).toBeNull();
      }
      expect(LastScreenUtils.toRestorable(null)).toBeNull();
      expect(LastScreenUtils.toRestorable(undefined)).toBeNull();
    });
  });

  describe('record', () => {
    it('writes a restorable screen with the time', () => {
      LastScreenUtils.record('/tabs/tab2', NOW);
      expect(stored()).toEqual({ userId: USER, url: '/tabs/tab2', savedAt: NOW });
    });

    it('stores the fallback screen for a goal sub-flow', () => {
      LastScreenUtils.record('/goals/new/house/plan', NOW);
      expect(stored()!.url).toBe('/my-profile');
    });

    it('leaves the record untouched on a screen that is not restorable', () => {
      seed('/my-profile', NOW - HOUR);
      LastScreenUtils.record('/sell-withdraw', NOW);
      expect(stored()).toEqual({ userId: USER, url: '/my-profile', savedAt: NOW - HOUR });
    });

    it('does nothing when no user is signed in', () => {
      localStorage.removeItem('userId');
      LastScreenUtils.record('/tabs/tab2', NOW);
      expect(stored()).toBeNull();
    });
  });

  describe('touch', () => {
    it('re-stamps the time and keeps the screen, even on an old record', () => {
      seed('/goals/7', NOW - 9 * HOUR);
      LastScreenUtils.touch(NOW);
      expect(stored()).toEqual({ userId: USER, url: '/goals/7', savedAt: NOW });
    });

    it('does nothing without a record', () => {
      LastScreenUtils.touch(NOW);
      expect(stored()).toBeNull();
    });

    it('does not touch another user\'s record', () => {
      seed('/tabs/tab2', NOW - HOUR, 'someone-else');
      LastScreenUtils.touch(NOW);
      const other = JSON.parse(localStorage.getItem(LastScreenUtils.storageKey('someone-else'))!);
      expect(other.savedAt).toBe(NOW - HOUR);
    });
  });

  describe('landingChain', () => {
    const HOME = ['/tabs/tab1'];

    it('lands on tab 1 with no record', () => {
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
    });

    it('lands on tab 1 when the record is corrupt', () => {
      localStorage.setItem(KEY, '{not json');
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
      localStorage.setItem(KEY, JSON.stringify({ userId: USER, url: '/tabs/tab2' }));
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
    });

    it('restores a tab on its own', () => {
      seed('/tabs/chat', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(['/tabs/chat']);
    });

    it('rebuilds the Profile tab under My Profile', () => {
      seed('/my-profile', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(['/tabs/tab3', '/my-profile']);
    });

    it('rebuilds the Profile tab and My Profile under a goal and the Freedom Plan', () => {
      seed('/goals/7', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(['/tabs/tab3', '/my-profile', '/goals/7']);
      seed('/freedom', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(['/tabs/tab3', '/my-profile', '/freedom']);
    });

    it('restores just inside the 4 hour window and not at it', () => {
      seed('/tabs/tab2', NOW - LastScreenUtils.MAX_AGE_MS + 1);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(['/tabs/tab2']);
      seed('/tabs/tab2', NOW - LastScreenUtils.MAX_AGE_MS);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
    });

    it('lands on tab 1 when the saved time is in the future', () => {
      seed('/tabs/tab2', NOW + 1000);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
    });

    it('lands on tab 1 for a record that belongs to another user', () => {
      localStorage.setItem(KEY, JSON.stringify({ userId: 'someone-else', url: '/tabs/tab2', savedAt: NOW }));
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
      expect(LastScreenUtils.landingChain(ctx({ userId: null }))).toEqual(HOME);
    });

    it('lands on tab 1 until the first-time tour has been seen', () => {
      seed('/tabs/tab2', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx({ tourSeen: false }))).toEqual(HOME);
    });

    it('lands on tab 1 when the subscription has expired', () => {
      seed('/tabs/tab2', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx({ subExpired: true }))).toEqual(HOME);
    });

    it('lands on tab 1 when the stored screen is not restorable', () => {
      seed('/sell-withdraw', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
      seed('/goals/7/edit', NOW - HOUR);
      expect(LastScreenUtils.landingChain(ctx())).toEqual(HOME);
    });
  });

  describe('isOutletSettled', () => {
    let outlet: HTMLElement;

    function page(classes: string, contentHeight = 400): HTMLElement {
      const el = document.createElement('div');
      el.className = classes;
      const content = document.createElement('ion-content');
      // Stubbed rather than laid out: the test page is not rendered until Ionic hydrates.
      Object.defineProperty(content, 'offsetHeight', { value: contentHeight });
      el.appendChild(content);
      outlet.appendChild(el);
      return el;
    }

    beforeEach(() => {
      outlet = document.createElement('div');
      document.body.appendChild(outlet);
    });

    afterEach(() => outlet.remove());

    it('is false with no outlet or no pages', () => {
      expect(LastScreenUtils.isOutletSettled(null)).toBeFalse();
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeFalse();
    });

    it('is true for a single painted page', () => {
      page('ion-page');
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeTrue();
    });

    it('is true for a pushed page over hidden pages', () => {
      page('ion-page ion-page-hidden');
      page('ion-page ion-page-hidden');
      page('ion-page');
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeTrue();
    });

    it('is false while the entering page is still invisible', () => {
      page('ion-page');
      page('ion-page ion-page-invisible');
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeFalse();
    });

    it('is false while a page has not been adopted yet', () => {
      page('ion-page ion-page-hidden');
      page('');
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeFalse();
    });

    it('is false while the page underneath is still showing', () => {
      page('ion-page');
      page('ion-page');
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeFalse();
    });

    it('is false until the top page has laid out its content', () => {
      page('ion-page', 0);
      expect(LastScreenUtils.isOutletSettled(outlet)).toBeFalse();
    });
  });

  describe('clear', () => {
    it('removes the record', () => {
      seed('/tabs/tab2', NOW);
      LastScreenUtils.clear(USER);
      expect(stored()).toBeNull();
    });
  });
});
