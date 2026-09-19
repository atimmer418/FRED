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
| `V2__user_public_id.sql` | Adds `users.public_id` (UUID), backfills existing rows, then makes it `NOT NULL` + unique |
| `spring.flyway.baseline-on-migrate=true`, `baseline-version=1` | In `application.properties`, so it applies to every profile |

## How an existing database is handled

Nothing manual is needed. On the first boot after this change:

- **An empty database** (CI, a fresh laptop) runs `V1` and then `V2`.
- **A database that already has tables** (your local `investing_dev`, Railway dev) is *stamped* at
  version 1 instead of running `V1` against it, then `V2` runs. That is what `baseline-on-migrate`
  does, and it is the in-place baseline.

Both paths were verified before this shipped:

1. Fresh empty schema: `V1` + `V2` applied, `validate` passed, app started.
2. A copy of the existing local dev schema, with rows in `users`: baselined at 1, `V2` applied, every
   existing row got a distinct `public_id`, `validate` passed, app started.

The copy was thrown away afterwards; `investing_dev` itself was never modified.

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
