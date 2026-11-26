package com.investingapp.backend.controller;

import com.investingapp.backend.model.Beneficiary;
import com.investingapp.backend.model.Beneficiary.BeneficiaryType;
import com.investingapp.backend.model.Beneficiary.Relationship;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The same cross-user rules as BeneficiaryControllerOwnershipTest, but against a real database, so
 * the owner-scoped query and the "a create is always an INSERT" rule are proved with real SQL and
 * not with a mock's idea of it.
 *
 * Two users are seeded. B owns one beneficiary. A is signed in and attacks it every way the API
 * allows, and after each attempt B's row is read back and must be exactly as seeded.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class CrossUserBeneficiaryIsolationTest {

    @Autowired
    private BeneficiaryController controller;

    @Autowired
    private JdbcTemplate jdbc;

    /** The broker is mocked so the test never leaves the machine, and so any call to it is visible. */
    @MockitoBean
    private com.investingapp.backend.service.AlpacaService alpacaService;

    @Autowired
    private com.investingapp.backend.service.EncryptionService encryptionService;

    private String seededSsn;
    private Long userAId;
    private Long userBId;
    private Long bBeneficiaryId;
    private Authentication userA;

    @BeforeEach
    void seed() {
        String emailA = "iso-a-" + UUID.randomUUID() + "@fredvested.test";
        String emailB = "iso-b-" + UUID.randomUUID() + "@fredvested.test";
        userAId = insertUser(emailA);
        userBId = insertUser(emailB);
        // Stored the way the application stores it: encrypted. The exact ciphertext is kept so
        // "exactly as seeded" can be checked byte for byte.
        seededSsn = encryptionService.encrypt("111-22-3333");
        jdbc.update("INSERT INTO beneficiaries (user_id, beneficiary_type, first_name, last_name, "
                + "date_of_birth, social_security_number, percentage_allocation, relationship, status, "
                + "created_at, updated_at) VALUES (?, 'PRIMARY', 'Bea', 'Owner', '1980-02-03', "
                + "?, 100.00, 'SPOUSE', 'PENDING', NOW(), NOW())", userBId, seededSsn);
        bBeneficiaryId = jdbc.queryForObject(
                "SELECT id FROM beneficiaries WHERE user_id = ?", Long.class, userBId);
        userA = new UsernamePasswordAuthenticationToken(emailA, null, List.of());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM beneficiaries WHERE user_id IN (?, ?)", userAId, userBId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userBId);
    }

    private Long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out) "
                + "VALUES (?, ?, ?, 0)", email, LocalDateTime.now(), UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static Beneficiary attackerBody() {
        Beneficiary b = new Beneficiary();
        b.setBeneficiaryType(BeneficiaryType.PRIMARY);
        b.setFirstName("Mallory");
        b.setLastName("Attacker");
        b.setDateOfBirth(LocalDate.of(1990, 1, 1));
        b.setSocialSecurityNumber("999-88-7777");
        b.setPercentageAllocation(new BigDecimal("100.00"));
        b.setRelationship(Relationship.FRIEND);
        return b;
    }

    private void assertBRowExactlyAsSeeded() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, first_name, last_name, social_security_number, status "
                        + "FROM beneficiaries WHERE id = ?", bBeneficiaryId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userBId);
        assertThat(row.get("first_name")).isEqualTo("Bea");
        assertThat(row.get("last_name")).isEqualTo("Owner");
        assertThat(row.get("social_security_number")).isEqualTo(seededSsn);
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    private long rowsOwnedBy(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM beneficiaries WHERE user_id = ?", Long.class, userId);
    }

    @Test
    @DisplayName("user A's list does not include user B's beneficiary")
    void listIsScopedToTheCaller() {
        ResponseEntity<List<Beneficiary>> response = controller.getBeneficiaries(userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("a create carrying B's row id inserts a new row for A and leaves B's row alone")
    void createWithAnotherUsersIdDoesNotTakeTheRow() {
        Beneficiary body = attackerBody();
        body.setId(bBeneficiaryId);

        ResponseEntity<?> response = controller.createBeneficiary(body, userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Beneficiary created = (Beneficiary) response.getBody();
        assertThat(created.getId()).isNotNull().isNotEqualTo(bBeneficiaryId);
        assertThat(rowsOwnedBy(userAId)).isEqualTo(1);
        assertThat(rowsOwnedBy(userBId)).isEqualTo(1);
        assertBRowExactlyAsSeeded();
    }

    @Test
    @DisplayName("the owner can still change and delete their own beneficiary")
    void ownerStillCan() {
        // The positive control. Without it, an owner-scoped query that never matched anything
        // would pass every refusal test in this class while breaking the feature for everyone.
        Beneficiary own = (Beneficiary) controller.createBeneficiary(attackerBody(), userA).getBody();
        Beneficiary edit = attackerBody();
        edit.setLastName("Renamed");

        assertThat(controller.updateBeneficiary(own.getId(), edit, userA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT last_name FROM beneficiaries WHERE id = ?", String.class, own.getId()))
                .isEqualTo("Renamed");

        assertThat(controller.deleteBeneficiary(own.getId(), userA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM beneficiaries WHERE id = ?", String.class, own.getId()))
                .isEqualTo("INACTIVE");
        assertBRowExactlyAsSeeded();
    }

    @Test
    @DisplayName("user A cannot change user B's beneficiary")
    void updateIsRefused() {
        ResponseEntity<?> response = controller.updateBeneficiary(bBeneficiaryId, attackerBody(), userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertBRowExactlyAsSeeded();
    }

    @Test
    @DisplayName("user A cannot delete user B's beneficiary")
    void deleteIsRefused() {
        ResponseEntity<?> response = controller.deleteBeneficiary(bBeneficiaryId, userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertBRowExactlyAsSeeded();
        verifyNoInteractions(alpacaService);
    }

    @Test
    @DisplayName("user A cannot submit user B's beneficiary to the broker")
    void submitIsRefused() {
        ResponseEntity<?> response = controller.submitToAlpaca(bBeneficiaryId, userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertBRowExactlyAsSeeded();
        verifyNoInteractions(alpacaService);
    }
}
