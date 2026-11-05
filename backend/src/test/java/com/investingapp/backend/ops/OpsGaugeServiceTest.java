package com.investingapp.backend.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The parts of the gauge snapshot that need no database: the SQL text and the digest's shape. */
class OpsGaugeServiceTest {

    private static List<String> gaugeQueries() throws IllegalAccessException {
        List<String> sql = new ArrayList<>();
        for (Field field : OpsGaugeService.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && field.getName().startsWith("SQL_")) {
                field.setAccessible(true);
                sql.add((String) field.get(null));
            }
        }
        return sql;
    }

    @Test
    @DisplayName("no gauge query reads the SQL clock: the session is UTC and the data is Eastern")
    void noGaugeQueryReadsTheSqlClock() throws Exception {
        List<String> queries = gaugeQueries();

        // Fourteen queries; a fifteenth must be added here too, so it cannot dodge this check.
        assertThat(queries).hasSize(14);
        for (String query : queries) {
            assertThat(query.toUpperCase(Locale.ROOT)).as(query).doesNotContain(
                    "NOW(", "CURDATE(", "CURRENT_TIMESTAMP", "CURRENT_DATE", "UTC_TIMESTAMP",
                    "SYSDATE(", "LOCALTIME", "UNIX_TIMESTAMP(");
        }
    }

    @Test
    @DisplayName("the digest context carries every gauge, in the published order, buckets sorted")
    void contextCarriesEveryGaugeKeyInOrder() {
        OpsGaugeSnapshot snapshot = new OpsGaugeSnapshot(
                Map.of("SEARCHING", 2L), Map.of("OK", 3L, "LOGIN_REQUIRED", 1L), 3,
                Map.of("MANUAL_REVIEW", 1L), 4, 0, 120, 9, 1, 2, 5, 0, 1, true);

        Map<String, Object> context = snapshot.toContext();

        assertThat(new ArrayList<>(context.keySet())).containsExactlyElementsOf(OpsGaugeSnapshot.GAUGE_KEYS);
        assertThat(context)
                .containsEntry("plaid.items.billable", 3L)
                .containsEntry("inbox.backlog", 4L)
                .containsEntry("inbox.oldest_age_sec", 120L)
                .containsEntry("ops_alerts.24h", 5L)
                .containsEntry("reconcile.ran_yesterday", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) context.get("plaid.items");
        assertThat(new ArrayList<>(items.keySet())).containsExactly("LOGIN_REQUIRED", "OK");
    }

    @Test
    @DisplayName("no gauge is named for data an ops alert may never carry")
    void contextKeysNameNoForbiddenData() {
        for (String key : OpsGaugeSnapshot.GAUGE_KEYS) {
            assertThat(key).doesNotContain("amount", "date", "descriptor", "name", "token", "cursor", "email");
        }
    }
}
