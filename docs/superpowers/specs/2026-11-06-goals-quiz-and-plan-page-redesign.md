# Goals v3.1: the quiz and the plan page, redesigned

Date: 2026-11-06. Owner: Andy. Written after six rounds of mockups in the brainstorm session
("betterGoals", continued). The final mockup is committed beside this document at
`assets/2026-11-06-goals-redesign-mockup.html`; open it in a browser, the sliders and toggles work.

This document amends the Goals v3 design (`2026-11-02-goals-v3-plans-design.md`) and its kid-costs
addendum (`2026-11-05-goals-v3-kid-costs-addendum.md`). Where it differs from them, this document
wins. Everything those documents say about the math, the data model, the API, the anchor and status
model, the tier rule and the import stays in force unless named below.

## Intent

Goals v3 shipped the right idea with the wrong presentation. Andy's review of the built branch:
"the presentation of data is inconcise, dull and boring ... there's too much information, you're
not even reading." What he wants instead is the feel of the Monte Carlo flow (one question per full
blue screen, a big number, a slider, no keyboard) for the setup, and the feel of the Freedom Plan
sheet (one hero number, a band under it, a few tiles, insights, rate chips) for the plan. One page
each, nothing to scroll through, premium fintech, friendly, easy to read.

The second thing he wants is the plan to read as something FRED does for you. "You come on the app
and get this reassurance that FRED is taking care of all these things and you don't have to think
as much about your finances." The hero says what to save *this month*; the page says FRED handles
where it goes.

## Goals

- Setting up any goal is a short blue quiz: one question per screen, every screen fits without
  scrolling at iPhone height, and no screen needs the keyboard unless the user chooses to type an
  exact number.
- The plan page fits one screen and reads top-down: what to save this month, where the family is
  in its story, what each kid gets, one offer, the return chips, how FRED helps, the button.
- Every title fits on one line at 390px. No sentence wraps mid-way. Helper copy is one line.
- The same quiz, prefilled, is how a plan gets changed later.
- The Freedom Date opens the same way goals do: a pushed page, not a sheet.

## Non-goals

- New plan math, new goal types, new tier rules. The utilities, services, API and table stay.
- The portfolio split across goals (FRED investing toward goals). It is the next project; this
  design writes copy that assumes it (see "How FRED helps") and flags that.
- AI-written plan summaries (backlog FRED-230).
- Push notifications or a monthly reminder. "FRED reminds you in December" in the gift-days row is
  copy for a later feature; until it exists the row ends at "for each kid" (see Gift days).
- Dark mode.

## Anti-goals

- A quiz screen that scrolls, or a keyboard that opens without the user asking for it.
- A title wrapped onto a second line, or a sentence broken mid-way to fit.
- A number on a screen whose meaning the user has to work out (the old row's "$1,500/mo · 4 kids ·
  2043 to 2044"). Every amount says what it covers.
- Asking for anything the plan does not need. No birthdays, no take-home pay, no kid names up
  front.
- A progress bar that frames a kids plan as a savings total ("Goal $637K").

## House rules that apply (from memory and CLAUDE.md)

- No em dashes anywhere in user-facing copy.
- Titles on one line; break copy only at sentence boundaries; shorten until it fits. Use "~" for
  approximations in numbers ("~$1,540"), never "about" in front of a dollar figure. The big reveal
  number on the cost screen has no tilde.
- Manrope, light-only, the section-card patterns in `FREDdocs/FRED_UI_STYLE_GUIDE.md`.
- Analytics: no new events or properties without telling Andy first. This design adds none.
- Any icon returned from TypeScript rather than a template literal goes in `DYNAMIC_ICONS` in
  `frontend/scripts/subset-icons.mjs`, then `npm run subset-icons`, commit the font, bump the
  cache-buster, and tell Andy to run `npx cap sync ios`.

## The quiz shell

One standalone component, `GoalQuizComponent`, used by every goal type. It is a **pushed route**,
not a modal: `/goals/new/:type` (new) and `/goals/:id/edit` (change an existing plan). A route
keeps edge-swipe-back, deep links and the Edit-plan re-entry simple. (My call; the Monte Carlo flow
is a modal, and the visual shell is copied from it, not its presentation.)

The host is the Monte Carlo blue: `.flow-host` tokens and gradient from
`monte-carlo-flow.component.scss` (`--sim-deep #0e3a96`, `--sim-mid #1e60e3`, the radial
highlight), `.fs-eyebrow`, `.fs-h1` (27px here, 30px there, so titles fit), `.fs-sub`,
`.big-readout`, `.readout-hint`, `.sim-range`, `.range-ends`, `.glass-field`, `.fetch-chip`,
`.fs-cta`, the step dots and the back/close buttons. Lift those styles into a shared partial
(`theme/_blue-flow.scss`) and have both components use it rather than copying.

Each step is a `.fscreen` that fills the viewport: top bar (back when there is a previous step,
dots, close), eyebrow, title, a one-line subtitle, a centered body, and the CTA pinned at the
bottom. Every step on every goal type has a title and a subtitle. All titles share one style
(27px, weight 800, the same class); nothing per-step overrides it. Steps slide horizontally (the Monte Carlo transition). The dots show the steps that
will actually run; when a toggle removes a later step the dots shrink.

Controls, in order of preference: slider, chips, stepper, tappable cards, switch. Sliders snap to
round steps. Every slider that drives a dollar amount shows the value as a `big-readout`; tapping
the readout swaps it for a `big-input` (type tel, inputmode numeric) exactly as the Monte Carlo
portfolio step does, so an exact amount is always possible. Sliders fire a light haptic on each
detent via `@capacitor/haptics` (already a dependency; `pin-prompt.component.ts` shows the import),
native only, no-op on web.

The CTA reads "Continue" on every step but the last. The last step's CTA reads "Show my plan" with
the `auto_awesome` icon in front, on every goal type.

Close (the X) on a new quiz discards and returns to my-profile. Close on an edit quiz discards the
edits and returns to the plan page. Back on the first step is the same as close.

### After "Show my plan": the calc screen

A full blue screen for about 1.2 seconds (never less than 0.9, never more than 2.5 while the plan
computes, which is instant): a spinning ring, the title "Working out this month's number", and
under it one line per unit of the plan ticking from an empty circle to a green check, 250ms apart.
For kids, one line per kid: "Ellie, already here: college and nest egg", "Kid 2, 2028: baby fund
first", "Kid 3, not dated: starting the baby fund now". For other goals, two lines: "Your target"
and "Your monthly". Then the plan page pushes in and the hero number counts up from 0 over 600ms
(WAAPI, not CSS transitions; see the reduced-motion note in memory). Respect `prefers-reduced-
motion`: no count-up, the calc screen still shows for 0.9s so the sequence reads the same.

### The preview and the save

The quiz ends by pushing the plan page in **preview** mode with the draft held in a
`GoalDraftService` signal (not route state, so a reload of the preview URL falls back to
my-profile). The page shows the full plan from the draft, and its CTA reads "Start this plan"
(new) or "Update my plan" (edit). Start POSTs; Update PUTs; both then replace the URL with
`/goals/:id`. Back from a preview discards the draft. The tier rule is unchanged: a free user with
an active goal sees the Plus nudge when they tap "Add a goal", before any quiz.

## The Kids quiz

Sample used throughout the mockup: three kids, born 2024, expected 2028, not yet dated.
The sample family's figures assume a $250,000 nest egg per kid, as the mockup card shows; at the quiz's $100,000 default the hero is $2,249/mo (~$1,036 a paycheck), dropping to ~$1,988 (~$916) with gift days on.

### Step 1, how many

Eyebrow "Your family". Title "How many kids are you planning for?" (this one is allowed two lines;
it breaks after "you", before a clause, and Andy approved it). Subtitle "Count the ones already
here too." Body: a row of `child_care` icons in glass circles, one per kid, centered; under it a
stepper (minus, big number, plus). **Six is the cap**; at six the plus button dims and a line
under the stepper reads "Six is the most FRED plans for at once". Default 1.

Backend: `validateKids` cap goes from 10 to 6. No stored goal exceeds 6 (no production backend
exists yet; the dev database can be checked with one query).

### Step 2, when

Eyebrow "Timing". Title "When do you think they'll arrive?" (two lines, breaks after "think",
approved). Subtitle "A rough guess is fine." Body: one compact glass row per kid, all six fit:
`child_care` icon, "Kid N", an inline slider, and the value right-aligned in a fixed-width column
("Born 2024", "This year", "2028", "Not sure"). Under the rows, three labels across: "Already
here", "Soon", "Not sure".

Slider range is `currentYear - 17` to `currentYear + 10`, plus one extra stop past the end that
means "Not sure" (`birthYear = null`). The bounds come from today's date, so next year reads 2010
to 2037 without a code change. Default position for a new kid: "Not sure". `birthMonth` is no
longer collected and is sent as null; the field stays in the model.

No pen, no names here. Names are given on the plan page.

### Step 3, what a kid costs

Eyebrow "The real number". Title "What one kid costs". Subtitle "The U.S. average, adjusted to
today's prices." Body, centered: the big number with no
tilde, "$422K" for one kid, "$332K" for two, "$252K" for three, **"$213K" for four, "$189K" for
five, "$173K" for six**; hint line "From birth to 18". That is the general statistic. It holds for about 2.2
seconds, then **crossfades (500ms, opacity only, WAAPI) into the one that matters, in exactly the
same styling**: the big number becomes "$3,510" with a smaller "/mo" unit after it and the hint
line becomes "For all 3 kids" ("For your kid", "For both kids"). Nothing else is on the screen;
no extra lines under the number. Tapping the number flips between the two (my call). With reduced
motion on, the swap is instant after the same hold. Nothing else on the screen: no per-kid
monthly line, no family card. The info button in the top bar (in the close button's place on this
step, as the mockup shows) opens the sources sheet, `KidCostSourcesSheetComponent`.

The four-, five- and six-kid figures are FRED's own extension of the USDA table, which stops at "three or
more". The USDA marginal cost of a third child is 0.28 of a two-child-average child (2.28 − 2.00 in
family-size units); FRED applies that same marginal factor to the fourth, fifth and sixth, giving family
factors 2.56, 2.84 and 3.12 and per-kid factors 0.64, 0.568 and 0.52. Record this in `FREDdocs/kid-cost-
figures.md` next to the 1.27 / 1.00 / 0.76 factors and label the three new figures "FRED estimate" in
the sources sheet. The monthly line is the per-kid figure over 216 months, rounded to the nearest
$10; the family line is per-kid × count over 216 months.

### Step 4, choose what to fund

Eyebrow "Your plan". Title "Choose what to fund". Subtitle "Turn one off to see what it would save
you." Body: three glass rows with a switch each, all on by default:

- `child_care` **Baby fund**: "$16,500 ready on day one." (the first-year target for this family
  size). Shown only when at least one kid is not born yet; hidden otherwise.
- `school` **College**: "4 years of tuition, in a 529."
- `savings` **A nest egg at 18**: "Invested now, theirs at 18."

No dollar totals on this screen while everything is on. When a switch is turned off, its row dims
and its helper line turns green and reads what the user would have put in over the whole plan,
in K, as a realistic range for the units whose target is a choice:

- **Baby fund:** one figure, the unborn kids' rank targets added: "Saves you $37K."
- **College:** from the in-state preset to the out-of-state preset, contributions summed over all
  the kids: "Saves you $55K to $135K."
- **Nest egg:** from a $50,000 to a $250,000 target per kid, contributions summed over all the
  kids: "Saves you $55K to $275K."

Contributions = Σ over kids of (flat monthly for that target × horizon months) at the plan rate,
using each kid's own horizon (an undated kid counts as born this year). Round each end to the
nearest $5K (under $20K, to the nearest $1K). A range whose ends round to the same value shows one
figure. The figures use the family's actual kid count and ages, so they refresh if the user goes
back and changes either.

The switches set `plan529Target`, `brokerageTarget` and `firstYearFund` on the draft. College off
means `plan529Target = 0` and step 5 is skipped; nest egg off means `brokerageTarget = 0` and step
6 is skipped; both off makes this the last step (CTA "Show my plan" with the icon) and the goal is
baby-fund-only, which the server already accepts. All three off: the CTA is disabled and the
subtitle reads "Pick at least one."

### Step 5, college (only if College is on)

Eyebrow "College". Title "Saving for college?". Subtitle "Four years of tuition per kid, at today's
prices." (shortened from "Pick a target per kid. Four years of tuition at today's prices.", which
measured 351px in Manrope against the 338px a 390px screen gives it; see the one-line rule under
"The other quizzes"). Body: four tappable glass cards in a 2×2 grid, each with an icon, a
label and the amount: `school` "In-state public" $47,800 (selected by default), `travel_explore`
"Out-of-state" $123,960, `account_balance` "Private" $180,000, `do_not_disturb_on` "Skip for now"
$0. Under the grid a text link "Set my own amount" that swaps the grid for a big readout and a
slider ($5,000 to $300,000, step $1,000) with "Back to the presets" under it. Choosing "Skip for
now" is the same as the switch being off.

### Step 6, the nest egg (only if the nest egg is on)

Eyebrow "A nest egg at 18". Title "How much per kid?". Subtitle "You invest it as they grow. Theirs
at 18." (shortened from "You invest it as they grow up. It's theirs on their 18th birthday.", which
measured 366px against the same 338px). Body: big readout, slider $0 to $500,000 step $10,000,
ends "$0" and "$500K", hint "Per kid · tap to type", and under the slider the live chip
"~$342 a month per kid" (the flat monthly for a kid born this year at the chosen rate; "Nothing
extra at 18" at $0). **Default $100,000** (my call; Andy's examples used $250,000, and a default
that most users slide up from reads better than one they slide down from). $0 here is the same as
the switch being off.

Then the calc screen, then the plan page preview.

## The other quizzes

Every other type is one or two screens in the same shell. Defaults are the current goal-form
defaults unless stated.

- **House**, two screens. (1) "What will it cost?" / "A rough price for the home you have in
  mind.", a log-scale slider $100K to $3M, default $650,000. (2) "How much, and when?" / "More
  down now, less each month.", a "Down payment" chip row 10% / 20% / Full price (20% default), a
  "Buy in" big readout over a year slider from next year to +20 years, and a live chip
  "$130K down · ~$1,310 a month". No "in cash" suffix anywhere; the cash rate shows on the plan
  page as today.
- **Car**, same two screens: "What will it cost?" / "New or used, the price you expect to pay."
  ($5K to $200K, default $35,000) and "How much, and when?" / "More down now, less each month."
  with the same chips (Andy's "financing a car" means 20% default stays).
- **Vacation**, one screen: "Trips and budget" / "Kept in cash, ready to spend.", a "Trips a
  year" stepper **1 to 10** (the server's `tripsPerYear` bound and the TS type widen from 1..4 to
  1..10; the subtitle logic "N trips a year" already handles any count), a "Budget per trip" big readout over a slider $500 to $15,000 step $250,
  and the live chip "~$834 a month".
- **Emergency fund**, one screen: "Your safety net" / "Three to six months of expenses, in cash.",
  a "Monthly expenses" big readout over a slider $1,000 to $20,000 step $100, a "Months" chip row
  3 / 6, a "By" chip row 1 year / 2 years / 3 years (default 2), and a live chip
  "$24,000 by Dec 2028".
- **Wedding**, one screen: "The budget, and when" / "What you'd like to have set aside by the
  day.", amount slider $5,000 to $150,000 step $1,000 and a year slider; live chip with the
  monthly.
- **Something else**, one screen: "How much, and when?" / "Any target with a date.", amount
  slider ($500 to $1,000,000, log scale) and a year slider, plus an icon chip row (the existing
  icon options) under them. The name defaults to "My goal" and is renamed on the plan page.

Every subtitle above is one line at 390px; if a translation or a copy change makes one wrap, it is
shortened, not wrapped.

The type picker sheet on my-profile stays; picking a type pushes `/goals/new/:type`.

## The plan page

`/goals/:id` (and the preview of a draft). White page, standard pushed-page header: back chevron,
the goal name as the title, and a three-dots button on the right. Everything below fits one screen
at iPhone height; the page may scroll on the smallest phones but is designed not to.

### Header menu (three dots)

A small action sheet: **Edit plan** (pushes `/goals/:id/edit`, the quiz prefilled with every stored
answer: count, years, switches, targets, price, chips), **Rename** (all types; for KIDS this
renames the goal, kids are renamed on their cards), **Mark completed** (not for VACATION; "Reopen"
when completed), **Remove goal** (the existing confirm sheet). Because Edit plan changes plan
inputs, the server re-anchors status from that day, which it already does.

### Hero

The Freedom sheet hero card (`.fd-hero` gradient, radius 20, the shadow). Label "SAVE THIS MONTH"
(not "each month"; the number is re-solved from today's balances, dates and kids, so it falls when
a baby fund finishes and rises when a kid is added). Big "$2,830/mo". Meta line
"for all 3 kids · ~$1,306 a paycheck" (per paycheck from the user's schedule frequency via
`toPerPaycheckEquivalent`; hidden when there is no schedule). For KIDS with one kid: "for Ellie";
two: "for both kids"; three or more: "for all N kids". For other types: "for your home", "for your
car", "for N trips a year", "for your safety net", "for the wedding", or the goal name.

When the plan is at its deadline with a shortfall, the hero keeps the existing "due now" treatment
(amendment in the v3 design).

### The band under the hero

- **KIDS: the family timeline** (Andy's pick, "Band A"). A thin bar from the earliest event to the
  last 18th birthday. Dots: each kid's birth year (filled green when passed), "Now" (a blue dot
  with a white ring), each kid with no date as a dashed dot placed at this year, and the end "All
  18 · YYYY". The portion before Now is green. Labels alternate above and below the bar so they do
  not collide, and the scale is non-linear: the first ten years from the earliest event take about
  65% of the width and the rest is compressed, so near-term dots never pile up. Labels show the
  kid's name once it has one.
- **Other types: the Freedom-sheet progress bar**: "Saved $X · Y% there · Target $T by Mon YYYY".
  For VACATION: "Saved $X this year · Y% there · $T for N trips".

### Per kid (KIDS only)

Zone label "PER KID" with swipe dots on the right. A horizontal pager of one card per kid (Swiper,
the same setup as the tabs pager, `swiper-no-swiping` not needed), the next card peeking at the
right edge. Each card: header row with a `child_care` avatar, the name (with a small `edit` pen
after it), a sub-line ("Born 2024", "This year", "Arrives 2028", "Not dated yet"; no "18 in
2042", the user knows), and the kid's monthly in blue on the right; then one row per funded unit with an icon tile, label, target and
date, and the monthly on the right: `child_care` "Baby fund · $16,500 by Jun 2028",
`school` "College · $47,800 by 2042", `savings` "Nest egg · $250,000 by 2042". A finished baby
fund shows a check and "Done" instead of a monthly.

**Renaming in place.** Tapping the pen turns the name into an inline text field on the card (the
only keyboard on this page besides balances), with a `check` button; tapping the check, pressing
return or tapping elsewhere saves. The PUT carries the new `label`; the server already treats
`label` as not a plan input, so nothing re-anchors. Names show on the timeline, in the hero meta
and on the my-profile row.

**Logging a balance.** Each unit row's target text is tappable and reveals an inline "Saved so
far" numeric field under the row; blur saves it (the existing `updateBalance` path, serialized
through `pendingWrite`). For simple goals, one "Saved so far" tile sits between the hero and the
insight row. The status chip (Plan started / On plan / Ahead / Behind / Done) sits at the right end
of the "PER KID" zone label for KIDS and at the right end of the band for other types. (My call on
placement; the rule that status is graded only after a logged balance is unchanged.)

**Cash or Invested, on every row.** Each unit row's label carries a small pill: "Invested" (blue
on `#eef4ff`) or "Cash" (green on `#ecfdf5`), from that unit's rate kind. Simple goals show the
same pill at the right end of the band. This is how the user knows which money is in the market
and which is not, without reading a note.

The 22 and 30 milestone values, the cash-rate explanation, the sources and the per-paycheck
breakdown per unit move into a detail sheet opened by tapping a unit row's label. They are not on
the page.

### Gift days (KIDS only; shown when any kid has a college fund or nest egg)

> **Removed 2026-11-08 (Andy).** The gift-days row is gone from the plan page in both states and
> kids plans are monthly only: no New Year lump posts, live or at the anchor. `giftDays` stays on
> `KidsConfig` so stored rows still load; the view model ignores it. The section below is history.

One insight row with a `cake` icon and a switch, off by default:
"**Lighten the monthly.** Put in $1,000 per kid every New Year, and this drops to ~$2,580."
When on, the hero shows the lower monthly, its per-paycheck figure follows ("~$1,191 a paycheck"
for $2,580), the my-profile row follows, and the row reads
"**Next up:** New Year, $1,000 for each kid." (append " FRED reminds you in December." only once a
reminder exists).

This replaces the birthday and New Year rituals. The lump is a flat **$1,000 per kid per
January**, for every kid with a nest egg or college fund, posted to the nest egg when the kid has
one and to the 529 otherwise (my call). No birthday is ever asked for. `kidLumpSchedule` becomes
"every January inside the horizon, amount 1,000" and the ritual sizing (`friendlyRound`, the 70/20/
10 split, the shrink loop) is deleted along with its specs. The `n ≥ 24 and s0 ≥ $600` rule is
replaced by: the offer appears when the plan's horizon for that kid is at least 24 months. The
monthly is solved net of the lumps exactly as before; status anchors keep including lumps.

Persistence: `giftDays: boolean` on `KidsConfig` (both the Java record and the TS type), default
false, missing in old rows reads as false, and flipping it is a plan-input change (re-anchors).

### Return chips

> **Changed 2026-11-08 (Andy).** One return assumption runs the whole app and it is set only on the
> Freedom Plan page. The plan page shows the Freedom page's Assumptions card read-only: "Avg
> annual return / what this plan grows by" with the figure ("10%") where the chips were, and a
> note under it: "Set on your Freedom Plan page, where it runs every FRED projection." No chips, no
> Reset to standard, and Start / Update never write the shared rate. The page reads the shared
> signal live, so a change saved on the Freedom page shows here on the next open with no reload.
> Cash-planned goals keep the cash note.

The existing row: "Return" label and the 8% / 10% / 12% segmented chips (cash-planned goals show
the cash note instead, as today). Written on Start / Update / and, for a saved goal, immediately,
as today.

### How FRED helps

A light blue note card (`#eef4ff`, `account_balance_wallet` icon) between the chips and the button,
with the plan's monthly substituted in, word for word:

> **How FRED helps.** Already investing $2,830 or more a month? FRED sets this plan's share aside
> inside your portfolio and handles where it goes.

That is the all-invested form. The sentence follows the plan's rate kinds (round 9):

- All units invested: the sentence above.
- Mixed (a kids plan with a baby fund, or a cash unit inside an invested plan): "... FRED sets this
  plan's share aside inside your portfolio, keeps the baby funds in cash, and handles where it
  goes." (name the cash unit: "the baby fund", "the baby funds").
- All cash (vacation, emergency fund, anything under three years): "**How FRED helps.** FRED keeps
  this in cash, ready the day you need it."

Andy chose this wording knowing it describes the portfolio split, which is the next project. Flag
for whoever builds the split: this copy is live before the feature. Until then the my-profile
disclaimer ("FRED invests only toward your Freedom Date ...") comes out, since the two would
contradict each other (Andy's decision to ship the sentence implies this; confirm in the plan
review if unsure).

### CTA

"Start this plan" (preview of a new goal), "Update my plan" (preview of an edit), none for a saved
goal (balances and names save as you go). Black full-width button, the Freedom-sheet `.fd-cta`.

## my-profile

- The Goals card keeps the Freedom Date row first, then one row per goal. The goal row's subtitle
  is **"$2,830/mo for all 3 kids"** (Andy's pick, "Row A"): the monthly lead, then "for" + the same
  audience phrase the hero uses. Status chip on the right, thin bar under. No year ranges.
- **The Freedom Date row opens a pushed page**, `/freedom`, built from the same pieces as the goal
  page: hero (the year, "Age 52 · 24 years away", the progress bar with Now / % / Goal), the two
  editable tiles (Income goal, Investing), the two insight rows, the return chips with "Reset to
  standard", the 4% note, and "Save plan". The content and behavior of `FreedomDetailSheetComponent`
  move into `FreedomPlanPage`; the sheet component is deleted and the first-time tour's
  `profileFreedom` step keeps pointing at the row (`data-tour="freedom-row"`), which now navigates.
  Update the three my-profile specs and the tour specs that assume a modal.

## Which goals are invested and which are cash

Unchanged from v3, restated because the new copy leans on it. The rule is one line: **money
needed within three years is planned as cash at ~4%; everything else is planned at the chosen
8 / 10 / 12% and is a candidate for the portfolio split.** Per type:

| Goal | Planned as | Why |
|---|---|---|
| Kids: college (529) and nest egg | Invested, per kid; cash only once that kid is within 36 months of 18 | 18-year horizons |
| Kids: baby fund | Cash when the due date is under 36 months out; invested for an undated kid (36-month runway) | short, fixed date |
| House, Car, Wedding, Something else | Invested when the target is 36+ months out, cash under that | by horizon |
| Vacation | Always cash | spent within the year |
| Emergency fund | Always cash | must be available on a bad day |

The rate kind is frozen when the plan is anchored, so a goal does not flip from market to cash by
itself a month later; it is re-evaluated on Edit plan. The "How FRED helps" excerpt appears on
every plan page, but when the portfolio split ships only the invested rows above are candidates
for a slice; cash goals stay outside the portfolio.

## Icons

Add to `DYNAMIC_ICONS` any of these that end up returned from TypeScript: `edit`, `check`,
`auto_awesome`, `cake`, `travel_explore`, `do_not_disturb_on`, `info`, `close`,
`arrow_back_ios_new`, `more_horiz`, `check_circle`. Template literals are picked up by the subset
script automatically. Regenerate the font, bump the cache-buster (`?v=3` → `?v=4` in `global.scss`
and `index.html`), and list `npx cap sync ios` as an owner step.

## What changes in code (orientation for the planner)

Frontend, new: `pages/goal-quiz/` (component, per-type step definitions, step components for
slider / chips / stepper / cards / switch rows, the calc screen), `services/goal-draft.service.ts`,
`pages/freedom-plan/`, `theme/_blue-flow.scss`, `components/kid-timeline/`.
Frontend, rewritten: `pages/goal/goal.page.*` (the plan page; the setup/quiz half comes out, the
page becomes the plan view with preview mode, the header menu, per-kid pager, in-place rename,
inline balances), `goal-plan.view-model.ts` (audience phrase, timeline model, gift-days payoff,
"saves you" totals), `goal-form.model.ts` (draft shape and validation, now driven by the quiz),
`utils/goal-plan.utils.ts` (lump schedule = January $1,000; ritual code removed),
`utils/kid-cost.utils.ts` + `constants/kid-costs.ts` (4, 5 and 6 kid factors), my-profile row
subtitle and Freedom row navigation, routes.
Frontend, deleted: `components/freedom-detail-sheet/`, the quiz cards inside `goal.page.html`, the
ritual math and its specs.
Backend: `KidsConfig.giftDays` (record + codec + tests), kids cap 6 (validator + test), nothing
else. No migration.
Docs: `FREDdocs/kid-cost-figures.md` (4/5/6 factors), `FREDdocs/analytics-events.md` only if a
comment references the sheet, the backlog (the redesign story; a new story for the portfolio
split), and the v3 design's amendment section gets a pointer to this document.

## Verification

- Specs: cost figures for 1 to 6 kids and the family monthly; "saves you" totals; the January lump
  schedule and the solved monthly with the offer on and off; the timeline model (positions, dashed
  dots, name labels); the audience phrase; the draft service round trip; quiz step visibility as
  switches flip; the prefilled edit quiz; in-place rename PUT body; Freedom page parity with the
  old sheet's specs.
- `npx ng build --configuration dev`, `npm run lint`, `npm run test:events-documented`, Karma
  with only the known FRED-226 failures, `./gradlew test`.
- A copy audit: render every quiz screen and the plan page at 390×844 in the dev server and check
  that no title wraps and no sentence breaks mid-way; list any that do.
- Device pass by Andy: one of each type end to end; a kids plan with a born, a dated and an undated
  kid; the switches; Edit plan round trip; rename in place; the Freedom page; swipe-back from
  every pushed page; reduced motion on.

## Answers from the builder check

A reviewer read this as a builder and asked five things. Settled here; the ones marked (owner)
were put to Andy.

1. **Baby-fund targets stay by birth rank** ($25,700 / $20,800 / $16,500 for the first, second,
   third-and-later kid), exactly as the addendum pins them. The new four-, five- and six-kid cost factors
   apply to the cost headline only and never to the fund; `firstYearTargetForRank` keeps its rank-3
   floor. On step 4 the Baby fund helper line reads "$20,800 ready on day one." when one kid is
   unborn and "Ready on day one, for each one." when several are; the "saves you" figure is the sum
   of the unborn kids' rank targets ($37,300 in the sample, not $33,000; the mockup's number was
   wrong). Each kid card shows its own figure.
2. **A lump that would overshoot hides the offer.** The gift-days row appears only when, for every
   kid unit that would receive the January lump, the monthly solved net of the lumps stays above
   $0 (the lumps alone do not reach the target) and the horizon is at least 24 months. No shrink
   loop, no cap; the offer is simply absent for very small targets.
3. **"This year" means expecting this year, and the fund defaults on** (owner decision, see the
   question put to Andy). A kid whose slider sits on the current year is not yet born: eligible for
   the baby fund with a due date of December this year, "This year" on the timeline and the card.
   Born kids are strictly before the current year. The addendum's "default off under 12 months"
   rule and its short-runway warning line are both dropped (Andy, round 8): the switch is on and
   the row reads "$20,800 ready on day one." whatever the runway. The resulting monthly shows on
   the plan page like any other. `birthMonth` is null and reads as July for born
   kids' ages, as today.
4. **What survives on the one-screen plan page.** The catch-up sentence is cut: the hero number is
   already the catch-up figure and the chip says Behind. The 60-day prompt becomes a chip state,
   "Update balance", which tapping scrolls to the balance field. The vacation reset shows in the
   band ("Saved $0 this year"). A finished fund is the "Done" row. The undecided-kid note and the
   monthly-only reason line move to the unit detail sheet. **Emergency fund gets a "By" chip row**
   on its one quiz screen: 1 year / 2 years / 3 years (default 2), so the year is set in the quiz
   and changed through Edit plan; there is no year control on the plan page.
5. **Tour handoff.** The `profileFreedom` gate passes when the user returns to my-profile from
   `/freedom` (the same moment the sheet's dismiss used to fire); the tour overlay is hidden while
   the Freedom page is up. Hooks live in the Freedom page's `ionViewWillLeave` and my-profile's
   `ionViewWillEnter`; the tour specs assert that order.

## Calls I made (check these while reading)

- The quiz is a pushed route, not a modal.
- Default nest egg $100,000 (Andy's examples said $250,000).
- Four-, five- and six-kid cost figures extend the USDA table by its own third-child marginal factor.
- The January lump goes to the nest egg when the kid has one, else the 529.
- The gift-days offer appears from a 24-month horizon.
- Status chip placement (end of the "PER KID" label; end of the band for other types).
- Balance logging is inline under each unit row; milestones and sources move to a detail sheet.
- The my-profile disclaimer comes out when the "How FRED helps" copy ships.
- The calc screen's timing (about 1.2s) and the count-up (600ms).
- "Something else" defaults to the name "My goal".

## Follow-ups recorded

- Backlog: a story for this redesign (supersedes the UI half of FRED-229's acceptance criteria).
- Backlog: "One portfolio, split across long-term goals by share": each long-term goal (Freedom
  Date, Kids nest eggs and 529 targets, a house years out) owns a share of the single FRED
  portfolio, every paycheck splits by those shares, each slice shows its own growth and progress,
  and the kids' nest eggs move to custodial accounts (Alpaca Broker API lists custodial accounts;
  verify before writing copy). Short goals (under three years, vacations, emergency funds) stay in
  cash outside it. Andy: "that is the goal of FRED." Starts after this redesign ships.
- FRED-230, the AI-written plan summary, unchanged.
