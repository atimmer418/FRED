# Acceptance Check Manifest — MFU dev mock (manual trigger that reflects the portfolio mock tier)

Story (no backlog ID, Andy-directed 2026-09-01): add a DEV-only switch that forces the Monthly
Freedom Update popup on a local build, modeled on `services/dev/portfolio-mock.ts`. The mocked
update must REFLECT whatever portfolio tier `portfolio-mock.ts` has active: every equity-driven
field is derived from that tier's mocked dashboard/history using the backend's MFU rules.

Files in scope:
- NEW  frontend/src/app/services/dev/mfu-mock.ts (+ mfu-mock.spec.ts)
- NEW  frontend/src/app/services/monthly-freedom-update.service.spec.ts
- MOD  frontend/src/app/services/monthly-freedom-update.service.ts
- MOD  frontend/src/app/services/mfu-store.service.ts (+ mfu-store.service.spec.ts)

Karma command (the shared checkout currently has UNRELATED compile errors in
first-time-tour.component.ts / ai-chat.page.ts from another session's in-progress work, and karma
type-checks every spec via tsconfig.spec.json, so use the scratch tsconfig that includes only the
three specs above):

    cd frontend && npx ng test \
      --ts-config=/private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/tsconfig.spec.mfu.json \
      --include='**/mfu-mock.spec.ts' --include='**/monthly-freedom-update.service.spec.ts' --include='**/mfu-store.service.spec.ts' \
      --watch=false --browsers=ChromeHeadless

NOTE: karma exits 0 even on "Found 1 load error" (a compile failure). Read the log; do not trust the exit code.

VERIFIED 2026-09-01 by verifier-agent. Karma: 102/102 SUCCESS, no load error, no `error TS`
(log: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log).
AOT `ng build --configuration dev`: fails only on the pre-existing first-time-tour / ai-chat errors;
no in-scope file appears in any error
(log: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/build.clean.log).

## AC-1: With a portfolio tier active, the mocked update's equity fields derive from that tier's mocked dashboard/history
- Type:     frontend-unit
- Check:    mfu-mock.spec.ts "derived payload reflects the mocked portfolio" passes for SMALL/MEDIUM/LARGE/XLARGE: currentEquityValue == tier summary.equity; start/end equity == history value at last month's bounds (3M window, prod findEquityAtDate rule, end falls back to current); pig level from end equity equals pigSrcFromEquity(endEquity); change/return/contributions recomputed from the fixtures.
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log — "TOTAL: 102 SUCCESS"; the 14-case `derived payload reflects the mocked portfolio` block ran green for all 4 funded tiers (current/start/end equity, pig level, change, return, contributions).
- Status:   pass

## AC-2: Milestone card follows prod's rule by default; forced modes never alter equity
- Type:     frontend-unit
- Check:    mfu-mock.spec.ts: DERIVED gives SMALL "1st Investment! $1k Total Equity Reached!" (2 milestones) and DEFAULT for MEDIUM/LARGE/XLARGE; EQUITY forces $1k/$10k/$250k/$1M per tier with start/end/current/equityLevel identical to DERIVED; COMBO = "1st and 10th Investment! $1M Total Equity Reached!" / "You reached 3 major milestones."; NONE = DEFAULT; overlayMilestone on a real payload rewrites only `milestones`.
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log — `derived milestones apply the prod rule` (SMALL 1st+$1k / MEDIUM+LARGE+XLARGE DEFAULT), `milestone modes only swap the card, never the equity` (4 tiers), COMBO, NONE, and the 3 `overlayMilestone` cases all green.
- Status:   pass

## AC-3: Quarterly toggle populates the every-3rd-month section from the same curve
- Type:     frontend-unit
- Check:    mfu-mock.spec.ts "quarterly toggle": showQuarterlyCompare true, mfuCount 2, equity12MonthsAgo from the 1Y window, netWorthChange = current - that, yearlyContributions from the mocked transactions, freedomYearsChange via monthsToTarget; a <1-year tier reports $0 twelve months ago.
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log — `quarterly toggle` both cases green (XLARGE full quarterly derivation; MEDIUM reports $0 twelve months ago).
- Status:   pass

## AC-4: With the mock on, the service never leaves the device except for the live-payload case, and projects with the app's live inputs
- Type:     frontend-unit
- Check:    monthly-freedom-update.service.spec.ts: tier active -> /check answered locally, /generate derived (no HTTP), /dismiss never POSTed; no tier -> real /generate fetched and only the milestone card overlaid; inputs = saved goal else schedule, income/withdrawalRate else $1.5M, KYC age else 35 (parity with mockMfu called with the same inputs). ZERO tier -> shouldShow false (mfu-mock.spec "mockMfuCheck").
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log — monthly-freedom-update.service.spec `mock on, portfolio tier active` (5 cases) + `mock on, no portfolio tier` (2 cases) green; mfu-mock.spec `mockMfuCheck` ZERO/funded/null green.
- Status:   pass

## AC-5: With the mock off (every karma run and every deployed build) behavior is unchanged
- Type:     frontend-unit
- Check:    monthly-freedom-update.service.spec.ts "mock off": /check GET, /generate GET with reopen param, /dismiss POST with {seenMonth}; isMockActive() false; mfu-mock.spec "dev toggle": ACTIVE_MFU_MOCK is null under karma.
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log — `mock off (the karma default)` 4 cases green (GET /check, GET /generate?reopen=true, POST /dismiss {seenMonth}, isMockActive false) and `dev toggle` asserts ACTIVE_MFU_MOCK is null; http.verify() in afterEach found no stray requests.
- Status:   pass

## AC-6: The reopen snapshot is never written or hydrated while the mock is active; existing store behavior intact
- Type:     frontend-unit
- Check:    mfu-store.service.spec.ts: the two "DEV mock active" cases pass AND all 12 pre-existing cases still pass.
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/karma.clean.log — mfu-store.service.spec ran 13 cases green = 11 pre-existing (count at HEAD: `git show HEAD:...mfu-store.service.spec.ts | grep -c '^\s*it('` = 11, NOT the 12 this manifest assumed) + the 2 new `DEV mock active` cases.
- Status:   pass

## AC-7: Cannot leak to deployed builds
- Type:     frontend-unit (static review)
- Check:    monthly-freedom-update.service.ts gates BOTH mockConfig() and mockTier() on `environment.local` (same as PortfolioService.activeTier()); dev/mfu-mock.ts has `FORCE_MFU: boolean = false` and forces ACTIVE_MFU_MOCK null under karma; environment.dev.ts / environment.prod.ts have local:false.
- Evidence: monthly-freedom-update.service.ts:103 `return environment.local ? ACTIVE_MFU_MOCK : null;` and :108 `return environment.local ? ACTIVE_MOCK_TIER : null;` — identical shape to portfolio.service.ts:89 activeTier(). dev/mfu-mock.ts:43 `const FORCE_MFU: boolean = false;`; :61-62 `ACTIVE_MFU_MOCK = IN_KARMA || !FORCE_MFU ? null : {...}`. environment.dev.ts:3 / environment.test.ts:3 / environment.prod.ts:3 all `local: false`. NOTE: environment.ci.ts:6 is `local: true` (pre-existing; harmless here only because FORCE_MFU ships false).
- Status:   pass

## AC-8: Changed/new files compile under AOT
- Type:     frontend-unit (build)
- Check:    `cd frontend && npx ng build --configuration dev` reports NO errors whose path is one of the in-scope files. Errors in src/app/components/first-time-tour/* or src/app/pages/ai-chat/* are pre-existing, out of scope, and must be reported separately, not as failures of this story.
- Evidence: /private/tmp/claude-501/-Users-andrewtimmer-PersonalTypeshit-FRED/d2b3116e-67b5-421d-8500-d2d9193c6b1b/scratchpad/verifier/build.clean.log — `npx ng build --configuration dev`: 7 `[ERROR]` lines total, all in src/app/components/first-time-tour/first-time-tour.component.ts (5) and src/app/pages/ai-chat/ai-chat.page.ts (2), i.e. the pre-existing out-of-scope FirstTimeTour rewrite. ZERO errors in any in-scope file. All other file refs in the log are pre-existing TS-998113 unused-Ionic-import WARNINGS.
- Status:   pass

## AC-9: House style and hygiene
- Type:     frontend-unit (static review)
- Check:    no em dashes in any user-facing string added (labels/subtitles mirror the backend strings exactly); no TODOs, no dead code, no unused imports in the in-scope files; doc comments explain the dev-only gating.
- Evidence: grep for U+2014 across the 5 in-scope files returns no hit outside comments (no hits at all in string literals). grep -niE 'todo|fixme|xxx|hack' over the in-scope files: none. Only console call is dev/mfu-mock.ts:272 console.info (a dev hint, no em dash). Milestone label/subtitle literals match MonthlyFreedomUpdateService.java calculateMilestones byte-for-byte. All imports in dev/mfu-mock.ts and the service diff are used.
- Status:   pass
