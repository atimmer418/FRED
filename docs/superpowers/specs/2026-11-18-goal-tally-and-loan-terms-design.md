# Goals: FRED keeps the tally, plus real down payments for house and car

Date: 2026-11-18. Author: the assistant, from Andy's brainstorm the same day. Builds on
`2026-11-02-goals-v3-plans-design.md` (status and anchors), `2026-11-06-goals-quiz-and-plan-page-redesign.md`
(quiz, plan page) and `2026-11-10-family-everyday-plan-design.md` (unit sheet). Where this document
and those disagree, this one wins.

## Why

Today a goal's Saved so far is a number the user types. Nobody types it. The plan page then asks
for a balance it never gets, grades nothing, and after 60 days nags with "Update balance". Andy's
framing: FRED proposes the plan and the user commits to it, so FRED should assume the money went in,
move Saved so far on its own on every payday, and give the user one tap to say "I missed one" or
"I doubled up". FRED keeps score without ever seeing the account.

Two smaller fixes ride along in the quiz: nobody pays a house or car in full, so the "Full price"
option goes; the house gets a 5% floor, the car gets a dollar down payment, and both say plainly
that the plan saves the down payment only, with the loan payment that follows shown as its own
line at a rate the user can slide.

## Goals

- Saved so far moves by itself: one paycheck's share lands on each payday from the user's FRED
  investment schedule, for every saving unit of every goal type.
- Two taps correct it: "Missed one" and "Doubled up", each worth one share, each dated, each
  undoable with the opposite tap.
- Status reads off the taps, never off a typed balance: On plan by default, "Ahead by $450" or
  "Behind by $450" after taps.
- Typing an exact figure still works and re-bases the tally from today; it becomes the rare path.
- House: down payment 5% / 10% / 20%, the mortgage shown as its own monthly line at a rate the
  user sets. Car: dollar down payment on a slider, the loan payment shown the same way.
- Every figure comes from arithmetic in code. Default loan rates are published figures quoted with
  their source and as-of date, never typed from memory.

## Non-goals

- Reading any account (Plaid, Alpaca, screenshots). The tally is contributions FRED assumed plus
  the user's corrections, nothing else.
- Market value. The tally counts money put in. "What it grows to" keeps running off the plan, and
  the one grown figure the page shows is labelled as assumed (see "The assumed-growth line").
- Changing the Freedom Plan, its return assumption, or the rule that the return assumption is set
  only on the Freedom Plan page. A mortgage or car loan rate is a cost of borrowing, a different
  number, and lives with the goal.
- Gift days, the kids everyday line (it is spending, not saving, so it has no tally), the quiz
  flow for other goal types, my-profile row layout.
- Notifications on payday. A later project.

## Anti-goals

- A "Behind" that appears because the user did nothing. Silence means the plan went in.
- A tally that moves by a different amount than the pills and the "Next $450" fragment show. (The
  hero's live figure may differ after taps: that is the catch-up, and the page says so.)
- Credits that re-write history when the user later changes the monthly amount or pay frequency.
- A frontend that computes paydays on its own clock and disagrees with the backend by a day.
- "Full price" or a 0% down payment with nothing to save for.

## House rules that apply

- Plain language, no em dashes, "~$1,540" never "about", titles on one line.
- No AI-written numbers: every figure on screen is arithmetic over stored inputs and sourced
  constants. Sourced constants follow `frontend/src/app/constants/kid-costs.ts`: published figure
  quoted exactly, source, series and as-of label in the JSDoc, refresh steps in a FREDdocs file.
- No PII and no raw amounts to PostHog; band anything that can grow; declare new properties in both
  taxonomies and in `FREDdocs/analytics-events.md`.
- Backend dates are Eastern (`EasternTime`). The frontend never projects paydays itself.
- Goal money lives in accounts the user opens outside FRED; FRED only works out the amounts and
  keeps score.

## Part 1: the tally

### What the user sees

**Before the first payday.** Saved so far shows the starting figure (what they typed in the quiz,
usually $0). The band under the hero says `Saved $0 · First $450 lands Dec 1`. Status chip: Plan
started.

**After paydays.** Saved so far shows the running tally. Band: `Saved $900 · 3% of your $30K · Next
$450 Jan 1`. Status chip: On plan. "Your $30K" is the part of the target the plan asks the user to
put in (anchor balance plus the anchored monthly over the plan's months); for a cash plan that is the
target itself, for an invested plan it is the target less the growth the plan assumes. Measuring
against it keeps a 529 from reading "3% there" for years while it is exactly on plan.

**The two taps.** Under the Saved so far tile sit two ghost pills:

- `remove` **Missed one** `−$450`
- `add` **Doubled up** `+$450`

A tap writes at once, with a light haptic, and a FRED toast confirms: "Saved so far is now $450.
Undo." Undo writes the opposite entry. Tapping Missed one three times means three missed
paychecks; each is its own entry. The pills show the share for the next payday, so the amount on
the pill is always the amount the tap will move. They hide when the share is $0 (a plan with
nothing left to save, a completed goal) and in preview modes (nothing is saved yet).

**Status with taps.** Status reads off the net of the taps since the plan's last re-base, against
zero, no tolerance (the old max($50, 1%) tolerance was for typed balances and goes with them):

- net 0: **On plan**
- net positive: **Ahead**, band adds ` · Ahead by $450`
- net negative: **Behind**, band adds ` · Behind by $450 since Nov 15` (the date of the oldest
  uncovered missed entry)

**The hero after taps.** The hero's monthly keeps re-solving live, as it does today, but from the
plan's value rather than the raw tally: `planValue = futureValue(anchorBalance, anchorMonthly,
monthsSinceAnchor, planRate) + net`. On plan, that reproduces the anchored monthly, so the hero,
the "a paycheck" line and the pills all agree. After a Missed one it rises to the catch-up figure,
and the page keeps its line "$X a month gets you back on plan". The tally itself never feeds the
re-solve directly: it counts money put in, and an invested plan's re-solve would otherwise climb
every month the assumed growth is missing from it.

The chip word stays one word (Ahead / Behind / On plan / Plan started / Done); the amount lives in
the band line (simple goals) and in the one-line Status tile of the unit sheet (kids). The 60-day
"Update balance" prompt goes away everywhere: the tally is never stale.

**The assumed-growth line** (Andy, 2026-11-21). Saved so far is money put in, because that is all
FRED can know. For an invested unit (plan rate kind `market`: a house, car, wedding or custom goal
with a 3+ year runway, a kid's 529 or nest egg) the page also shows what the plan says that money
is worth now, as one line under the Saved so far tile:

`Assuming growth at 10%, worth ~$14,200 today`

The figure is `planValue` (the anchored plan grown at the plan's rate, plus the net of taps), the
same number the hero re-solves from, so the two never disagree. The rate is the plan's anchored
rate as a whole percent. The line hides while `planValue` is less than $50 above the tally, so the
first months never read "$900 saved, worth ~$905", and it never shows on a cash unit (baby fund,
emergency fund, vacation, short house or car): there is no assumed growth to report. It is a plan
figure, never a balance: the word "Assuming" is required in the copy. Typing a real balance into
the Saved so far tile still re-bases the tally, so a parent who checks the actual 529 can correct
FRED any time; the line then grows again from that figure.

**Typing an exact figure.** The Saved so far tile still opens the amount tray. Closing the tray
with a new value writes a `set` entry: the tally becomes that figure, the net resets to 0, status
returns to On plan. The band's next-payday fragment is unchanged.

**Kids.** Each saving unit (529, nest egg, baby fund) runs its own tally at its own share, inside
its unit sheet where Saved so far already lives, with the same two pills under the Saved so far and
Status tiles. The goal's chip becomes the worst of its units' statuses (any Behind reads Behind;
else any Ahead reads Ahead; else On plan; Plan started only while no unit has credited). This is a
change: today kids status is one pooled grade over summed balances and anchors. With a tally per
unit the pooled grade has nothing to say, and a parent wants to know which account slipped. The
everyday row has no tally, no pills (unchanged).

**Vacation.** Same pills; the tally is Saved so far this year and resets on January 1 as it does
today (the reset is a `set 0` entry dated Jan 1, written by the roll-forward). Once the year's
budget is in, status reads Done and credits stop for the rest of that year; January 1 starts again.

**Imported goals** (the v2 import, `anchorMonthly` 0) keep "Plan updated" and show no pills until
the user opens and saves the plan, which anchors a real monthly and starts the tally.

**Done.** When a unit's tally reaches its target, or its deadline passes with the net at zero or
better, status reads Done, credits stop, and its pills hide. Marking the goal completed hides them
too.

### How FRED moves it

**One clock, the server's.** Credits are computed on the backend, in Eastern time, from the
user's investment schedule, by the same `calculateNextInvestmentDate` and business-day rules the
scheduler uses. The frontend displays what the server returns and never projects a payday itself.
This is what keeps "next $450 on Jan 1" and the scheduler's Jan 1 the same day, holidays included.

**Roll-forward on read.** Every `GET /api/goals` (and the single-goal paths that return a goal)
first rolls each of the user's goals forward: for each saving unit, walk the paydays strictly after
`credited_through` up to and including today (Eastern); for each, append a `credit` entry worth the
unit's share in force, add it to the unit's tally, and advance `credited_through`. Then return the
goal. Rolling forward is idempotent (a unique key on goal, unit, date, kind `credit`), so two
devices reading at once cannot double-credit. No scheduler job is needed; a user who never opens the
app never needs an up-to-date tally.

**The share.** One payday credits `InvestmentFrequency.fromMonthly(anchorMonthly, frequency)`,
the same cents-exact figure the page shows as "a paycheck" (backend `InvestmentFrequency`, frontend
`InvestmentFrequencyUtils.toPerPaycheckEquivalent`; the two are parity-pinned). `anchorMonthly` is
the unit's monthly as written at the last anchor (goal-level for simple goals, per account for
kids, the vacation monthly for vacation). A later Update that changes the monthly re-anchors and
changes the share from the next payday on; credits already written stay as written.

**Paydays.** A `PaydayCalendar` in the ledger service lists the paydays inside a date window from
the schedule's rule fields (`frequency`, `chosenDate`, `dayOfWeek`, `dayOfMonth`, with BIWEEKLY
parity anchored on `chosenDate ?? startDate`, SEMI_MONTHLY always the 1st and 15th), each nominal
date pushed by the entity's existing `adjustForBusinessDay` (weekends and its holiday list). It
does not call `calculateNextInvestmentDate`, whose MONTHLY branch is anchored on the date it is
called from and skips a payday later in the same month when started from a non-payday; that method
drives real money movement and is not modified. A parity test pins that, started from a real
payday, the calendar and the scheduler produce the same next date for every frequency. A paused or
held schedule still produces paydays: this money never ran through FRED, the pay cadence is what
matters (Andy's call). No schedule at all: one credit a month on the plan's anchor day of month
(clamped to the month's length, business-day adjusted), at the MONTHLY share, i.e. the full
monthly.

**First credit, last credit.** The first payday strictly after `plan_anchored_at` (its Eastern
date); a plan anchored on a payday does not credit that same day. Credits stop after the unit's
`creditsUntil` date or once the tally reaches the target, whichever comes first; the last credit
is capped so the tally lands on the target exactly. `creditsUntil` is written by the client beside
`anchorMonthly` whenever it anchors (the client already computes every unit's deadline: the last
day of December of the target year for simple goals, the last day of the 18th-birthday month for a
kid's accounts, the last day of the due month for a baby fund; null for vacation, which stops at
its target and resets each year). The server never needs the plan math.

**Entries.** Table `goal_ledger_entries`: `goal_id`, `unit` (`'goal'` for simple and vacation
goals, `'kid:<index>:<plan529|brokerage|firstYear>'` for kids), `entry_date` (DATE, Eastern),
`kind` (`credit`, `missed`, `extra`, `set`), `amount` (DECIMAL(12,2); for `set` the new absolute
tally, for the others the share moved), `created_at`. The tally itself stays where the balance
lives today (`goals.current_balance`, `AccountState.balance` in the kids config, `savedThisYear`
for vacation), now owned by the server and mutated only through entries. `credited_through` (DATE,
nullable) is added beside each tally (a column on `goals`, a field on `AccountState`).

**Adjustments.** `POST /api/goals/{id}/ledger` with `{ unit, kind: 'missed' | 'extra' | 'set',
amount? }`. `missed` writes `−share`, `extra` writes `+share` (the server computes the share, the
client never sends it), `set` requires `amount` and writes the new absolute tally. The tally never
goes below $0: a `missed` that would cross zero writes the amount that reaches zero and the response
says so. The response is the updated goal.

**Status.** For each unit: `net` = sum of `extra` minus sum of `missed` since the later of
`plan_anchored_at` and the last `set`; `netSince` = the date of the oldest `missed` entry not yet
covered by an `extra` when net is negative, else null. Covering is FIFO in entry order, and an
`extra` written before a miss counts too: a Doubled up on Nov 2, a Missed one on Nov 3 and another
on Nov 16 read "Behind by $450 since Nov 16", because the Nov 2 surplus paid for Nov 3 (ruling
2026-11-21, during the build). Status kind: completed, or
tally ≥ target, or `creditsUntil` passed with net ≥ 0 → done; imported (clientId set and
anchorMonthly 0) → imported; no credit yet and no adjustments → started; net = 0 → on_plan;
net > 0 → ahead; net < 0 → behind. The old expected-balance grading (`planStatus`'s futureValue
path), its tolerance and `STALE_BALANCE_DAYS` are retired; `balance_updated_at` stays as the time
of the last entry of any kind. Progress (`progressPct`, "% of your $30K") = tally ÷
(anchorBalance + anchorMonthly × plan months), capped at 100.

**Re-anchor.** An Update that changes a plan input re-anchors as today (anchor balance = the
tally, anchor monthly = the new monthly, anchored at = now) and the net resets, because entries
before the new anchor no longer count. `credited_through` is untouched. A `set` entry also resets
the net without re-anchoring the plan.

**Writes from the client.** For an existing goal the server ignores `currentBalance` and the kids
account balances in a PUT body; it owns them. On create, the quiz's starting balance becomes the
unit's tally and a `set` entry dated today. The Saved so far tray on the plan page and in the unit
sheet posts a `set` instead of a PUT. Preview modes keep today's draft-balance behaviour (nothing
is saved yet).

**The response.** `GoalResponse` gains, per unit, `tally` (the balance, already present),
`creditedThrough`, `nextCreditDate`, `nextCreditAmount`, `net`, `netSince`, and
`startedCrediting` (whether any credit exists). Simple goals carry these at the top level; kids
carry them on each `AccountState`. The frontend builds every status word and band fragment from
these fields; it does no payday math.

**Existing goals.** A goal with `credited_through` null is backfilled on its first roll-forward:
`credited_through` = today, no retroactive credits, tally unchanged. Andy's dev data and any
imported v2 goal start fresh from there.

### Backend shape (what exists, what changes)

Today: `goals` (V9) holds `current_balance DECIMAL(12,2)`, `balance_updated_at DATETIME(6) NULL`,
`anchor_balance`, `anchor_monthly DECIMAL(10,2)`, `plan_rate`, `plan_rate_kind`, `plan_anchored_at`,
`completed`, and `config TEXT` written by `GoalConfigCodec` from typed records (fail on unknown
properties, always re-serialized from the record). Kids balances live in the config as
`AccountState(balance, balanceUpdatedAt, anchorBalance, anchorMonthly)` per account.
`GoalService.update` re-anchors when `planInputsChanged` (or the first plan), else `keepAnchors`,
which today copies the client's balance in. All stamps use `EasternTime`.

Changes:

- Migration **V10** (`V10__goal_ledger.sql`, information_schema-guarded like V6/V7): table
  `goal_ledger_entries` (`id`, `goal_id` FK goals, `unit VARCHAR(40)`, `entry_date DATE`,
  `kind VARCHAR(8)`, `amount DECIMAL(12,2)`, `created_at DATETIME(6)`; unique key
  `(goal_id, unit, entry_date, kind)` for `kind = 'credit'` via a generated column or by making
  the key `(goal_id, unit, entry_date, kind, created_at)` only for adjustments, builder's choice
  as long as a credit can never be written twice for one unit and day) and column
  `goals.credited_through DATE NULL`. The unmerged Paycheck Autopilot branch also claims V10; this
  work lands on develop first, so `FREDdocs/handoff-paycheck-autopilot.md` is updated to say V11 in
  the same change (the same thing happened with V9).
- `AccountState` gains `creditedThrough` and `creditsUntil` (`yyyy-MM-dd`, nullable); old JSON
  still parses. `goals` gains `credits_until DATE NULL` beside `credited_through`. `GoalRequest`
  gains `creditsUntil` (goal level) and the kids config carries it per account, written by the
  client with `anchorMonthly` (the request already carries the client's plan figures).
- `HouseCarConfig` gains `loanRate` (BigDecimal, nullable, 0 to 30) and `downPaymentAmount`
  (BigDecimal, nullable, 0 to price); `planInputsChanged` adds `downPaymentAmount` and leaves
  `loanRate` out.
- `GoalService.update` ignores `currentBalance` and account balances for existing goals;
  `keepAnchors` no longer copies them. `create` seeds the tally and a `set` entry.
- New `GoalLedgerService`: `rollForward(goal, schedule, todayEastern)` and
  `adjust(goal, unit, kind, amount)`; `UserDeletionService` deletes a user's ledger entries before
  their goals (explicit child delete, as goals are deleted before users today).
- `GoalResponse` carries the per-unit tally fields listed above; `GoalController` gains
  `POST /{id}/ledger`.

### Analytics

New event `goal_tally_changed` with `goal_type` and `kind: 'missed' | 'extra' | 'set'`. No amounts,
no unit index. Declared in both taxonomies and in `FREDdocs/analytics-events.md`, or the
events-documented script fails the build.

## Part 2: house and car terms

### House

Quiz step `house_terms` ("How much, and when?") becomes:

- **Down payment** chips: `5%`, `10%`, `20%` (default 20%). Full price is gone.
- **Mortgage rate** slider: 3.0% to 10.0% in steps of 0.125%, default from the sourced constant
  (below). The readout shows `6.5%`. The quiz slider is integer-only today, so this slider works in
  eighths of a percent (value 52 = 6.5%) with a format function; tap-to-type is off for it.
- **Buy in** year slider, unchanged.
- The chip under the controls becomes two lines of plain text in the same `fetch-chip static`
  block:
  - `$32,500 down · ~$X a month to get there`
  - `Then the mortgage: ~$3,900 a month at 6.5% over 30 years, before taxes and insurance.`

The subtitle of the step says what the plan is for: "FRED plans the down payment. The mortgage
comes after." (fits one line at 27px on 390px, shortened if not).

`HouseCarConfig` gains `loanRate: number | null` (percent, e.g. 6.5). Saved HOUSE rows without it
read the default. `downPaymentPct` keeps its 1..100 server rule; existing rows with `null` (the old
Full price) keep meaning 100% so they still load, but the quiz never writes `null` again and shows
such a row as 20% when edited.

### Car

Quiz step `car_terms` becomes:

- **Down payment** dollar slider: $0 to $25,000 in $500 steps, linear, default $5,000, never above
  the price (the max shrinks to the price when the price is lower, the value clamps). $0 is allowed:
  FRED then plans nothing to save and says so, and the step's Continue is disabled with the hint
  "With $0 down there is nothing to save. Set a down payment, or pick Something else." (a $0 target
  cannot anchor a plan).
- **Loan rate** slider: 3.0% to 15.0% in eighths of a percent, default from the sourced constant.
- **Buy in** year slider, unchanged.
- Two lines:
  - `$5,000 down · ~$X a month to get there`
  - `Then the loan: ~$520 a month at 7.5% over 60 months.`

`HouseCarConfig` gains `downPaymentAmount: number | null` (dollars, CAR only; HOUSE leaves it
null). `simpleTarget` for CAR becomes `downPaymentAmount ?? price × pct / 100` so old CAR rows
keep working. The car's `downPaymentPct` is left as it was on old rows and ignored by the quiz.

### The loan payment line

`loanPayment(principal, annualRatePct, months)` is standard amortization:
`P × r / (1 − (1 + r)^−n)` with `r = annualRatePct / 100 / 12`, rounded to the nearest dollar,
shown with `~`. A 0% rate divides evenly. Principal = price − down payment. Terms are fixed: 360
months for a house, 60 for a car (Andy's brief named the rate as the personal knob; the term is a
FRED assumption and the copy says it).

The same two lines appear on the plan page for HOUSE and CAR, as the second note in the info sheet
("What the monthly covers") and as the simple goal's subtitle lead, so the distinction between the
down payment plan and the loan is in front of the user after the quiz too. The rate is changed in
the quiz (Edit plan); the plan page shows it read-only. Changing the loan rate is not a plan input:
it does not re-anchor, because it does not change the savings target.

### Sourced defaults

`frontend/src/app/constants/loan-rates.ts`, in the `kid-costs.ts` pattern:

- `MORTGAGE_RATE_DEFAULT_PCT`: Freddie Mac Primary Mortgage Market Survey, 30-year fixed, the
  latest weekly average at build time, quoted exactly with the week's date in the JSDoc.
- `CAR_LOAN_RATE_DEFAULT_PCT`: Federal Reserve G.19 Consumer Credit, commercial bank new car
  loan rate, 60-month, the latest quarterly figure at build time, quoted exactly with its period.
- `MORTGAGE_TERM_MONTHS = 360`, `CAR_LOAN_TERM_MONTHS = 60`: FRED assumptions, labeled as such.
- `LOAN_RATE_SOURCES` with name, period and URL, shown in the info sheet's sources line the way
  kid-cost sources are.

Refresh steps go in `FREDdocs/loan-rate-figures.md`. The implementer looks the two figures up when
building and records the as-of labels; they are never recalled from memory.

## What is left to the builder

- Table and column names beyond those given, index choices, DTO field names, the Flyway version
  (the next free one when the migration is written; never edit a run migration).
- Exact pill styling (they follow the `.fd-tile` family and the ghost-pill look of the quiz's
  fetch chip; 34px tall, full-row pair under the tile).
- Toast wording beyond the pattern given, and the haptic strength.
- How the roll-forward is unit-tested against the schedule's date rules (a fixed Eastern clock and
  a schedule fixture per frequency is the expected shape).
- Whether `set` from the tray debounces or writes on close only (write on close only is what the
  tray does today and is fine).
- The slider control change for eighths of a percent (a `format` plus `tapToType=false` is enough;
  a new control is not wanted).

## Builder check rulings (2026-11-18)

A reviewer read the first draft without being able to ask questions. Its seven questions and the
rulings, all made by the assistant under Andy's "answer what you can" instruction:

1. Which number is a paycheck: the anchored monthly's share, server-computed. The hero re-solves
   from the plan's value (anchor grown plus net), so it agrees with the share while on plan.
2. When crediting stops: at `creditsUntil` or the target, whichever first; the client writes the
   date, the server needs no plan math.
3. Done and progress for invested units: progress against "your part"; Done at the target or at
   the deadline with net ≥ 0.
4. Taps grade against zero; the tolerance is gone.
5. The scheduler's date math is not modified; the ledger has its own payday calendar with a
   parity test.
6. Worst-of-units for the kids chip is a deliberate change, not existing behaviour.
7. Imported goals keep "Plan updated" with no pills until saved; vacation reads Done when the
   year's budget is in and resets on January 1.
8. The assumed-growth line (Andy asked for returns to be visible, 2026-11-21): shown for market
   units only, from `planValue`, with "Assuming" in the copy; the $50 hide threshold is my call.

## Calls I made (Andy can veto any of these)

1. Credits are computed server-side on read, not by a job and not on the frontend.
2. The share is the per-paycheck figure already shown, so the tally per month equals the monthly.
3. No schedule means one credit a month on the anchor day, at the full monthly.
4. A paused or held schedule still produces paydays.
5. The chip stays a single word; the amount goes in the band line and the unit sheet's Status tile.
6. "Update balance" and the 60-day stale rule are removed, not kept alongside.
7. Loan terms are fixed at 30 years and 60 months; only the rate is the user's.
8. The car's down payment is dollars with a $0 floor that blocks Continue; the house's is percent
   with a 5% floor.
9. Changing the loan rate does not re-anchor the plan.
10. The loan line appears on the plan page too (info sheet and subtitle), read-only.

## Build notes (2026-11-22)

Rulings that moved during the build, one line each.

- Progress measures against the target when no monthly is planned (imports and due-now plans).
- A never-planned import shows no growth line.
- netSince counts an earlier Doubled up as cover for a later miss (already above).
- Undo puts back exactly what moved (the server honours an explicit amount on missed and extra) and never offers a second Undo.
- `set` stays allowed on a finished unit.
- A toast with an action stays five seconds.
- The vacation band says "this year's".
- The loan card on the plan page is its own card, with a "Default rate" source sentence that never names the user's own rate.
- A legacy full-price car opens in the quiz at the $25,000 slider max.
- The payday calendar does not copy the scheduler's day 29 to 31 quirk (backlog FRED-236).
- One analytics event, `goal_tally_changed`, carries goal type and kind only; an Undo emits the kind it posted.
- A tap with no amount moves exactly the pill figure: one share, capped so the tally lands on the target (a $29,800 tally on a $30,000 target moves $200 either way). An Undo's explicit amount is honoured as sent; a miss still never crosses $0.
- The server reports what moved: every ledger POST answers with `X-Fred-Moved` (2 decimals, `0.00` when nothing was written), exposed through CORS beside `X-Fred-Capped`. The toast's "$0" copy shows only when nothing moved, and Undo posts exactly that figure, so a payday rolled under an open page cannot confuse it.
- A plain save takes a sent `target` (goal level and per kid account), as it already took `creditsUntil`, so a goal saved before targets existed gets its cap and Done-at-target on the next save.
- A typed figure (`set`) re-bases the plan on simple and vacation goals: the anchor balance becomes the typed figure and the plan is anchored now, so the line grows again from that figure (monthly, creditsUntil, target and creditedThrough unchanged). Simple and vacation goals only; kids units would need a per-account anchor date (backlog FRED-238), so a kids `set` re-bases nothing.
- The pills stay until a unit is finished, even after the last payday inside its window; only the band's "Next" fragment needs a payday ahead.
- An untouched loan rate slider keeps the exact published default (7.28 for a mortgage, 7.54 for a car loan): the slider sits at the nearest eighth and its readout shows the exact rate; only sliding it writes an eighth. The quiz says "No loan needed." when the down payment covers the price.
