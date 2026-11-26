package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.Beneficiary;
import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * A beneficiary's Social Security Number, end to end against a real database.
 *
 * The number belongs to someone who is not our user and never agreed to anything, so the rules are
 * strict: the database holds only ciphertext, no response ever carries more than the last four
 * digits, the log never carries it, and the broker still gets the full number when the list is sent.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
@ExtendWith(OutputCaptureExtension.class)
class BeneficiarySsnAtRestTest {

    private static final String SSN = "512-34-6789";
    private static final String SSN_DIGITS = "512346789";
    private static final String OTHER_SSN = "623-45-1234";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EncryptionService encryptionService;
    @Autowired private com.investingapp.backend.repository.BeneficiaryRepository beneficiaryRepository;
    @Autowired private com.investingapp.backend.repository.UserRepository userRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** The broker is mocked so the test never leaves the machine, and so what it is sent is visible. */
    @MockitoBean private AlpacaService alpacaService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private String email;
    private Long userId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "ssn-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, alpaca_account_id) "
                + "VALUES (?, ?, ?, 0, ?)", email, LocalDateTime.now(), UUID.randomUUID().toString(),
                encryptionService.encrypt("acct-" + UUID.randomUUID()));
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM beneficiaries WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request = request.with(authentication(new UsernamePasswordAuthenticationToken(email, null, List.of())));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private static Map<String, Object> body(String firstName, String ssn, Object percent) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("beneficiaryType", "PRIMARY");
        b.put("firstName", firstName);
        b.put("lastName", "Example");
        b.put("dateOfBirth", "1984-05-06");
        if (ssn != null) {
            b.put("socialSecurityNumber", ssn);
        }
        b.put("relationship", "SPOUSE");
        b.put("percentageAllocation", percent);
        return b;
    }

    private long create(String firstName, String ssn, Object percent) throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/beneficiaries"),
                body(firstName, ssn, percent));
        assertThat(response.getStatus()).as("create: %s", response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("id").asLong();
    }

    private String storedSsn(long id) {
        return jdbc.queryForObject("SELECT social_security_number FROM beneficiaries WHERE id = ?", String.class, id);
    }

    /** No full number, with or without its dashes, anywhere in the text. */
    private static void assertNoFullSsn(String text, String... ssns) {
        for (String ssn : ssns) {
            assertThat(text).doesNotContain(ssn).doesNotContain(ssn.replace("-", ""));
        }
    }

    // ------------------------------------------------------------------ at rest

    @Test
    @DisplayName("the database holds ciphertext, never the number")
    void theColumnHoldsCiphertext() throws Exception {
        long id = create("Ada", SSN, "50.00");

        String stored = storedSsn(id);
        assertThat(stored).startsWith("ENC:");
        assertNoFullSsn(stored, SSN);
        assertThat(stored).doesNotContain("6789");
        assertThat(encryptionService.decrypt(stored)).isEqualTo(SSN);
    }

    @Test
    @DisplayName("two people with the same number do not share a stored value")
    void sameNumberIsStoredDifferentlyEachTime() throws Exception {
        long first = create("Ada", SSN, "40.00");
        long second = create("Bob", SSN, "40.00");

        assertThat(storedSsn(first)).isNotEqualTo(storedSsn(second));
    }

    // ------------------------------------------------------------------ responses

    @Test
    @DisplayName("create answers with the last four digits and nothing more")
    void createReturnsLastFourOnly() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/beneficiaries"),
                body("Ada", SSN, "50.00"));

        assertThat(response.getStatus()).isEqualTo(200);
        String text = response.getContentAsString();
        assertNoFullSsn(text, SSN);
        JsonNode node = json.readTree(text);
        assertThat(node.has("socialSecurityNumber")).isFalse();
        assertThat(node.path("ssnLast4").asText()).isEqualTo("6789");
    }

    @Test
    @DisplayName("no read of the list carries a full number")
    void listsNeverCarryTheNumber() throws Exception {
        create("Ada", SSN, "50.00");
        long contingent = create("Cy", OTHER_SSN, "50.00");
        jdbc.update("UPDATE beneficiaries SET beneficiary_type = 'CONTINGENT', status = 'APPROVED' WHERE id = ?",
                contingent);

        for (String path : List.of("", "/active", "/primary", "/contingent")) {
            MockHttpServletResponse response = call(MockMvcRequestBuilders.get("/api/beneficiaries" + path), null);
            assertThat(response.getStatus()).as("GET %s", path).isEqualTo(200);
            String text = response.getContentAsString();
            assertThat(json.readTree(text).size()).as("GET %s returns rows", path).isGreaterThan(0);
            assertNoFullSsn(text, SSN, OTHER_SSN);
            assertThat(text).as("GET %s", path).doesNotContain("socialSecurityNumber").doesNotContain("ENC:");
            assertThat(text).contains("ssnLast4");
        }
    }

    // ------------------------------------------------------------------ edits

    @Test
    @DisplayName("an edit that sends no number keeps the one on file")
    void editWithoutNumberKeepsIt() throws Exception {
        long id = create("Ada", SSN, "50.00");
        Map<String, Object> edit = body("Ada", null, "50.00");
        edit.put("city", "Tulsa");

        MockHttpServletResponse response = call(MockMvcRequestBuilders.put("/api/beneficiaries/" + id), edit);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode node = json.readTree(response.getContentAsString());
        assertThat(node.path("ssnLast4").asText()).isEqualTo("6789");
        assertThat(node.path("city").asText()).isEqualTo("Tulsa");
        assertThat(encryptionService.decrypt(storedSsn(id))).isEqualTo(SSN);
    }

    @Test
    @DisplayName("an edit that only changes the address does not send an approved beneficiary back to pending")
    void editWithoutNumberIsNotASignificantChange() throws Exception {
        long id = create("Ada", SSN, "50.00");
        jdbc.update("UPDATE beneficiaries SET status = 'APPROVED' WHERE id = ?", id);
        // 50, not "50.00": the app sends the allocation as a bare number.
        Map<String, Object> edit = body("Ada", null, 50);
        edit.put("city", "Tulsa");

        MockHttpServletResponse response = call(MockMvcRequestBuilders.put("/api/beneficiaries/" + id), edit);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsString()).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an edit with a new number replaces it, encrypted, and needs sending to the broker again")
    void editWithNewNumberReplacesIt() throws Exception {
        long id = create("Ada", SSN, "50.00");
        jdbc.update("UPDATE beneficiaries SET status = 'APPROVED' WHERE id = ?", id);

        MockHttpServletResponse response = call(MockMvcRequestBuilders.put("/api/beneficiaries/" + id),
                body("Ada", OTHER_SSN, "50.00"));

        assertThat(response.getStatus()).isEqualTo(200);
        String text = response.getContentAsString();
        assertNoFullSsn(text, SSN, OTHER_SSN);
        JsonNode node = json.readTree(text);
        assertThat(node.path("ssnLast4").asText()).isEqualTo("1234");
        assertThat(node.path("status").asText()).isEqualTo("PENDING");
        assertThat(storedSsn(id)).startsWith("ENC:");
        assertThat(encryptionService.decrypt(storedSsn(id))).isEqualTo(OTHER_SSN);
    }

    @Test
    @DisplayName("an edit that sends an empty number keeps the one on file too")
    void editWithBlankNumberKeepsIt() throws Exception {
        long id = create("Ada", SSN, "50.00");
        for (String blank : List.of("", "   ")) {
            Map<String, Object> edit = body("Ada", blank, "50.00");
            edit.put("city", "Tulsa");

            MockHttpServletResponse response = call(MockMvcRequestBuilders.put("/api/beneficiaries/" + id), edit);

            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
            assertThat(encryptionService.decrypt(storedSsn(id))).isEqualTo(SSN);
        }
    }

    @Test
    @DisplayName("closing an account removes its beneficiaries even when a stored number cannot be read")
    void deletionDoesNotNeedToReadTheNumber() throws Exception {
        long id = create("Ada", SSN, "50.00");
        // A row from before encryption: plain text, which this profile refuses to read.
        jdbc.update("UPDATE beneficiaries SET social_security_number = ? WHERE id = ?", SSN, id);

        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(
                tx -> beneficiaryRepository.deleteAllByUser(userRepository.findById(userId).orElseThrow()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM beneficiaries WHERE user_id = ?", Long.class, userId))
                .isZero();
    }

    @Test
    @DisplayName("a new beneficiary must come with a number")
    void createWithoutNumberIsRefused() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/beneficiaries"),
                body("Ada", null, "50.00"));
        MockHttpServletResponse blank = call(MockMvcRequestBuilders.post("/api/beneficiaries"),
                body("Ada", " ", "50.00"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(blank.getStatus()).isEqualTo(400);
        assertThat(blank.getContentAsString()).contains("Social Security Number is required");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM beneficiaries WHERE user_id = ?", Long.class, userId))
                .isZero();
    }

    // ------------------------------------------------------------------ the broker

    @Test
    @DisplayName("the broker is sent every full number, including one the edit did not resend")
    @SuppressWarnings("unchecked")
    void brokerStillGetsFullNumbers() throws Exception {
        when(alpacaService.updateAccountBeneficiaries(any(), anyList())).thenReturn(true);
        long ada = create("Ada", SSN, "50.00");
        create("Bob", OTHER_SSN, "50.00");
        Map<String, Object> edit = body("Ada", null, "50.00");
        edit.put("city", "Tulsa");
        assertThat(call(MockMvcRequestBuilders.put("/api/beneficiaries/" + ada), edit).getStatus()).isEqualTo(200);

        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/beneficiaries/submit-all"), null);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        ArgumentCaptor<List<Beneficiary>> sent = ArgumentCaptor.forClass(List.class);
        verify(alpacaService).updateAccountBeneficiaries(any(), sent.capture());
        assertThat(sent.getValue()).extracting(Beneficiary::getSocialSecurityNumber)
                .containsExactlyInAnyOrder(SSN, OTHER_SSN);
    }

    @Test
    @DisplayName("removing one beneficiary resends the rest to the broker with their full numbers")
    @SuppressWarnings("unchecked")
    void deleteResendsTheRestWithFullNumbers() throws Exception {
        when(alpacaService.updateAccountBeneficiaries(any(), anyList())).thenReturn(true);
        long ada = create("Ada", SSN, "50.00");
        create("Bob", OTHER_SSN, "50.00");

        assertThat(call(MockMvcRequestBuilders.delete("/api/beneficiaries/" + ada), null).getStatus()).isEqualTo(200);

        ArgumentCaptor<List<Beneficiary>> sent = ArgumentCaptor.forClass(List.class);
        verify(alpacaService).updateAccountBeneficiaries(any(), sent.capture());
        assertThat(sent.getValue()).extracting(Beneficiary::getSocialSecurityNumber).containsExactly(OTHER_SSN);
    }

    // ------------------------------------------------------------------ logs

    @Test
    @DisplayName("a mistyped number is refused without being echoed back or written to the log")
    void aBadNumberIsNotEchoedOrLogged(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/beneficiaries"),
                body("Ada", SSN_DIGITS, "50.00"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("SSN must be in format");
        assertNoFullSsn(response.getContentAsString(), SSN);
        assertNoFullSsn(output.getAll(), SSN);
    }

    @Test
    @DisplayName("a whole create, edit, send and remove leaves no number in the log")
    void nothingInTheLog(CapturedOutput output) throws Exception {
        when(alpacaService.updateAccountBeneficiaries(any(), anyList())).thenReturn(true);
        long id = create("Ada", SSN, "50.00");
        call(MockMvcRequestBuilders.put("/api/beneficiaries/" + id), body("Ada", OTHER_SSN, "50.00"));
        call(MockMvcRequestBuilders.get("/api/beneficiaries"), null);
        call(MockMvcRequestBuilders.post("/api/beneficiaries/" + id + "/submit"), null);
        call(MockMvcRequestBuilders.delete("/api/beneficiaries/" + id), null);

        assertNoFullSsn(output.getAll(), SSN, OTHER_SSN);
    }
}
