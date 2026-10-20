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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v_schedule_churn against a dataset whose answers are known by construction.
 *
 * <p>Two things about this view shape the test. First, it reports only what the schema can honestly
 * answer: schedules_created, active_at_month_end and schedules_existing_at_month_end. There is no
 * paused / resumed / cancelled column, because investment_schedules is a one-row-per-user snapshot
 * with no history, so nothing here tests for them.
 *
 * <p>Second, and unlike every other metrics view, this one is NOT keyed by cohort: its two
 * cumulative columns count the whole table ("created on or before the end of this month"), so
 * schedules belonging to other tests land in them. Every assertion below therefore pins a DELTA
 * against a baseline measured after cleanCohort and before this test seeds anything, which is
 * exactly the set of rows this test does not own. The delta is the hand-computed number under test;
 * the baseline is a measurement, not a second opinion about what the answer should be.
 *
 * <p>schedules_created is bucketed by the month of created_at, and this test owns the months
 * 2024-06 and 2024-07, so its baselines there are expected to be zero. They are still subtracted,
 * so the arithmetic survives a double-booked date.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ScheduleChurnViewTest {

    /** The view reports month_start as the first of the month, formatted by DATE_FORMAT. */
    private static final String JUNE = "2024-06-01";
    private static final String JULY = "2024-07-01";

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDateTime signupAt;

    private long baseCreatedJune;
    private long baseActiveJune;
    private long baseExistingJune;
    private long baseCreatedJuly;
    private long baseActiveJuly;
    private long baseExistingJuly;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        // A Wednesday in the cohort week this test owns: Monday 2024-06-10.
        signupAt = LocalDateTime.of(2024, 6, 12, 10, 0);
        LocalDate cohortWeek = MetricsViewSeed.mondayOf(signupAt.toLocalDate());
        seed.cleanCohort(cohortWeek);

        // Taken with the cohort already removed, so these are the foreign rows only.
        baseCreatedJune = foreignCreated(JUNE);
        baseActiveJune = foreignActive(JUNE);
        baseExistingJune = foreignExisting(JUNE);
        baseCreatedJuly = foreignCreated(JULY);
        baseActiveJuly = foreignActive(JULY);
        baseExistingJuly = foreignExisting(JULY);
    }

    private Map<String, Object> row(String monthStart) {
        return jdbc.queryForMap("SELECT * FROM v_schedule_churn WHERE month_start = ?", monthStart);
    }

    private List<Map<String, Object>> rows(String monthStart) {
        return jdbc.queryForList("SELECT * FROM v_schedule_churn WHERE month_start = ?", monthStart);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private long foreignCreated(String monthStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM investment_schedules "
                + "WHERE created_at >= ? AND created_at < ? + INTERVAL 1 MONTH",
                Long.class, monthStart, monthStart);
    }

    private long foreignActive(String monthStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM investment_schedules "
                + "WHERE created_at < ? + INTERVAL 1 MONTH AND is_paused = 0",
                Long.class, monthStart);
    }

    private long foreignExisting(String monthStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM investment_schedules "
                + "WHERE created_at < ? + INTERVAL 1 MONTH", Long.class, monthStart);
    }

    @Test
    @DisplayName("counts creations in the month of created_at, and carries existing/active forward")
    void countsPerMonthAndCumulativeTotals() {
        // One schedule per user: investment_schedules has a unique key on user_id.
        // June: one unpaused, one paused. July: one unpaused, one paused.
        long juneActive = seed.user(signupAt);
        seed.schedule(juneActive, LocalDateTime.of(2024, 6, 14, 9, 0), false);

        long junePaused = seed.user(signupAt);
        seed.schedule(junePaused, LocalDateTime.of(2024, 6, 25, 9, 0), true);

        long julyActive = seed.user(signupAt);
        seed.schedule(julyActive, LocalDateTime.of(2024, 7, 3, 9, 0), false);

        long julyPaused = seed.user(signupAt);
        seed.schedule(julyPaused, LocalDateTime.of(2024, 7, 18, 9, 0), true);

        Map<String, Object> june = row(JUNE);
        assertThat(String.valueOf(june.get("month_start"))).isEqualTo("2024-06-01");
        // Created in June: juneActive + junePaused = 2.
        assertThat(num(june, "schedules_created")).isEqualTo(baseCreatedJune + 2);
        // Existing at the end of June ignores the pause flag: 2.
        assertThat(num(june, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 2);
        // Active at the end of June counts only the unpaused one: existing = 2, active = 1.
        assertThat(num(june, "active_at_month_end")).isEqualTo(baseActiveJune + 1);

        Map<String, Object> july = row(JULY);
        assertThat(String.valueOf(july.get("month_start"))).isEqualTo("2024-07-01");
        // Created in July: julyActive + julyPaused = 2. June's two are NOT counted again.
        assertThat(num(july, "schedules_created")).isEqualTo(baseCreatedJuly + 2);
        // Existing is cumulative: all four of June's and July's schedules = 4.
        assertThat(num(july, "schedules_existing_at_month_end")).isEqualTo(baseExistingJuly + 4);
        // Active is the same cumulative set minus the paused ones: juneActive + julyActive = 2.
        assertThat(num(july, "active_at_month_end")).isEqualTo(baseActiveJuly + 2);
    }

    @Test
    @DisplayName("a month in which no schedule was created produces NO ROW, not a zero row")
    void quietMonthIsMissingEntirely() {
        // One June schedule, still unpaused, and nothing created in July.
        long user = seed.user(signupAt);
        seed.schedule(user, LocalDateTime.of(2024, 6, 14, 9, 0), false);

        // June is reported: 1 created, 1 existing, 1 active.
        Map<String, Object> june = row(JUNE);
        assertThat(num(june, "schedules_created")).isEqualTo(baseCreatedJune + 1);
        assertThat(num(june, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 1);
        assertThat(num(june, "active_at_month_end")).isEqualTo(baseActiveJune + 1);

        // July is not reported at all, even though that schedule was active for the whole of July.
        // The view's month list comes from created_at, so a month with no creations is absent
        // rather than a row of zeros, and a reader joining months to this view loses July.
        // (This test owns July 2024: a row here means another test seeded into these dates.)
        assertThat(rows(JULY)).isEmpty();
    }

    @Test
    @DisplayName("active_at_month_end reads the pause flag as it is NOW, not as it was that month")
    void activeAtMonthEndFollowsTheCurrentPauseFlag() {
        long user = seed.user(signupAt);
        seed.schedule(user, LocalDateTime.of(2024, 6, 14, 9, 0), false);

        // Unpaused today, so June reads active = 1.
        assertThat(num(row(JUNE), "active_at_month_end")).isEqualTo(baseActiveJune + 1);

        // Pause it now. Nothing about June 2024 changed, but June's answer does.
        jdbc.update("UPDATE investment_schedules SET is_paused = 1 WHERE user_id = ?", user);

        Map<String, Object> june = row(JUNE);
        assertThat(num(june, "active_at_month_end")).isEqualTo(baseActiveJune); // + 0: 1 - 1 = 0
        // The other two columns are history and do not move.
        assertThat(num(june, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 1);
        assertThat(num(june, "schedules_created")).isEqualTo(baseCreatedJune + 1);
    }

    @Test
    @DisplayName("a cancelled schedule (a row DELETE) vanishes from the month it was created in")
    void cancellationRewritesThePastMonthsCreatedCount() {
        long kept = seed.user(signupAt);
        seed.schedule(kept, LocalDateTime.of(2024, 6, 14, 9, 0), false);

        long cancelled = seed.user(signupAt);
        seed.schedule(cancelled, LocalDateTime.of(2024, 6, 20, 9, 0), false);

        // Two schedules really were created in June.
        assertThat(num(row(JUNE), "schedules_created")).isEqualTo(baseCreatedJune + 2);
        assertThat(num(row(JUNE), "active_at_month_end")).isEqualTo(baseActiveJune + 2);

        // Cancelling is a row delete, which is how the app does it.
        jdbc.update("DELETE FROM investment_schedules WHERE user_id = ?", cancelled);

        Map<String, Object> june = row(JUNE);
        // June now reports ONE creation where two happened: 2 - 1 = 1. schedules_created is
        // really "created that month and not since cancelled", which is not what the name says.
        assertThat(num(june, "schedules_created")).isEqualTo(baseCreatedJune + 1);
        assertThat(num(june, "active_at_month_end")).isEqualTo(baseActiveJune + 1);
        assertThat(num(june, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 1);
    }
}
