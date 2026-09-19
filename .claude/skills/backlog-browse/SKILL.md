---
name: backlog-browse
description: Use when you want a quick overview of the ACTIVE FRED backlog — ready, sleeping, and blocked stories. Excludes done/OBE (checkmarked) items; those live in backlog-browse-done. Shows only story ID and title, grouped by prefix then status.
---

# FRED Backlog — Browse (active)

## Overview

Reads `FREDdocs/backlog.md` and prints every ACTIVE story ID + title, one per line. No descriptions, no context — just the index, grouped by prefix and then by status. Done/OBE items (marked `✓`) are excluded here; use `backlog-browse-done` to see those.

## Process

1. Run: `grep -E "^## [A-Z]+-[0-9]+" FREDdocs/backlog.md`
2. For each matching line, reformat `## PREFIX-NNN — Title` → `PREFIX-NNN: Title`. Keep any status emoji (`💤` / `🚫`) that sits right after the em-dash — it stays in front of the title.
3. Determine each item's **status** from the character right after `— `:
   - no emoji (title starts with a normal character) → **Ready**
   - `💤` → **Sleeping**
   - `🚫` → **Blocker**
   - `✓` → **Done** → **EXCLUDE from this skill's output** (shown by `backlog-browse-done`)
4. **Drop every Done (`✓`) item.** Only Ready, Sleeping, and Blocker items are listed.
5. Group by prefix in this order: FRED, LPFRED, LEGAL, BUSINESS, DESIGN, DEV, QA. Omit any prefix with no active items.
6. Within each prefix, order the items by status: **Ready → Sleeping → Blocker**. Preserve backlog file order within each status. Put a blank line between status sub-groups; omit a status sub-group entirely if it has no items.
7. Print each prefix as a bare header line (e.g. `FRED`) followed by its status-ordered items. Separate prefix groups with a blank line.
8. Print a final count line: `Total: N active items (M done — see backlog-browse-done)`, where N is the number of items listed above and M is the count of `✓` items excluded.

## Output Format

```
FRED
FRED-187: Investigate Plaid paycheck-triggered investment flow
FRED-191: Measure and optimize app loading performance

FRED-100: 💤 RAG chunks for app knowledge and philosophy
FRED-101: 💤 Set up emailer for all transactional notifications

FRED-173: 🚫 Lock referral entry until 30 days post-trial

LPFRED
LPFRED-159: 💤 Adjust landing page comparison table for accuracy

Total: N active items (M done — see backlog-browse-done)
```

## Rules

- Do not read or summarize item bodies — title line only.
- Do not add commentary, recommendations, or triage scoring.
- **Never list a `✓` (Done/OBE) item here** — that is the whole point of the split; those belong to `backlog-browse-done`.
- Keep the status emoji on each line; do not add status text labels.
- Order within a prefix is always Ready → Sleeping (`💤`) → Blocker (`🚫`); skip any status with no items.
- Do not ask clarifying questions. Just print the index and stop.
