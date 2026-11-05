### Task 13: plaid-item-state

One service, `PlaidItemStateService`, becomes the only webhook- and error-driven writer of `users.plaid_item_status`, `plaid_item_error_code`, `plaid_disconnect_at` and `plaid_webhook_acked_at`, the only writer of the three Item-state holds (`ITEM_LOGIN_REQUIRED`, `PERMISSION_REVOKED`, `CONSENT_REQUIRED`), the home of the update-mode consent writes, and the home of the relink reset that the exchange calls inside its own transaction (spec 5.7 in both modes, spec 2.3, 2.4 step 3, 7.9). Nothing here calls Plaid or Alpaca, moves money, writes `InvestmentSchedule.status` or `mode`, or touches `isReadyForInvestment`. T15 and T17 keep their own `plaid_item_status = REMOVED` writes beside `enqueueRemoval`, and T15 its `consent_expiration_time` write. Callers: T14 (webhook dispatcher, `ITEM_ERROR` routing), T15 (reconcile `/item/get` fallback), T16 (`update-mode-complete`).

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it under `backend/src/test/java/com/investingapp/backend/`. Line numbers are HEAD 137998c; no earlier Phase 1 task edits `PlaidService.java` or its two unit tests, and every anchor also quotes its line. `PhaseOneMoneyInvarianceTest` (T03) and `ops/OpsAlerts` (T04) are anchored on quoted text. Run every command block from the repository root.

**Facts this task depends on (restated from the handoff and rulings, so you need nothing else):**

- `investment_schedules.is_paused` no longer exists. The user's stop is `InvestmentSchedule.status` (`PENDING_BANK | ACTIVE | PAUSED_BY_USER`); the system's stop is `hold_reason`, and the two are orthogonal: neither ever writes the other. Never call the deprecated `getIsPaused()` or `isPausedForAnyReason()`. This task never calls `setStatus(` with a `Status.` argument or `setMode(` (T03's `phaseOneSourcesNeverCallSetStatusOrSetMode` walks `service/Plaid*` and fails on either).
- Money moves only when `isReadyForInvestment(todayET)` passes: `status == ACTIVE`, `achRequestId != null`, no hold in `BLOCKING_HOLDS` (`ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`), date due. The three Item-state holds are NOT blocking, so R2 lets this task write them in both modes: a MANUAL user keeps investing under shadow.
- A KYC rejection sets `MANUAL_REVIEW` (handoff 2.1), the only reliable stop for a rejected applicant. Decision 30: an Item-state hold is written ONLY when `holdReason == null`; a repair or consent path clears a hold ONLY when it equals the hold that path writes. An unconditional write would replace `MANUAL_REVIEW` or an ACH return hold with a non-blocking one and unblock money.
- R3: a non-blocking hold emits no analytics event; this class has no `AnalyticsOutboxService` dependency, and a test pins that. R1: detection outcomes are mirrored onto the schedule row in every mode; `mode` stays MANUAL under shadow.
- R2 and decision 33: `TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED` sets `SYNC_LIMIT`, mirrors UNDETECTABLE, raises an ops alert and writes NO hold in any mode in Phase 1 (the `MANUAL_REVIEW` hold is a Phase 2 seam on `onSyncLimit`).
- R18: `RECONNECT` is for users who can invest: nothing when the account is REJECTED or the user has no schedule row (still onboarding).
- Spec 5.7: a handler writes only when the call's `item_id` equals `users.plaid_item_id`, else it logs `plaid.stale_item_event` and writes nothing. No Plaid HTTP, email or ops-alert delivery inside a DB transaction (decision 18).
- Logging rule (spec 12.3): INFO and above carry FRED's numeric user id, handler names, states, Plaid error codes and booleans only; never a Plaid item, account or transaction id, a cursor, a token, a descriptor, a payer name, an amount or a date. The ops alert uses the same vocabulary.
- This task adds no property, so the gitignored `application-local.properties` needs no edit.

**Files:**
- Create: `.../service/PlaidItemStateService.java`
- Modify: `.../service/PlaidService.java` (field after `:101-102`, the quoted `private PlaidItemLifecycleService plaidItemLifecycleService;`; one call after `:256`, the quoted `accountStatusService.handleBankAccountChange(appUser);`, inside `exchangePublicTokenAndLinkUser`'s `@Transactional` and after its last Plaid and Alpaca call); `.../model/PlaidItemStatus.java:3` (comment); `.../ops/OpsAlerts.java` (one constant, `PLAID_SYNC_ITEM_LIMIT`, before the quoted `    private OpsAlerts() {`)
- Test: create `backend/src/test/java/com/investingapp/backend/service/PlaidItemStateServiceTest.java`; modify `backend/src/test/java/com/investingapp/backend/service/PlaidServiceExchangeLifecycleTest.java` (`:60`, `:71`, `:81` plus one test), `backend/src/test/java/com/investingapp/backend/service/PlaidLinkedAnalyticsTest.java` (`:83`), `backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java` (one method, `onlyAllowlistedFilesCallSetHoldReason`). `PlaidLinkedAnalyticsAtomicityTest` needs no edit; Step 9 runs it.

**Interfaces:**
- Consumes (HEAD): `UserRepository.findByIdForUpdate(Long)` (`:81-83`, `Optional<User>`); `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(User)` (`:19`, `Optional`), `save`, `saveAndFlush`; `@Transactional(MANDATORY) PlaidItemLifecycleService.enqueueRemoval(User)` (`:87-88`); `EasternTime.ZONE`, `today()`, `now()` (`:17,23,28`); `User` accessors `plaidItemId`, `plaidAccountId`, `accountStatus`, `plaidItemStatus`, `plaidItemErrorCode`, `plaidTransactionsConsented`, `plaidWebhookAckedAt` (`model/User.java:147-204`); `HoldReason`; `PlaidItemStatus`.
- Consumes (T02): `DetectionState`, `PaycheckStreamState`, `PaycheckCycleState`, `PaycheckSkipReason.HOLD` (all `model/`); `PaycheckStream` (`state`, `rawDescriptor`, `plaidSnapshotJson`, `payerToken`, `cadence`, `anchorDow`, `trailingMedian`), `PaycheckCycle` (`state`, `skipReason`); `User` `plaidSyncCursor`, `plaidHistoryComplete`, `plaidTransactionsUpdateStatus`, `plaidDisconnectAt`, `plaidLastSyncAt`, `plaidStreamsReadAt`; `InvestmentSchedule` `detectionState`, `primaryStreamId`, `nextExpectedDate`, `short missedCount`, `dormantSince`, `detectionStartedAt`; `PaycheckStreamRepository.findByUserIdOrderByIdAsc`, `PaycheckCycleRepository.findByUserIdAndState`, `PaycheckEventRepository.deleteByUserIdAndPlaidAccountId`, `long deleteByUserId(Long)` on all three; `ColumnText.fit(String, int)`.
- Consumes (T03): `NotificationService.sendReconnect(User, LocalDate incidentDate)` (return value not read); `PhaseOneMoneyInvarianceTest`. (T04): `OpsAlertService.raise(String, String, String, Map<String, Object>)` (never throws; async delivery); `ops/OpsAlerts`. (T23, runs before this task): `InvestmentSchedule.isBlockingHold(HoldReason)`; `@DynamicUpdate` on `InvestmentSchedule`.
- Produces: exactly the list in the closing "Produces" section (both enums nested).

Caller contract: `STALE_ITEM` = no write (user missing, or the item is no longer `users.plaid_item_id`); `NO_OP` = guard passed but the state forbids the write (a `REVOKED` or `REMOVED` Item for every handler except `onWebhookAcked`; `onLoginRepaired` from any status but `LOGIN_REQUIRED`); `APPLIED` = committed. RECONNECT and the sync-limit alert go out after commit, only on the transition into the state (a nightly repeat sends nothing), RECONNECT under R18. Called inside an outer transaction, the follow-up is registered `afterCommit` on it.

- [ ] **Step 1: Failing tests for the Item-state handlers**

Create `backend/src/test/java/com/investingapp/backend/service/PlaidItemStateServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 5.7 in both modes, the R2 hold gates, R18, and the relink reset (spec 2.4 step 3, 7.9). */
class PlaidItemStateServiceTest {

    private static final Long USER_ID = 42L;
    private static final String ITEM_ID = "item-current";
    private static final String FUNDING_ACCOUNT = "acc-checking";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
    private final PaycheckEventRepository eventRepository = mock(PaycheckEventRepository.class);
    private final PaycheckCycleRepository cycleRepository = mock(PaycheckCycleRepository.class);
    private final PlaidItemLifecycleService lifecycleService = mock(PlaidItemLifecycleService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final OpsAlertService opsAlertService = mock(OpsAlertService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private PlaidItemStateService service;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new PlaidItemStateService(userRepository, scheduleRepository, streamRepository,
                eventRepository, cycleRepository, lifecycleService, notificationService, opsAlertService, txManager);

        user = new User("item-state@fredvested.com");
        user.setId(USER_ID);
        user.setPlaidItemId(ITEM_ID);
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidAccountId(FUNDING_ACCOUNT);
        user.setAccountStatus("ACTIVE");
        user.setPlaidSyncCursor("cursor-1");

        schedule = new InvestmentSchedule();
        schedule.setUser(user);
        schedule.setMode(ScheduleMode.MANUAL);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        schedule.setAchRequestId("rel-1");
        schedule.setStartDate(EasternTime.today());
        schedule.setNextInvestmentDate(EasternTime.today().plusDays(7));

        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of());
        when(cycleRepository.findByUserIdAndState(eq(USER_ID), any())).thenReturn(List.of());
    }

    private PaycheckStream stream(PaycheckStreamState state) {
        PaycheckStream s = new PaycheckStream();
        s.setState(state);
        s.setPayerToken("acme");
        s.setRawDescriptor("ACME PAYROLL PPD");
        s.setPlaidSnapshotJson("{\"description\":\"ACME PAYROLL\"}");
        s.setCadence(InvestmentFrequency.BIWEEKLY);
        s.setAnchorDow((short) 5);
        s.setTrailingMedian(new BigDecimal("2418.00"));
        return s;
    }

    // 5.7 row 1 and R2: the hold lands on a MANUAL row and money still moves.
    @Test
    void loginRequiredAppliesToManualToo() {
        assertThat(schedule.isReadyForInvestment(EasternTime.today())).isTrue();

        ItemStateResult result = service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.LOGIN_REQUIRED);
        assertThat(user.getPlaidItemErrorCode()).isEqualTo("ITEM_LOGIN_REQUIRED");
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.ITEM_LOGIN_REQUIRED);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.ITEM_BROKEN);
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.ACTIVE);
        assertThat(schedule.isReadyForInvestment(EasternTime.today())).isTrue();
        verify(userRepository).save(user);
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void loginRequiredPurgesEventsAndNullsDescriptors() {
        PaycheckStream s = stream(PaycheckStreamState.CONFIRMED);
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(s));

        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");

        verify(eventRepository).deleteByUserId(USER_ID);
        assertThat(s.getRawDescriptor()).isNull();
        assertThat(s.getPlaidSnapshotJson()).isNull();
        verify(streamRepository).save(s);
    }

    @Test
    void loginRequiredKeepsStreamSkeleton() {
        PaycheckStream s = stream(PaycheckStreamState.CONFIRMED);
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(s));
        PaycheckCycle confirmed = new PaycheckCycle();
        confirmed.setState(PaycheckCycleState.CONFIRMED);
        when(cycleRepository.findByUserIdAndState(USER_ID, PaycheckCycleState.CONFIRMED)).thenReturn(List.of(confirmed));

        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");

        assertThat(s.getPayerToken()).isEqualTo("acme");
        assertThat(s.getCadence()).isEqualTo(InvestmentFrequency.BIWEEKLY);
        assertThat(s.getAnchorDow()).isEqualTo((short) 5);
        assertThat(s.getTrailingMedian()).isEqualByComparingTo("2418.00");
        assertThat(s.getState()).isEqualTo(PaycheckStreamState.CONFIRMED);
        verify(streamRepository, never()).deleteByUserId(any());
        verify(cycleRepository, never()).deleteByUserId(any());
        assertThat(user.getPlaidSyncCursor()).isEqualTo("cursor-1");
        // CONFIRMED skipped for the hold; OPEN never read.
        assertThat(confirmed.getState()).isEqualTo(PaycheckCycleState.SKIPPED);
        assertThat(confirmed.getSkipReason()).isEqualTo(PaycheckSkipReason.HOLD);
        verify(cycleRepository, never()).findByUserIdAndState(USER_ID, PaycheckCycleState.OPEN);
        verify(lifecycleService, never()).enqueueRemoval(any());
    }

    // Row 2 of 5.7.
    @Test
    void revokedPurgesEverythingAndQueuesRemoval() {
        user.setPlaidHistoryComplete(true);

        ItemStateResult result = service.onRevoked(USER_ID, ITEM_ID, "USER_PERMISSION_REVOKED");

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.REVOKED);
        assertThat(user.getPlaidItemErrorCode()).isEqualTo("USER_PERMISSION_REVOKED");
        assertThat(user.getPlaidSyncCursor()).isNull();
        assertThat(user.getPlaidHistoryComplete()).isFalse();
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.PERMISSION_REVOKED);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.REVOKED);
        verify(cycleRepository).deleteByUserId(USER_ID);
        verify(eventRepository).deleteByUserId(USER_ID);
        verify(streamRepository).deleteByUserId(USER_ID);
        verify(lifecycleService).enqueueRemoval(user);
        verify(notificationService, never()).sendReconnect(any(), any());
    }

    @Test
    void revokedClearsMirrorBeforePurge() {
        schedule.setPrimaryStreamId(7L);
        schedule.setNextExpectedDate(LocalDate.of(2026, 11, 13));
        schedule.setDetectionState(DetectionState.CONFIRMED);
        AtomicReference<Long> primaryAtPurge = new AtomicReference<>(-1L);
        AtomicReference<DetectionState> stateAtPurge = new AtomicReference<>();
        when(streamRepository.deleteByUserId(USER_ID)).thenAnswer(i -> {
            primaryAtPurge.set(schedule.getPrimaryStreamId());
            stateAtPurge.set(schedule.getDetectionState());
            return 1L;
        });

        service.onRevoked(USER_ID, ITEM_ID, "ITEM_NOT_FOUND");

        assertThat(primaryAtPurge.get()).isNull();
        assertThat(stateAtPurge.get()).isEqualTo(DetectionState.REVOKED);
        assertThat(schedule.getNextExpectedDate()).isNull();
        InOrder order = inOrder(scheduleRepository, cycleRepository, eventRepository, streamRepository);
        order.verify(scheduleRepository).saveAndFlush(schedule);
        order.verify(cycleRepository).deleteByUserId(USER_ID);
        order.verify(eventRepository).deleteByUserId(USER_ID);
        order.verify(streamRepository).deleteByUserId(USER_ID);
    }

    @Test
    void fundingAccountRevokedIsAFullRevokeOtherAccountDropsOnlyItsEvents() {
        ItemStateResult other = service.onAccountRevoked(USER_ID, ITEM_ID, "acc-savings");

        assertThat(other).isEqualTo(ItemStateResult.APPLIED);
        verify(eventRepository).deleteByUserIdAndPlaidAccountId(USER_ID, "acc-savings");
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(schedule.getHoldReason()).isNull();
        verify(lifecycleService, never()).enqueueRemoval(any());

        ItemStateResult funding = service.onAccountRevoked(USER_ID, ITEM_ID, FUNDING_ACCOUNT);

        assertThat(funding).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.REVOKED);
        assertThat(user.getPlaidItemErrorCode()).isEqualTo("USER_ACCOUNT_REVOKED");
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.PERMISSION_REVOKED);
        verify(streamRepository).deleteByUserId(USER_ID);
        verify(lifecycleService).enqueueRemoval(user);
    }

    // Row 3 of 5.7.
    @Test
    void loginRepairedRestoresOkAndTheStreamState() {
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        user.setPlaidItemErrorCode("ITEM_LOGIN_REQUIRED");
        schedule.setHoldReason(HoldReason.ITEM_LOGIN_REQUIRED);
        schedule.setDetectionState(DetectionState.ITEM_BROKEN);
        schedule.setPrimaryStreamId(7L);
        when(streamRepository.findById(7L)).thenReturn(Optional.of(stream(PaycheckStreamState.CONFIRMED)));

        ItemStateResult result = service.onLoginRepaired(USER_ID, ITEM_ID, RepairPath.LOGIN_REPAIRED);

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(user.getPlaidItemErrorCode()).isNull();
        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);

        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        schedule.setDetectionState(DetectionState.ITEM_BROKEN);
        schedule.setPrimaryStreamId(null);

        service.onLoginRepaired(USER_ID, ITEM_ID, RepairPath.UPDATE_MODE);

        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.SEARCHING);
    }

    @Test
    void loginRepairedIgnoredUnlessLoginRequired() {
        for (PlaidItemStatus status : List.of(PlaidItemStatus.OK, PlaidItemStatus.PENDING_DISCONNECT,
                PlaidItemStatus.SYNC_LIMIT, PlaidItemStatus.NOT_SUPPORTED)) {
            user.setPlaidItemStatus(status);
            schedule.setHoldReason(HoldReason.ITEM_LOGIN_REQUIRED);

            ItemStateResult result = service.onLoginRepaired(USER_ID, ITEM_ID, RepairPath.LOGIN_REPAIRED);

            assertThat(result).isEqualTo(ItemStateResult.NO_OP);
            assertThat(user.getPlaidItemStatus()).isEqualTo(status);
            assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.ITEM_LOGIN_REQUIRED);
        }
        verify(userRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void repairClearsOnlyItsOwnHold() {
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        schedule.setHoldReason(HoldReason.CONSENT_REQUIRED);
        service.onLoginRepaired(USER_ID, ITEM_ID, RepairPath.UPDATE_MODE);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.CONSENT_REQUIRED);

        schedule.setHoldReason(HoldReason.ITEM_LOGIN_REQUIRED);
        service.onConsentGranted(USER_ID, ITEM_ID);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.ITEM_LOGIN_REQUIRED);

        schedule.setHoldReason(HoldReason.MANUAL_REVIEW);
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        service.onLoginRepaired(USER_ID, ITEM_ID, RepairPath.LOGIN_REPAIRED);
        service.onConsentGranted(USER_ID, ITEM_ID);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);

        schedule.setHoldReason(HoldReason.CONSENT_REQUIRED);
        service.onConsentGranted(USER_ID, ITEM_ID);
        assertThat(schedule.getHoldReason()).isNull();
    }

    // Row 4 of 5.7, first trigger.
    @Test
    void consentRequiredFlipsFlagAndHoldsConsent() {
        ItemStateResult result = service.onConsentRequired(USER_ID, ITEM_ID);

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidTransactionsConsented()).isFalse();
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.CONSENT_REQUIRED);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.NEEDS_CONSENT);
        verify(notificationService).sendReconnect(user, EasternTime.today());
    }

    @Test
    void consentGrantedClearsOnlyConsentHoldAndResumesSearching() {
        user.setPlaidTransactionsConsented(Boolean.FALSE);
        user.setPlaidDisconnectAt(LocalDateTime.of(2026, 11, 20, 9, 0));
        schedule.setHoldReason(HoldReason.CONSENT_REQUIRED);
        schedule.setDetectionState(DetectionState.NEEDS_CONSENT);

        ItemStateResult result = service.onConsentGranted(USER_ID, ITEM_ID);

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidTransactionsConsented()).isTrue();
        assertThat(user.getPlaidDisconnectAt()).isNull();
        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.SEARCHING);
        verify(notificationService, never()).sendReconnect(any(), any());
    }

    @Test
    void consentRenewedOnlyFromPendingDisconnect() {
        user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        service.onConsentRenewed(USER_ID, ITEM_ID, OffsetDateTime.parse("2027-05-01T16:00:00Z"));
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(user.getPlaidDisconnectAt()).isEqualTo(LocalDateTime.of(2027, 5, 1, 12, 0)); // EDT

        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        service.onConsentRenewed(USER_ID, ITEM_ID, null);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.LOGIN_REQUIRED);
        assertThat(user.getPlaidDisconnectAt()).isNull();
    }

    // Row 4 of 5.7, second trigger, under R2 and decision 33.
    @Test
    void syncLimitWritesNoHoldInEitherMode() {
        ItemStateResult result = service.onSyncLimit(USER_ID, ITEM_ID);

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.SYNC_LIMIT);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(schedule.getHoldReason()).isNull();
        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_SYNC_ITEM_LIMIT), eq("42"), anyString(), anyMap());

        user.setPlaidItemStatus(PlaidItemStatus.OK);
        schedule.setMode(ScheduleMode.DETECTED);
        service.onSyncLimit(USER_ID, ITEM_ID);
        service.onSyncLimit(USER_ID, ITEM_ID);

        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.DETECTED);
        // One alert per transition.
        verify(opsAlertService, times(2)).raise(eq(OpsAlerts.PLAID_SYNC_ITEM_LIMIT), eq("42"), anyString(), anyMap());
        verify(notificationService, never()).sendReconnect(any(), any());
    }

    @Test
    void notSupportedMarksUndetectableWithNoHoldOrNotice() {
        ItemStateResult result = service.onNotSupported(USER_ID, ITEM_ID);

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.NOT_SUPPORTED);
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(schedule.getHoldReason()).isNull();
        verify(notificationService, never()).sendReconnect(any(), any());
        verify(opsAlertService, never()).raise(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void pendingDisconnectStampsEasternTimeWithoutHold() {
        ItemStateResult result = service.onPendingDisconnect(USER_ID, ITEM_ID,
                OffsetDateTime.parse("2026-11-12T05:00:00Z"));

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.PENDING_DISCONNECT);
        assertThat(user.getPlaidDisconnectAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 0, 0)); // EST
        assertThat(schedule.getHoldReason()).isNull();
        verify(notificationService).sendReconnect(user, EasternTime.today());
    }

    @Test
    void webhookAckedStampsAckTime() {
        user.setPlaidItemStatus(PlaidItemStatus.REMOVED);

        ItemStateResult result = service.onWebhookAcked(USER_ID, ITEM_ID);

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(user.getPlaidWebhookAckedAt()).isAfter(EasternTime.now().minusMinutes(1));
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.REMOVED);
    }

    static Stream<Arguments> handlersOverExistingHolds() {
        List<Arguments> cases = new ArrayList<>();
        for (String handler : List.of("login_required", "revoked", "consent_required")) {
            for (HoldReason existing : Arrays.stream(HoldReason.values())
                    .filter(h -> InvestmentSchedule.isBlockingHold(h) || h == HoldReason.BANK_CHANGE).toList()) {
                cases.add(Arguments.of(handler, existing));
            }
        }
        return cases.stream();
    }

    // Decision 30: MANUAL_REVIEW and the ACH holds keep blocking.
    @ParameterizedTest(name = "{0} over {1}")
    @MethodSource("handlersOverExistingHolds")
    void itemStateHoldNeverReplacesAnExistingHold(String handler, HoldReason existing) {
        schedule.setHoldReason(existing);

        ItemStateResult result = switch (handler) {
            case "login_required" -> service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");
            case "revoked" -> service.onRevoked(USER_ID, ITEM_ID, "USER_PERMISSION_REVOKED");
            default -> service.onConsentRequired(USER_ID, ITEM_ID);
        };

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(schedule.getHoldReason()).isEqualTo(existing);
    }

    // R3: this class cannot reach the outbox at all.
    @Test
    void holdWriteEmitsNoScheduleEvent() {
        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.ITEM_LOGIN_REQUIRED);

        List<Class<?>> fieldTypes = Arrays.stream(PlaidItemStateService.class.getDeclaredFields())
                .map(Field::getType).toList();
        List<Class<?>> constructorTypes = Arrays.stream(PlaidItemStateService.class.getDeclaredConstructors())
                .flatMap(c -> Arrays.stream(c.getParameterTypes())).toList();
        assertThat(fieldTypes).doesNotContain(AnalyticsOutboxService.class);
        assertThat(constructorTypes).doesNotContain(AnalyticsOutboxService.class);
    }

    @Test
    void reconnectSentOncePerIncidentDayAfterCommit() {
        InOrder order = inOrder(txManager, notificationService);

        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");
        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");

        order.verify(txManager).commit(any());
        order.verify(notificationService).sendReconnect(user, EasternTime.today());
        verify(notificationService, times(1)).sendReconnect(any(), any());
    }

    // R18.
    @Test
    void reconnectSkippedForRejectedApplicantAndUserWithoutSchedule() {
        user.setAccountStatus("REJECTED");
        schedule.setHoldReason(HoldReason.MANUAL_REVIEW);
        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.LOGIN_REQUIRED);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);

        user.setAccountStatus("ACTIVE");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED");
        service.onConsentRequired(USER_ID, ITEM_ID);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.LOGIN_REQUIRED);
        assertThat(user.getPlaidTransactionsConsented()).isFalse();

        verify(notificationService, never()).sendReconnect(any(), any());
    }

    @Test
    void staleItemWritesNothing() {
        assertThat(service.onLoginRequired(USER_ID, "item-old", "ITEM_LOGIN_REQUIRED"))
                .isEqualTo(ItemStateResult.STALE_ITEM);
        assertThat(service.onRevoked(USER_ID, null, "ITEM_NOT_FOUND")).isEqualTo(ItemStateResult.STALE_ITEM);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        assertThat(service.onConsentRequired(USER_ID, ITEM_ID)).isEqualTo(ItemStateResult.STALE_ITEM);

        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(schedule.getHoldReason()).isNull();
        verify(userRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
        verify(eventRepository, never()).deleteByUserId(any());
        verify(lifecycleService, never()).enqueueRemoval(any());
        verify(notificationService, never()).sendReconnect(any(), any());
    }

    @Test
    void terminalItemIgnoresLaterEvents() {
        user.setPlaidItemStatus(PlaidItemStatus.REVOKED);

        assertThat(service.onLoginRequired(USER_ID, ITEM_ID, "ITEM_LOGIN_REQUIRED")).isEqualTo(ItemStateResult.NO_OP);
        assertThat(service.onRevoked(USER_ID, ITEM_ID, "USER_PERMISSION_REVOKED")).isEqualTo(ItemStateResult.NO_OP);
        assertThat(service.onConsentRenewed(USER_ID, ITEM_ID, null)).isEqualTo(ItemStateResult.NO_OP);

        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.REVOKED);
        verify(lifecycleService, never()).enqueueRemoval(any());
        verify(notificationService, never()).sendReconnect(any(), any());
    }

    // Spec 2.4 step 3 and 7.9, decision 34.
    @Test
    void relinkNullsCursorAndPurgesDetectionData() {
        user.setPlaidHistoryComplete(true);
        user.setPlaidTransactionsUpdateStatus("HISTORICAL_UPDATE_COMPLETE");
        user.setPlaidLastSyncAt(LocalDateTime.of(2026, 11, 1, 22, 31));
        user.setPlaidStreamsReadAt(LocalDateTime.of(2026, 11, 1, 22, 32));
        user.setPlaidDisconnectAt(LocalDateTime.of(2027, 5, 1, 12, 0));
        schedule.setDetectionState(DetectionState.CONFIRMED);
        schedule.setPrimaryStreamId(7L);
        schedule.setNextExpectedDate(LocalDate.of(2026, 11, 13));
        schedule.setDormantSince(LocalDate.of(2026, 10, 1));
        schedule.setDetectionStartedAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        schedule.setMissedCount((short) 2);
        schedule.setHoldReason(HoldReason.BANK_CHANGE);
        schedule.setStatus(InvestmentSchedule.Status.PAUSED_BY_USER);

        service.resetForNewItem(user);

        assertThat(user.getPlaidSyncCursor()).isNull();
        assertThat(user.getPlaidTransactionsUpdateStatus()).isNull();
        assertThat(user.getPlaidLastSyncAt()).isNull();
        assertThat(user.getPlaidStreamsReadAt()).isNull();
        assertThat(user.getPlaidDisconnectAt()).isNull();
        assertThat(user.getPlaidHistoryComplete()).isFalse();
        assertThat(schedule.getDetectionState()).isNull();
        assertThat(schedule.getPrimaryStreamId()).isNull();
        assertThat(schedule.getNextExpectedDate()).isNull();
        assertThat(schedule.getDormantSince()).isNull();
        assertThat(schedule.getDetectionStartedAt()).isNull();
        assertThat(schedule.getMissedCount()).isEqualTo((short) 0);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.BANK_CHANGE);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        InOrder order = inOrder(scheduleRepository, cycleRepository, eventRepository, streamRepository);
        order.verify(scheduleRepository).saveAndFlush(schedule);
        order.verify(cycleRepository).deleteByUserId(USER_ID);
        order.verify(eventRepository).deleteByUserId(USER_ID);
        order.verify(streamRepository).deleteByUserId(USER_ID);
        verify(txManager, never()).getTransaction(any()); // joins the exchange's transaction
    }

    @Test
    void resetForNewItemRequiresCallerTransaction() throws NoSuchMethodException {
        Transactional tx = PlaidItemStateService.class.getMethod("resetForNewItem", User.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
```

- [ ] **Step 2: Run the new test and watch it fail**

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidItemStateServiceTest' --console=plain)
```

Expected: `BUILD FAILED` at `:compileTestJava`, with `error: cannot find symbol` and `symbol:   class PlaidItemStateService` (and `package com.investingapp.backend.service.PlaidItemStateService does not exist` for the nested-enum imports).

- [ ] **Step 3: Declare the alert type**

In `.../ops/OpsAlerts.java`, first run `grep -n 'PLAID_SYNC_ITEM_LIMIT' backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java`. When it prints nothing, insert this line, with the blank line after it, immediately before the quoted line `    private OpsAlerts() {`:

```java
    /** Plaid refused /transactions/sync with TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED (raised by PlaidItemStateService.onSyncLimit). */
    public static final String PLAID_SYNC_ITEM_LIMIT = "plaid.sync.item_limit";
```

When it prints a line, the constant already exists; leave the file untouched and drop it from the Step 10 `git add` list.

- [ ] **Step 4: Create PlaidItemStateService**

Create `.../service/PlaidItemStateService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every webhook- and error-driven write of Plaid Item state (spec 5.7, both modes), the three
 * Item-state holds, the update-mode consent writes (spec 2.3) and the relink reset (spec 2.4 step 3,
 * 7.9).
 *
 * <p>One shape per handler: a TransactionTemplate under the user row lock, the item guard (else
 * plaid.stale_item_event, no write), the writes, commit, then the RECONNECT notice or ops alert.
 * No Plaid or Alpaca call. Hold rule (decision 30): an Item-state hold is written only over no
 * hold and a repair clears only its own, so MANUAL_REVIEW and ACH return holds keep blocking. The
 * three holds are non-blocking (R2) and emit no event (R3). Never writes status or mode.
 */
@Service
public class PlaidItemStateService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidItemStateService.class);

    public enum ItemStateResult { APPLIED, STALE_ITEM, NO_OP }

    public enum RepairPath { LOGIN_REPAIRED, UPDATE_MODE }

    /** users.plaid_item_error_code is VARCHAR(64). */
    static final int ERROR_CODE_WIDTH = 64;

    /** Items FRED is finished with: removal is queued, so a late webhook must not revive them. */
    private static final Set<PlaidItemStatus> TERMINAL = EnumSet.of(PlaidItemStatus.REVOKED, PlaidItemStatus.REMOVED);

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final PaycheckCycleRepository cycleRepository;
    private final PlaidItemLifecycleService plaidItemLifecycleService;
    private final NotificationService notificationService;
    private final OpsAlertService opsAlertService;
    private final TransactionTemplate writeTx;

    public PlaidItemStateService(UserRepository userRepository,
            InvestmentScheduleRepository scheduleRepository,
            PaycheckStreamRepository streamRepository,
            PaycheckEventRepository eventRepository,
            PaycheckCycleRepository cycleRepository,
            PlaidItemLifecycleService plaidItemLifecycleService,
            NotificationService notificationService,
            OpsAlertService opsAlertService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.cycleRepository = cycleRepository;
        this.plaidItemLifecycleService = plaidItemLifecycleService;
        this.notificationService = notificationService;
        this.opsAlertService = opsAlertService;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /** What one handler did inside its transaction, and what it owes once that commits. */
    private record Outcome(ItemStateResult result, User user, boolean reconnectDue, boolean syncLimitAlertDue) {
        static Outcome of(ItemStateResult result) {
            return new Outcome(result, null, false, false);
        }

        static Outcome applied(User user) {
            return new Outcome(ItemStateResult.APPLIED, user, false, false);
        }
    }

    @FunctionalInterface
    private interface Writes {
        Outcome apply(User user, Optional<InvestmentSchedule> schedule);
    }

    /** 5.7 row 1: ITEM_LOGIN_REQUIRED (webhook ERROR or API error), ACCESS_NOT_GRANTED, NO_ACCOUNTS. */
    public ItemStateResult onLoginRequired(Long userId, String itemId, String errorCode) {
        return run("login_required", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = user.getPlaidItemStatus() != PlaidItemStatus.LOGIN_REQUIRED;
            user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
            user.setPlaidItemErrorCode(ColumnText.fit(errorCode, ERROR_CODE_WIDTH));
            boolean holdWritten = schedule.map(s -> writeItemHold(s, HoldReason.ITEM_LOGIN_REQUIRED)).orElse(false);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.ITEM_BROKEN));
            // CONFIRMED cycles are skipped for the hold. Phase 1 has no CONFIRMED writer, so this
            // finds nothing today; Phase 2 inherits it. OPEN cycles are left open (decision 39).
            for (PaycheckCycle cycle : cycleRepository.findByUserIdAndState(user.getId(), PaycheckCycleState.CONFIRMED)) {
                cycle.setState(PaycheckCycleState.SKIPPED);
                cycle.setSkipReason(PaycheckSkipReason.HOLD);
                cycleRepository.save(cycle);
            }
            // Spec 7.9: events go and descriptors are nulled when an Item breaks. The stream skeleton
            // (token, cadence, anchors, median) stays so a repair does not restart detection.
            eventRepository.deleteByUserId(user.getId());
            for (PaycheckStream stream : streamRepository.findByUserIdOrderByIdAsc(user.getId())) {
                stream.setRawDescriptor(null);
                stream.setPlaidSnapshotJson(null); // the raw Plaid object holds the description
                streamRepository.save(stream);
            }
            logApplied("login_required", user, holdWritten);
            return new Outcome(ItemStateResult.APPLIED, user, newIncident && reconnectAllowed(user, schedule), false);
        });
    }

    /** 5.7 row 2: USER_PERMISSION_REVOKED, ITEM_NOT_FOUND. {@code source} is that code, a closed word. */
    public ItemStateResult onRevoked(Long userId, String itemId, String source) {
        return run("revoked", userId, itemId, true, (user, schedule) -> revoke(user, schedule, source));
    }

    /** USER_ACCOUNT_REVOKED: the funding account is a full revoke; another account drops its events only (spec 2.8). */
    public ItemStateResult onAccountRevoked(Long userId, String itemId, String accountId) {
        return run("account_revoked", userId, itemId, true, (user, schedule) -> {
            if (accountId != null && accountId.equals(user.getPlaidAccountId())) {
                return revoke(user, schedule, "USER_ACCOUNT_REVOKED");
            }
            eventRepository.deleteByUserIdAndPlaidAccountId(user.getId(), accountId);
            logApplied("account_revoked_other", user, false);
            return Outcome.applied(user);
        });
    }

    /** 5.7 row 4: ADDITIONAL_CONSENT_REQUIRED or ACCESS_NOT_GRANTED on a sync (class CONSENT). */
    public ItemStateResult onConsentRequired(Long userId, String itemId) {
        return run("consent_required", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = !Boolean.FALSE.equals(user.getPlaidTransactionsConsented());
            user.setPlaidTransactionsConsented(Boolean.FALSE);
            boolean holdWritten = schedule.map(s -> writeItemHold(s, HoldReason.CONSENT_REQUIRED)).orElse(false);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.NEEDS_CONSENT));
            logApplied("consent_required", user, holdWritten);
            return new Outcome(ItemStateResult.APPLIED, user, newIncident && reconnectAllowed(user, schedule), false);
        });
    }

    /** Update mode added the Transactions consent (T16). The first sync is left to the reconcile or the status endpoint. */
    public ItemStateResult onConsentGranted(Long userId, String itemId) {
        return run("consent_granted", userId, itemId, true, (user, schedule) -> {
            user.setPlaidTransactionsConsented(Boolean.TRUE);
            user.setPlaidDisconnectAt(null);
            schedule.ifPresent(s -> {
                clearItemHold(s, HoldReason.CONSENT_REQUIRED);
                if (s.getDetectionState() == DetectionState.NEEDS_CONSENT) {
                    s.setDetectionState(DetectionState.SEARCHING);
                }
            });
            logApplied("consent_granted", user, false);
            return Outcome.applied(user);
        });
    }

    /**
     * Every successful update-mode completion (T16, spec 2.3): store /item/get's
     * consent_expiration_time as Eastern wall time (null when Plaid sends none) and clear a
     * PENDING_DISCONNECT, so the reconnect card goes away.
     */
    public ItemStateResult onConsentRenewed(Long userId, String itemId, OffsetDateTime consentExpiration) {
        return run("consent_renewed", userId, itemId, true, (user, schedule) -> {
            user.setPlaidDisconnectAt(toEastern(consentExpiration));
            if (user.getPlaidItemStatus() == PlaidItemStatus.PENDING_DISCONNECT) {
                user.setPlaidItemStatus(PlaidItemStatus.OK);
            }
            logApplied("consent_renewed", user, false);
            return Outcome.applied(user);
        });
    }

    /**
     * 5.7 row 4: TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED. No hold in any mode in Phase 1 (R2, decision 33).
     * Phase 2 seam: the MANUAL_REVIEW hold, gated on !props.isShadow(), with
     * schedule_paused(system, a new reason) in this same transaction.
     */
    public ItemStateResult onSyncLimit(Long userId, String itemId) {
        return run("sync_limit", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = user.getPlaidItemStatus() != PlaidItemStatus.SYNC_LIMIT;
            user.setPlaidItemStatus(PlaidItemStatus.SYNC_LIMIT);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.UNDETECTABLE));
            logApplied("sync_limit", user, false);
            return new Outcome(ItemStateResult.APPLIED, user, false, newIncident);
        });
    }

    /** Spec 2.1 NOT_SUPPORTED: no hold, no reconnect card, no notice, off the reconcile list. */
    public ItemStateResult onNotSupported(Long userId, String itemId) {
        return run("not_supported", userId, itemId, true, (user, schedule) -> {
            user.setPlaidItemStatus(PlaidItemStatus.NOT_SUPPORTED);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.UNDETECTABLE));
            logApplied("not_supported", user, false);
            return Outcome.applied(user);
        });
    }

    /** 5.7 row 3: LOGIN_REPAIRED webhook or update-mode success, only from LOGIN_REQUIRED. */
    public ItemStateResult onLoginRepaired(Long userId, String itemId, RepairPath path) {
        return run("login_repaired", userId, itemId, true, (user, schedule) -> {
            if (user.getPlaidItemStatus() != PlaidItemStatus.LOGIN_REQUIRED) {
                logger.warn("plaid.login_repaired.ignored user_id={} path={} item_status={}",
                        user.getId(), path, user.getPlaidItemStatus());
                return Outcome.of(ItemStateResult.NO_OP);
            }
            user.setPlaidItemStatus(PlaidItemStatus.OK);
            user.setPlaidItemErrorCode(null);
            schedule.ifPresent(s -> {
                clearItemHold(s, HoldReason.ITEM_LOGIN_REQUIRED);
                if (s.getDetectionState() == DetectionState.ITEM_BROKEN) {
                    s.setDetectionState(stateAfterRepair(s));
                }
            });
            logger.info("plaid.item_state handler=login_repaired user_id={} path={}", user.getId(), path);
            return Outcome.applied(user);
        });
    }

    /** PENDING_DISCONNECT or PENDING_EXPIRATION: the date and a RECONNECT, no hold until an error arrives. */
    public ItemStateResult onPendingDisconnect(Long userId, String itemId, OffsetDateTime disconnectAt) {
        return run("pending_disconnect", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = user.getPlaidItemStatus() == PlaidItemStatus.OK;
            if (newIncident) {
                user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
            }
            if (disconnectAt != null) {
                user.setPlaidDisconnectAt(toEastern(disconnectAt));
            }
            logApplied("pending_disconnect", user, false);
            return new Outcome(ItemStateResult.APPLIED, user, newIncident && reconnectAllowed(user, schedule), false);
        });
    }

    /** WEBHOOK_UPDATE_ACKNOWLEDGED, any Item status. */
    public ItemStateResult onWebhookAcked(Long userId, String itemId) {
        return run("webhook_acked", userId, itemId, false, (user, schedule) -> {
            user.setPlaidWebhookAckedAt(EasternTime.now());
            return Outcome.applied(user);
        });
    }

    /**
     * Spec 2.4 step 3 and 7.9, called by PlaidService.exchangePublicTokenAndLinkUser inside its own
     * transaction: the new Item starts with no cursor and no detection data. Never status, mode or
     * hold_reason (the exchange's resetForBankChange owns the hold).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resetForNewItem(User user) {
        user.setPlaidSyncCursor(null);
        user.setPlaidTransactionsUpdateStatus(null);
        user.setPlaidLastSyncAt(null);
        user.setPlaidStreamsReadAt(null);
        user.setPlaidDisconnectAt(null);
        user.setPlaidHistoryComplete(false);
        scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).ifPresent(s -> {
            s.setDetectionState(null);
            s.setPrimaryStreamId(null);
            s.setNextExpectedDate(null);
            s.setDormantSince(null);
            s.setDetectionStartedAt(null);
            s.setMissedCount((short) 0);
            scheduleRepository.saveAndFlush(s); // release the stream before its delete
        });
        cycleRepository.deleteByUserId(user.getId());
        eventRepository.deleteByUserId(user.getId());
        streamRepository.deleteByUserId(user.getId());
        logger.info("plaid.item_state handler=reset_for_new_item user_id={}", user.getId());
    }

    private Outcome revoke(User user, Optional<InvestmentSchedule> schedule, String source) {
        user.setPlaidItemStatus(PlaidItemStatus.REVOKED);
        user.setPlaidItemErrorCode(ColumnText.fit(source, ERROR_CODE_WIDTH));
        boolean holdWritten = false;
        if (schedule.isPresent()) {
            InvestmentSchedule s = schedule.get();
            holdWritten = writeItemHold(s, HoldReason.PERMISSION_REVOKED);
            // Decision 70: clear the mirror and flush it BEFORE the purge, so the schedule never
            // points at a stream row that is about to be deleted.
            s.setPrimaryStreamId(null);
            s.setNextExpectedDate(null);
            s.setDetectionState(DetectionState.REVOKED);
            scheduleRepository.saveAndFlush(s);
        }
        cycleRepository.deleteByUserId(user.getId());
        eventRepository.deleteByUserId(user.getId());
        streamRepository.deleteByUserId(user.getId());
        user.setPlaidSyncCursor(null);
        user.setPlaidHistoryComplete(false);
        // Plaid bills until /item/remove; repair is a fresh link (spec 5.7).
        plaidItemLifecycleService.enqueueRemoval(user);
        logApplied("revoked", user, holdWritten);
        return Outcome.applied(user);
    }

    private ItemStateResult run(String handler, Long userId, String itemId, boolean refuseTerminal, Writes writes) {
        Outcome outcome = writeTx.execute(status -> {
            Optional<User> locked = userRepository.findByIdForUpdate(userId);
            if (locked.isEmpty() || itemId == null || !itemId.equals(locked.get().getPlaidItemId())) {
                logger.info("plaid.stale_item_event handler={} user_id={}", handler, userId);
                return Outcome.of(ItemStateResult.STALE_ITEM);
            }
            User user = locked.get();
            if (refuseTerminal && TERMINAL.contains(user.getPlaidItemStatus())) {
                logger.warn("plaid.item_state.ignored handler={} user_id={} item_status={}",
                        handler, userId, user.getPlaidItemStatus());
                return Outcome.of(ItemStateResult.NO_OP);
            }
            Optional<InvestmentSchedule> schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
            Outcome result = writes.apply(user, schedule);
            if (result.result() == ItemStateResult.APPLIED) {
                userRepository.save(user);
                schedule.ifPresent(scheduleRepository::save);
            }
            return result;
        });
        followUpAfterCommit(handler, userId, outcome);
        return outcome.result();
    }

    /** Email and alert I/O never sit inside a DB transaction (decision 18). */
    private void followUpAfterCommit(String handler, Long userId, Outcome outcome) {
        if (!outcome.reconnectDue() && !outcome.syncLimitAlertDue()) {
            return;
        }
        Runnable followUp = () -> {
            if (outcome.syncLimitAlertDue()) {
                opsAlertService.raise(OpsAlerts.PLAID_SYNC_ITEM_LIMIT, String.valueOf(userId),
                        "Plaid refused transactions sync (TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED); detection stopped for this user",
                        Map.of("item_status", PlaidItemStatus.SYNC_LIMIT.name()));
            }
            if (outcome.reconnectDue()) {
                try {
                    notificationService.sendReconnect(outcome.user(), EasternTime.today());
                } catch (RuntimeException e) {
                    logger.warn("paycheck.notice.reconnect_failed handler={} user_id={} error_class={}",
                            handler, userId, e.getClass().getSimpleName());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Inside an outer transaction: wait for its commit.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    followUp.run();
                }
            });
        } else {
            followUp.run();
        }
    }

    /**
     * R18: RECONNECT is for users who can invest. A rejected applicant and a user with no schedule
     * row (still onboarding) get none. Read directly: DetectionEligibility.exclusion reports one
     * reason, which can be DISABLED or ITEM_STATUS for a REJECTED account.
     */
    private static boolean reconnectAllowed(User user, Optional<InvestmentSchedule> schedule) {
        return schedule.isPresent() && !"REJECTED".equals(user.getAccountStatus());
    }

    /** Decision 30: write over no hold only. Returns whether the hold was written. */
    private static boolean writeItemHold(InvestmentSchedule schedule, HoldReason hold) {
        if (schedule.getHoldReason() != null) {
            return false;
        }
        schedule.setHoldReason(hold);
        return true;
    }

    /** Decision 30: a repair or consent path clears only the hold it writes. */
    private static void clearItemHold(InvestmentSchedule schedule, HoldReason hold) {
        if (schedule.getHoldReason() == hold) {
            schedule.setHoldReason(null);
        }
    }

    /** Spec 5.7 row 3: back to the primary stream's state, or SEARCHING when there is none. */
    private DetectionState stateAfterRepair(InvestmentSchedule schedule) {
        if (schedule.getPrimaryStreamId() == null) {
            return DetectionState.SEARCHING;
        }
        return streamRepository.findById(schedule.getPrimaryStreamId())
                .map(stream -> switch (stream.getState()) {
                    case PROVISIONAL -> DetectionState.PROVISIONAL;
                    case CONFIRMED -> DetectionState.CONFIRMED;
                    case DORMANT -> DetectionState.DORMANT;
                    default -> DetectionState.SEARCHING;
                })
                .orElse(DetectionState.SEARCHING);
    }

    private static LocalDateTime toEastern(OffsetDateTime instant) {
        return instant == null ? null : instant.atZoneSameInstant(EasternTime.ZONE).toLocalDateTime();
    }

    private static void logApplied(String handler, User user, boolean holdWritten) {
        logger.info("plaid.item_state handler={} user_id={} item_status={} hold_written={}",
                handler, user.getId(), user.getPlaidItemStatus(), holdWritten);
    }
}
```

- [ ] **Step 5: Run the handler tests**

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidItemStateServiceTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.PlaidItemStateServiceTest.xml
```

Expected: `BUILD SUCCESSFUL`; the grep prints `tests="35" skipped="0" failures="0" errors="0"` (23 plain tests plus 12 cases of `itemStateHoldNeverReplacesAnExistingHold`).

- [ ] **Step 6: Failing exchange test (the relink reset runs inside the exchange)**

In `backend/src/test/java/com/investingapp/backend/service/PlaidServiceExchangeLifecycleTest.java`:

1. Add `import org.mockito.InOrder;` after the quoted line `import org.junit.jupiter.api.Test;`, and `import static org.mockito.Mockito.inOrder;` after the quoted line `import static org.mockito.Mockito.doAnswer;`.
2. After `:60`, the quoted `    private PlaidItemLifecycleService lifecycleService;`, add `    private PlaidItemStateService itemStateService;`.
3. After `:71`, the quoted `        lifecycleService = mock(PlaidItemLifecycleService.class);`, add `        itemStateService = mock(PlaidItemStateService.class);`.
4. After `:81`, the quoted `        ReflectionTestUtils.setField(plaidService, "plaidItemLifecycleService", lifecycleService);`, add `        ReflectionTestUtils.setField(plaidService, "plaidItemStateService", itemStateService);`.
5. Add this test immediately before the quoted line `    @Test` that precedes `    void legacySplitRowFallsBackToTheScheduleWhenTheUserFieldIsNull() throws IOException {`:

```java
    @Test
    void relinkResetsDetectionDataInsideTheExchangeAfterTheOldItemIsQueued() throws IOException {
        User user = lockedUser();
        user.setPlaidAccessToken("ENC:old");
        user.setPlaidItemId("item-old");
        AtomicReference<String> itemAtReset = new AtomicReference<>();
        doAnswer(i -> {
            itemAtReset.set(((User) i.getArgument(0)).getPlaidItemId());
            return null;
        }).when(itemStateService).resetForNewItem(user);

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        InOrder order = inOrder(lifecycleService, userRepository, itemStateService);
        order.verify(lifecycleService).enqueueRemoval(user);
        order.verify(userRepository).save(user);
        order.verify(itemStateService).resetForNewItem(user); // after every Plaid call
        assertThat(itemAtReset.get()).isEqualTo(ITEM_ID); // the new Item is already on the row
    }
```

In `backend/src/test/java/com/investingapp/backend/service/PlaidLinkedAnalyticsTest.java`, after `:83`, the quoted `        ReflectionTestUtils.setField(plaidService, "plaidItemLifecycleService", mock(PlaidItemLifecycleService.class));`, add:

```java
        ReflectionTestUtils.setField(plaidService, "plaidItemStateService", mock(PlaidItemStateService.class));
```

Run:

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest.xml
```

Expected: `BUILD FAILED`; the grep prints `tests="6" skipped="0" failures="6" errors="0"` (Gradle's XML files every failed test under `failures`), every case failing in `setUp` with `java.lang.IllegalArgumentException: Could not find field 'plaidItemStateService'` (the field does not exist on `PlaidService` yet).

- [ ] **Step 7: Call the reset from the exchange**

In `.../service/PlaidService.java`, after `:101-102`:

```java
    @Autowired
    private PlaidItemLifecycleService plaidItemLifecycleService;
```

add:

```java

    /** Item-state writer; the exchange calls its relink reset inside this class's transaction. */
    @Autowired
    private PlaidItemStateService plaidItemStateService;
```

Then after `:256`, the quoted line `        accountStatusService.handleBankAccountChange(appUser);`, add:

```java
        // Spec 2.4 step 3 and 7.9: the new Item starts with no cursor, no history flag and no
        // detection data; the old Item's cycles, events and streams go in this transaction.
        plaidItemStateService.resetForNewItem(appUser);
```

`resetForNewItem` is `MANDATORY`, reached through the Spring proxy, so it joins this `@Transactional`. It runs after the method's last Plaid and Alpaca call, so its `saveAndFlush` never holds the schedule row across HTTP; it never touches `hold_reason`, so `BANK_CHANGE` stands.

In `.../model/PlaidItemStatus.java`, replace line 3, the quoted `/** users.plaid_item_status (spec 3.1). Written by the webhook processor from Phase 1 on. */`, with:

```java
/**
 * users.plaid_item_status (spec 3.1). Writers: the exchange (OK on a new Item),
 * PlaidItemStateService (every webhook- and error-driven state, spec 5.7), and REMOVED beside
 * enqueueRemoval in the reconcile's REJECTED pass and the admin remove-item verb.
 */
```

Run, one class at a time:

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest.xml
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.PlaidLinkedAnalyticsTest.xml
```

Expected: `BUILD SUCCESSFUL` twice; `tests="6" skipped="0" failures="0" errors="0"` and `tests="8" skipped="0" failures="0" errors="0"`.

- [ ] **Step 8: Pin the hold-writer allowlist**

First run the two commands at the end of this step once and note the `tests=` value (T03 and T11 both add methods). Then, in `backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java` (created by T03), add this method immediately before the class's final closing brace. It uses fully qualified names so it needs no import edits and cannot collide with T03's helpers:

```java
    /** Decision 41 (T13): hold_reason has a closed writer list; Phase 0 keeps its four sites. */
    @org.junit.jupiter.api.Test
    void onlyAllowlistedFilesCallSetHoldReason() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java/com/investingapp/backend");
        java.util.Set<String> allowed = java.util.Set.of(
                "service/InvestmentScheduleService.java",
                "service/AccountStatusService.java",
                "service/AchRelationshipService.java",
                "service/PlaidItemStateService.java",
                "service/AdminPaycheckService.java");
        java.util.Map<String, Integer> sites = new java.util.TreeMap<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = java.nio.file.Files.readString(file);
                int count = text.split("setHoldReason\\(", -1).length - 1;
                if (count > 0) {
                    sites.put(root.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(sites.keySet())
                .as("files calling setHoldReason( outside the allowlist")
                .isSubsetOf(allowed);
        org.assertj.core.api.Assertions.assertThat(
                sites.getOrDefault("service/InvestmentScheduleService.java", 0)
                        + sites.getOrDefault("service/AccountStatusService.java", 0)
                        + sites.getOrDefault("service/AchRelationshipService.java", 0))
                .as("Phase 0 hold-writer sites")
                .isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(sites).containsKey("service/PlaidItemStateService.java");
    }
```

Run:

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml
```

Expected: `BUILD SUCCESSFUL`, and the `tests=` value is one higher than the value you noted, with `failures="0" errors="0"`. This is a guard, so it passes on the first run; prove it can fail by temporarily appending the comment line `// probe setHoldReason(` as the last line of `.../service/NotificationGate.java` (a comment trips the text scan), re-running (expected: one failure whose message reads `files calling setHoldReason( outside the allowlist`, listing `service/NotificationGate.java`), then deleting that line by hand and re-running green. Confirm with `git diff HEAD -- ':/backend/src/main/java/com/investingapp/backend/service/NotificationGate.java'` that the file shows no change.

- [ ] **Step 9: Prove the reset through the real proxy and the bean graph on a scratch MySQL**

Both classes are `@RequiresCiDatabase` and skip silently without a database; a skip proves nothing. The first runs the real exchange, so `resetForNewItem` goes through the `MANDATORY` proxy against V6's tables; the second proves the new bean wires with no circular dependency. Run the block as ONE shell invocation from the repository root: it reads the local MySQL user and password from the gitignored `application-local.properties` into variables without printing them and uses a throwaway schema (never `investing_dev`).

```
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t13_scratch; CREATE DATABASE fred_t13_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t13_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
X=backend/build/test-results/test/TEST-com.investingapp.backend
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsAtomicityTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $X.service.PlaidLinkedAnalyticsAtomicityTest.xml
(cd backend && ./gradlew test --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $X.BackendApplicationTests.xml
db -e "DROP DATABASE fred_t13_scratch"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected: `BUILD SUCCESSFUL` twice; `tests="2" skipped="0" failures="0" errors="0"` and `tests="1" skipped="0" failures="0" errors="0"`. Any `skipped` above 0 means the database was not reached: fix the connection and re-run.

Then the hygiene checks:

```
grep -rn 'setHoldReason(' backend/src/main/java
grep -n 'getIsPaused\|isPausedForAnyReason\|setMode(\|setStatus(\|analyticsOutbox' backend/src/main/java/com/investingapp/backend/service/PlaidItemStateService.java
git diff HEAD -- backend/src | perl -CSD -ne 'print if /^\+/ && /\x{2014}/'
```

Expected: the first lists the four Phase 0 sites (one each in `InvestmentScheduleService.java` and `AccountStatusService.java`, two in `AchRelationshipService.java`; T04 shifts those line numbers) plus `writeItemHold` and `clearItemHold` in `PlaidItemStateService.java`; the second and third print nothing.

- [ ] **Step 10: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (stage nothing you did not write; never `git checkout`, `git restore`, `git stash` or `git reset`; never stage `application-local.properties` or `ITPM/agent-memory/*`), then:

```
git add backend/src/main/java/com/investingapp/backend/service/PlaidItemStateService.java backend/src/main/java/com/investingapp/backend/service/PlaidService.java backend/src/main/java/com/investingapp/backend/model/PlaidItemStatus.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java backend/src/test/java/com/investingapp/backend/service/PlaidItemStateServiceTest.java backend/src/test/java/com/investingapp/backend/service/PlaidServiceExchangeLifecycleTest.java backend/src/test/java/com/investingapp/backend/service/PlaidLinkedAnalyticsTest.java backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java
git commit -m "feat(plaid): Item-state handling in both modes and the relink reset (Phase 1 T13)"
```

Append your session's attribution trailer.

## Verification

Run each class alone with `(cd backend && ./gradlew test --tests '<class>' --console=plain)` and read the XML:

| Class (`com.investingapp.backend.` prefix) | Expected XML counts |
|---|---|
| `service.PlaidItemStateServiceTest` | `tests="35" skipped="0" failures="0"` |
| `service.PlaidServiceExchangeLifecycleTest` | `tests="6" skipped="0" failures="0"` |
| `service.PlaidLinkedAnalyticsTest` | `tests="8" skipped="0" failures="0"` |
| `PhaseOneMoneyInvarianceTest` | the count noted in Step 8 plus 1 (`onlyAllowlistedFilesCallSetHoldReason`), `failures="0"` |
| `service.PlaidLinkedAnalyticsAtomicityTest` (Step 9 scratch block) | `tests="2" skipped="0" failures="0"` |
| `BackendApplicationTests` (Step 9 scratch block) | `tests="1" skipped="0" failures="0"` |

Count command: `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-<fully.qualified.Class>.xml`. When the plan's full-suite gate runs (`(cd backend && ./gradlew cleanTest test)` on a scratch schema with the Step 9 exports, then the count command over `TEST-*.xml`), the total rises by exactly 37 over the previous task's total (35 + 1 + 1), with 0 skipped and 0 failures.

## Produces

- `com.investingapp.backend.service.PlaidItemStateService` (`@Service`), constructor `(UserRepository, InvestmentScheduleRepository, PaycheckStreamRepository, PaycheckEventRepository, PaycheckCycleRepository, PlaidItemLifecycleService, NotificationService, OpsAlertService, PlatformTransactionManager)`.
- `PlaidItemStateService.ItemStateResult { APPLIED, STALE_ITEM, NO_OP }`; `PlaidItemStateService.RepairPath { LOGIN_REPAIRED, UPDATE_MODE }`.
- Handlers returning `ItemStateResult`: `onLoginRequired(Long, String, String errorCode)`, `onRevoked(Long, String, String source)`, `onAccountRevoked(Long, String, String accountId)`, `onConsentRequired(Long, String)`, `onConsentGranted(Long, String)`, `onConsentRenewed(Long, String, OffsetDateTime consentExpiration)`, `onSyncLimit(Long, String)`, `onNotSupported(Long, String)`, `onLoginRepaired(Long, String, RepairPath)`, `onPendingDisconnect(Long, String, OffsetDateTime disconnectAt)`, `onWebhookAcked(Long, String)`.
- `@Transactional(propagation = Propagation.MANDATORY) void resetForNewItem(User)`, called by `PlaidService.exchangePublicTokenAndLinkUser`.
- `PlaidItemStateService.ERROR_CODE_WIDTH = 64` (package-private).
- `OpsAlerts.PLAID_SYNC_ITEM_LIMIT = "plaid.sync.item_limit"`.
- `PhaseOneMoneyInvarianceTest.onlyAllowlistedFilesCallSetHoldReason` (the three Phase 0 files, `PlaidItemStateService`, `AdminPaycheckService`).
- Phase 2 seam: `onSyncLimit` gains the `MANUAL_REVIEW` hold gated on `!props.isShadow()` with `schedule_paused(system, a new reason)` in its transaction.
