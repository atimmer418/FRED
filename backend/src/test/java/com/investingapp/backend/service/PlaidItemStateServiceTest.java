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
                eventRepository, cycleRepository, lifecycleService, notificationService, opsAlertService,
                new UserRowLock(userRepository, mock(jakarta.persistence.EntityManager.class)), txManager);

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

    // Final review PAL-1 / SCTF-2: the withdrawn account's descriptors go with its events.
    @Test
    void otherAccountRevokedBlanksOnlyThatAccountsDescriptors() {
        PaycheckStream savings = stream(PaycheckStreamState.PROVISIONAL);
        savings.setPlaidAccountId("acc-savings");
        PaycheckStream funding = stream(PaycheckStreamState.CONFIRMED);
        funding.setPlaidAccountId(FUNDING_ACCOUNT);
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(savings, funding));

        assertThat(service.onAccountRevoked(USER_ID, ITEM_ID, "acc-savings")).isEqualTo(ItemStateResult.APPLIED);

        assertThat(savings.getRawDescriptor()).isNull();
        assertThat(savings.getPlaidSnapshotJson()).isNull();
        assertThat(savings.getPayerToken()).as("the skeleton stays").isEqualTo("acme");
        assertThat(funding.getRawDescriptor()).isEqualTo("ACME PAYROLL PPD");
        assertThat(funding.getPlaidSnapshotJson()).isNotNull();
        verify(streamRepository).save(savings);
        verify(streamRepository, never()).save(funding);
        verify(streamRepository, never()).deleteByUserId(any());
        verify(eventRepository).deleteByUserIdAndPlaidAccountId(USER_ID, "acc-savings");
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
