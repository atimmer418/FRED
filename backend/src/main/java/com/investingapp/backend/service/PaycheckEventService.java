package com.investingapp.backend.service;

import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.PlaidAmounts;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.PaymentMeta;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionCounterparty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns one /transactions/sync buffer into paycheck_events rows (spec 3.4, 4.8; Phase 1 has no
 * cycle voids, so removedEventIds and modifiedEventIds are handed back for Phase 2).
 *
 * <p>Only inflow candidates are stored: Plaid amount below zero on a depository account. Pending
 * inflows are stored too, so their posted twin can supersede them. Every write is replay-safe on
 * plaid_transaction_id, which is unique across the whole table. The order is added, then modified,
 * then removed, so a posted twin supersedes its pending row before removed[] reaches it.
 *
 * <p>MANDATORY: the caller (PlaidTransactionsSyncService) holds the user row lock in its own transaction and makes every
 * Plaid call before opening it. These rows are payroll data: nothing here logs a descriptor,
 * name, amount, Plaid id or date.
 */
@Service
public class PaycheckEventService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckEventService.class);

    /** Column widths of paycheck_events as V6 built them (V6__paycheck_autopilot.sql:65-97). */
    static final int ID_WIDTH = 80;
    static final int RAW_DESCRIPTOR_WIDTH = 255;
    static final int MERCHANT_NAME_WIDTH = 120;
    static final int COUNTERPARTY_NAME_WIDTH = 120;
    static final int COUNTERPARTY_TYPE_WIDTH = 32;
    static final int PFC_WIDTH = 40;
    static final int PAYMENT_CHANNEL_WIDTH = 12;
    static final int PPD_ID_WIDTH = 64;

    /** Plaid's counterparty confidence below which the name is not trusted (spec 4.3). */
    static final String LOW_CONFIDENCE = "LOW";

    private final PaycheckEventRepository eventRepository;

    public PaycheckEventService(PaycheckEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** Everything one sync returned, across all its pages. Null lists become empty. */
    public record SyncBatch(List<Transaction> added, List<Transaction> modified,
                            List<RemovedTransaction> removed, List<AccountBase> accounts) {
        public SyncBatch {
            added = copy(added);
            modified = copy(modified);
            removed = copy(removed);
            accounts = copy(accounts);
        }

        private static <T> List<T> copy(List<T> list) {
            return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    /**
     * newPostedInflowEventIds: rows inserted posted, plus stored pending rows a modification turned
     * posted (spec 4.7 acts on both). removedEventIds: rows moved to REMOVED. modifiedEventIds:
     * stored rows updated from modified[]. ignored: entries that produced no write.
     */
    public record ApplyResult(List<Long> newPostedInflowEventIds, List<Long> removedEventIds,
                              List<Long> modifiedEventIds, int ignored) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApplyResult applySyncBatch(Long userId, SyncBatch batch, LocalDateTime nowET) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(batch, "batch");
        Map<String, AccountType> accountTypes = accountTypes(batch.accounts());
        Tally tally = new Tally();

        for (Transaction t : batch.added()) {
            if (!usableId(t)) {
                tally.ignored++;
                continue;
            }
            Optional<PaycheckEvent> stored = eventRepository.findByPlaidTransactionId(t.getTransactionId());
            if (stored.isPresent()) {
                // Replay (a cursor restart re-sends added[]): the first row stands.
                if (!userId.equals(stored.get().getUserId())) {
                    tally.foreign++;
                }
                tally.ignored++;
            } else if (!insertCandidate(userId, t, accountTypes, nowET, tally)) {
                tally.ignored++;
            }
        }

        for (Transaction t : batch.modified()) {
            if (!usableId(t)) {
                tally.ignored++;
                continue;
            }
            Optional<PaycheckEvent> stored = eventRepository.findByPlaidTransactionId(t.getTransactionId());
            boolean written = stored.isPresent()
                    ? applyModification(userId, stored.get(), t, nowET, tally)
                    // A transaction ignored when added (an outflow, say) can come back as an inflow.
                    : insertCandidate(userId, t, accountTypes, nowET, tally);
            if (!written) {
                tally.ignored++;
            }
        }

        for (RemovedTransaction r : batch.removed()) {
            if (r == null || r.getTransactionId() == null) {
                tally.ignored++;
                continue;
            }
            Optional<PaycheckEvent> stored = eventRepository.findByPlaidTransactionId(r.getTransactionId());
            if (stored.isEmpty() || !applyRemoval(userId, stored.get(), nowET, tally)) {
                tally.ignored++;
            }
        }

        if (tally.foreign > 0) {
            logger.warn("paycheck.events.foreign_rows count={}", tally.foreign);
        }
        logger.debug("paycheck.events.applied user={} posted={} modified={} removed={} ignored={}",
                userId, tally.posted.size(), tally.modified.size(), tally.removed.size(), tally.ignored);
        return new ApplyResult(List.copyOf(tally.posted), List.copyOf(tally.removed),
                List.copyOf(tally.modified), tally.ignored);
    }

    /**
     * One Plaid transaction as a new ACTIVE row. Descriptor is original_description, else name;
     * the counterparty is counterparties[0] unless its confidence is LOW (then both fields stay
     * null); enum-backed strings are the wire value (getValue()); every string is cut to its V6
     * width. Ids are never cut: an over-long pending id is dropped, since matching never needs it.
     */
    public static PaycheckEvent fromTransaction(Long userId, Transaction t, LocalDateTime nowET) {
        PaycheckEvent e = new PaycheckEvent();
        e.setUserId(userId);
        e.setPlaidTransactionId(t.getTransactionId());
        e.setPendingTransactionId(idOrNull(t.getPendingTransactionId()));
        e.setPlaidAccountId(t.getAccountId());
        e.setRawAmount(t.getAmount() == null ? null : BigDecimal.valueOf(t.getAmount()));
        e.setAmount(PlaidAmounts.inflow(t.getAmount()));
        e.setPostedDate(t.getDate());
        e.setAuthorizedDate(t.getAuthorizedDate());
        e.setPending(Boolean.TRUE.equals(t.getPending()));
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(nowET);
        e.setRawDescriptor(ColumnText.fit(descriptor(t), RAW_DESCRIPTOR_WIDTH));
        e.setMerchantName(ColumnText.fit(t.getMerchantName(), MERCHANT_NAME_WIDTH));

        TransactionCounterparty counterparty = trustedCounterparty(t);
        if (counterparty != null) {
            e.setCounterpartyName(ColumnText.fit(counterparty.getName(), COUNTERPARTY_NAME_WIDTH));
            e.setCounterpartyType(ColumnText.fit(
                    counterparty.getType() == null ? null : counterparty.getType().getValue(), COUNTERPARTY_TYPE_WIDTH));
        }

        PersonalFinanceCategory pfc = t.getPersonalFinanceCategory();
        if (pfc != null) {
            e.setPfcPrimary(ColumnText.fit(pfc.getPrimary(), PFC_WIDTH));
            e.setPfcDetailed(ColumnText.fit(pfc.getDetailed(), PFC_WIDTH));
            e.setPfcConfidence(ColumnText.fit(pfc.getConfidenceLevel(), PFC_WIDTH));
        }

        if (t.getPaymentChannel() != null) {
            e.setPaymentChannel(ColumnText.fit(t.getPaymentChannel().getValue(), PAYMENT_CHANNEL_WIDTH));
        }

        PaymentMeta meta = t.getPaymentMeta();
        if (meta != null) {
            e.setPpdId(ColumnText.fit(meta.getPpdId(), PPD_ID_WIDTH));
        }
        return e;
    }

    /** Spec 4.2 gates (1) and (2) at storage: money in, on a depository account, with a date. */
    static boolean isInflowCandidate(Transaction t, Map<String, AccountType> accountTypes) {
        return t.getAmount() != null && t.getAmount() < 0
                && t.getDate() != null
                && t.getAccountId() != null && t.getAccountId().length() <= ID_WIDTH
                && accountTypes.get(t.getAccountId()) == AccountType.DEPOSITORY;
    }

    static String descriptor(Transaction t) {
        String original = t.getOriginalDescription();
        return original != null && !original.isBlank() ? original : t.getName();
    }

    static TransactionCounterparty trustedCounterparty(Transaction t) {
        List<TransactionCounterparty> parties = t.getCounterparties();
        if (parties == null || parties.isEmpty() || parties.get(0) == null) {
            return null;
        }
        TransactionCounterparty first = parties.get(0);
        return LOW_CONFIDENCE.equalsIgnoreCase(first.getConfidenceLevel()) ? null : first;
    }

    private boolean insertCandidate(Long userId, Transaction t, Map<String, AccountType> accountTypes,
                                    LocalDateTime nowET, Tally tally) {
        if (!isInflowCandidate(t, accountTypes)) {
            return false;
        }
        PaycheckEvent saved = eventRepository.save(fromTransaction(userId, t, nowET));
        if (!saved.isPending()) {
            tally.posted.add(saved.getId());
            supersedePendingTwin(userId, saved);
        }
        return true;
    }

    /** The posted row's pending_transaction_id is the link; the pending row is marked SUPERSEDED. */
    private void supersedePendingTwin(Long userId, PaycheckEvent posted) {
        String pendingId = posted.getPendingTransactionId();
        if (pendingId == null) {
            return; // institutions without pending data: nothing to link (spec 4.8)
        }
        eventRepository.findByPlaidTransactionId(pendingId)
                .filter(p -> userId.equals(p.getUserId()))
                .filter(p -> p.isPending() && p.getStatus() != PaycheckEventStatus.SUPERSEDED)
                .ifPresent(p -> {
                    p.setStatus(PaycheckEventStatus.SUPERSEDED);
                    eventRepository.save(p);
                });
    }

    /**
     * Spec 4.8 modified: amount, date, pending, descriptor and modified_at. The status is kept
     * (MODIFIED is not written in Phase 1); amount stays -raw_amount, so a restatement to money out
     * reads negative and spec 4.1's raw_amount filter drops it.
     */
    private boolean applyModification(Long userId, PaycheckEvent e, Transaction t, LocalDateTime nowET, Tally tally) {
        if (!userId.equals(e.getUserId())) {
            tally.foreign++;
            return false;
        }
        if (e.getStatus() == PaycheckEventStatus.REMOVED || e.getStatus() == PaycheckEventStatus.SUPERSEDED) {
            return false;
        }
        if (t.getAmount() == null || t.getDate() == null) {
            return false;
        }
        boolean wasPending = e.isPending();
        e.setRawAmount(BigDecimal.valueOf(t.getAmount()));
        e.setAmount(PlaidAmounts.inflow(t.getAmount()));
        e.setPostedDate(t.getDate());
        e.setPending(Boolean.TRUE.equals(t.getPending()));
        e.setRawDescriptor(ColumnText.fit(descriptor(t), RAW_DESCRIPTOR_WIDTH));
        e.setModifiedAt(nowET);
        eventRepository.save(e);
        tally.modified.add(e.getId());
        if (wasPending && !e.isPending() && e.getRawAmount().signum() < 0) {
            tally.posted.add(e.getId());
        }
        return true;
    }

    /**
     * Spec 4.8 removed: REMOVED with removed_at, once. A SUPERSEDED pending row keeps its status
     * (its posted twin carries on) and only gets removed_at; it never confirmed anything.
     */
    private boolean applyRemoval(Long userId, PaycheckEvent e, LocalDateTime nowET, Tally tally) {
        if (!userId.equals(e.getUserId())) {
            tally.foreign++;
            return false;
        }
        if (e.getRemovedAt() != null) {
            return false; // replay
        }
        e.setRemovedAt(nowET);
        if (e.getStatus() != PaycheckEventStatus.SUPERSEDED) {
            e.setStatus(PaycheckEventStatus.REMOVED);
            tally.removed.add(e.getId());
        }
        eventRepository.save(e);
        return true;
    }

    private static boolean usableId(Transaction t) {
        return t != null && t.getTransactionId() != null && t.getTransactionId().length() <= ID_WIDTH;
    }

    private static String idOrNull(String id) {
        return id != null && id.length() <= ID_WIDTH ? id : null;
    }

    private static Map<String, AccountType> accountTypes(List<AccountBase> accounts) {
        Map<String, AccountType> types = new HashMap<>();
        for (AccountBase account : accounts) {
            if (account != null && account.getAccountId() != null && account.getType() != null) {
                types.put(account.getAccountId(), account.getType());
            }
        }
        return types;
    }

    private static final class Tally {
        final List<Long> posted = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final List<Long> modified = new ArrayList<>();
        int ignored;
        int foreign;
    }
}
