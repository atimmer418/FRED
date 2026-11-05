package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.PayerTokenizer;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionStreamStatus;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetRequestOptions;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only caller of /transactions/recurring/get (spec 2.6).
 *
 * <p>Plaid's inflow streams become a catalog on paycheck_streams: the eight plaid_* columns the
 * detector reads as a tiebreaker (spec 4.2 gate 3, the 4.5 Plaid-status points, the 4.4 cadence
 * tiebreak) and the raw object in plaid_snapshot_json. A stream is never a trigger and never money:
 * this class writes no schedule column, no hold, no cycle, no event row, no analytics and no alert.
 *
 * <p>Shape (PlaidItemLifecycleService.recordConsentAfterLink): read the user, call Plaid with no
 * transaction open, then one TransactionTemplate that locks the user row with findByIdForUpdate,
 * re-checks that the Item and its state are unchanged, and writes everything or nothing.
 *
 * <p>Matching, per inflow stream: the row with the same (plaid_account_id, payer token); else the
 * row on the same account that owns the most of the stream's transaction_ids through
 * paycheck_events.stream_id; else a new CANDIDATE row with role NONE. Plaid's stream_id is not a
 * key because it is unstable (spec 3.3).
 *
 * <p>Logging: INFO and above carry statuses, counts, Plaid error codes and exception class names
 * only. User and Item ids go to DEBUG. Never log a Plaid model: its toString prints descriptions
 * and amounts.
 */
@Service
public class PlaidRecurringService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidRecurringService.class);

    /** Operation name the gateway logs beside Plaid's request_id. */
    static final String OP = "transactions_recurring_get";

    // V6 widths (V6__paycheck_autopilot.sql:20-63). MySQL strict mode rejects an over-long value and
    // the rollback would repeat on every read, so every Plaid string is cut to fit.
    static final int ID_WIDTH = 80;
    static final int PLAID_CODE_WIDTH = 32;
    static final int PAYER_TOKEN_WIDTH = 64;
    static final int DISPLAY_NAME_WIDTH = 120;
    static final int DESCRIPTOR_WIDTH = 255;
    /** plaid_snapshot_json is TEXT (65,535 bytes); 16,000 characters fit even at 4 bytes each. */
    static final int SNAPSHOT_MAX_CHARS = 16_000;

    private static final String UNKNOWN = "UNKNOWN";

    public enum StreamsReadStatus { APPLIED, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS, ITEM_ERROR, FAILED }

    /**
     * inflowStreams is the size of Plaid's inflow array; matched counts streams written onto a row
     * that existed when the stream was considered (a row seeded earlier in the same read included);
     * seeded counts rows created; the rest were skipped (no account id, no description and no
     * merchant name, or an empty payer token). error is set only for ITEM_ERROR.
     */
    public record StreamsReadOutcome(StreamsReadStatus status, int inflowStreams, int matched, int seeded,
                                     PlaidApiError error) {
    }

    private final UserRepository userRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final PlaidApi plaidApi;
    private final PlaidGateway plaidGateway;
    private final EncryptionService encryptionService;
    private final TransactionTemplate writeTx;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlaidRecurringService(UserRepository userRepository,
            PaycheckStreamRepository streamRepository,
            PaycheckEventRepository eventRepository,
            PlaidApi plaidApi,
            PlaidGateway plaidGateway,
            EncryptionService encryptionService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.plaidApi = plaidApi;
        this.plaidGateway = plaidGateway;
        this.encryptionService = encryptionService;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Read the Item's recurring streams and store the inflow catalog. Never throws. Must be called
     * with no transaction open: the Plaid call would otherwise sit inside the caller's transaction.
     */
    public StreamsReadOutcome readStreams(Long userId, String itemId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            logger.error("plaid.recurring.read status=FAILED cause=caller_transaction");
            return outcome(StreamsReadStatus.FAILED);
        }
        try {
            Optional<User> found = userRepository.findById(userId);
            if (found.isEmpty() || itemId == null || !itemId.equals(found.get().getPlaidItemId())
                    || isBlank(found.get().getPlaidAccessToken())) {
                logger.info("plaid.recurring.read status=REFUSED_STALE_ITEM");
                logger.debug("plaid.recurring.read refused stale user={} item={}", userId, itemId);
                return outcome(StreamsReadStatus.REFUSED_STALE_ITEM);
            }
            User user = found.get();
            if (!readable(user.getPlaidItemStatus())) {
                logger.info("plaid.recurring.read status=REFUSED_ITEM_STATUS item_status={}", user.getPlaidItemStatus());
                logger.debug("plaid.recurring.read refused item status user={} item={}", userId, itemId);
                return outcome(StreamsReadStatus.REFUSED_ITEM_STATUS);
            }

            TransactionsRecurringGetRequest request = new TransactionsRecurringGetRequest()
                    .accessToken(encryptionService.decrypt(user.getPlaidAccessToken()))
                    .options(new TransactionsRecurringGetRequestOptions()
                            .personalFinanceCategoryVersion(PersonalFinanceCategoryVersion.V2));
            PlaidCallResult<TransactionsRecurringGetResponse> result =
                    plaidGateway.call(OP, plaidApi.transactionsRecurringGet(request));
            if (result.error() != null) {
                logger.info("plaid.recurring.read status=ITEM_ERROR code={} class={}",
                        result.error().errorCode(), result.error().errorClass());
                logger.debug("plaid.recurring.read item error user={} item={}", userId, itemId);
                return new StreamsReadOutcome(StreamsReadStatus.ITEM_ERROR, 0, 0, 0, result.error());
            }
            if (result.body() == null) {
                logger.error("plaid.recurring.read status=FAILED cause=empty_body");
                return outcome(StreamsReadStatus.FAILED);
            }
            List<TransactionStream> inflow = result.body().getInflowStreams() == null
                    ? List.of()
                    : result.body().getInflowStreams();
            LocalDateTime nowET = EasternTime.now();

            StreamsReadOutcome applied = writeTx.execute(status -> apply(userId, itemId, inflow, nowET));
            logger.info("plaid.recurring.read status={} inflow={} matched={} seeded={}",
                    applied.status(), applied.inflowStreams(), applied.matched(), applied.seeded());
            logger.debug("plaid.recurring.read done user={} item={}", userId, itemId);
            return applied;
        } catch (RuntimeException e) {
            // Class name only: a persistence exception message can quote the row's values.
            logger.error("plaid.recurring.read status=FAILED cause={}", e.getClass().getSimpleName());
            return outcome(StreamsReadStatus.FAILED);
        }
    }

    /** Runs inside writeTx: lock, re-check, match, write. */
    private StreamsReadOutcome apply(Long userId, String itemId, List<TransactionStream> inflow, LocalDateTime nowET) {
        Optional<User> lockedUser = userRepository.findByIdForUpdate(userId);
        if (lockedUser.isEmpty() || !itemId.equals(lockedUser.get().getPlaidItemId())
                || isBlank(lockedUser.get().getPlaidAccessToken())) {
            // A relink won the race while Plaid answered: this read belongs to an Item the user no longer has.
            return outcome(StreamsReadStatus.REFUSED_STALE_ITEM);
        }
        User user = lockedUser.get();
        if (!readable(user.getPlaidItemStatus())) {
            // The Item broke or was revoked while Plaid answered; writing now would resurrect purged data.
            return outcome(StreamsReadStatus.REFUSED_ITEM_STATUS);
        }

        List<PaycheckStream> rows = new ArrayList<>(streamRepository.findByUserIdOrderByIdAsc(userId));
        Map<String, Long> streamIdByTransaction = new HashMap<>();
        for (PaycheckEvent event : eventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(
                userId, PaycheckEventStatus.ACTIVE)) {
            if (event.getStreamId() != null && event.getPlaidTransactionId() != null) {
                streamIdByTransaction.put(event.getPlaidTransactionId(), event.getStreamId());
            }
        }

        Map<PaycheckStream, Integer> writtenWith = new IdentityHashMap<>();
        int matched = 0;
        int seeded = 0;
        for (TransactionStream stream : inflow) {
            String accountId = ColumnText.fit(stream.getAccountId(), ID_WIDTH);
            if (isBlank(accountId) || (isBlank(stream.getDescription()) && isBlank(stream.getMerchantName()))) {
                continue;
            }
            String token = ColumnText.fit(PayerTokenizer.token(stream.getDescription(), stream.getMerchantName()),
                    PAYER_TOKEN_WIDTH);
            if (isBlank(token)) {
                continue;
            }
            List<String> transactionIds = stream.getTransactionIds() == null ? List.of() : stream.getTransactionIds();
            Optional<PaycheckStream> existing = byPayerToken(rows, accountId, token)
                    .or(() -> byTransactionIds(rows, accountId, transactionIds, streamIdByTransaction));

            PaycheckStream row;
            if (existing.isPresent()) {
                row = existing.get();
                matched++;
                Integer previous = writtenWith.get(row);
                if (previous != null && previous >= transactionIds.size()) {
                    continue; // Plaid split one payer in two; the stream with more history already wrote this row
                }
            } else {
                row = seed(userId, accountId, token, stream, nowET);
                rows.add(row);
                seeded++;
            }
            writeCatalog(row, itemId, stream, nowET);
            writtenWith.put(row, transactionIds.size());
            streamRepository.save(row);
        }

        user.setPlaidStreamsReadAt(nowET);
        userRepository.save(user);
        return new StreamsReadOutcome(StreamsReadStatus.APPLIED, inflow.size(), matched, seeded, null);
    }

    /** Case-insensitive, as the utf8mb4_0900_ai_ci unique key uq_paycheck_streams_payer compares. */
    private static Optional<PaycheckStream> byPayerToken(List<PaycheckStream> rows, String accountId, String token) {
        return rows.stream()
                .filter(r -> accountId.equalsIgnoreCase(r.getPlaidAccountId()) && token.equalsIgnoreCase(r.getPayerToken()))
                .findFirst();
    }

    /** The row on the same account owning the most of these transaction ids; rows are id-ascending, so a tie keeps the older row. */
    private static Optional<PaycheckStream> byTransactionIds(List<PaycheckStream> rows, String accountId,
            List<String> transactionIds, Map<String, Long> streamIdByTransaction) {
        Map<Long, Integer> overlap = new HashMap<>();
        for (String transactionId : transactionIds) {
            Long streamId = streamIdByTransaction.get(transactionId);
            if (streamId != null) {
                overlap.merge(streamId, 1, Integer::sum);
            }
        }
        if (overlap.isEmpty()) {
            return Optional.empty();
        }
        PaycheckStream best = null;
        int bestCount = 0;
        for (PaycheckStream row : rows) {
            if (row.getId() == null || !accountId.equalsIgnoreCase(row.getPlaidAccountId())) {
                continue;
            }
            int count = overlap.getOrDefault(row.getId(), 0);
            if (count > bestCount) {
                best = row;
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }

    /** A new catalog row: CANDIDATE, role NONE; evaluate (T11) decides everything else. */
    private static PaycheckStream seed(Long userId, String accountId, String token, TransactionStream stream,
            LocalDateTime nowET) {
        String displayName = PayerTokenizer.displayName(null, null, stream.getMerchantName(), token);
        PaycheckStream row = new PaycheckStream();
        row.setUserId(userId);
        row.setPlaidAccountId(accountId);
        row.setPayerToken(token);
        row.setPayerDisplayName(ColumnText.fit(isBlank(displayName) ? token : displayName, DISPLAY_NAME_WIDTH));
        row.setRawDescriptor(ColumnText.fit(stream.getDescription(), DESCRIPTOR_WIDTH));
        row.setState(PaycheckStreamState.CANDIDATE);
        row.setRole(PaycheckStreamRole.NONE);
        row.setCreatedAt(nowET);
        return row;
    }

    /** The eight plaid_* columns plus the snapshot. Never touches state, role, token, cadence, counters or amounts. */
    private void writeCatalog(PaycheckStream row, String itemId, TransactionStream stream, LocalDateTime nowET) {
        PersonalFinanceCategory pfc = stream.getPersonalFinanceCategory();
        row.setPlaidItemId(ColumnText.fit(itemId, ID_WIDTH));
        row.setPlaidStreamId(ColumnText.fit(stream.getStreamId(), ID_WIDTH));
        row.setPlaidStatus(ColumnText.fit(statusWire(stream.getStatus()), PLAID_CODE_WIDTH));
        row.setPlaidFrequency(ColumnText.fit(frequencyWire(stream.getFrequency()), PLAID_CODE_WIDTH));
        row.setPlaidPfcDetailed(pfc == null ? null : ColumnText.fit(pfc.getDetailed(), PLAID_CODE_WIDTH));
        row.setPlaidPfcConfidence(pfc == null ? null : ColumnText.fit(pfc.getConfidenceLevel(), PLAID_CODE_WIDTH));
        row.setPlaidIsActive(stream.getIsActive());
        row.setPlaidPredictedNextDate(stream.getPredictedNextDate());
        row.setPlaidSnapshotJson(snapshotJson(stream));
        row.setUpdatedAt(nowET);
    }

    /** Wire string; a value newer than plaid-java 46.0.0 (ENUM_UNKNOWN) or a missing one is UNKNOWN, which the hard gate rejects. */
    static String statusWire(TransactionStreamStatus status) {
        return status == null || status == TransactionStreamStatus.ENUM_UNKNOWN ? UNKNOWN : status.getValue();
    }

    static String frequencyWire(RecurringTransactionFrequency frequency) {
        return frequency == null || frequency == RecurringTransactionFrequency.ENUM_UNKNOWN ? UNKNOWN : frequency.getValue();
    }

    /** The spec 2.11 stream fields in Plaid's snake_case wire names, amounts with Plaid's own sign. */
    String snapshotJson(TransactionStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stream_id", stream.getStreamId());
        node.put("account_id", stream.getAccountId());
        node.put("description", stream.getDescription());
        node.put("merchant_name", stream.getMerchantName());
        node.put("frequency", frequencyWire(stream.getFrequency()));
        node.put("status", statusWire(stream.getStatus()));
        node.put("is_active", stream.getIsActive());
        node.put("first_date", iso(stream.getFirstDate()));
        node.put("last_date", iso(stream.getLastDate()));
        node.put("predicted_next_date", iso(stream.getPredictedNextDate()));
        ArrayNode ids = node.putArray("transaction_ids");
        if (stream.getTransactionIds() != null) {
            for (String id : stream.getTransactionIds()) {
                ids.add(id);
            }
        }
        putAmount(node, "average_amount", stream.getAverageAmount());
        putAmount(node, "last_amount", stream.getLastAmount());
        PersonalFinanceCategory pfc = stream.getPersonalFinanceCategory();
        if (pfc == null) {
            node.putNull("personal_finance_category");
        } else {
            ObjectNode category = node.putObject("personal_finance_category");
            category.put("primary", pfc.getPrimary());
            category.put("detailed", pfc.getDetailed());
            category.put("confidence_level", pfc.getConfidenceLevel());
        }
        String json = node.toString();
        if (json.length() > SNAPSHOT_MAX_CHARS) {
            logger.info("plaid.recurring.snapshot_dropped chars={}", json.length());
            return null;
        }
        return json;
    }

    private static void putAmount(ObjectNode node, String field, TransactionStreamAmount amount) {
        if (amount == null) {
            node.putNull(field);
            return;
        }
        ObjectNode value = node.putObject(field);
        value.put("amount", amount.getAmount());
        value.put("iso_currency_code", amount.getIsoCurrencyCode());
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static StreamsReadOutcome outcome(StreamsReadStatus status) {
        return new StreamsReadOutcome(status, 0, 0, 0, null);
    }

    private static boolean readable(PlaidItemStatus status) {
        return status == PlaidItemStatus.OK || status == PlaidItemStatus.PENDING_DISCONNECT;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
