# Authentication & JWT System

## Overview

The app uses a **Passkey-First** authentication model with JWT tokens for session management.

- **Identity:** Users are identified by a unique database ID.
- **Authentication:** Primary access via **WebAuthn Passkeys** (biometrics/device PIN). Passwordless, phishing-resistant.
- **Sessions:** JWT tokens (1-hour expiry) stored in localStorage. Active users get tokens refreshed automatically — the JWT expiry effectively acts as the inactivity timeout.
- **Communication:** Email is used for notifications and account recovery, not for login.

---

## 1. Login & Authentication

### Passkey Authentication (Production)
Usernameless (discoverable) passkeys — the user does not type their email to log in.
1. User initiates login.
2. Browser prompts for biometric/device authentication (Face ID, Touch ID, PIN).
3. Frontend sends passkey assertion to `POST /api/passkey/authenticate/finish`.
4. Backend validates passkey against stored credentials, identifies user from the passkey's user handle.
5. Backend generates JWT token.
6. Frontend stores JWT and uses it for all subsequent requests.

### Dev Authentication (Development Only)
```
POST /api/dev/authenticate-as-user
Header: X-Dev-Tools-Key: <app.dev-tools.key / DEV_TOOLS_KEY>
Body: { "email": "user@example.com" }
→ Returns: { success, jwtToken, id, email }
```
Quick login for testing. The endpoint is registered only in the `local`, `dev`, `test` and `ci`
profiles, so it does not exist in production, and the app refuses to start if it ever is registered
there. Without a matching `X-Dev-Tools-Key` the response is **404 with an empty body** (not 401), so
the endpoint does not advertise itself. The same key gates simulated passkey attestation.

In the browser, pass it once as `?devKey=...` alongside `?devPage=...`; it is remembered per device.

---

## 2. JWT Token System

### Token Structure
```json
{
  "sub": "user@example.com",
  "jti": "a1b2c3d4-e5f6-7890-1234",
  "iat": 1638360000,
  "exp": 1638363600,
  "iss": "investingapp"
}
```
Signed with HS512 (HMAC-SHA512).

### localStorage Keys

| Key | Value | Purpose |
|-----|-------|---------|
| `jwtToken` | JWT string | Auth header for API requests |
| `jwtExpiration` | Unix timestamp (seconds) | Client-side expiry check |
| `userId` | Database ID | Identify logged-in user |
| `userEmail` | Email string | Display / identify user |

### Token Lifecycle

1. **Creation** — On passkey login, passkey re-auth, dev login, or token refresh.
2. **Storage** — `JwtTokenUtils.storeJwtToken()` decodes the JWT to extract `exp` and stores all 4 localStorage keys.
3. **Usage** — Every HTTP request includes `Authorization: Bearer <token>`.
4. **Refresh** — `AppLockService` checks every 60 seconds: if user was active within 5 minutes AND token expires within 30 minutes → `POST /api/auth/refresh` issues a new 1-hour token. Net effect: token never expires while the user is actively using the app.
5. **Expiry → Lock** — When token expires (user inactive), `checkTokenExpiry()` detects it and shows the lock screen. Passkey re-auth (no JWT required — endpoints are `permitAll`) issues a fresh token → app unlocks.

### Inactivity & App Lock

There is **no separate inactivity timer**. The JWT expiry IS the inactivity timeout.

```
User active → activity tracked (touch/click/keydown/scroll/HTTP requests)
           → token refreshed proactively when nearing expiry
           → token never expires

User inactive → no activity events after 5 minutes
             → token stops being refreshed
             → token expires naturally (30-60 min from last refresh)
             → lock screen appears
             → passkey re-auth → new JWT → unlock
```

**Effective timeout:** 30–60 minutes of inactivity.

**App backgrounding:**
- No code runs while backgrounded — no refresh happens.
- On resume: if **App Lock ON** → always lock. If **App Lock OFF** → lock only if JWT expired.

### Activity Tracking
Captured from two sources:
1. **DOM events** in `AppComponent`: `touchstart`, `touchmove`, `scroll`, `click`, `keydown` — throttled to 1 update per 60s.
2. **HTTP requests** via functional interceptor in `main.ts`.

Both call `AppLockService.updateLastActiveTime()`.

---

## 3. Step-Up Authentication

An additional auth step for higher-risk actions after the user is already signed in.

**Triggers:** Withdrawing funds, placing lump-sum trades, changing account settings, viewing tax documents.

On iOS, both passkey auth and step-up auth look identical (Face ID prompt), but the intent differs:
- **Passkey auth** = "Who are you?"
- **Step-up auth** = "Are you really you, right now?"

### Lockout Policy
- **5 failed attempts** allowed.
- **Initial lockout:** 30 minutes.
- **Escalation:** Each subsequent lockout doubles (30m → 1h → 2h → 4h...).
- **Reset:** Successful PIN entry resets lockout to initial 30 minutes.

---

## 4. Smart Re-Authentication (IP-Based)

When a user opens the app with no valid JWT, the system decides whether to prompt for passkey re-auth or send them to the signup flow.

### Logic
- `GET /api/user/should-prompt-reauth` checks if any user from this IP has previously completed `auth-finalize`.
- **Previous user exists** → Passkey prompt (returning user experience).
- **No previous user** → Direct to `/get-started` (new user experience).
- **Passkey fails/cancelled** → Falls back to normal signup flow.

### Scenarios
| Situation | Behavior |
|-----------|----------|
| New user, fresh device | No passkey prompt → straight to onboarding |
| Returning user, JWT expired | Passkey prompt → instant access to progress |
| New user on shared device | Passkey prompt → cancel → normal signup |
| Early-stage user returns (pre-authfinalize) | No passkey prompt → resume from progress |

---

## 5. Account Recovery

Since there are no passwords, recovery uses **Biometric Identity Verification** via Persona + email.

### Recovery Flow
1. User clicks "Lost Device" / "Reset Passkey Access" on login screen.
2. **Persona biometric scan** — liveness detection + 1:1 face match against original KYC profile.
3. **Email OTP/Magic Link** sent to the email on file (requires both biometric identity AND email access).
4. **Security purge** — all existing passkeys are immediately deleted (stolen device becomes useless).
5. **Mandatory re-enrollment** — user registers a new passkey before regaining full access.

### Security Notes
- No raw biometric data is stored (Persona handles matching).
- Recovery endpoint is rate-limited.
- Security alert email sent when recovery is initiated.

---

## 6. Endpoint Authorization

### Public (no JWT needed)
```
/api/auth/**                         — login, register, refresh
/api/passkey/**                      — passkey start/finish (used by lock screen)
/api/dev/**                          — dev-only, and additionally profile-gated + X-Dev-Tools-Key gated
/api/user/should-prompt-reauth       — smart re-auth check
```

### Authenticated (JWT required)
```
/api/user/progress
Everything else under /api/**
```

---

## 7. Code Reference

### Frontend

**JwtTokenUtils** (`/frontend/src/app/utils/jwt-token.utils.ts`)
- `storeJwtToken(token, userId, email)` — Decode JWT, store all 4 localStorage keys.
- `isJwtExpired()` — Pure check, no side effects. `true` if `Date.now() >= exp * 1000`.
- `getValidJwtToken()` — Returns token or `null` if expired. Non-destructive (doesn't clear data).
- `shouldRefreshToken()` — `true` when valid AND ≤30 min until expiry.
- `clearJwtData()` — Remove all 4 localStorage keys.

**AuthService** (`/frontend/src/app/services/auth.service.ts`)
- `refreshToken()` — `POST /api/auth/refresh`. On success: stores new JWT. On failure: returns false.
- `checkExistingSession()` — Valid token → logged in. Expired → clear data, may prompt re-auth.

**AppLockService** (`/frontend/src/app/services/app-lock.service.ts`)
- `checkTokenRefresh()` — Every 60s: refresh if active + near expiry.
- `checkTokenExpiry()` — Every 60s: lock if expired + was logged in.
- `checkLockOnResume()` — App Lock ON → always lock; OFF → lock if expired.
- `updateLastActiveTime()` — Called by DOM events and HTTP interceptor.

### Backend

**AuthTokenFilter** — Intercepts every request, extracts/validates JWT from `Authorization` header, sets `SecurityContext`.

**POST /api/auth/refresh** — Requires valid JWT. Issues fresh 1-hour token for same user.

**POST /api/passkey/authenticate/start** — Start usernameless authentication (no email needed).

**POST /api/passkey/authenticate/finish** — Complete authentication. Backend identifies user from passkey's user handle, issues JWT.

---

*Last Updated: February 27, 2026*
