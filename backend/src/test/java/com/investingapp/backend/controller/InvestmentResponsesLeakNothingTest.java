package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentTrade;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The investment history, dashboard and execution routes used to put the InvestmentExecution
 * entity straight into the response. Jackson followed it into the whole User record (PIN hash, SSN
 * ciphertext and hash, recovery code, bank and broker ids), then looped between the user and their
 * passkeys until it gave up, so the caller got a broken body with those values in it.
 *
 * Every value that must never leave the server is seeded here as a recognisable marker, so the
 * check is simply that no marker appears anywhere in any answer.
 *
 * Runs through the real security filter chain, real controllers, the real JSON mapper and real SQL.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class InvestmentResponsesLeakNothingTest {

    /** Every seeded secret carries this, so one search finds any of them. */
    private static final String MARK = "LEAKMARK";

    /** Names that belong to the user record or to internal plumbing, never to these answers. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "user", "pinHash", "ssn", "ssnHash", "recoveryOtp", "userHandle", "passkeyCredentials",
            "userProgress", "plaidAccessToken", "plaidItemId", "plaidSyncCursor", "alpacaAccountId",
            "alpacaAccountNumber", "alpacaAchRelationshipId", "previousAchRelationshipId", "deviceId",
            "devicePushToken", "registrationIpAddress", "email", "dateOfBirth", "investmentExecution",
            "schedule", "sourceKey", "nightKey", "alpacaTransferId", "alpacaOrderId", "errorMessage",
            "userId");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper appMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private InvestmentExecutionRepository executionRepository;
    @Autowired private InvestmentTradeRepository tradeRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Value("${app.dev-tools.key}") private String devToolsKey;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private String email;
    private Long userId;
    private Long executionId;
    private Long otherUserId;
    private Long otherExecutionId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "leak-" + UUID.randomUUID() + "@fredvested.test";
        userId = seedUser(email);
        executionId = seedExecution(userId);
        jdbc.update("INSERT INTO investment_trades (user_id, investment_execution_id, symbol, notional_amount, "
                + "filled_quantity, filled_avg_price, status, alpaca_order_id, error_message, created_at, updated_at) "
                + "VALUES (?, ?, 'VTI', 60.00, 0.250000, 240.00, 'FILLED', ?, ?, NOW(), NOW())",
                userId, executionId, MARK + "-order", MARK + "-trade-error");

        otherUserId = seedUser("leak-other-" + UUID.randomUUID() + "@fredvested.test");
        otherExecutionId = seedExecution(otherUserId);
    }

    private Long seedUser(String address) {
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, user_handle, first_name, "
                + "pin_hash, ssn, ssn_hash, recovery_otp, plaid_access_token, plaid_item_id, plaid_sync_cursor, "
                + "alpaca_account_id, alpaca_account_number, alpaca_ach_relationship_id, "
                + "previous_ach_relationship_id, device_id, device_push_token, registration_ip_address, "
                + "monthly_investment, pay_frequency) "
                + "VALUES (?, ?, ?, 0, ?, 'Leaky', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 400.0, 'biweekly')",
                address, LocalDateTime.now(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 22),
                MARK + "-pin", "ENC:" + MARK + "-ssn", MARK + "-ssnhash", "ENC:" + MARK + "-otp",
                "ENC:" + MARK + "-plaid", MARK + "-item", MARK + "-cursor", "ENC:" + MARK + "-acct",
                "ENC:" + MARK + "-acctnum", "ENC:" + MARK + "-ach", "ENC:" + MARK + "-prevach",
                MARK + "-device", MARK + "-push", MARK + "-ip");
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, address);
        // A passkey and a progress row: the two back references that made the old answer loop.
        jdbc.update("INSERT INTO passkey_credentials (user_id, external_id, public_key_cose, signature_count) "
                + "VALUES (?, ?, ?, 0)", id, MARK + "-cred-" + UUID.randomUUID(), MARK + "-cose");
        jdbc.update("INSERT INTO user_progress (user_id, auth_finalize_completed, fi_plan_results_completed, "
                + "get_started_completed, investment_confirmation_completed, investment_schedule_completed, "
                + "kyc_verification_completed, link_plaid_completed, initial_survey_completed) "
                + "VALUES (?, 1, 1, 1, 1, 1, 1, 1, 1)", id);
        return id;
    }

    private Long seedExecution(Long owner) {
        String sourceKey = MARK + "-source-" + UUID.randomUUID();
        jdbc.update("INSERT INTO investment_executions (user_id, scheduled_date, amount, status, "
                + "alpaca_transfer_id, alpaca_account_id, error_message, investment_type, funding_source, "
                + "source_key, created_at, updated_at) "
                + "VALUES (?, NOW(), 125.50, 'COMPLETED', ?, ?, ?, 'portfolio', 'bank', ?, NOW(), NOW())",
                owner, MARK + "-transfer", "ENC:" + MARK + "-exec-acct", MARK + "-exec-error", sourceKey);
        return jdbc.queryForObject("SELECT id FROM investment_executions WHERE source_key = ?", Long.class, sourceKey);
    }

    @AfterEach
    void tearDown() {
        for (Long id : new Long[] { userId, otherUserId }) {
            if (id == null) {
                continue;
            }
            jdbc.update("DELETE FROM investment_trades WHERE user_id = ?", id);
            jdbc.update("DELETE FROM investment_executions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM passkey_credentials WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_sessions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_progress WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private String signIn() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/dev/authenticate-as-user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DevToolsKeyValidator.HEADER, devToolsKey)
                .content(json.writeValueAsString(Map.of("email", email))), null);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.path("success").asBoolean()).as("sign-in").isTrue();
        return body.path("jwtToken").asText();
    }

    /** Calls a route and returns its body as JSON, having checked it is clean. */
    private JsonNode cleanAnswer(String path) throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.get(path), signIn());
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(path + " status").isEqualTo(200);
        assertThat(body).as(path + " must not carry any secret value").doesNotContain(MARK);
        JsonNode parsed = json.readTree(body); // throws on a broken or cut-off body
        assertThat(keysIn(parsed)).as(path + " must not carry user or internal fields")
                .doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        return parsed;
    }

    private static List<String> keysIn(JsonNode node) {
        List<String> keys = new ArrayList<>();
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> field = it.next();
                keys.add(field.getKey());
                keys.addAll(keysIn(field.getValue()));
            }
        } else if (node.isArray()) {
            node.forEach(child -> keys.addAll(keysIn(child)));
        }
        return keys;
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ------------------------------------------------------------------ the three routes

    @Test
    @DisplayName("history answers with the executions and nothing of the user record")
    void historyIsClean() throws Exception {
        JsonNode body = cleanAnswer("/api/investments/history");

        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("monthlyInvestment").asDouble()).isEqualTo(400.0);
        assertThat(body.path("payFrequency").asText()).isEqualTo("biweekly");
        assertThat(body.path("executions")).hasSize(1);
        JsonNode execution = body.path("executions").get(0);
        assertThat(names(execution)).containsExactlyInAnyOrder("id", "amount", "status", "investmentType",
                "targetSymbol", "fundingSource", "scheduledDate", "executionDate", "fundingCompletedAt",
                "tradingCompletedAt", "createdAt");
        assertThat(execution.path("id").asLong()).isEqualTo(executionId);
        assertThat(execution.path("amount").decimalValue()).isEqualByComparingTo("125.50");
        assertThat(execution.path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("dashboard answers with the totals and recent executions, and nothing of the user record")
    void dashboardIsClean() throws Exception {
        JsonNode body = cleanAnswer("/api/investments/dashboard");

        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("totalInvested").asDouble()).isEqualTo(125.5);
        assertThat(body.path("pendingCount").asLong()).isZero();
        assertThat(body.path("failedCount").asLong()).isZero();
        assertThat(body.path("recentExecutions")).hasSize(1);
        assertThat(body.path("recentExecutions").get(0).path("id").asLong()).isEqualTo(executionId);
    }

    @Test
    @DisplayName("one execution answers with its trades, and nothing of the user record")
    void executionDetailsAreClean() throws Exception {
        JsonNode body = cleanAnswer("/api/investments/execution/" + executionId);

        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("execution").path("id").asLong()).isEqualTo(executionId);
        assertThat(body.path("trades")).hasSize(1);
        JsonNode trade = body.path("trades").get(0);
        assertThat(names(trade)).containsExactlyInAnyOrder("id", "symbol", "quantity", "notionalAmount",
                "filledQuantity", "filledAvgPrice", "status", "submittedAt", "filledAt", "canceledAt", "failedAt");
        assertThat(trade.path("symbol").asText()).isEqualTo("VTI");
        assertThat(trade.path("status").asText()).isEqualTo("FILLED");
    }

    @Test
    @DisplayName("someone else's execution looks exactly like one that does not exist")
    void anotherUsersExecutionIsNotFound() throws Exception {
        String token = signIn();
        MockHttpServletResponse theirs = call(
                MockMvcRequestBuilders.get("/api/investments/execution/" + otherExecutionId), token);
        MockHttpServletResponse nobodys = call(
                MockMvcRequestBuilders.get("/api/investments/execution/999999999"), token);

        assertThat(theirs.getStatus()).isEqualTo(404);
        assertThat(nobodys.getStatus()).isEqualTo(404);
        assertThat(theirs.getContentAsString()).isEqualTo(nobodys.getContentAsString());
        assertThat(theirs.getContentAsString()).doesNotContain(MARK);
    }

    // ------------------------------------------------------------------ the safety net

    @Test
    @DisplayName("no entity can be written out with the user's secrets, even if a route returns one by mistake")
    void entitiesNeverSerializeSecrets() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            User user = userRepository.findById(userId).orElseThrow();
            InvestmentExecution execution = executionRepository.findById(executionId).orElseThrow();
            List<InvestmentTrade> trades = tradeRepository.findByInvestmentExecutionId(executionId);
            Object[] entities = {
                execution, trades, user, user.getUserProgress(), new ArrayList<>(user.getPasskeyCredentials()),
            };
            for (Object entity : entities) {
                String written;
                try {
                    written = appMapper.writeValueAsString(entity);
                } catch (Exception e) {
                    throw new AssertionError("could not write " + entity.getClass().getSimpleName() + ": " + e, e);
                }
                assertThat(written).as(entity.getClass().getSimpleName()).doesNotContain(MARK);
            }
        });
    }
}
