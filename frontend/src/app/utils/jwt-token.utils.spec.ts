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
