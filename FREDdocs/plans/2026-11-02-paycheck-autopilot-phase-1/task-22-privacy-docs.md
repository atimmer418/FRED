### Task 22: privacy-docs

Declare the payroll-deposit data Paycheck Autopilot holds (the employer's descriptor and display name, deposit amounts, deposit dates, the cadence derived from them) in the three places that describe what FRED collects: the iOS privacy manifest `frontend/ios/App/App/PrivacyInfo.xcprivacy`, its answer sheet `FREDdocs/privacy-manifest-and-label.md`, and the counsel-facing `FREDdocs/what-we-send.md` (ruling R14, handoff section 8.2, Decision 22). Docs and one plist only: no Java, TypeScript, property, migration, endpoint, analytics emission or money path is touched, so nothing here can move money, write `InvestmentSchedule.status`, change `isReadyForInvestment` or write a hold. The counsel conversation itself is Andy's and stays a manual checkbox in the plan header.

Facts you need (verified on HEAD 137998c, 2026-11-04):

- **Why this task exists (handoff 8.2, restated).** Detecting a paycheck means reading a person's payroll deposits: the amount, the date, and in practice the employer's name as it appears on the transaction. The app did not hold this kind of data before. The manifest at HEAD declares analytics only (Product Interaction and User ID, both not linked). Apple rejects a build whose manifest is incomplete, and the handoff asks for the entry "under Financial Info and probably Employment Info, linked to the user, purpose app functionality", plus a line in the counsel-facing description of what FRED holds.
- **Decision 22 (binding).** The manifest declares `NSPrivacyCollectedDataTypeOtherFinancialInfo` only. `privacy-manifest-and-label.md` has no rule for employers, income or paychecks, so whether the employer's name is also employment data goes to counsel on a checkbox, not into the manifest. Apple's definition of Other Financial Info names salary and income. Apple's list of manifest data types has no employment type that the plan author knows of; the doc row added in Step 6 says so and names `NSPrivacyCollectedDataTypeOtherDataTypes` as the nearest candidate if counsel wants one. That is unverified against Apple's current page; re-read it before the first submission.
- **What Phase 1 actually stores, so the docs describe it truthfully.** `paycheck_events` rows (T07) hold the bank's description (`raw_descriptor`), `merchant_name`, `counterparty_name`, the amount and the posted and authorized dates of each inflow on a depository account: every inflow, not only paychecks (T07 keeps any Plaid `amount < 0` on a depository account, a Zelle or Venmo credit naming a person included), so the docs say so. `paycheck_streams` (T02, T10, T11) hold `payer_display_name`, `payer_token` (a normalized form of the descriptor), `raw_descriptor`, cadence, anchors, `last_amount`, `trailing_median`, `predicted_next_date` and T10's `plaid_snapshot_json`. T10's snapshot carries Plaid's `description` and `merchant_name`, and T13's `onLoginRequired` sets `plaid_snapshot_json` to null together with `raw_descriptor`, so the docs' sentence that a login break blanks the bank's descriptions holds. `paycheck_cycles` (T12) hold, per pay period, the matched event, `deposit_amount` and, under shadow, `amount_to_pull` (what would have been invested). Retention as built by the plan: the 22:30 ET reconcile deletes events older than 24 months (T15 `EVENT_RETENTION_MONTHS = 24`, events only, and it runs only while `paycheck.autopilot.enabled=true`); `PlaidItemStateService.onLoginRequired` deletes the user's events and nulls `raw_descriptor` on every stream but keeps the stream skeleton (display name, token, cadence, anchors, amounts) and the cycles (T13); `onRevoked` and `resetForNewItem` purge cycles, events, streams and the cursor (T13); account deletion removes all three tables through V6's `ON DELETE CASCADE` on `user_id` (V6 lines 62, 95, 161); a REJECTED applicant keeps their row, and T15 only queues their Item for removal and marks it REMOVED (R8), with no purge, so what is already held stays. The section written in Step 8 describes Phase 1 as a whole. If T07, T11, T12, T13 or T15 is dropped from the plan, re-read those bullets before the branch merges.
- **What reaches analytics.** No payer string, descriptor, amount, date or Plaid id ever enters an analytics property (handoff 8). The one Phase 1 event about paychecks is `paycheck_stream_switched` (added by T11) with exactly `frequency` (the cadence slug from `InvestmentScheduleService.frequencySlug`: `weekly`, `biweekly`, `semi_monthly`, `monthly`) then `reason` (`succession`). The cadence is part of the payroll data, so the counsel doc says plainly that this one word does reach analytics; "never sends any of it" would be false for cadence.
- **Xcode wiring is already done.** `PrivacyInfo.xcprivacy` is a bundle resource in `frontend/ios/App/App.xcodeproj/project.pbxproj` (lines 18, 36, 107, 195), so editing the file is enough; no project change.
- **The manifest is indented with tabs.** Keep tabs in every line you add. `plutil` does not care, but the file's own style does.
- **One em dash already exists** in `what-we-send.md` at line 35 (the Sentry row's "Where it is" cell). It is not this task's; leave it. The no-em-dash check in this task looks only at lines this task adds. An em dash is matched in the shell with `$'\xe2\x80\x94'`.
- **Shared tree rules.** Never `git checkout`, `git restore`, `git stash` or `git reset`. Hunk-verify `git diff HEAD -- <file>` before staging, stage only the three files below, never stage `application-local.properties` or `ITPM/agent-memory/*`. No property is added, so `application-local.properties` needs no hand edit for this task.
- **Merge seam.** No other Phase 1 task edits these three files, so the line numbers below hold at execution time. Every edit below still quotes the exact text to replace, so a drifted line number never matters.

**Files:**
- Modify: `frontend/ios/App/App/PrivacyInfo.xcprivacy:6-7` (header comment, "WHAT THIS FILE COVERS"), `:14-15` (header comment, "covers only analytics"), after `:69` (new `<dict>` for `NSPrivacyCollectedDataTypeOtherFinancialInfo` before the `</array>` at `:70` that closes `NSPrivacyCollectedDataTypes`)
- Modify: `FREDdocs/privacy-manifest-and-label.md:14-15` (last-reviewed line), after `:26` (declared table, rows 21 to 27: new "Other financial info: payroll deposits" row), after `:41` (one paragraph scoping the "not linked" argument to analytics), `:62-63` ("The manifest covers analytics."), `:72` (owed table, rows 65 to 72: the existing Other financial info row amended, a new employer-name row for counsel), `:92-95` (label answers: new item 4, the rest renumbered)
- Modify: `FREDdocs/what-we-send.md:12` (last-reviewed line), between `:28` and `:30` (new section "What FRED holds and never sends" after "The short version" and before "Destinations"), after `:253` (open item 4)
- Test: none on disk. The test is the shell acceptance check written in Step 2 to a file outside the repository and never committed (the contract's Files list has no test file, and no existing suite reads these documents).

**Interfaces:**
- Consumes: no code. The facts above from the contract entries of T07 (`paycheck_events` columns), T11 (`paycheck_stream_switched {frequency, reason=succession}`), T12 (`paycheck_cycles.amount_to_pull` under shadow), T13 (`onLoginRequired`, `onRevoked`, `resetForNewItem` purges) and T15 (`EVENT_RETENTION_MONTHS = 24`). T22 has no dependency and can run first: it reads none of their code.
- Produces: the manifest entry `NSPrivacyCollectedDataTypeOtherFinancialInfo` with `NSPrivacyCollectedDataTypeLinked` true, `NSPrivacyCollectedDataTypeTracking` false and the single purpose `NSPrivacyCollectedDataTypePurposeAppFunctionality`; the privacy-doc rows "Other financial info: payroll deposits" (declared) and "Employer name, if counsel says it needs its own declaration." (owed, Andy with counsel); label answer "4. **Financial Info:** Other Financial Info (payroll deposits)."; the `what-we-send.md` section `## What FRED holds and never sends` and open item 4; and one line for the plan header's Manual prerequisites (Step 11).

- [ ] **Step 1: Confirm the starting state**

Run from the repository root:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
git status --short -- FREDdocs/privacy-manifest-and-label.md FREDdocs/what-we-send.md frontend/ios/App/App/PrivacyInfo.xcprivacy
plutil -lint frontend/ios/App/App/PrivacyInfo.xcprivacy
plutil -extract NSPrivacyCollectedDataTypes raw -o - frontend/ios/App/App/PrivacyInfo.xcprivacy; echo
grep -c OtherFinancialInfo frontend/ios/App/App/PrivacyInfo.xcprivacy
grep -c $'\xe2\x80\x94' FREDdocs/what-we-send.md FREDdocs/privacy-manifest-and-label.md frontend/ios/App/App/PrivacyInfo.xcprivacy
```

Expected: `git status` prints nothing (if it prints a line, another session has work in that file: hunk-verify it and keep it, never revert it); `frontend/ios/App/App/PrivacyInfo.xcprivacy: OK`; `2` (two collected data types today); `0`; and the em dash counts `FREDdocs/what-we-send.md:1`, `FREDdocs/privacy-manifest-and-label.md:0`, `frontend/ios/App/App/PrivacyInfo.xcprivacy:0`.

- [ ] **Step 2: Write the acceptance check**

This is the failing test. It reads the manifest with `plutil -extract` (array indices are key path components; `raw` on an array prints its length), and it reads the two docs for the exact rows and headings this task adds. Create `${TMPDIR:-/tmp}/t22-privacy-check.sh` (outside the repository; never commit it) with exactly this content:

```bash
#!/usr/bin/env bash
# T22 acceptance check: the payroll-deposit declaration in the iOS manifest and in both docs.
# Run from the repository root. Prints one PASS or FAIL line per rule; exits 0 only when none fail.
set -u
M=frontend/ios/App/App/PrivacyInfo.xcprivacy
P=FREDdocs/privacy-manifest-and-label.md
W=FREDdocs/what-we-send.md
EM=$'\xe2\x80\x94'
failures=0

check() { # check <label> <command and args...>
  local label=$1
  shift
  if "$@" >/dev/null 2>&1; then
    echo "PASS $label"
  else
    echo "FAIL $label"
    failures=$((failures + 1))
  fi
}
x() { plutil -extract "$1" raw -o - "$M" 2>/dev/null; }

# The manifest
check "manifest lints" plutil -lint "$M"
check "manifest still says no tracking" test "$(x NSPrivacyTracking)" = false
check "manifest declares three collected types" test "$(x NSPrivacyCollectedDataTypes)" = 3
check "analytics entries unchanged and not linked" test \
  "$(x NSPrivacyCollectedDataTypes.0.NSPrivacyCollectedDataType)/$(x NSPrivacyCollectedDataTypes.0.NSPrivacyCollectedDataTypeLinked)/$(x NSPrivacyCollectedDataTypes.1.NSPrivacyCollectedDataType)/$(x NSPrivacyCollectedDataTypes.1.NSPrivacyCollectedDataTypeLinked)" \
  = "NSPrivacyCollectedDataTypeProductInteraction/false/NSPrivacyCollectedDataTypeUserID/false"
check "third type is Other Financial Info" test "$(x NSPrivacyCollectedDataTypes.2.NSPrivacyCollectedDataType)" = NSPrivacyCollectedDataTypeOtherFinancialInfo
check "third type is linked" test "$(x NSPrivacyCollectedDataTypes.2.NSPrivacyCollectedDataTypeLinked)" = true
check "third type is not tracking" test "$(x NSPrivacyCollectedDataTypes.2.NSPrivacyCollectedDataTypeTracking)" = false
check "third type has exactly one purpose" test "$(x NSPrivacyCollectedDataTypes.2.NSPrivacyCollectedDataTypePurposes)" = 1
check "third type purpose is app functionality" test "$(x NSPrivacyCollectedDataTypes.2.NSPrivacyCollectedDataTypePurposes.0)" = NSPrivacyCollectedDataTypePurposeAppFunctionality
check "manifest names OtherFinancialInfo exactly once" test "$(grep -c OtherFinancialInfo "$M")" = 1
check "manifest header no longer says analytics only" grep -qF 'analytics and payroll deposits is incomplete' "$M"

# The privacy manifest and label doc
check "privacy doc has the declared payroll row" grep -qF '| Other financial info: payroll deposits | collected, **linked**, not for tracking, purpose **app functionality** |' "$P"
check "privacy doc owes the employer-name question to counsel" grep -qF '| Employer name, if counsel says it needs its own declaration.' "$P"
check "privacy doc label answer for Financial Info" grep -qF '4. **Financial Info:** Other Financial Info (payroll deposits).' "$P"
check "privacy doc no longer says the manifest covers analytics only" sh -c "! grep -qF 'The manifest covers analytics. It does' '$P'"

# The counsel-facing doc
check "what-we-send has the section once" test "$(grep -cxF '## What FRED holds and never sends' "$W")" = 1
check "section sits after The short version and before Destinations" awk \
  '/^## The short version$/{a=NR} /^## What FRED holds and never sends$/{b=NR} /^## Destinations$/{c=NR} END{exit !(a && b && c && a < b && b < c)}' "$W"
check "what-we-send carries the plain sentence" grep -qF "FRED reads the payroll deposits on a user's linked bank account to detect their paycheck, keeps" "$W"
check "what-we-send lists the counsel open item" grep -qF '4. **Payroll deposits have not been reviewed by counsel.**' "$W"

# House style: nothing this task adds may contain an em dash
check "no em dash in any added line" test "$(git diff HEAD -- "$M" "$P" "$W" | grep '^+' | grep -c "$EM")" = 0

echo "failures=$failures"
[ "$failures" -eq 0 ]
```

- [ ] **Step 3: Run the check and watch it fail**

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
bash "${TMPDIR:-/tmp}/t22-privacy-check.sh"; echo "exit=$?"
```

Expected, exactly:

```
PASS manifest lints
PASS manifest still says no tracking
FAIL manifest declares three collected types
PASS analytics entries unchanged and not linked
FAIL third type is Other Financial Info
FAIL third type is linked
FAIL third type is not tracking
FAIL third type has exactly one purpose
FAIL third type purpose is app functionality
FAIL manifest names OtherFinancialInfo exactly once
FAIL manifest header no longer says analytics only
FAIL privacy doc has the declared payroll row
FAIL privacy doc owes the employer-name question to counsel
FAIL privacy doc label answer for Financial Info
FAIL privacy doc no longer says the manifest covers analytics only
FAIL what-we-send has the section once
FAIL section sits after The short version and before Destinations
FAIL what-we-send carries the plain sentence
FAIL what-we-send lists the counsel open item
PASS no em dash in any added line
failures=16
exit=1
```

- [ ] **Step 4: Declare the payroll deposits in the manifest**

Three replacements in `frontend/ios/App/App/PrivacyInfo.xcprivacy`. Each quoted old text occurs exactly once in the file.

(a) Header comment, `:6-7`. Replace:

```
  WHAT THIS FILE COVERS: the analytics this repository's analytics layer sends, and the
  required-reason APIs the app itself calls. It is written to agree, line for line, with
```

with:

```
  WHAT THIS FILE COVERS: the analytics this repository's analytics layer sends, the payroll
  deposits Paycheck Autopilot reads from the linked bank, and the required-reason APIs the app
  itself calls. It is written to agree, line for line, with
```

(b) Header comment, `:14-15`. Replace:

```
A manifest that covers only
  analytics is incomplete
```

with:

```
A manifest that covers only
  analytics and payroll deposits is incomplete
```

(the old text starts mid-line at `:14` after `has to declare it. ` and ends mid-line at `:15` before ` for the app as a whole.`; the rest of both lines is unchanged).

(c) The new collected data type, after `:69`. Replace (the tail of the User ID entry, `:67-70`, indented with tabs):

```
				<string>NSPrivacyCollectedDataTypePurposeAnalytics</string>
			</array>
		</dict>
	</array>
```

with:

```
				<string>NSPrivacyCollectedDataTypePurposeAnalytics</string>
			</array>
		</dict>
		<!-- Payroll deposits, read by Paycheck Autopilot from the linked bank through Plaid. To
		     tell the paycheck apart, every incoming deposit on that bank's deposit accounts is
		     stored, not only paychecks: the payer's name as the bank and Plaid describe it, a
		     normalized form of it used for matching and a display name derived from it, each
		     deposit's amount and date, and the pay cadence worked out from those dates.
		     LINKED because it is stored in FRED's own database against the account; purpose app
		     functionality, because finding the paycheck is the feature. None of it is sent to
		     analytics except the pay cadence, as one word on one event (FREDdocs/what-we-send.md).
		     Apple's definition of this type names salary and income. Whether the employer's name
		     also needs its own declaration is with counsel; see
		     FREDdocs/privacy-manifest-and-label.md. -->
		<dict>
			<key>NSPrivacyCollectedDataType</key>
			<string>NSPrivacyCollectedDataTypeOtherFinancialInfo</string>
			<key>NSPrivacyCollectedDataTypeLinked</key>
			<true/>
			<key>NSPrivacyCollectedDataTypeTracking</key>
			<false/>
			<key>NSPrivacyCollectedDataTypePurposes</key>
			<array>
				<string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string>
			</array>
		</dict>
	</array>
```

The comment deliberately never spells the type key, so `grep -c OtherFinancialInfo` stays at 1.

- [ ] **Step 5: Run the check; the manifest rules pass**

Run the Step 3 command. Expected: the eleven manifest lines and the em dash line read `PASS`; exactly these eight read `FAIL`, then `failures=8` and `exit=1`:

```
FAIL privacy doc has the declared payroll row
FAIL privacy doc owes the employer-name question to counsel
FAIL privacy doc label answer for Financial Info
FAIL privacy doc no longer says the manifest covers analytics only
FAIL what-we-send has the section once
FAIL section sits after The short version and before Destinations
FAIL what-we-send carries the plain sentence
FAIL what-we-send lists the counsel open item
```

- [ ] **Step 6: Make the answer sheet agree with the manifest**

Six replacements in `FREDdocs/privacy-manifest-and-label.md`. Each quoted old text occurs exactly once.

(a) `:14-15`. Replace:

```
This page is the answer sheet for both, and the reason for each answer. **Last reviewed:**
2026-10-24. **Owner:** Andrew Timmer.
```

with:

```
This page is the answer sheet for both, and the reason for each answer. **Last reviewed:**
2026-10-24, and again when Paycheck Autopilot Phase 1 added the payroll-deposit row. **Owner:**
Andrew Timmer.
```

(b) The declared table, after `:26`. Replace the User ID row:

```
| User ID | collected, **not linked**, not for tracking, purpose **analytics** | The opaque `public_id` UUID. |
```

with the same row followed by the new one (two lines; the `NSPrivacyAccessedAPITypes` row stays after them):

```
| User ID | collected, **not linked**, not for tracking, purpose **analytics** | The opaque `public_id` UUID. |
| Other financial info: payroll deposits | collected, **linked**, not for tracking, purpose **app functionality** | Paycheck Autopilot reads the deposits on the linked bank account, through Plaid, to find the user's paycheck. To tell the paycheck apart it stores every incoming deposit on that bank's deposit accounts, not only paychecks: the payer's name as the bank and Plaid describe it, a normalized form of it used for matching and a shorter display name derived from it, each deposit's amount and date, and the pay cadence worked out from those dates. It is stored in FRED's own database against the account, which is why it is linked. None of it is sent to analytics except the pay cadence, as one word on one event (see [what-we-send.md](what-we-send.md)). Apple's definition of Other Financial Info names salary and income, which is what a paycheck is. Whether the employer's name also needs a declaration of its own is with counsel (see the table below). |
```

(c) "Why not linked", after `:41`. Replace:

```
forbidden payload and proves it never leaves.
```

with:

```
forbidden payload and proves it never leaves.

The argument above is about the analytics rows. The payroll-deposit row is **linked**, on purpose:
those records sit in FRED's own database against the account they belong to, which is what Apple
means by linked, and none of them is part of what analytics sends except the pay cadence, as one
word on one event.
```

(d) `:62-63`. Replace:

```
The manifest covers analytics. It does **not** cover the data the rest of the app collects, and
that has to be declared before any submission. This is listed rather than quietly omitted.
```

with:

```
The manifest covers analytics and the payroll deposits above. It does **not** cover the rest of
the data the app collects, and that has to be declared before any submission. This is listed
rather than quietly omitted.
```

(e) The owed table, `:72`. One Apple type entry covers all other financial info, so the existing row is now a confirmation rather than a missing entry, and the employer question goes to counsel. Replace:

```
| Other financial info (portfolio, contributions) | the app's own screens | investing |
```

with:

```
| Other financial info (portfolio, contributions). The type is now declared for payroll deposits, linked, for app functionality; confirm those answers also fit this data, then delete this row | the app's own screens | investing |
| Employer name, if counsel says it needs its own declaration. Apple's list of collected data types has no employment type that we could find; if one is wanted, the nearest is `NSPrivacyCollectedDataTypeOtherDataTypes` | the deposit description Paycheck Autopilot reads | Andy, with counsel |
```

(f) The label answers, `:92-95` ("Answer these to match the manifest exactly", so the new manifest entry needs its answer). Replace:

```
4. **Diagnostics:** nothing, while Sentry is absent. Revisit the day it lands.
5. **Tracking:** No. The app does not track users, so no ATT prompt is required or shown.
6. Every other category: answered by the KYC, auth and banking owners per the table above, not by
   this work.
```

with:

```
4. **Financial Info:** Other Financial Info (payroll deposits). Used for **app functionality**.
   **Linked** to the user's identity. **Not** used for tracking.
5. **Diagnostics:** nothing, while Sentry is absent. Revisit the day it lands.
6. **Tracking:** No. The app does not track users, so no ATT prompt is required or shown.
7. Every other category: answered by the KYC, auth and banking owners per the table above, not by
   this work.
```

Run the Step 3 command. Expected: `failures=4`, `exit=1`, the four failing lines being the four `what-we-send` rules.

- [ ] **Step 7: Add the counsel-facing section to what-we-send.md (last-reviewed line)**

`FREDdocs/what-we-send.md:12`. Replace:

```
**Last reviewed:** 2026-10-24 (second revision: the outbound filter now exists). **Owner:** Andrew Timmer.
```

with:

```
**Last reviewed:** 2026-10-24 (second revision: the outbound filter now exists; third revision, for
Paycheck Autopilot Phase 1: the payroll data FRED holds). **Owner:** Andrew Timmer.
```

- [ ] **Step 8: The section "What FRED holds and never sends" and open item 4**

(a) Between "The short version" and "Destinations" (`:26-30`). Replace:

```
  honoured by the server as well as the app.

---

## Destinations
```

with:

```
  honoured by the server as well as the app.

---

## What FRED holds and never sends

FRED reads the payroll deposits on a user's linked bank account to detect their paycheck, keeps
that payroll data on its own servers, and never sends a deposit, its amount, its date or the
employer's name to analytics or to any other third party.

To tell the paycheck apart from everything else, FRED keeps every incoming deposit on the user's
checking, savings and other deposit accounts at that bank, not only paychecks, so a transfer from
a friend is kept the same way. That data, in full:

- the name of whoever paid each deposit (for a paycheck, the employer) as the bank and Plaid
  describe it, a normalized form of that name FRED uses to recognise the same payer again, and a
  shorter display name FRED derives from it;
- each deposit's amount and its date;
- the pay cadence FRED works out from those dates (weekly, every two weeks, twice a month or
  monthly) and the date it expects the next one;
- for each pay period, the deposit it matched and what a paycheck-based schedule would have
  invested from it.

It arrives from the bank through Plaid, over the connection the user already made when they linked
their bank, and it is stored against their account. The App Store label therefore declares it as
Other Financial Info, linked to the user, used for app functionality; see
[privacy-manifest-and-label.md](privacy-manifest-and-label.md).

How long it is kept:

- Individual deposit records older than 24 months are deleted by the nightly job that runs while
  Paycheck Autopilot is switched on. The per-pay-period records, which keep that period's deposit
  amount, stay until one of the events below.
- When the bank connection breaks and the user has to sign in to their bank again, the deposit
  records are deleted and the bank's full descriptions are blanked. The display name, the
  normalized payer name, the cadence, the typical and most recent amounts, and the per-pay-period
  records are kept so that detection can resume when they reconnect.
- When the user revokes FRED's access at their bank, or links a different bank, everything
  detected from the old connection is deleted. If they withdraw access to just one account that
  FRED does not invest from, that account's deposit records are marked removed and then fall
  under the 24-month rule above.
- When the account is deleted, all of it is deleted with the user row.
- When the broker rejects the application, FRED disconnects the bank and reads no new deposits,
  but what it already holds stays with the rest of the rejected application (see "Retention"):
  deposit records until the 24-month rule, the rest until the account is deleted.

One analytics event concerns paychecks at all. `paycheck_stream_switched` says that the paycheck
FRED follows moved to a different payer, and carries only the new pay cadence as a word (such as
`biweekly`) and the word `succession`. No name, amount, date or identifier is in it.

Whether the employer's name also counts as employment information, which may need its own App
Store declaration, is with counsel; see open item 4.

---

## Destinations
```

The first paragraph is the one plain sentence the contract asks for. The rest is there because the cadence word does reach analytics through T11's event (so the sentence alone would be wrong about cadence) and because counsel needs the retention rules of spec 7.9 next to the list.

(b) Open items, after `:253`. Replace:

```
   nothing today would tell us that an event stopped arriving.
```

with:

```
   nothing today would tell us that an event stopped arriving.
4. **Payroll deposits have not been reviewed by counsel.** The payroll data in "What FRED holds and
   never sends" is new collected data. Andy takes it to counsel with the other privacy questions,
   including whether the employer's name must also be declared as employment information, whether
   the cadence word on `paycheck_stream_switched` means the declaration also needs an analytics
   purpose, and the fact that deposits other than paychecks, which can carry another person's
   name, are kept too. The privacy policy must list it before Paycheck Autopilot moves any money
   (the design puts that before its Phase 2).
```

- [ ] **Step 9: Run the check; everything passes**

Run the Step 3 command. Expected, exactly:

```
PASS manifest lints
PASS manifest still says no tracking
PASS manifest declares three collected types
PASS analytics entries unchanged and not linked
PASS third type is Other Financial Info
PASS third type is linked
PASS third type is not tracking
PASS third type has exactly one purpose
PASS third type purpose is app functionality
PASS manifest names OtherFinancialInfo exactly once
PASS manifest header no longer says analytics only
PASS privacy doc has the declared payroll row
PASS privacy doc owes the employer-name question to counsel
PASS privacy doc label answer for Financial Info
PASS privacy doc no longer says the manifest covers analytics only
PASS what-we-send has the section once
PASS section sits after The short version and before Destinations
PASS what-we-send carries the plain sentence
PASS what-we-send lists the counsel open item
PASS no em dash in any added line
failures=0
exit=0
```

If `no em dash in any added line` fails, find the line with `git diff HEAD -- FREDdocs/privacy-manifest-and-label.md FREDdocs/what-we-send.md frontend/ios/App/App/PrivacyInfo.xcprivacy | grep -n $'^+.*\xe2\x80\x94'` and rewrite it as a sentence, a comma, a colon or parentheses.

- [ ] **Step 10: Confirm nothing else moved**

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
plutil -lint frontend/ios/App/App/PrivacyInfo.xcprivacy
grep -c OtherFinancialInfo frontend/ios/App/App/PrivacyInfo.xcprivacy
git diff HEAD --stat -- FREDdocs/privacy-manifest-and-label.md FREDdocs/what-we-send.md frontend/ios/App/App/PrivacyInfo.xcprivacy
git diff HEAD -- FREDdocs/privacy-manifest-and-label.md FREDdocs/what-we-send.md frontend/ios/App/App/PrivacyInfo.xcprivacy | grep '^-' | grep -v '^---'
```

Expected: `frontend/ios/App/App/PrivacyInfo.xcprivacy: OK`; `1`; the stat lists exactly the three files (`3 files changed`); and the removed lines are only the old text of the replacements above: in the manifest the two header-comment lines of 4(a) and the `analytics is incomplete...` line of 4(b) (no `<key>`, `<string>`, `<dict>` or `</array>` line is removed, because 4(c) re-adds its quoted tail unchanged); in `privacy-manifest-and-label.md` the old `2026-10-24. **Owner:**` line, the two old lines of 6(d) (`The manifest covers analytics. It does ...` and `that has to be declared before any submission. ...`), the old `| Other financial info (portfolio, contributions) |` row and the old items `4.` to `6.` (the continuation line `   this work.` survives as context); in `what-we-send.md` only the old `**Last reviewed:**` line. No backend or frontend suite reads these files, so no gradle or karma run is needed for this task; `npm run test:events-documented` reads `FREDdocs/analytics-events.md`, which this task does not touch.

- [ ] **Step 11: Record the manual prerequisite for the plan header**

The plan header's Manual prerequisites list (in `FREDdocs/plans/2026-11-02-paycheck-autopilot-phase-1.md`, owned by the header) carries these two lines; if the header does not have them when you execute this task, report it to the controller rather than editing the header yourself:

```
- [ ] Andy raises the payroll-deposit data, and whether the employer name is Employment Info, with counsel (T22; `FREDdocs/what-we-send.md` open item 4). Before Phase 1 ships, per handoff 8.2.
- [ ] Before the first App Store submission, answer Financial Info, Other Financial Info, in App Store Connect exactly as label item 4 of `FREDdocs/privacy-manifest-and-label.md` says (app functionality, linked, not tracking).
```

- [ ] **Step 12: Commit**

Hunk-verify each file with `git diff HEAD -- <file>` (shared tree; stage nothing you did not write), then:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
git add FREDdocs/privacy-manifest-and-label.md FREDdocs/what-we-send.md frontend/ios/App/App/PrivacyInfo.xcprivacy
git status --short
git commit -m "docs(privacy): declare the payroll deposits Paycheck Autopilot holds"
```

`git status --short` must show `M ` (staged) for exactly those three paths; any other staged path belongs to someone else and must not be in this commit (commit by pathspec, `git commit -m "..." -- <the three paths>`, if one appears). The executing session appends its own attribution trailer to the message. Delete `${TMPDIR:-/tmp}/t22-privacy-check.sh` once the Verification commands below have run (its first command uses it); it was never in the repository.

## Verification

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
bash "${TMPDIR:-/tmp}/t22-privacy-check.sh"; echo "exit=$?"     # before the commit (the em dash rule reads git diff HEAD)
plutil -lint frontend/ios/App/App/PrivacyInfo.xcprivacy
grep -c OtherFinancialInfo frontend/ios/App/App/PrivacyInfo.xcprivacy
plutil -extract NSPrivacyCollectedDataTypes raw -o - frontend/ios/App/App/PrivacyInfo.xcprivacy; echo
git show --stat --format='%s' HEAD                               # after the commit
git show HEAD | grep '^+' | grep -c $'\xe2\x80\x94'              # after the commit
```

Expected: 20 `PASS` lines, `failures=0`, `exit=0`; `frontend/ios/App/App/PrivacyInfo.xcprivacy: OK`; `1`; `3`; the commit subject `docs(privacy): declare the payroll deposits Paycheck Autopilot holds` with exactly the three files; `0` em dashes added. No gradle or karma command applies: the task changes no compiled or tested source.

## Produces

- `frontend/ios/App/App/PrivacyInfo.xcprivacy`: a third `NSPrivacyCollectedDataTypes` entry, `NSPrivacyCollectedDataTypeOtherFinancialInfo`, `NSPrivacyCollectedDataTypeLinked` true, `NSPrivacyCollectedDataTypeTracking` false, `NSPrivacyCollectedDataTypePurposes` = [`NSPrivacyCollectedDataTypePurposeAppFunctionality`]; the two analytics entries unchanged and not linked; `NSPrivacyTracking` still false.
- `FREDdocs/privacy-manifest-and-label.md`: declared row "Other financial info: payroll deposits" (collected, linked, not for tracking, app functionality); owed row "Employer name, if counsel says it needs its own declaration." (Andy, with counsel); the amended "Other financial info (portfolio, contributions)" owed row; label answer "4. **Financial Info:** Other Financial Info (payroll deposits)." with the following items renumbered 5 to 7.
- `FREDdocs/what-we-send.md`: section `## What FRED holds and never sends` between "The short version" and "Destinations", opening with the plain sentence; open item 4, "Payroll deposits have not been reviewed by counsel."
- For the plan header's Manual prerequisites: the two checkbox lines of Step 11.
