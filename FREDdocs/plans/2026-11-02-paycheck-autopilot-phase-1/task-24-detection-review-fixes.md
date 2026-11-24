# Task 24: detection review fixes

Three defects the per-task reviews of T10 and T12 found in code the plan itself mandated. Each is small, each is shadow-data only today, and each would become user-visible in Phase 2 (a wrong rejection, a merged payer, a false "no paycheck" state), so they are fixed now. Added by the controller during execution; runs after T12 and before T13.

Facts this task depends on: Phase 1 is shadow mode, so nothing here moves money, writes `InvestmentSchedule.status`, or writes a hold. The schedule fields touched are the detection mirror only (`detectionState`, `missedCount`, `dormantSince`), written inside the existing transaction under the user row lock. No analytics event is added or changed. Run database-gated tests through `.superpowers/phase1-planning/suite.sh`.

**Files:**
- Modify: `backend/src/main/java/com/investingapp/backend/service/PlaidRecurringService.java` (`statusWire`, the token skip in `apply`)
- Modify: `backend/src/test/java/com/investingapp/backend/service/PlaidRecurringServiceTest.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/PaycheckCycleService.java` (`markMissesForUser`)
- Modify: `backend/src/test/java/com/investingapp/backend/service/PaycheckCycleServiceTest.java`

**Interfaces:** consumes T09 (`PayerTokenizer.UNKNOWN`, `HardGates`), T10 (`PlaidRecurringService`), T12 (`PaycheckCycleService.markMissesForUser`, `MissDetector`). Produces no new public name and changes no signature.

## Fix A: an unrecognized Plaid stream status must not reject the stream

Today `PlaidRecurringService.statusWire` turns `null` and `TransactionStreamStatus.ENUM_UNKNOWN` (the SDK's catch-all for a value newer than plaid-java 46.0.0) into the string `"UNKNOWN"`, and `HardGates` rejects `"UNKNOWN"` as `PLAID_TOMBSTONED`. Plaid's real `UNKNOWN` status is a statement about the stream and stays a rejection (spec 4.2 gate 3). A missing or unrecognized status is the absence of information and must not reject: T09 already scores it as zero points.

- [ ] Step A1. In `PlaidRecurringServiceTest`, add `unrecognizedOrMissingStatusIsStoredAsNoStatus`: a stream with `status = TransactionStreamStatus.ENUM_UNKNOWN` and one with `status = null` are each stored with `plaidStatus == null`, and the snapshot JSON carries a JSON null for `status`; a stream with `TransactionStreamStatus.UNKNOWN` is still stored as `"UNKNOWN"`. Run it and see it fail on the first two.
- [ ] Step A2. Change `statusWire` to return `null` for `null` and `ENUM_UNKNOWN`, and the wire value otherwise. Update its javadoc to say why. In `snapshotJson`, write the status with a null-safe put (a JSON null when absent). Leave `frequencyWire` as it is: `UNKNOWN` is a real Plaid frequency and the cadence gate handles it. Check every reader of `getPlaidStatus()` in `backend/src/main/java` (grep) handles null without a NullPointerException; `HardGates` compares with a constant on the left, which is null-safe.
- [ ] Step A3. Run `PlaidRecurringServiceTest`; expect the previous count plus 1, all green.

## Fix B: a stream with no usable payer token is skipped, not seeded

`PayerTokenizer.token` never returns blank: it returns `PayerTokenizer.UNKNOWN` when the description normalizes to nothing. The `isBlank(token)` skip in `PlaidRecurringService.apply` therefore never fires, and every such stream on an account is seeded onto one shared `UNKNOWN` row. The javadoc and the plan both say these streams are skipped.

- [ ] Step B1. Add `streamWithoutAUsablePayerTokenIsSkipped` to `PlaidRecurringServiceTest`: two inflow streams on one account whose descriptions normalize to nothing (for example digits only, with no merchant name) seed no `PaycheckStream` row, and a normal stream in the same read is still seeded. See it fail.
- [ ] Step B2. In `apply`, skip when the token is blank or equals `PayerTokenizer.UNKNOWN`. Count the skipped streams in the existing outcome if it already has a skipped or ignored counter; do not add a field if it has none.
- [ ] Step B3. Run `PlaidRecurringServiceTest`; expect one more test, all green.

## Fix C: a missed period does not make a stream dormant when a later deposit already arrived

Spec 4.7 rule 5 lets a qualifying deposit that lands outside every open window open and confirm a new period. The earlier period's cycle stays `OPEN`, and the nightly miss pass later marks it `MISSED` and sets the stream and the schedule mirror to `DORMANT`, although a paycheck arrived after that period. In Phase 2 that state sends a "no deposit since" notice and writes a hold. Spec 6.4 defines a miss as a window that passed "with no qualifying deposit"; dormancy is for a payer that has gone quiet, so it requires that nothing has arrived since.

Rule: in `PaycheckCycleService.markMissesForUser`, an overdue `OPEN` cycle is always closed as `MISSED` (the period really had no deposit). The stream side effects (`missedCount` increment, `PaycheckStreamState.DORMANT`, the mirror's `detectionState`, `missedCount` and `dormantSince`) are applied only when the stream has no deposit dated after the missed cycle's `expectedDate`. Use the stream's own last-deposit field that T11 maintains (`PaycheckStream.lastDate`); if at HEAD that field is not updated when a deposit is recorded for a stream, use the newest `paycheck_events` posted date for the stream instead, and say which in the report. A stream that is already `DORMANT` and gets a second overdue cycle with no later deposit still increments `missedCount` as today. The next cycle is still opened for a PRIMARY stream exactly as today.

- [ ] Step C1. In `PaycheckCycleServiceTest`, add `missedPeriodWithALaterDepositDoesNotMakeTheStreamDormant`: a trusted PRIMARY biweekly stream whose last deposit date is after an overdue `OPEN` cycle's expected date; after `markMissesForUser` the cycle is `MISSED`, the stream state and `missedCount` are unchanged, and the schedule mirror's `detectionState`, `missedCount` and `dormantSince` are unchanged. Add `missedPeriodWithNoLaterDepositStillMakesTheStreamDormant` as the mirror case if no existing test already pins it by that meaning (check first; do not duplicate). See the new case fail.
- [ ] Step C2. Implement the rule with the smallest change inside the loop in `markMissesForUser`. Keep `MAX_MISSES_PER_RUN`, the ordering, and the INFO log line (counts only).
- [ ] Step C3. Run `PaycheckCycleServiceTest` and `PaycheckDetectionCycleWiringTest`; all green with the added tests.

## Verification

- `.superpowers/phase1-planning/suite.sh` with no arguments: failures=0, errors=0, skipped=0, and the total is the count before this task (1048) plus the tests added (state the number).
- `PhaseOneMoneyInvarianceTest` unchanged and green.
- A search for the em dash character (U+2014) over the four files finds nothing new.

## Commit

One commit, staging exactly the four files:

```
fix(paycheck): unknown Plaid status, tokenless streams, and misses after a later deposit
```

Body in plain sentences, one per fix.

## Produces

No new public names. Behaviour changes: `PaycheckStream.plaidStatus` is null for a missing or unrecognized Plaid status; a Plaid stream with no usable payer token is not seeded; a missed period makes a stream dormant only when no deposit has arrived after it.
