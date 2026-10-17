// JWT token utilities for managing authentication tokens
export class JwtTokenUtils {
  
  /**
   * Decode JWT payload to extract expiration and other claims
   * Note: This only decodes, does not verify signature (that's done on backend)
   */
  static decodeJwtPayload(token: string): any {
    try {
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const jsonPayload = decodeURIComponent(atob(base64).split('').map(function(c) {
        return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
      }).join(''));
      
      return JSON.parse(jsonPayload);
    } catch (error) {
      console.error('Error decoding JWT payload:', error);
      return null;
    }
  }

  /**
   * Store JWT with expiration info in localStorage
   */
  static storeJwtToken(token: string, userId?: number, email?: string): void {
    const payload = this.decodeJwtPayload(token);
    
    if (payload) {
      localStorage.setItem('jwtToken', token);
      localStorage.setItem('jwtExpiration', payload.exp.toString()); // exp is Unix timestamp
      
      if (userId) localStorage.setItem('userId', userId.toString());
      if (email) localStorage.setItem('userEmail', email);
    } else {
      console.error('Failed to decode JWT, storing token only');
      localStorage.setItem('jwtToken', token);
    }
  }

  /**
   * Check if stored JWT is expired (client-side check).
   * This is a pure check — does NOT clear any data.
   */
  static isJwtExpired(): boolean {
    const token = localStorage.getItem('jwtToken');
    const expiration = localStorage.getItem('jwtExpiration');
    
    if (!token || !expiration) {
      return true; // No token or expiration info = expired
    }

    const expirationTime = parseInt(expiration) * 1000; // Convert to milliseconds
    const currentTime = Date.now();
    
    return currentTime >= expirationTime;
  }

  /**
   * Check if the token should be refreshed (within 30 minutes of expiry).
   * Returns true if the token is valid but will expire soon.
   * Using half the token lifetime (30 of 60 min) ensures any user
   * active past the halfway point gets a refresh, giving a consistent
   * minimum inactivity timeout of ~30 minutes.
   */
  static shouldRefreshToken(): boolean {
    if (this.isJwtExpired()) {
      return false; // Already expired, can't refresh
    }

    const minutesLeft = this.getMinutesUntilExpiration();
    return minutesLeft !== null && minutesLeft <= 30;
  }

  /**
   * Get time until token expires (in minutes)
   */
  static getMinutesUntilExpiration(): number | null {
    const expiration = localStorage.getItem('jwtExpiration');
    
    if (!expiration) {
      return null;
    }

    const expirationTime = parseInt(expiration) * 1000;
    const currentTime = Date.now();
    const minutesLeft = Math.floor((expirationTime - currentTime) / (1000 * 60));
    
    return minutesLeft > 0 ? minutesLeft : 0;
  }

  /**
   * Clear all JWT-related data from localStorage, including the step-up PIN
   * marker so a logged-out or switched user is never falsely gated.
   */
  static clearJwtData(): void {
    // Derive and clear per-user scoped keys BEFORE removing userId,
    // since their keys are scoped to the current userId (FRED-199 ordering).
    const userId = localStorage.getItem('userId');
    if (userId) {
      localStorage.removeItem(`stepUpEnabled:${userId}`);
      // FRED-200: clear the stat strip snapshot so a logged-out or switched user
      // never sees another user's freedom figures.
      localStorage.removeItem(`fred.statStrip.v1.${userId}`);
      // FRED-206: clear the portfolio snapshot for the same reason.
      localStorage.removeItem(`fred.portfolioDash.v1.${userId}`);
    }
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('jwtExpiration');
    localStorage.removeItem('userId');
    localStorage.removeItem('userEmail');
  }

  /**
   * Get stored JWT token if it's not expired.
   * Non-destructive: does NOT clear data if expired. Use clearJwtData() explicitly when needed.
   */
  static getValidJwtToken(): string | null {
    if (this.isJwtExpired()) {
      return null;
    }

    return localStorage.getItem('jwtToken');
  }

  /**
   * Full slug-to-route map for all onboarding next-step values.
   * v1 acts only on "complete" → /tabs/tab1; the full map is defined here for v2.
   */
  static readonly NEXT_STEP_ROUTE_MAP: Record<string, string> = {
    'get-started':           '/get-started',
    'surveyinitial':         '/survey-initial',
    'fi-plan-results':       '/fi-plan-results',
    'authfinalize':          '/auth-finalize',
    'kyc-verification':      '/kyc-verification',
    'linkplaid':             '/link-bank',
    'investment-schedule':   '/investment-schedule',
    'investmentconfirmation': '/investment-confirmation',
    'complete':              '/tabs/tab1',
  };

  /**
   * Read the "ns" (next-step) hint from the currently stored valid JWT.
   * Returns null if no valid token is stored or the claim is absent.
   * Reads only from the JWT — no separate localStorage key.
   */
  /**
   * Read the "pid" (public_id) claim from the currently stored valid JWT: the opaque analytics
   * identity. Returns null on an older token issued before the claim existed, in which case the
   * session payload carries it instead.
   */
  static getPublicId(): string | null {
    const t = this.getValidJwtToken();
    return t ? (this.decodeJwtPayload(t)?.pid ?? null) : null;
  }

  static getNextStepHint(): string | null {
    const t = this.getValidJwtToken();
    return t ? (this.decodeJwtPayload(t)?.ns ?? null) : null;
  }
}
