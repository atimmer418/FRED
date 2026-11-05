package com.investingapp.backend.support;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingTransactionManagerTest {

    private final RecordingTransactionManager tx = new RecordingTransactionManager();

    @Test
    void openOnlyWhileTemplateRuns() {
        assertThat(tx.open).isFalse();

        new TransactionTemplate(tx).executeWithoutResult(status -> {
            assertThat(tx.open).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        });

        assertThat(tx.open).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(tx.begins.get()).isEqualTo(1);
        assertThat(tx.commits.get()).isEqualTo(1);
        assertThat(tx.rollbacks.get()).isZero();
    }

    @Test
    void rollbackClosesIt() {
        assertThatThrownBy(() -> new TransactionTemplate(tx).executeWithoutResult(status -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tx.open).isFalse();
        assertThat(tx.rollbacks.get()).isEqualTo(1);
        assertThat(tx.commits.get()).isZero();
    }

    @Test
    void requiresNewNestsAndOuterStaysOpen() {
        TransactionTemplate inner = new TransactionTemplate(tx);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(tx).executeWithoutResult(outer -> {
            inner.executeWithoutResult(status -> assertThat(tx.open).isTrue());
            assertThat(tx.open).as("the outer transaction is still open after the inner commits").isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        assertThat(tx.open).isFalse();
        assertThat(tx.begins.get()).isEqualTo(2);
        assertThat(tx.commits.get()).isEqualTo(2);
    }

    @Test
    void nestedRequiredParticipates() {
        TransactionTemplate template = new TransactionTemplate(tx);

        template.executeWithoutResult(outer ->
                template.executeWithoutResult(inner -> assertThat(tx.open).isTrue()));

        assertThat(tx.begins.get()).isEqualTo(1);
        assertThat(tx.commits.get()).isEqualTo(1);
        assertThat(tx.open).isFalse();
    }
}
