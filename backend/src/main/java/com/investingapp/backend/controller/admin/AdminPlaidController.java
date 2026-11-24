package com.investingapp.backend.controller.admin;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.PlaidItemRow;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.PlaidItemLifecycleService;
import com.investingapp.backend.service.UserRowLock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Plaid Item verbs (spec 12.2); no @CrossOrigin. remove-item queues /item/remove and marks
 * REMOVED with its audit row in one transaction under the row lock; schedule, status, holds and the
 * ACH relationship are untouched, and a user still waiting for an ACH relationship cannot get one.
 */
@RestController
@RequestMapping("/api/admin/plaid")
public class AdminPlaidController {

    static final String VERB_ITEMS = "plaid.items";
    static final String VERB_REMOVE_ITEM = "plaid.remove-item";

    /** Selects no secret: the token and the cursor appear only inside IS NOT NULL tests. */
    static final String ITEMS_SQL = """
            SELECT u.public_id, u.plaid_item_status, u.plaid_transactions_consented,
                   u.plaid_sync_cursor IS NOT NULL AS has_cursor, u.plaid_history_complete,
                   u.plaid_last_sync_at, u.plaid_item_linked_at,
                   EXISTS (SELECT 1 FROM plaid_item_removals r
                           WHERE r.user_id = u.id AND r.removed_at IS NULL) AS pending_removal
            FROM users u
            WHERE u.plaid_access_token IS NOT NULL
            ORDER BY u.id
            """;

    enum RemoveOutcome { QUEUED, NOT_FOUND, NO_ITEM, ALREADY_REMOVED }

    record RemoveResult(RemoveOutcome outcome, String itemId, Map<String, Object> before) {
    }

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final PlaidItemLifecycleService plaidItemLifecycleService;
    private final AdminAuditService adminAuditService;
    private final UserRowLock userRowLock;
    private final TransactionTemplate writeTx;

    public AdminPlaidController(JdbcTemplate jdbc, UserRepository userRepository,
            PlaidItemLifecycleService plaidItemLifecycleService, AdminAuditService adminAuditService,
            UserRowLock userRowLock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.plaidItemLifecycleService = plaidItemLifecycleService;
        this.adminAuditService = adminAuditService;
        this.userRowLock = userRowLock;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    @GetMapping("/items")
    public ResponseEntity<List<PlaidItemRow>> items(HttpServletRequest request) {
        String actor = adminAuditService.actor(request);
        List<PlaidItemRow> rows = jdbc.query(ITEMS_SQL, (rs, rowNum) -> toRow(rs));
        adminAuditService.record(actor, null, VERB_ITEMS, null, null, Map.of("rows", rows.size()), null);
        return ResponseEntity.ok(rows);
    }

    /** 200 QUEUED; 404 unknown user; 409 NO_PLAID_ITEM or ITEM_ALREADY_REMOVED; every outcome audited. */
    @PostMapping("/{userId}/remove-item")
    public ResponseEntity<?> removeItem(@PathVariable("userId") Long userId,
            @RequestParam(value = "reason", required = false) String reason, HttpServletRequest request) {
        String actor = adminAuditService.actor(request);
        RemoveResult result = writeTx.execute(status -> userRowLock.lockFresh(userId)
                .map(user -> removeLocked(user, actor, reason))
                .orElseGet(() -> new RemoveResult(RemoveOutcome.NOT_FOUND, null, null)));
        if (result.outcome() != RemoveOutcome.QUEUED) {
            // A refusal wrote nothing, so its audit row commits on its own.
            adminAuditService.record(actor, userId, VERB_REMOVE_ITEM, result.itemId(), result.before(),
                    Map.of("outcome", result.outcome().name()), reason);
        }
        return switch (result.outcome()) {
            case QUEUED -> ResponseEntity.ok(Map.of("result", "QUEUED"));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NO_ITEM -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("NO_PLAID_ITEM", "This user has no linked Plaid Item."));
            case ALREADY_REMOVED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("ITEM_ALREADY_REMOVED", "This Item is already REMOVED and queued."));
        };
    }

    private RemoveResult removeLocked(User user, String actor, String reason) {
        Map<String, Object> before = itemState(user, false);
        if (user.getPlaidAccessToken() == null || user.getPlaidAccessToken().isBlank()) {
            return new RemoveResult(RemoveOutcome.NO_ITEM, user.getPlaidItemId(), before);
        }
        if (user.getPlaidItemStatus() == PlaidItemStatus.REMOVED) {
            return new RemoveResult(RemoveOutcome.ALREADY_REMOVED, user.getPlaidItemId(), before);
        }
        plaidItemLifecycleService.enqueueRemoval(user);
        user.setPlaidItemStatus(PlaidItemStatus.REMOVED);
        userRepository.save(user);
        adminAuditService.recordInTransaction(actor, user.getId(), VERB_REMOVE_ITEM, user.getPlaidItemId(),
                before, itemState(user, true), reason);
        return new RemoveResult(RemoveOutcome.QUEUED, user.getPlaidItemId(), before);
    }

    /** Closed vocabulary only: the status name and two flags, never the token. */
    private static Map<String, Object> itemState(User user, boolean removalQueued) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("itemStatus", user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name());
        state.put("hasItem", user.getPlaidAccessToken() != null && !user.getPlaidAccessToken().isBlank());
        state.put("removalQueued", removalQueued);
        return state;
    }

    private static PlaidItemRow toRow(ResultSet rs) throws SQLException {
        return new PlaidItemRow(
                rs.getString("public_id"),
                rs.getString("plaid_item_status"),
                nullableBoolean(rs, "plaid_transactions_consented"),
                rs.getBoolean("has_cursor"),
                rs.getBoolean("plaid_history_complete"),
                rs.getObject("plaid_last_sync_at", LocalDateTime.class),
                rs.getObject("plaid_item_linked_at", LocalDateTime.class),
                rs.getBoolean("pending_removal"));
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
