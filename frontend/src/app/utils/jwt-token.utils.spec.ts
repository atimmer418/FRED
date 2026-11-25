import { JwtTokenUtils } from './jwt-token.utils';

// Build a real JWT-shaped string: header.payload.signature, each segment base64url.
function makeToken(payload: object): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify(payload));
  return `${header}.${body}.fake-signature`;
}

// Helpers to seed / clear localStorage
function seedToken(payload: object): void {
  const token = makeToken(payload);
  localStorage.setItem('jwtToken', token);
  // Set expiration far in the future so getValidJwtToken() returns it
  localStorage.setItem('jwtExpiration', String(Math.floor(Date.now() / 1000) + 3600));
}

function clearToken(): void {
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtExpiration');
}

describe('JwtTokenUtils.decodeJwtPayload', () => {
  it('decodes the claims from a valid JWT payload segment', () => {
    const token = makeToken({ sub: '1234567890', email: 'test@fred.com', exp: 9999999999 });
    const result = JwtTokenUtils.decodeJwtPayload(token);
    expect(result).toEqual({ sub: '1234567890', email: 'test@fred.com', exp: 9999999999 });
  });

  it('returns null for a malformed token', () => {
    const result = JwtTokenUtils.decodeJwtPayload('not-a-jwt');
    expect(result).toBeNull();
  });
});

describe('JwtTokenUtils.getNextStepHint', () => {
  afterEach(() => clearToken());

  it('returns "complete" when the stored JWT carries ns="complete"', () => {
    seedToken({ sub: 'test@fred.com', exp: 9999999999, ns: 'complete' });
    expect(JwtTokenUtils.getNextStepHint()).toBe('complete');
  });

  it('returns "kyc-verification" when the stored JWT carries ns="kyc-verification"', () => {
    seedToken({ sub: 'test@fred.com', exp: 9999999999, ns: 'kyc-verification' });
    expect(JwtTokenUtils.getNextStepHint()).toBe('kyc-verification');
  });

  it('returns null when the stored JWT has no ns claim', () => {
    seedToken({ sub: 'test@fred.com', exp: 9999999999 });
    expect(JwtTokenUtils.getNextStepHint()).toBeNull();
  });

  it('returns null when no valid token is stored', () => {
    clearToken();
    expect(JwtTokenUtils.getNextStepHint()).toBeNull();
  });
});

describe('JwtTokenUtils.NEXT_STEP_ROUTE_MAP', () => {
  it('maps "complete" to "/tabs/tab1"', () => {
    expect(JwtTokenUtils.NEXT_STEP_ROUTE_MAP['complete']).toBe('/tabs/tab1');
  });

  it('maps "kyc-verification" to "/kyc-verification"', () => {
    expect(JwtTokenUtils.NEXT_STEP_ROUTE_MAP['kyc-verification']).toBe('/kyc-verification');
  });

  it('maps "surveyinitial" to "/survey-initial"', () => {
    expect(JwtTokenUtils.NEXT_STEP_ROUTE_MAP['surveyinitial']).toBe('/survey-initial');
  });

  it('contains entries for all 9 known slugs', () => {
    const slugs = [
      'get-started', 'surveyinitial', 'fi-plan-results', 'authfinalize',
      'kyc-verification', 'linkplaid', 'investment-schedule', 'investmentconfirmation', 'complete',
    ];
    slugs.forEach(slug => {
      expect(JwtTokenUtils.NEXT_STEP_ROUTE_MAP[slug]).toBeDefined();
    });
  });
});

describe('JwtTokenUtils.clearJwtData', () => {
  afterEach(() => {
    localStorage.removeItem('userId');
    localStorage.removeItem('fred.lastScreen.v1.u-1');
  });

  it('forgets the last screen so the next sign-in starts on the home tab', () => {
    localStorage.setItem('userId', 'u-1');
    localStorage.setItem('fred.lastScreen.v1.u-1', JSON.stringify({ userId: 'u-1', url: '/tabs/tab3', savedAt: 1 }));

    JwtTokenUtils.clearJwtData();

    expect(localStorage.getItem('fred.lastScreen.v1.u-1')).toBeNull();
  });
});

/**
 * Chat stored on the device is private to one account. Signing out, or a
 * different account signing in, removes all of it (see ChatStorageUtils).
 */
describe('JwtTokenUtils and chat kept on the device', () => {
  const day = '2026-01-01';

  function seedChatFor(userId: string): void {
    localStorage.setItem('userId', userId);
    localStorage.setItem(`fred_chat_history_${userId}`, '[{"id":"x"}]');
    localStorage.setItem(`fred_used_suggestions_${day}_${userId}`, '["q"]');
    localStorage.setItem(`fred_daily_suggestions_${day}_${userId}`, '["q"]');
    localStorage.setItem('fred_chat_history', '[{"id":"old"}]');
  }

  function chatKeys(): string[] {
    return Object.keys(localStorage).filter(k =>
      k.startsWith('fred_chat_history') || k.startsWith('fred_used_suggestions_') || k.startsWith('fred_daily_suggestions_'));
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('clearJwtData removes every stored chat key', () => {
    seedChatFor('1');
    localStorage.setItem('unrelated', 'stays');

    JwtTokenUtils.clearJwtData();

    expect(chatKeys()).toEqual([]);
    expect(localStorage.getItem('unrelated')).toBe('stays');
  });

  it('clearJwtData removes stored chat even when no user id is remembered', () => {
    seedChatFor('1');
    localStorage.removeItem('userId');

    JwtTokenUtils.clearJwtData();

    expect(chatKeys()).toEqual([]);
  });

  it('storing a token for a different user removes the last user\'s chat', () => {
    seedChatFor('1');

    JwtTokenUtils.storeJwtToken(makeToken({ sub: 'b@fred.com', exp: 9999999999 }), 2, 'b@fred.com');

    expect(chatKeys()).toEqual([]);
    expect(localStorage.getItem('userId')).toBe('2');
  });

  it('removes the last user\'s chat even when the new token cannot be decoded', () => {
    seedChatFor('1');

    JwtTokenUtils.storeJwtToken('not-a-jwt', 2, 'b@fred.com');

    expect(chatKeys()).toEqual([]);
  });

  it('storing a token for the same user keeps their chat', () => {
    seedChatFor('1');

    JwtTokenUtils.storeJwtToken(makeToken({ sub: 'a@fred.com', exp: 9999999999 }), 1, 'a@fred.com');

    expect(localStorage.getItem('fred_chat_history_1')).toBe('[{"id":"x"}]');
  });

  it('storing a token with no user id (refresh, email change) keeps the chat', () => {
    seedChatFor('1');

    JwtTokenUtils.storeJwtToken(makeToken({ sub: 'a@fred.com', exp: 9999999999 }), undefined, 'a@fred.com');

    expect(localStorage.getItem('fred_chat_history_1')).toBe('[{"id":"x"}]');
  });
});
