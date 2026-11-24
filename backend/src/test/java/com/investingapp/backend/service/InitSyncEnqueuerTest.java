package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.support.RecordingTransactionManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitSyncEnqueuerTest {

    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final List<Runnable> queued = new ArrayList<>();
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        user = eligibleUser();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    }

    static User eligibleUser() {
        User u = new User("init@fredvested.com");
        u.setId(7L);
        u.setAccountStatus("ACTIVE");
        u.setPlaidAccessToken("ENC:token");
        u.setPlaidItemId("item-1");
        u.setPlaidItemStatus(PlaidItemStatus.OK);
        u.setPaycheckAutopilotOptOut(false);
        return u;
    }

    private InvestmentSchedule manualRow() {
        InvestmentSchedule row = new InvestmentSchedule();
        row.setId(70L);
        row.setUser(user);
        row.setMode(ScheduleMode.MANUAL);
        row.setStatus(InvestmentSchedule.Status.ACTIVE);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(row));
        when(scheduleRepository.save(row)).thenAnswer(inv -> {
            assertThat(txManager.open).isTrue();
            return row;
        });
        return row;
    }

    private InitSyncEnqueuer enqueuer(Executor executor) {
        InitSyncEnqueuer enqueuer = new InitSyncEnqueuer(scheduleRepository, userRepository, pipeline, props,
                txManager, executor);
        ReflectionTestUtils.setField(enqueuer, "entityManager", entityManager);
        return enqueuer;
    }

    @Test
    void firstCallStampsAndEnqueuesInitOnce() {
        InvestmentSchedule row = manualRow();
        InitSyncEnqueuer enqueuer = enqueuer(queued::add);

        assertThat(enqueuer.enqueueIfNeeded(user)).isTrue();
        assertThat(row.getDetectionStartedAt()).isNotNull();
        assertThat(queued).hasSize(1);
        verify(entityManager).refresh(user);
        verify(entityManager).refresh(row, LockModeType.PESSIMISTIC_WRITE);

        assertThat(enqueuer.enqueueIfNeeded(user)).isFalse(); // stamped: never asked again from here
        queued.get(0).run();
        assertThat(enqueuer.enqueueIfNeeded(user)).isFalse();

        assertThat(queued).hasSize(1);
        verify(pipeline, times(1)).run(7L, "item-1", SyncReason.INIT);
        verify(scheduleRepository, times(1)).save(row);
    }

    @Test
    void excludedUserGetsNoInit() {
        InitSyncEnqueuer enqueuer = enqueuer(queued::add);
        List<Consumer<User>> exclusions = List.of(u -> u.setAccountStatus("REJECTED"),
                u -> u.setPaycheckAutopilotOptOut(true), u -> u.setPlaidAccessToken(null),
                u -> u.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED),
                u -> u.setPlaidTransactionsConsented(false), u -> u.setPlaidSyncCursor("cursor-1"));
        for (Consumer<User> exclusion : exclusions) {
            User excluded = eligibleUser();
            exclusion.accept(excluded);
            assertThat(enqueuer.enqueueIfNeeded(excluded)).isFalse();
        }
        when(props.isEnabled()).thenReturn(false);
        assertThat(enqueuer.enqueueIfNeeded(eligibleUser())).isFalse();

        assertThat(queued).isEmpty();
        verifyNoInteractions(pipeline, scheduleRepository);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void enqueueWithoutTransactionSubmitsDirectly() {
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        List<Boolean> synchronizationAtSubmit = new ArrayList<>();
        InitSyncEnqueuer enqueuer = enqueuer(task -> {
            synchronizationAtSubmit.add(TransactionSynchronizationManager.isSynchronizationActive());
            queued.add(task);
        });

        assertThat(enqueuer.enqueueIfNeeded(user)).isTrue();
        assertThat(synchronizationAtSubmit).containsExactly(false);
        assertThat(enqueuer.enqueueIfNeeded(user)).as("in flight").isFalse();
        queued.get(0).run();
        assertThat(enqueuer.enqueueIfNeeded(user)).as("still no cursor: asked again").isTrue();

        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void plaidCallsOutsideTransaction() {
        manualRow();
        when(pipeline.run(7L, "item-1", SyncReason.INIT)).thenAnswer(inv -> {
            assertThat(txManager.open).as("INIT starts only after the stamp commits").isFalse();
            return null;
        });

        assertThat(enqueuer(Runnable::run).enqueueIfNeeded(user)).isTrue();

        verify(pipeline).run(7L, "item-1", SyncReason.INIT);
    }

    @Test
    void stampRecheckUnderLockSkipsAWinningSync() {
        InvestmentSchedule row = manualRow();
        User locked = eligibleUser();
        locked.setPlaidSyncCursor("cursor-won-meanwhile");
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(locked));

        assertThat(enqueuer(queued::add).enqueueIfNeeded(user)).isFalse();

        assertThat(row.getDetectionStartedAt()).isNull();
        assertThat(queued).isEmpty();
    }

    @Test
    void rejectedSubmissionFreesTheUser() {
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        AtomicBoolean poolFull = new AtomicBoolean(true);
        InitSyncEnqueuer enqueuer = enqueuer(task -> {
            if (poolFull.get()) {
                throw new TaskRejectedException("queue full");
            }
            queued.add(task);
        });

        assertThat(enqueuer.enqueueIfNeeded(user)).isFalse();
        poolFull.set(false);
        assertThat(enqueuer.enqueueIfNeeded(user)).isTrue();
        assertThat(queued).hasSize(1);
    }
}
