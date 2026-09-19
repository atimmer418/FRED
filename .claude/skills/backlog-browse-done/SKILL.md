---
name: backlog-browse-done
description: Use when you want to see the COMPLETED and OBE'd FRED backlog stories — the checkmarked (`✓` Done) items that backlog-browse hides. Shows only story ID and title, one per line, grouped by prefix.
---

# FRED Backlog — Browse (done)

## Overview

Reads `FREDdocs/backlog.md` and prints only the DONE stories — the items marked `✓` (shipped or OBE'd). This is the counterpart to `backlog-browse`, which shows only the active (ready / sleeping / blocked) items. No descriptions, no context — just the index, grouped by prefix.

## Process

1. Run: `grep -E "^## [A-Z]+-[0-9]+" FREDdocs/backlog.md`
2. Keep ONLY lines whose title begins with `✓` right after the em-dash (`## PREFIX-NNN — ✓ Title`). Drop every other line (Ready, `💤` Sleeping, `🚫` Blocker) — those belong to `backlog-browse`.
3. For each kept line, reformat `## PREFIX-NNN — ✓ Title` → `PREFIX-NNN: ✓ Title` (keep the `✓`).
4. Group by prefix in this order: FRED, LPFRED, LEGAL, BUSINESS, DESIGN, DEV, QA. Omit any prefix with no done items.
5. Within each prefix, preserve backlog file order (all items are Done, so there is no status sub-grouping).
6. Print each prefix as a bare header line (e.g. `FRED`) followed by its items. Separate prefix groups with a blank line.
7. Print a final count line: `Total: N done items`.

## Output Format

```
FRED
FRED-207: ✓ Refactor Monte Carlo simulator into fullscreen premium flow
FRED-99: ✓ Implement all features from tiered pricing
FRED-189: ✓ Fix bank account subtype always showing Checking

LPFRED
LPFRED-153: ✓ Add emailer to FRED landing page
LPFRED-184: ✓ Update LP calculator to net-income yield model

DEV
DEV-190: ✓ Resolve @capacitor peer conflict (drop --legacy-peer-deps)
DEV-198: ✓ Eliminate package-lock.json libc-field churn in diffs

Total: N done items
```

## Rules

- Do not read or summarize item bodies — title line only.
- Do not add commentary, recommendations, or triage scoring.
- **Only list `✓` (Done/OBE) items here.** Never include Ready, `💤` Sleeping, or `🚫` Blocker items — those belong to `backlog-browse`.
- This includes both shipped and OBE'd stories (both carry `✓`); do not try to distinguish them — title line only.
- Keep the `✓` on each line; do not add status text labels.
- Do not ask clarifying questions. Just print the index and stop.
