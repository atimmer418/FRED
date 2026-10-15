# Deploying the PostHog proxy (m.fredvested.com)

The app does not talk to PostHog directly. It talks to `m.fredvested.com`, a Cloudflare Worker on
our own zone that forwards to PostHog. Two reasons: tracker-block lists match on vendor hostnames,
so a direct connection silently loses a slice of users and leaves a funnel that looks complete but
is not; and a first-party host keeps storage first-party.

The hostname has no analytics-sounding words in it on purpose. Do not rename it to anything
containing `posthog`, `analytics`, `track`, `telemetry`, `metrics` or `events`, because those
strings are themselves on block lists.

Code lives in `infra/posthog-proxy/`. Nothing in the app sends anything until a project key is set,
so the order below is safe to follow at your own pace.

## What you need first

- The Cloudflare account that holds the `fredvested.com` zone (the same one serving the landing
  page and `today.fredvested.com`).
- A PostHog account. Free plan is fine: it allows one project, which is what the analytics layer is
  configured for.

## Step 1: create the PostHog project and copy the key

1. Go to https://posthog.com and sign in (or sign up).
2. If asked to pick a region, note which one you choose. **If you pick EU**, tell me, because the
   Worker has to point at the EU hosts and ships pointing at US.
3. Create a project named **FRED Dev**.
4. Open **Settings** (bottom left) then **Project** then **Project ID / API key**.
5. Copy the **Project API key**. It starts with `phc_`. This key is designed to live in a client
   app, so it is not a secret in the way a server key is, but it still does not get pasted into
   source files: it goes in the Angular environment config in step 4.

## Step 2: add the DNS record

1. Cloudflare dashboard, pick the `fredvested.com` zone.
2. **DNS** then **Records** then **Add record**.
3. Type `AAAA`, Name `m`, IPv6 address `100::`, Proxy status **Proxied** (orange cloud).
   - `100::` is the documented discard address. The record exists only so Cloudflare has something
     to attach the Worker route to; no traffic ever reaches it.
4. Save.

## Step 3: deploy the Worker

From the repo, with the Cloudflare CLI:

```bash
cd infra/posthog-proxy
npx wrangler login          # opens a browser, authorizes the CLI
npx wrangler deploy         # reads wrangler.toml, creates the Worker and the route
```

If you would rather click than type:

1. Cloudflare dashboard, **Workers & Pages** then **Create application** then **Create Worker**.
2. Name it `fred-metrics-proxy`, deploy the placeholder.
3. **Edit code**, paste the contents of `infra/posthog-proxy/worker.js`, **Deploy**.
4. On the Worker, **Settings** then **Domains & Routes** then **Add route**: route
   `m.fredvested.com/*`, zone `fredvested.com`.

## Step 4: point the app at it

`frontend/src/environments/environment.ts` already has:

```ts
posthogKey: '',
posthogApiHost: 'https://m.fredvested.com'
```

Paste the `phc_...` key from step 1 into `posthogKey` in `environment.ts` (the local build) and in
`environment.dev.ts`. Leave `environment.ci.ts` empty, so CI renders never reach the project, and
leave `environment.prod.ts` empty until a production project exists.

## Step 5: check it works

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://m.fredvested.com/static/array.js
```

`200` means the proxy and the asset path are good. A `404` with a Cloudflare error page means the
route is not attached; a `530`/`1016` means the DNS record is missing.

Then, in the app, with a key configured and analytics consent on (Checkpoint C wires the real
consent toggle; until then the service is opted out by default and sends nothing):

1. Open the app, watch the browser network tab, and look for requests to `m.fredvested.com`.
2. In PostHog, **Activity** should show one `app_opened` event with `platform` and
   `app_environment`. That event carries nothing about any user.

If you see the request in the network tab but nothing in PostHog, the key is wrong. If you see
nothing in the network tab, either the key is empty or analytics is still opted out, which is the
expected state at the end of Checkpoint B.
