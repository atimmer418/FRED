package com.investingapp.backend.metrics;

import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v_activation_funnel against a dataset whose answers are known by construction.
 *
 * The cohort week is one nobody else uses, so these numbers describe exactly the five users seeded
 * below and nothing else in the schema.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ActivationFunnelViewTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDate cohortWeek;
    private LocalDateTime signupAt;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        // A Wednesday in a week this test owns.
        signupAt = LocalDateTime.of(2024, 3, 13, 10, 0);
        cohortWeek = MetricsViewSeed.mondayOf(signupAt.toLocalDate());
        seed.cleanCohort(cohortWeek);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM v_activation_funnel WHERE cohort_week = ?",
                cohortWeek);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private double pct(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    @Test
    @DisplayName("counts each step and converts against the step before it")
    void countsAndConversions() {
        // Five users, each dropping out one step later than the last.
        long justSignedUp = seed.user(signupAt);

        long plannedOnly = seed.user(signupAt);
        seed.plan(plannedOnly);

        long gotAPasskey = seed.user(signupAt);
        seed.plan(gotAPasskey);
        seed.passkey(gotAPasskey);

        long linkedABank = seed.user(signupAt);
        seed.plan(linkedABank);
        seed.passkey(linkedABank);
        seed.kycSubmitted(linkedABank, signupAt.plusDays(1));
        seed.accountStatus(linkedABank, "ACTIVE");
        seed.bankLinked(linkedABank);

        long wentAllTheWay = seed.user(signupAt);
        seed.plan(wentAllTheWay);
        seed.passkey(wentAllTheWay);
        seed.kycSubmitted(wentAllTheWay, signupAt.plusDays(1));
        seed.accountStatus(wentAllTheWay, "ACTIVE");
        seed.bankLinked(wentAllTheWay);
        seed.schedule(wentAllTheWay, signupAt.plusDays(2), true);
        seed.settledTransfer(wentAllTheWay, signupAt.plusDays(5), "transfer-1");

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(5);
        assertThat(num(row, "created_plan")).isEqualTo(4);
        assertThat(num(row, "created_passkey")).isEqualTo(3);
        assertThat(num(row, "submitted_kyc")).isEqualTo(2);
        assertThat(num(row, "approved")).isEqualTo(2);
        assertThat(num(row, "linked_bank")).isEqualTo(2);
        assertThat(num(row, "created_schedule")).isEqualTo(1);
        assertThat(num(row, "funded")).isEqualTo(1);

        assertThat(pct(row, "pct_plan_of_signup")).isEqualTo(80.0);     // 4/5
        assertThat(pct(row, "pct_passkey_of_plan")).isEqualTo(75.0);    // 3/4
        assertThat(pct(row, "pct_kyc_of_passkey")).isEqualTo(66.7);     // 2/3
        assertThat(pct(row, "pct_approved_of_kyc")).isEqualTo(100.0);   // 2/2
        assertThat(pct(row, "pct_bank_of_approved")).isEqualTo(100.0);  // 2/2
        assertThat(pct(row, "pct_schedule_of_bank")).isEqualTo(50.0);   // 1/2
        assertThat(pct(row, "pct_funded_of_schedule")).isEqualTo(100.0);// 1/1
        assertThat(pct(row, "pct_funded_of_signup")).isEqualTo(20.0);   // 1/5

        assertThat(justSignedUp).isPositive();   // seeded, and counted only in signed_up
    }

    @Test
    @DisplayName("a cohort where nobody funded reports 0, never NULL")
    void zeroFundedIsZeroNotNull() {
        seed.user(signupAt);
        seed.user(signupAt);

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(2);
        assertThat(num(row, "funded")).isZero();
        // Every rate below the first empty step divides by zero. All of them must read 0.0.
        assertThat(row.get("pct_plan_of_signup")).isNotNull();
        assertThat(pct(row, "pct_plan_of_signup")).isZero();
        assertThat(pct(row, "pct_passkey_of_plan")).isZero();
        assertThat(pct(row, "pct_funded_of_schedule")).isZero();
        assertThat(pct(row, "pct_funded_of_signup")).isZero();
    }

    @Test
    @DisplayName("an execution that never settled is not a funded user")
    void unsettledTransferDoesNotCount() {
        long user = seed.user(signupAt);
        seed.schedule(user, signupAt.plusDays(1), true);
        seed.unsettledTransfer(user, signupAt.plusDays(2));

        Map<String, Object> row = row();

        assertThat(num(row, "created_schedule")).isEqualTo(1);
        assertThat(num(row, "funded")).isZero();
    }

    @Test
    @DisplayName("steps are 'ever reached', so an out-of-order user still counts for both")
    void stepsAreNotOrdered() {
        // A bank linked before the broker approved: both are true of this user.
        long user = seed.user(signupAt);
        seed.bankLinked(user);
        seed.accountStatus(user, "SUBMITTED");

        Map<String, Object> row = row();

        assertThat(num(row, "linked_bank")).isEqualTo(1);
        assertThat(num(row, "approved")).isZero();
    }

    @Test
    @DisplayName("a user with no signup date is in no cohort at all")
    void nullSignupDateIsExcluded() {
        long user = seed.user(signupAt);
        jdbc.update("UPDATE users SET create_date = NULL WHERE id = ?", user);

        assertThat(jdbc.queryForList("SELECT * FROM v_activation_funnel WHERE cohort_week = ?",
                cohortWeek)).isEmpty();
    }
}
