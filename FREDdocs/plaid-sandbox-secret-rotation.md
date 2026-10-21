# Rotating the Plaid sandbox secret

Until Phase 0 of paycheck autopilot, `backend/src/main/resources/application-prod.properties`
carried the Plaid **sandbox** client id and secret as literals (commit b08d9e3, 2025-06-28, added
them). The file is tracked, so the secret is in git history for good; deleting the line does not
un-leak it. The only fix is to rotate the secret in the Plaid dashboard. This is a manual step for
Andy; nothing in the repo can do it. The same plaintext also sat four times in
`FREDdocs/plans/2026-10-19-paycheck-autopilot-phase-0/task-14-prod-plaid-block.md`, which was
committed to develop before this branch and scrubbed in f236785; both leak sites are permanent in
history, which is why rotation is the only remedy.

The leaked value is pinned as a SHA-256 digest, `BrokerEnvironmentGuard.KNOWN_SANDBOX_SECRET_SHA256`
(`backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java`), not as the
plaintext itself: the guard hashes `plaid.secret` with SHA-256 and compares digests, so neither this
source file nor the compiled jar holds a working credential for the old value. The digest was
computed once, locally, by reading the plaintext straight out of the pre-fix source file and piping
it through SHA-256 without printing or logging the plaintext anywhere; only the resulting hex digest
was pasted into the constant. That constant only recognizes the pre-rotation value: it is a tripwire
for the old secret, not proof that the new one is safe. After rotation it stays in the code
unchanged. Until the rotation is confirmed in the dashboard, the old secret is still a working
sandbox credential for anyone who reads the git history.

Sandbox secrets cannot move real money, so this is hygiene, not an incident. It still has to happen
before beta, because the same dashboard holds the production keys.

## Where the secret is used today

| Place | Key | Tracked by git |
|---|---|---|
| `application-local.properties:18` | `plaid.secret=<literal>` | No (gitignored, `.gitignore:44`) |
| `application-dev.properties:38` | `plaid.secret=${PLAID_SECRET_SANDBOX}` | Yes; the value lives on the Railway dev service |
| `application-test.properties:32` | `plaid.secret=${PLAID_SECRET_SANDBOX}` | Yes; the value lives on the Railway test service |
| `application-ci.properties:51` | `plaid.secret=ci-disabled` | Yes; no real secret |
| `application-prod.properties` | `plaid.secret=${PLAID_SECRET_PRODUCTION}` | Yes; production key, separate from this rotation |

## Steps

1. Plaid dashboard (https://dashboard.plaid.com), **Developers**, **Keys**. Find the **Sandbox**
   secret and start a rotation for it. Plaid issues a new secret and keeps the old one working
   until you confirm the rotation, so the order below never breaks a running environment. Button
   names in the Plaid dashboard change from time to time; the flow is "rotate, copy the new value,
   confirm once everything is switched".
2. Copy the new secret. It is shown once.
3. Railway, dev service, **Variables**: set `PLAID_SECRET_SANDBOX` to the new value. Redeploy.
4. Railway, test service, **Variables**: same key, same value. Redeploy.
5. Local: open `backend/src/main/resources/application-local.properties` and replace the value on
   line 18 (`plaid.secret=`). The file is gitignored; `git status` must not list it afterwards.
6. Restart the local backend. The log line `Initializing Plaid API client. Client ID set: true,
   Env: sandbox` (PlaidConfig) shows the properties loaded. Link a sandbox bank in the app
   (`user_good` / `pass_good`) to prove the new secret is accepted.
7. Back in the Plaid dashboard, confirm the rotation so the old secret stops working.
8. Check nothing new leaked: from the repo root run
   `git grep -n "plaid.secret=" -- backend/src/main/resources` and confirm every tracked hit is a
   `${...}` placeholder or `ci-disabled`.

## Never commit the new value

- No tracked file gets a Plaid secret literal, ever. The dev, test and prod profiles read
  `${PLAID_SECRET_SANDBOX}` or `${PLAID_SECRET_PRODUCTION}` from the environment.
- `application-local.properties` is the only place a literal belongs, and it is gitignored.
- Do not update `KNOWN_SANDBOX_SECRET_SHA256` to the new value's digest. It exists to catch the
  leaked one, and a rotated secret has nothing to do with it.

## Production variables (first prod deploy, separate from this rotation)

`PLAID_CLIENT_ID`, `PLAID_SECRET_PRODUCTION`, `PLAID_WEBHOOK_URL` (public https, no loopback, no
trycloudflare or ngrok), `PLAID_LINK_CUSTOMIZATION`, `PLAID_REDIRECT_URI`. The guard refuses to
start when any of the webhook rules fail or when the secret equals the leaked sandbox value.

## Not covered here

Phase 0 only moved the Plaid block to the environment; the final fix wave before Phase 0 shipped
then moved the datasource, JWT and WebAuthn values of `application-prod.properties` to environment
placeholders too, and the guard now refuses a loopback datasource and the committed dev JWT secret
in prod. Two further guard rules from spec 12.1, refusing `app.ops.alert.provider=logging` and an
unset `ADMIN_TOOLS_KEY`, arrive with the Phase 1 ops alert channel and admin surface.
