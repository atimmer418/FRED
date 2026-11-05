package com.investingapp.backend.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.admin.PlaidItemRow;
import com.investingapp.backend.security.AdminKeyFilter;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real SQL on the V6 columns, one commit for remove-item's three writes, and MANDATORY through the real proxy. */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class AdminPlaidItemsDatabaseTest {

    private static final String ACTOR = "t17-database-test";

    @Autowired
    private AdminPlaidController controller;

    @Autowired
    private AdminAuditService adminAuditService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM admin_actions WHERE actor = ?", ACTOR);
        for (Long id : userIds) {
            jdbc.update("DELETE FROM plaid_item_removals WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private Long seedLinkedUser(String publicId, String token, String cursor) {
        String email = "admin-items-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, plaid_access_token, "
                        + "plaid_item_id, plaid_sync_cursor, plaid_item_status, plaid_history_complete) "
                        + "VALUES (?, ?, ?, 0, ?, ?, ?, 'OK', 1)",
                email, EasternTime.now(), publicId, token, "item-" + publicId.substring(0, 8), cursor);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        userIds.add(id);
        return id;
    }

    private MockHttpServletRequest adminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE, ACTOR);
        return request;
    }

    private int auditRows(String verb) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_actions WHERE actor = ? AND verb = ?",
                Integer.class, ACTOR, verb);
    }

    private int pendingRemovals(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM plaid_item_removals WHERE user_id = ? AND removed_at IS NULL",
                Integer.class, userId);
    }

    @Test
    void listingNeverCarriesTokenOrCursor() throws Exception {
        String publicId = UUID.randomUUID().toString();
        seedLinkedUser(publicId, "ENC:token-that-must-not-leak", "cursor-that-must-not-leak");

        ResponseEntity<List<PlaidItemRow>> response = controller.items(adminRequest());

        PlaidItemRow row = response.getBody().stream()
                .filter(r -> publicId.equals(r.publicId())).findFirst().orElseThrow();
        assertThat(row.itemStatus()).isEqualTo("OK");
        assertThat(row.hasCursor()).isTrue();
        assertThat(row.historyComplete()).isTrue();
        assertThat(row.transactionsConsented()).isNull();
        assertThat(row.pendingRemoval()).isFalse();
        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(json).doesNotContain("token-that-must-not-leak").doesNotContain("cursor-that-must-not-leak");
        assertThat(auditRows(AdminPlaidController.VERB_ITEMS)).isEqualTo(1);
    }

    @Test
    void removeItemCommitsQueueStatusAndAuditTogether() {
        Long id = seedLinkedUser(UUID.randomUUID().toString(), "ENC:token-to-remove", null);

        assertThat(controller.removeItem(id, "beta cleanup", adminRequest()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(pendingRemovals(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plaid_item_status FROM users WHERE id = ?", String.class, id))
                .isEqualTo("REMOVED");
        String after = jdbc.queryForObject("SELECT after_json FROM admin_actions WHERE actor = ? AND user_id = ?",
                String.class, ACTOR, id);
        assertThat(after).contains("\"itemStatus\":\"REMOVED\"").doesNotContain("token-to-remove");

        assertThat(controller.removeItem(id, null, adminRequest()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pendingRemovals(id)).isEqualTo(1);
        assertThat(auditRows(AdminPlaidController.VERB_REMOVE_ITEM)).isEqualTo(2);
    }

    @Test
    void recordInTransactionRefusesWithoutTransaction() {
        assertThatThrownBy(() -> adminAuditService.recordInTransaction(ACTOR, null, "test.verb", null, null, null, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(auditRows("test.verb")).isZero();
    }
}
