package com.investingapp.backend.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real transaction manager with no database, for proving that code runs inside or outside a
 * transaction. TransactionTemplate over it begins a transaction, binds synchronization and makes
 * TransactionSynchronizationManager.isActualTransactionActive() true, which a Mockito
 * PlatformTransactionManager never does.
 *
 * <p>Usage (T08, T10, T14, T16): build the service with {@code new TransactionTemplate(tx)} or pass
 * {@code tx} where it takes a PlatformTransactionManager, and make the mocked
 * {@code Call.execute()} answer record {@code tx.open}; assert it was false and that
 * {@code tx.begins} is above 0, so the test proves both that a transaction ran and that the Plaid
 * call was not inside it.
 *
 * <p>REQUIRED participates in an open transaction, REQUIRES_NEW suspends and nests (open stays
 * true until the outer one ends), NOT_SUPPORTED suspends (open stays true while the outer one is
 * suspended, though isActualTransactionActive() is false there). Rollback-only marks set by a
 * participating scope are not tracked.
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    /** True while at least one transaction begun here has not yet committed or rolled back. */
    public volatile boolean open;

    public final AtomicInteger begins = new AtomicInteger();
    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();

    private final AtomicInteger openCount = new AtomicInteger();
    private final ThreadLocal<Object> current = new ThreadLocal<>();

    private static final class TxObject {
        private Object marker;
    }

    @Override
    protected Object doGetTransaction() {
        TxObject transaction = new TxObject();
        transaction.marker = current.get();
        return transaction;
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((TxObject) transaction).marker != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Object marker = new Object();
        ((TxObject) transaction).marker = marker;
        current.set(marker);
        begins.incrementAndGet();
        openCount.incrementAndGet();
        open = true;
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Object suspended = current.get();
        ((TxObject) transaction).marker = null;
        current.remove();
        return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        current.set(suspendedResources);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits.incrementAndGet();
        close();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks.incrementAndGet();
        close();
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        // A participating scope failed; the outer scope's own commit or rollback is what tests read.
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        current.remove();
    }

    private void close() {
        open = openCount.decrementAndGet() > 0;
    }
}
