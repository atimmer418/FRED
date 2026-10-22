package com.investingapp.backend.metrics;

import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.InvestmentScheduleService;
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

    @Autowired
    private InvestmentScheduleService scheduleService;

    @Autowired
    private UserRepository userRepository;

    private MetricsViewSeed seed;
    private LocalDateTime signupAt;

    private long baseCreatedJune;
    private long baseActiveJune;
    private long baseExistingJune;
    private long baseCreatedJuly;
    private long baseActiveJuly;
    private long baseExistingJuly;
    private long basePausedJune;
    private long basePausedByUserJune;
    private long baseResumedJune;
    private long baseResumedByUserJune;
    private long baseCancelledJune;
    private long baseCancelledByUserJune;
    private long baseNetUserChangeJune;

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
        basePausedJune = foreignEvents(JUNE, "schedule_paused", null);
        basePausedByUserJune = foreignEvents(JUNE, "schedule_paused", "user");
        baseResumedJune = foreignEvents(JUNE, "schedule_resumed", null);
        baseResumedByUserJune = foreignEvents(JUNE, "schedule_resumed", "user");
        baseCancelledJune = foreignEvents(JUNE, "schedule_cancelled", null);
        baseCancelledByUserJune = foreignEvents(JUNE, "schedule_cancelled", "user");
        baseNetUserChangeJune = baseResumedByUserJune - basePausedByUserJune
                - baseCancelledByUserJune;
    }

    /** Transition events already in the schema that this test did not write. */
    private long foreignEvents(String monthStart, String eventName, String initiator) {
        String sql = "SELECT COUNT(*) FROM analytics_outbox "
                + "WHERE event_name = ? AND occurred_at >= ? AND occurred_at < ? + INTERVAL 1 MONTH";
        if (initiator == null) {
            return jdbc.queryForObject(sql, Long.class, eventName, monthStart, monthStart);
        }
        return jdbc.queryForObject(
                sql + " AND JSON_UNQUOTE(JSON_EXTRACT(properties, '$.initiator')) = ?",
                Long.class, eventName, monthStart, monthStart, initiator);
    }

    /** A transition event as the services write it: two enum properties and nothing else. */
    private void transition(long userId, String eventName, String initiator, String reason,
                            LocalDateTime at) {
        seed.outboxEvent(userId, eventName, at,
                "{\"initiator\":\"" + initiator + "\",\"reason\":\"" + reason + "\"}", "SENT");
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
                + "WHERE created_at < ? + INTERVAL 1 MONTH AND status = 'ACTIVE'",
                Long.class, monthStart);
    }

    /** Mirrors held_at_month_end: the status still says the user wants it, a hold is stopping it. */
    private long foreignHeld(String monthStart) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM investment_schedules "
                + "WHERE created_at < ? + INTERVAL 1 MONTH AND status = 'ACTIVE' "
                + "AND hold_reason IS NOT NULL", Long.class, monthStart);
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
        jdbc.update("UPDATE investment_schedules SET status = 'PAUSED_BY_USER' WHERE user_id = ?", user);

        Map<String, Object> june = row(JUNE);
        assertThat(num(june, "active_at_month_end")).isEqualTo(baseActiveJune); // + 0: 1 - 1 = 0
        // The other two columns are history and do not move.
        assertThat(num(june, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 1);
        assertThat(num(june, "schedules_created")).isEqualTo(baseCreatedJune + 1);
    }

    @Test
    @DisplayName("a hold does NOT come out of active_at_month_end; it shows up in held_at_month_end")
    void activeAtMonthEndKeepsAHeldScheduleAndCountsItSeparately() {
        long baseHeldJune = foreignHeld(JUNE);
        long user = seed.user(signupAt);
        seed.schedule(user, LocalDateTime.of(2024, 6, 14, 9, 0), false);

        // Running: status ACTIVE, no hold.
        assertThat(num(row(JUNE), "active_at_month_end")).isEqualTo(baseActiveJune + 1);
        assertThat(num(row(JUNE), "held_at_month_end")).isEqualTo(baseHeldJune);

        // A bank-change hold stops the schedule WITHOUT touching the status (holds are orthogonal).
        // This is the deliberate reversal of the earlier hold-aware predicate: a hold is an
        // operational stop, not churn, so counting it as inactive would make a Plaid outage or a
        // bank change read as users leaving. It is visible in its own column instead.
        jdbc.update("UPDATE investment_schedules SET hold_reason = 'BANK_CHANGE' WHERE user_id = ?", user);

        Map<String, Object> held = row(JUNE);
        assertThat(num(held, "active_at_month_end")).isEqualTo(baseActiveJune + 1);
        assertThat(num(held, "held_at_month_end")).isEqualTo(baseHeldJune + 1);
        assertThat(num(held, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 1);
        assertThat(num(held, "schedules_created")).isEqualTo(baseCreatedJune + 1);

        // The hold clears when the new ACH relationship lands; the active count never moved.
        jdbc.update("UPDATE investment_schedules SET hold_reason = NULL WHERE user_id = ?", user);
        assertThat(num(row(JUNE), "active_at_month_end")).isEqualTo(baseActiveJune + 1);
        assertThat(num(row(JUNE), "held_at_month_end")).isEqualTo(baseHeldJune);
    }

    @Test
    @DisplayName("a user pause while a hold stands leaves active_at_month_end AND held_at_month_end")
    void aPausedAndHeldScheduleIsNeitherActiveNorHeld() {
        long baseHeldJune = foreignHeld(JUNE);
        long user = seed.user(signupAt);
        seed.schedule(user, LocalDateTime.of(2024, 6, 14, 9, 0), false);

        // Both stops at once: the system holds it and the user pauses it too.
        jdbc.update("UPDATE investment_schedules SET hold_reason = 'MANUAL_REVIEW', "
                + "status = 'PAUSED_BY_USER' WHERE user_id = ?", user);

        Map<String, Object> june = row(JUNE);
        // Not active: the user turned it off, and that IS churn.
        assertThat(num(june, "active_at_month_end")).isEqualTo(baseActiveJune);
        // Not held either: held_at_month_end is "the user still wants it but we are stopping it",
        // and that is no longer true once the user has paused it. Counting it in both would
        // double-count one schedule.
        assertThat(num(june, "held_at_month_end")).isEqualTo(baseHeldJune);
        assertThat(num(june, "schedules_existing_at_month_end")).isEqualTo(baseExistingJune + 1);
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

    @Test
    @DisplayName("counts pauses, resumes and cancellations from the events, split by who caused them")
    void countsTransitionsSplitByInitiator() {
        long user = seed.user(signupAt);
        LocalDateTime inJune = LocalDateTime.of(2024, 6, 20, 12, 0);

        // Two user pauses and one system pause during a bank change.
        transition(user, "schedule_paused", "user", "user_request", inJune);
        transition(user, "schedule_paused", "user", "user_request", inJune.plusDays(1));
        transition(user, "schedule_paused", "system", "bank_change", inJune.plusDays(2));
        // One user resume and one the system did when the ACH landed.
        transition(user, "schedule_resumed", "user", "user_request", inJune.plusDays(3));
        transition(user, "schedule_resumed", "system", "ach_confirmed", inJune.plusDays(4));
        // One cancellation, by the user closing their account.
        transition(user, "schedule_cancelled", "user", "account_closed", inJune.plusDays(5));

        Map<String, Object> june = row(JUNE);

        assertThat(num(june, "paused")).isEqualTo(basePausedJune + 3);          // 2 user + 1 system
        assertThat(num(june, "paused_by_user")).isEqualTo(basePausedByUserJune + 2);
        assertThat(num(june, "resumed")).isEqualTo(baseResumedJune + 2);        // 1 user + 1 system
        assertThat(num(june, "resumed_by_user")).isEqualTo(baseResumedByUserJune + 1);
        assertThat(num(june, "cancelled")).isEqualTo(baseCancelledJune + 1);
        assertThat(num(june, "cancelled_by_user")).isEqualTo(baseCancelledByUserJune + 1);
    }

    @Test
    @DisplayName("net_user_change is resumes minus pauses and cancellations, by the user only")
    void netUserChangeIgnoresTheSystem() {
        long user = seed.user(signupAt);
        LocalDateTime inJune = LocalDateTime.of(2024, 6, 20, 12, 0);

        // The user stopped twice and restarted once: net -1. The system events either side must
        // not move it, which is the whole reason the column is "by user".
        transition(user, "schedule_paused", "user", "user_request", inJune);
        transition(user, "schedule_cancelled", "user", "account_closed", inJune.plusDays(1));
        transition(user, "schedule_resumed", "user", "user_request", inJune.plusDays(2));
        transition(user, "schedule_paused", "system", "bank_change", inJune.plusDays(3));
        transition(user, "schedule_resumed", "system", "ach_confirmed", inJune.plusDays(4));

        // This test added, by the user: 1 resume, 1 pause, 1 cancellation. Net = 1 - 1 - 1 = -1.
        // The two system events must not move it at all.
        assertThat(num(row(JUNE), "net_user_change")).isEqualTo(baseNetUserChangeJune - 1);
    }

    @Test
    @DisplayName("a system pause is counted but is not user churn")
    void systemPauseIsNotChurn() {
        long user = seed.user(signupAt);
        transition(user, "schedule_paused", "system", "bank_change",
                LocalDateTime.of(2024, 6, 21, 9, 0));

        Map<String, Object> june = row(JUNE);

        assertThat(num(june, "paused")).isEqualTo(basePausedJune + 1);
        assertThat(num(june, "paused_by_user")).isEqualTo(basePausedByUserJune);
    }

    @Test
    @DisplayName("a month with transitions but no creations still gets a row")
    void eventOnlyMonthHasARow() {
        // The old view keyed months off created_at alone, so a month of pure churn was invisible.
        long user = seed.user(signupAt);
        transition(user, "schedule_cancelled", "user", "account_closed",
                LocalDateTime.of(2024, 7, 14, 9, 0));

        Map<String, Object> july = row(JULY);

        assertThat(num(july, "cancelled_by_user")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the real service's event is readable by this view, property names and all")
    void realServiceEventReachesTheView() {
        // The seam nothing else covers. Every other test here seeds the outbox by hand, and the
        // service tests mock the outbox away, so the one thing neither proves is that the JSON the
        // service writes is the JSON this view reads: a rename of 'initiator' on either side would
        // pass both suites and quietly zero the churn columns.
        String thisMonth = LocalDate.now().withDayOfMonth(1).toString();
        long baseByUser = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_outbox WHERE event_name = 'schedule_paused' "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(properties, '$.initiator')) = 'user' "
                        + "AND occurred_at >= ? AND occurred_at < ? + INTERVAL 1 MONTH",
                Long.class, thisMonth, thisMonth);

        long userId = seed.user(signupAt);
        // Running, so the pause is a real transition.
        seed.schedule(userId, LocalDateTime.now().minusDays(1), false);
        Long scheduleId = jdbc.queryForObject(
                "SELECT id FROM investment_schedules WHERE user_id = ?", Long.class, userId);
        User user = userRepository.findById(userId).orElseThrow();

        scheduleService.pauseSchedule(user, scheduleId);

        Map<String, Object> month = row(thisMonth);
        assertThat(num(month, "paused_by_user")).isEqualTo(baseByUser + 1);
        assertThat(num(month, "paused")).isGreaterThanOrEqualTo(baseByUser + 1);
    }
}
