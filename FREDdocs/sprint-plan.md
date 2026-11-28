# FRED sprint plan

Written 2026-11-27. The goal is a production-ready FRED in 8 weekly sprints, ending in late
January 2027. The numbers come from `production-readiness-scorecard.md`; ids such as B21 or K13 are
its item ids.

## How it works

- **The target is 12.5% of the scorecard a week.** The scorecard holds 506 points today, so that
  is about 63 points a week. Small items are 1 point, medium 3, large 8.
- **Andy approves each sprint's list up front.** After that the items are done one after another
  without asking again. Each still gets the same treatment: tests that fail first, the full test
  run, an independent review, a push to develop, a green CI run and a scorecard update.
- **A sprint runs Friday to Friday.** Sprint 1 ends Friday 2026-12-04. Sprint 8 ends Friday
  2027-01-22, which leaves one spare week before the end of January.
- **Order inside a sprint:** most points per hour of work first. A large item that nothing blocks
  goes ahead of small ones when they are close, and small items are done in batches.
- **Anything that needs a production setting, a secret or real data stops and waits for Andy.**

## Where things stand

| | Points |
|---|---|
| Done | 52 of 506 (10.3%) |
| Left | 454 |
| Left and waiting on an outside party (counsel, Alpaca, Apple, a vendor) | 106 |
| Left and waiting on a decision from Andy (and not on an outside party) | 70.5 |
| Left with nothing in the way | 277.5 |

## Sprint 1: 2026-11-27 to 2026-12-04 (66.5 points)

In the order they will be done. "Needs Andy" lines are collected again in the next section.

### 1. First batch, already done on 2026-11-27 (5.5 points)

| Id | Size | Points | In plain words |
|---|---|---|---|
| B14 | small | 1 | The screen for uploading an ID document accepted any file of any size and passed it to the brokerage. A person could send a huge or harmful file. It now takes only a photo or PDF up to a set size, and every request to the server has a size limit. |
| L4 | medium | 3 | When something failed, the server sometimes answered with its own internal error text, including raw messages from the brokerage and the bank. A user could see confusing technical text, and an attacker could learn how the system is built. Every failure now gets a plain sentence, and the detail is kept in the server's log. |
| K19 | small (half) | 0.5 | A failure that no part of the server expected had no safety net. There is now one on the server: it records the full detail and answers with a plain sentence. The matching safety net in the app is still to do. |
| J7 | small | 1 | A chat request with an overlong conversation id made the server fail, after the message had already been counted against the user's daily limit. It is now turned away as a bad request before anything is counted. |

### 2. Bank links without account numbers: B21, medium, 3 points (FRED-241)

Today, when someone links a bank, FRED's server reads their full account and routing number and
passes them to the brokerage. If FRED's server or logs were ever exposed, those numbers would be
too. The fix asks the bank-link service for a one-time pass that only the brokerage can use, so
FRED never sees a full account number. Bank links that already exist keep working.

### 3. Check the bank balance before every pull: K13, large, 8 points (FRED-244)

FRED pulls money from a user's bank without checking the money is there. If it is not, the pull
bounces: the user can be charged a fee by their bank, and the brokerage has said it requires this
check. The fix looks at the live balance before every pull and skips the pull, with a calm notice,
when there is too little. **Needs Andy** (see below).

### 4. No duplicate buys after a crash: F2, large, 8 points (FRED-223)

If the server restarts at the wrong moment while buying investments, it can place the same buy
twice with a user's money. The fix gives every buy a fixed identity that the brokerage recognises,
so a repeat is refused, and stops two copies of the job from running at once.

### 5. Money path, three medium items (9 points)

| Id | Size | Points | In plain words |
|---|---|---|---|
| F3 | medium | 3 | If a bank pull request times out, FRED records it as failed, but the brokerage may have gone ahead and pulled the money. The user's money would have moved with no record in FRED. The fix treats a timeout as "unknown", checks with the brokerage, and records what really happened. |
| F4 | medium | 3 | A buy order that only partly fills, or expires, leaves that investment stuck forever with money sitting uninvested and no one told. The fix keeps checking such orders and finishes or closes them properly. (FRED-222) |
| F7 | medium | 3 | Several timed jobs that place orders have nothing to stop two copies running at once if the server is ever scaled up, which would double the orders. The fix adds a lock to each. |

### 6. Stop the schedule when a pull bounces: F1, large, 8 points

When a bank pull is returned, for example for too little money or a closed account, FRED carries on
as if nothing happened and tries again next payday, and a return that arrives days later is never
noticed. A user could rack up bank fees and FRED could be left owing the brokerage. The fix pauses
the schedule when a pull is returned, tells the user how to fix it, and keeps watching for late
returns.

### 7. Small server fixes, one batch (9 points)

| Id | Size | Points | In plain words |
|---|---|---|---|
| L2 | small | 1 | Some parts of the server accept requests from any website. A harmful site could try to use a signed-in user's session. The fix limits requests to FRED's own addresses. |
| L3 | small | 1 | Old test and password sign-in doors are still open on the server, although the app only uses passkeys. Each unused door is one more thing to attack. The fix removes them. |
| L8 | small | 1 | Some calls to the brokerage will wait forever if it stops answering, which can freeze parts of FRED. The fix sets a time limit on every call. |
| B17 | small | 1 | The sign-up and recovery screens answer differently when an email already has an account. A stranger could check whether someone is a FRED customer. The fix makes the answers look the same. |
| F10 | small | 1 | The checks on how much money can be moved in are loose and differ from screen to screen. A typo or a bug could pull far more than intended. The fix applies one set of limits everywhere, including a daily cap. |
| F12 | small | 1 | Accounts are meant to be cash only, with no borrowing. If switching borrowing off fails when the account is opened, it is never tried again. The fix records whether it worked, retries, and blocks trading until it has. |
| I23 | small | 1 | A user can save a custom portfolio with a ticker symbol that does not exist or cannot be bought, and only find out when their money fails to invest. The fix checks each symbol with the brokerage when the portfolio is saved. |
| I28 | small | 1 | The monthly market email job has no off switch and no guard against running twice, and it pays for outside services even where no email is really sent. The fix adds both. |
| B22 | small | 1 | Two server answers include the brokerage's internal account id, which the app does not need. The less of that leaves the server the better. The fix removes it. |

### 8. Small app fixes, one batch (11 points)

| Id | Size | Points | In plain words |
|---|---|---|---|
| G2 | small | 1 | If someone submits their identity details twice, the app shows a raw error and they are stuck. The fix treats "already submitted" as success and moves them on. |
| G6 | small | 1 | If saving the chosen plan fails at the end of sign-up, the new user is locked out behind a button that does nothing. The fix does not let sign-up finish without a saved plan, and gives the button somewhere to go. **Needs Andy** (see below). |
| G8 | small | 1 | The profile screen greets everyone with the literal words "Hi, Firstname". The fix shows the person's real first name. |
| G11 | small | 1 | The security screen asks the server for the account number in a way the server does not support, so it never shows. The fix adds the missing piece or removes the row. |
| G13 | small | 1 | A one-time investment goes ahead on a single tap, with no "are you sure". A slip of the thumb moves real money. The fix adds a confirm step that states the amount and the bank. |
| G14 | small | 1 | The change-bank screen shows errors inside a green "success" box and leaves the user stuck if the bank link cannot start. The fix shows errors as errors and makes "Try again" work. |
| G15 | small | 1 | The security screen says "Verified" for everyone, whatever their real status, and some buttons fail silently. The fix shows the real status and tells the user when something did not work. |
| G18 | small | 1 | If the app cannot load how far a new user got in sign-up, it drops them into the main app as if they had finished. The fix keeps them where they are with a retry. |
| G21 | small | 1 | If a phone has no passkey the server accepts, the lock screen has no way out. The fix adds a quiet "Trouble signing in?" link that leads to account recovery. |
| E12 | small | 1 | When tax documents fail to load, the screen says there are none, and it only looks back three years. A user could believe a tax form does not exist. The fix shows a "could not load, try again" message and shows every year. |
| K6 | small | 1 | A few places in the app still say "coming soon". The fix removes or replaces them. **Needs Andy** (see below). |

### 9. Small iPhone build fixes, one batch (5 points)

| Id | Size | Points | In plain words |
|---|---|---|---|
| C4 | small | 1 | The build that would be sent to Apple is set to the developer version, which would not work for real users. The fix sets it to the release version. |
| C6 | small | 1 | The app can open the camera for an ID photo but never says why, and iOS closes any app that does that. The fix adds the required explanation. |
| C8 | small | 1 | Switches that fill the app with fake demo data are saved in the "on" position. A release could go out showing made-up balances. The fix saves them off and makes a release build fail if any is on. |
| K7 | small | 1 | The app claims to run on an older iPhone system that it has not been built or tested for. The fix raises the minimum to one that is supported. |
| L1 | small | 1 | The app writes technical notes, including sign-in details, to a hidden log on the phone. The fix strips those from the release version. |

**Needs Andy** for this batch: a look at the result on a real iPhone (see below).

## Blocked items in Sprint 1: what is needed from Andy

1. **K13, balance check (8 points).** Please confirm that Plaid Balance is switched on for the FRED
   Plaid account, for both sandbox and production. Without it the check can be built and tested
   with stand-ins, but not proven against the real service.
2. **B21, bank links without account numbers (3 points).** The last step is to link a test bank and
   see the link approved in the brokerage's test system. That uses the sandbox keys, which I do not
   touch. Either run that check yourself when the code lands, or say that I may run it against the
   sandbox.
3. **B14, already done: one check still owed.** While reading the brokerage's documentation I found
   that the ID upload had been sending the document in a form the brokerage does not accept. It is
   fixed to match the documentation, but it has not been tried against the brokerage's test system.
   Same question as item 2.
4. **G6, plan not saved at sign-up (1 point).** What should the "Reactivate" button do today, with
   no billing yet: go back to the plan picker, or contact support? Recommendation: the plan picker.
5. **K6, "coming soon" placeholders (1 point).** For each of the five, remove it or replace it with
   something? Recommendation: remove all five; I will list them before changing anything.
6. **C4, C6, K7, L1, iPhone build batch (4 points).** I can make and test the changes, but only a
   build on your iPhone proves them. Please run one build after the batch lands.
7. **Not in this sprint, but it gates Sprint 3:** the iPhone test for FRED-242 (link the sandbox
   test bank from the phone and see whether it returns to FRED), and which build is the beta build
   (scorecard decisions 1 and 2).

Nothing else in Sprint 1 waits on anyone.

## Sprints 2 to 8, in outline

| Sprint | Ends | Theme | Main items |
|---|---|---|---|
| 2 | 2026-12-11 | Finish money safety, protect money actions | Server checks and records for withdraw and sell (F5), a nightly check that FRED's records match the brokerage (F9), a freeze switch (F8), the activity screen (F6), a fresh passkey for every money action (B9), smaller money items (F11, F13, L16, L17, L9). |
| 3 | 2026-12-18 | A build that can ship, and a place to run it | The real release build and passkeys inside it (C1, C2, C3, C5, C7), crash reporting, health checks, backups and key rules (H2 to H6), the production server itself (H1, needs Andy and the host). FRED-242 if the phone test says it is needed. |
| 4 | 2026-12-25 | Tell users what happened | A real email provider (D1), notices when something fails or money moves (D3, D5), the payday email fixes (D4, D6, D7), notification settings (D9, E14), shared email layout (D8). Short week: expect fewer points. |
| 5 | 2026-01-01 | Sign-up and account life | Status screens for pending and rejected applicants (G1), age and citizenship checks (G4), the document request flow (G5), proper empty and error states (G7), account deletion that meets Apple's rule (K1), email ownership at sign-up (B11), prefilled sign-up from the bank (G22). Short week. |
| 6 | 2026-01-08 | Plans and what each plan promises | A real subscription record (I2), plan limits enforced on the server (I3), plan names and prices made consistent (I9), the per-paycheck price maths and screen (I32, first half), and every "build it or stop promising it" decision turned into code or copy (I11 to I19). |
| 7 | 2026-01-15 | App Store readiness | Forced update and maintenance mode (K4), offline state (K8), accessibility (K16), all tests running in CI (K9, K20), the legal text slots (E1 to E11, text from counsel), chat hardening (J2, J3, G17), remaining "fix soon" items. |
| 8 | 2026-01-22 | Billing, outside sign-offs, device pass | Fee collection and plan management if the outside answers are in (I4, I5, I20, I32), push notifications (K2), tax-loss harvesting go-live gates (I12, I30), the full real-iPhone pass (K17), and whatever the earlier sprints uncovered. |

## Honest check: is 8 weeks realistic?

**For the work that depends only on us, it is possible but tight. For the whole scorecard, no, not
by effort alone.**

- **106 of the 454 points left wait on someone outside** (counsel, the brokerage, Apple, a
  vendor): legal text, billing sign-offs, the production brokerage keys, push notification setup,
  tax-loss harvesting approval. No amount of building finishes these. They finish when the answers
  arrive, so the requests need to go out now, not in sprint 7.
- **Another 70.5 points wait on a decision from Andy** (33 open decisions on the scorecard). Most
  are quick to answer, and several get smaller once answered: "build the feature or change the
  wording" is often 8 points one way and 1 the other.
- **277.5 points have nothing in the way.** At 63 points a week that is four and a half weeks of
  work, so the first four sprints are safe from running dry.
- **The pace so far is lower than the target.** From 2026-11-25 to 2026-11-27, about 16 points
  were finished: roughly 5 a day, or 35 a week. The target needs 9 a day. Chaining items without
  waiting for a go-ahead removes most of the idle time, so 45 to 60 a week is a fair expectation.
  A steady 63 is a stretch, mostly because the large money items need care and each one ends with a
  check against a test system or a real phone.
- **The list grows as we work.** In those three days, fixing things uncovered 6 new items worth 8
  points, and five new stories added 37 more. Expect 10 to 15 points a week of newly found work,
  plus whatever new stories are added. That alone can eat a fifth of a sprint.
- **Two holiday weeks** (sprints 4 and 5) sit in the middle.

What this means in practice:

1. The app-only work (400 points, 52 done) can plausibly be finished by late January if decisions
   are answered within a day or two and no large new stories are added. A slip of one to two weeks
   is the likely case.
2. "Fully production ready", including the 106 outside points, depends on counsel, the brokerage
   and Apple. Late January is realistic only if those requests are sent this week.
3. The percentage will not rise in a straight line. Weeks that find new work will show less than
   12.5% even when 63 points were finished. The plan tracks points finished per week as well as the
   percentage, so the two are not confused.

## Keeping this current

At the end of each sprint: record the points finished, move anything unfinished to the top of the
next sprint, and write the next sprint's list in the same detail as Sprint 1 for Andy to approve.
