# Database migrations (Flyway)

Until 2026-09-19 the schema was created by Hibernate: `ddl-auto=update` added tables and columns at
boot in every environment. That is now off. The schema is owned by versioned SQL under
`backend/src/main/resources/db/migration`, and Hibernate only checks that the entities match what
the migrations built (`ddl-auto=validate` in every profile).

## Why this changed now

No production database exists yet, so this was the cheapest moment to switch. `ddl-auto=update`
never drops, renames or retypes anything, and it cannot create views at all, so the metrics layer
had no way to ship. It also silently accepts drift: a renamed field just leaves the old column
behind.

## What is in place

| Piece | Where |
|---|---|
| `V1__baseline.sql` | The schema exactly as Hibernate generated it from the entities at `1c9c44c` (24 tables, 11 foreign keys) |
| `V2__user_public_id.sql` | Adds `users.public_id` (UUID v4), backfills existing rows, adds the unique key |
| `V3__catch_up_pre_flyway_schemas.sql` | Creates what a pre-Flyway database is missing: the two TLH tables and `users.tlh_enabled` / `tlh_enrolled_at`. Guarded, so it is a no-op where V1 ran |
| `spring.flyway.baseline-on-migrate=true`, `baseline-version=1` | In `application.properties`, so it applies to every profile |

## How an existing database is handled

Nothing manual is needed. On the first boot after this change:

- **An empty database** (CI, a fresh laptop) runs `V1`, `V2` and `V3` in order.
- **A database that already has tables** (your local `investing_dev`, Railway dev) is *stamped* at
  version 1 instead of running `V1` against it, then `V2` and `V3` run. That is what
  `baseline-on-migrate` does, and it is the in-place baseline.

### Why V3 exists

Stamping instead of running V1 has a catch: anything V1 contains that the target database lacks is
never created, and `validate` then refuses to start the app. That is the real situation on Railway
dev, which runs `develop @ 7c0d5e8` (2026-07-15) and whose schema was built by `ddl-auto` from those
entities, so it predates the TLH work.

The gap was measured rather than guessed: the 7c0d5e8 entities were booted against a scratch
database and the resulting schema compared column by column with what these migrations build. The
difference was exactly two tables (`tlh_cycle_runs`, `tlh_user_states`) and two columns
(`users.tlh_enabled`, `users.tlh_enrolled_at`). V3 adds those, guarded by `information_schema`
checks so it does nothing on a database that already ran V1.

### Verified before this shipped

1. **Fresh empty schema:** V1 + V2 + V3 applied, `validate` passed, app started.
2. **A copy of the current local dev schema**, with rows in `users`: baselined at 1, later migrations
   applied, every existing row got a distinct `public_id`, `validate` passed, app started.
3. **A rebuild of what Railway dev actually runs** (7c0d5e8 entities, 22 tables, with user rows):
   baselined at 1, V2 and V3 applied, `validate` passed, app started, and the backfill produced
   distinct v4 ids. This is the closest available rehearsal of the next api-dev deploy.

Every scratch database was dropped afterwards; `investing_dev` itself was never modified.

### Two follow-ups this leaves open

- **`public_id` is still NULLable in the database.** The entity treats it as required and every
  insert path sets it, but making the column `NOT NULL` in the same release that teaches the app to
  populate it would break inserts from the old container during a rolling deploy. A later migration
  should tighten it once this code is live everywhere.
- **A failed migration needs a manual repair.** MySQL has no transactional DDL, so if a migration
  fails halfway, Flyway records it as failed and every later boot refuses to start until
  `flyway repair` runs (or the failed row is deleted from `flyway_schema_history`). This is not
  hypothetical: V3 failed once during development on a quoting bug and had to be repaired.

## Rules from here on

- **New table or column:** a new `V<n>__short_name.sql`. Never change an entity and rely on boot.
- **SQL views:** repeatable migrations, `R__v_<view_name>.sql`, holding `CREATE OR REPLACE VIEW`.
  Flyway re-runs a repeatable migration whenever its checksum changes, which suits views exactly.
- **Never edit a migration that has run anywhere.** Flyway stores a checksum and will refuse to
  start. Write a new version instead. (`V2` was edited during development, but only against scratch
  databases that were dropped; nothing had it applied.)
- **Entities follow migrations, not the other way around.** If the two disagree, the app will not
  start. That is deliberate.

## What a failure looks like

If an entity and the schema drift apart, startup fails with a clear message:

```
Schema-validation: wrong column type encountered in column [public_id] in table [users];
found [char (Types#CHAR)], but expecting [varchar(36) (Types#VARCHAR)]
```

This happened for real while building `V2` (the migration said `CHAR(36)`, the entity mapped to
`VARCHAR(36)`) and the app refused to boot until the migration was corrected. Under the old
`ddl-auto=update` the mismatch would simply have persisted.

To fix one: write a new migration that changes the column to match the entity. Do not edit history.

## Before the next deploy to Railway dev

`api-dev` currently runs `develop @ 7c0d5e8`. When this branch reaches it, the first boot will
baseline the dev database and apply `V2`. Two things are worth knowing:

1. **If the dev database has drifted from the entities**, the app will now refuse to start where it
   previously booted. Nothing in the repo can prove the Railway dev schema matches, because it is
   not reachable from here. The local dev schema did match. If the deploy fails on a
   `Schema-validation` error, the message names the exact table and column, and the fix is a
   migration.
2. **Flyway prints a version warning against MySQL 9.x** ("support has not been tested"). That is
   what the local machine runs; migrations applied cleanly anyway. Railway's MySQL is likely 8.x,
   which is fully supported.
