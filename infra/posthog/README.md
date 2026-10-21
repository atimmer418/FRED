# PostHog dashboards, from code

Four dashboards defined as JSON in `dashboards/` and applied by `sync-dashboards.mjs`.

Dashboards built by hand drift, and six months later nobody can say what a number means or when it
changed. These live in version control, so the review history of a metric is the git history of
its file.

## Running it

```bash
export POSTHOG_PERSONAL_API_KEY=phx_...
node infra/posthog/sync-dashboards.mjs --dry-run    # show what would change, touch nothing
node infra/posthog/sync-dashboards.mjs              # apply
```

Options: `--host=https://eu.posthog.com` (default `https://us.posthog.com`), `--project=12345`
(or `POSTHOG_PROJECT_ID`; only needed when the key can see more than one project).

It is **idempotent**. Run it as often as you like:

- a dashboard that does not exist is created
- one that does is updated **in place**, keeping its id, so shared links keep working
- a tile already on the dashboard is updated, not duplicated
- a tile no longer in the JSON is **removed** from the dashboard, so deleting a tile in version
  control actually deletes it

Renaming a dashboard or tile in the JSON creates a new one and leaves the old behind. That is
deliberate: silently renaming something people have bookmarked is worse than a stale copy a human
can delete.

## The API key, and exactly which scopes

Create it in PostHog under **Settings → Personal API keys**. Scope it to the FRED project rather
than the whole organisation.

| Scope | Why |
|---|---|
| `dashboard:read` | find a dashboard that already exists, so a re-run updates instead of duplicating |
| `dashboard:write` | create a dashboard and change its description |
| `insight:read` | find the tiles already on a dashboard |
| `insight:write` | create and update tiles, and detach ones removed from the JSON |

**That is the whole list.** The script never reads event data, never reads person records, and
never writes a feature flag, so it needs no `query:read`, `person:read` or `feature_flag:*`.

One optional extra: `project:read`, needed **only** if you do not pass `--project=` or set
`POSTHOG_PROJECT_ID`, because the script then has to list projects to find the id. Passing the
project id explicitly is the smaller permission and is what I would do.

A `403` from the script is nearly always a missing scope rather than a wrong key; it says so when
that happens.

## The dashboards

| File | What it answers |
|---|---|
| `onboarding-funnel.json` | signup through first settled transfer, from **server** events, plus KYC outcomes and attempts |
| `activation.json` | first open through funded, mixing device and server events, plus a time-to-convert distribution and where onboarding stops |
| `contribution-retention.json` | monthly contribution retention, transfer volume, and user-initiated schedule churn |
| `data-health.json` | events per day by name, split into server and client, plus consent changes |

## Two things PostHog structurally cannot tell you

Worth knowing before trusting the Data health dashboard, because the gaps are not bugs:

**The opt-out share.** A user who opts out sends *nothing*. PostHog therefore cannot see them at
all, and the opt-out event itself is the last thing it ever receives from them. The dashboard
shows consent *changes*, which is the nearest it can get. The real share is `v_consent` in MySQL,
which can see everybody.

**Suppressed and parked outbox rows.** By definition these were never sent, so PostHog has no
record of them. `v_outbox_reconciliation` in MySQL has the sent / suppressed / parked / pending
split, and is what the weekly reconciliation compares *against* PostHog.

This is the same reason the metrics layer exists at all: PostHog shows behaviour, MySQL shows
money and knows about everyone. See `FREDdocs/metrics.md`.

**Retention is approximate here too.** PostHog's retention grid counts every cohort, including
ones too young to have had a month-6 window at all, which drags recent rows down and looks like
churn. `v_contribution_retention` gives every month its own denominator. Use the dashboard to spot
a change; use the view before acting on one.

## Changing a dashboard

Edit the JSON, run with `--dry-run`, then apply. Each tile is a PostHog query node
(`InsightVizNode` wrapping a `TrendsQuery`, `FunnelsQuery` or `RetentionQuery`); the quickest way
to author a new one is to build it in the PostHog UI, open the insight's **"Edit source"** / JSON
view, and paste the `query` object in.

Keep the `description` on every tile. It is the only place a reader finds out what a number
excludes, and these numbers all exclude something.
