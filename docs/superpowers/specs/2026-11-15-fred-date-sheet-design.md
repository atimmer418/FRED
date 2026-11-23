# FRED date sheet: one bottom sheet for every date in the app

Date: 2026-11-15. Decided with Andy ("That date picker looks incredible. Let's go with that."). Runs after the
amount tray rollout, FRED sheets and skeletons, which are all in the tree.
Approved mockup: https://claude.ai/artifact/PBtg3b9RJTvjaSzukfhdUK (scratchpad copy: date-picker.html). The
mockup is the visual contract: sizes, colours, spacing and motion come from it.

## Intent

Andy: the app's calendars "are not very native for an iOS app" and "kind of clunky". No ion-datetime, no browser
date input, no typed date anywhere a date is picked. One component, two faces, in the chrome FRED already ships.
Design panel winner (5 proposals, 3 judges): the FRED Date Sheet, 23.5 of 30.

## The component: `components/fred-date-sheet/`

Standalone `FredDateSheetComponent`, presented like the other FRED sheets (ModalController +
BOTTOM_SHEET_MODAL_OPTS from utils/bottom-sheet.utils.ts, `hostDirectives: [SheetDragDismissDirective]`,
`host: { class: 'ion-content-scroll-host' }`, the `.gs-handle` and dark `.fd-cta` recipes from
components/fred-confirm-sheet). One helper, `presentDateSheet(modalCtrl, opts): Promise<string | null>`, resolves
the chosen date as a local `YYYY-MM-DD` string or null (Cancel, drag, backdrop). Same file layout and export
pattern as components/fred-confirm-sheet.

Options:
```ts
interface FredDateSheetOptions {
  title: string;                       // zone label, e.g. 'Transfer date', 'Date of birth'
  mode: 'upcoming' | 'birthday';
  value?: string | null;               // YYYY-MM-DD
  min?: string;                        // YYYY-MM-DD inclusive
  max?: string;                        // YYYY-MM-DD inclusive
  businessDaysOnly?: boolean;          // upcoming: weekends drawn disabled and unreachable
  quickPicks?: QuickPick[];            // upcoming: up to four { label, date } chips, already filtered to the range
  subline?: (date: string) => string;  // readout subline for a chosen date
  doneLabel?: (date: string) => string;// default 'Use Fri, Nov 20' (upcoming) / 'Use Mar 14, 1991' (birthday)
}
```

Anatomy, top to bottom (mockup tab A and B):
- Handle. Zone label 10px/800 uppercase #9aa6ba. Readout 26px/800 #0f172a (placeholder "Pick a day" in #cbd5e1
  when empty), subline 12.5px/600 #6b7280, aria-live polite. Readout cross-fades (120ms, house easing) on change.
- Birthday only: the year rail. This IS `app-plan-amount-tray` with a new `unit: 'year'` (plain integer labels,
  no $ or %, step label "± 1 yr" is not needed: the rail does not show the step label), a uniform scale
  `{ min: thisYear - 110, max: thisYear, step: 1, fine: 1 }` and hold gears x1 / x5 / x10 (new optional
  `gears` input on the tray; default stays the existing gears). A "YEAR" caption left and the live year right
  (blue while dragging) above the band. Moving the year keeps month and day, clamping Feb 29 and month ends with
  a one-line note under the grid ("Feb 29 moved to Feb 28 in 1991."), and never past `max`.
- Upcoming only: quick-pick chips, up to four, two lines (label 12px/800, date 9.5px/700 #9aa6ba), 44px min
  height, white with #e5e7eb border, selected #eef4ff with #2563EB border and label.
- Grid card (#f8fafc, 1px #eef1f6, radius 14): header row with the month button ("November 2026" 14px/800 plus
  an expand chevron that rotates when the month pane is up) and two 28px round chevrons (previous and next month,
  disabled outside the range). Weekday row S M T W T F S 10px/800 #9aa6ba. Day grid 7 columns, 40px rows, six
  rows fixed (240px pane) so the sheet never changes height between months. Cells 15px/700; today has a 4px blue
  dot; out-of-range and (businessDaysOnly) weekend cells are #cbd5e1 weight 500 and aria-disabled; a tap on one
  shakes the cell (180ms, 6px) with a Medium haptic (thumpHaptic) and selects nothing. One absolutely positioned
  36px blue ring glides between selected cells (180ms, house easing, WAAPI); the selected cell's number turns
  white 90ms into the glide. Month pane: 3 x 4 tiles behind the month button, cross-fade 88ms out / 132ms in;
  tiles outside the range disabled; a tile tap sets the month and flips back. Chevrons slide the grid 24px with a
  88ms out / 132ms in fade. Keyboard: arrows move a day, Home/End on the rail, Enter on a cell selects.
- Note line under the grid (11.5px/600 #6b7280, 16px min height): the businessDaysOnly caption "Transfers run on
  business days." or the year-clamp note; otherwise empty.
- Done: `.fd-cta` 50px black, label from `doneLabel` ("Use Fri, Nov 20"), disabled until a valid day is chosen.
  Birthday adds a text "Cancel" under it (`.gs-cancel`). Upcoming has no Cancel (drag or backdrop).
- Haptics: tickHaptic on a day select, a chip tap, a month page, each year detent; thumpHaptic on a disabled tap.
- Motion: WAAPI only (the global reduced-motion rule zeroes CSS transitions); under reduced motion everything
  snaps. The sheet itself enters and leaves through the house sheet animation.
- Nothing is written to the page until Done. Drag, backdrop and Cancel discard.
- Empty birthday opens on today minus 30 years, no day selected. Empty upcoming opens on `min`'s month.
- a11y: role dialog with aria-label = title; grid role grid / gridcell with full-date aria-labels and
  aria-selected; chips role radio in a radiogroup; the rail is the tray's existing role slider with
  aria-valuetext = the year; VoiceOver reads the readout on every change; Dynamic Type one step up must not
  break the 7-column grid (cells shrink their font before they wrap).

## Shared utils (new, pure, each with its own spec)

- `utils/local-date.ts`: `todayYmd()`, `ymd(y, m, d)`, `parseYmd`, `addDays`, `dayOfWeek`, `isWeekend`,
  `daysInMonth`, `formatShort` ("Fri, Nov 20", year appended when not this year), `formatLong`
  ("Mar 14, 1991"), `formatFull` ("Friday, November 20, 2026"), `ageOn(dob, today)`. Local time only; no
  `toISOString()` anywhere in the four sites afterwards (today it is one day off after about 7pm Eastern).
- `utils/business-days.ts`: `addBusinessDays(ymd, n)`, `nextBusinessDay(ymd)`, `tradeWindow(ymd)` ("Nov 23 to
  Nov 25": one to three business days after the transfer date), used by both the page echo and the sheet subline
  so they always agree. No bank-holiday table (rejected: backend parity risk).
- `utils/quick-picks.ts`: `quickPicks(frequency, min, today)`: WEEKLY and BIWEEKLY give Next Mon and Next Fri;
  MONTHLY gives 1st, 15th and Month end (last business day); SEMI_MONTHLY gives none (the field is locked);
  padded to four from Tomorrow / Next Fri / 1st / 15th; filtered to >= min; when businessDaysOnly, every pick
  is moved forward to the next business day; de-duplicated; sorted ascending. The field pages pass the result in.

## The tray prerequisites (components/plan-amount-tray, ADD only)

- `unit: 'year'` in AmountUnit and formatValue (plain integer, no separators), end labels "1916" / "2026".
- Optional `gears` input (the HOLD_GEARS shape) so the rail can run x1 / x5 / x10; default unchanged.
- Optional projected header slot (`<ng-content select="[trayHead]">`) rendered inside the card above the band,
  so the sheet can put the "YEAR" caption and the live year inside the rail card as the mockup draws them.
  Empty for every existing consumer.
- Optional `edgeGuard` input (default true). The sheet passes false: inside a modal there is no swipe-back, and
  the guard would otherwise swallow taps on the first ~4px of track at the 1916 end.
- The tray already ticks a haptic per detent and gear change; the sheet must not tick again on a year change.
- Nothing else about the tray changes; its existing specs stay green, Freedom Plan untouched.

## An incoming value that is not selectable

An `upcoming` sheet may be opened with a `value` that is before `min` (a recurring transfer date that has
passed) or on a weekend under `businessDaysOnly` (an onboarding default that landed on a Saturday). The sheet
opens on that value's month but treats it as not chosen: no ring, readout "Pick a day", Done disabled until a
valid day is tapped. The page keeps showing its stored value until Done. To make this rare, the pages fix their
seeds: investment-schedule's `getInvestmentDate()` MONTHLY default (the 1st of next month) moves to the next
business day via the util; SEMI_MONTHLY seeds stay exactly as they are on both pages (the field is locked and
the backend owns the 1st/15th rule). investment-schedule's `new Date('YYYY-MM-DD')` parses (UTC midnight, a day
early in every US timezone) become `parseYmd`; the gate grep adds `new Date('` and `new Date(\`` over the four
sites.

## The four sites

| Site | Today | Becomes |
|---|---|---|
| recurring-investments Transfer Date | centred ion-datetime, min today, raw ISO string stored | `.field-value-btn` with a calendar_month glyph on the right (mockup tab C) opening `presentDateSheet` in upcoming mode: min today, `businessDaysOnly: true` (Andy: weekends not selectable), quick picks from the util for the edited frequency, subline "Trades land Nov 23 to Nov 25". SEMI_MONTHLY: button disabled (opacity .4) and the echo reads "Set by your pay schedule: the 1st and 15th." with a lock glyph. The two helper lines under the field collapse into ONE echo line "Est. investment Nov 23 to Nov 25" (blue, schedule glyph) from the shared util. Stored value becomes a local YYYY-MM-DD string; the save path's ISO-splitting code goes. hasChanges / saveChanges / analytics unchanged otherwise. |
| investment-schedule Next pay date (onboarding) | browser `<input type="date">` | same button and sheet, upcoming mode, min = tomorrow (getMinDate), `businessDaysOnly: true`, quick picks for the chosen frequency, no subline; SEMI_MONTHLY disabled with the same echo. The existing weekend adjustment in onStartDateChange becomes unnecessary (the sheet cannot return a weekend) and is removed; schedule.startDate keeps its YYYY-MM-DD format. appKeyboardAvoid stays (the account number input remains). |
| add-beneficiary Date of Birth | centred ion-datetime, `today` = toISOString | button + sheet in birthday mode, max today, echo "34 years old" (cake glyph); value stays whatever parseBackendDate / formatDateForBackend expect (read them; convert at the boundary). The ion-modal, ion-datetime imports and `today` ISO go. |
| kyc-verification Date of Birth | typed mm/dd/yyyy text, numeric keypad, auto-advance to phone | button + sheet in birthday mode in the same two-column row; the reactive control keeps its MM/DD/YYYY string (adapter at the boundary: sheet gives YYYY-MM-DD, control stores MM/DD/YYYY, validator untouched); after Done, focus moves to the phone input as the auto-advance did; before presenting, blur the active input so the keyboard drops. Keep a visually hidden `<input autocomplete="bday">` bridge ONLY if it can be done without a focusable element in the tab order; otherwise drop autofill and say so. Ships last. |

Every converted flow keeps its analytics events, toasts, validation and navigation. No other field changes.

## Decisions made for Andy (reject any)

- Weekends are disabled and unreachable on BOTH money dates (recurring transfer, onboarding pay date), with the
  caption "Transfers run on business days."; quick picks land on business days. (Andy asked for recurring; the
  onboarding field gets the same rule for consistency, replacing its silent weekend bump.)
- Month pane and chevrons only navigate; only a day tap or the year rail moves the selection.
- No horizontal month-swipe gesture in v1 (it would fight drag-to-dismiss and swipe-back).
- No age floor on KYC (none exists today); flagged for Alpaca's 18+ rule if wanted later.
- Birthday rail covers today minus 110 years to today.

## Gates

Specs: the three utils (dates around month ends, leap years, year boundaries, 7pm-Eastern style off-by-one
guarded by using local constructors), the sheet (renders each mode, disabled cells, chips, month pane, year rail
drives the selection and clamps, Done payload and roles, nothing committed on cancel, reduced motion snaps), the
tray's year unit and gears, and each converted page (opens the sheet with the right options, writes the value on
'save'/Done, nothing on dismiss, SEMI_MONTHLY lock, echo text, KYC focus handoff). `npx ng test` on all touched
specs prints Executed N of N SUCCESS; grep shows no `ion-datetime`, `type="date"`, or `toISOString()` left in the
four sites; `npx ng build --configuration dev` passes. Device pass by Andy: rail feel at ~2.3px a year, sheet
height on an iPhone SE, KYC keyboard handoff.
