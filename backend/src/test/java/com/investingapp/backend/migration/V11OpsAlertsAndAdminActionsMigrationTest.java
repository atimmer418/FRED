package com.investingapp.backend.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of V11 without a database: exactly two CREATE TABLE IF NOT EXISTS, nothing that
 * alters, drops or references an existing table, every column and key exactly as the Phase 1
 * design states it, and one file per Flyway version in db/migration (two branches choosing the
 * same number merge cleanly and then stop Flyway at boot, ruling R15). The validate gate
 * (BackendApplicationTests under ci) proves the SQL runs and the entities match it.
 */
class V11OpsAlertsAndAdminActionsMigrationTest {

    private static final String FILE = "db/migration/V11__ops_alerts_and_admin_actions.sql";
    private static final String ENGINE = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final char EM_DASH = (char) 0x2014;

    private static final List<String> OPS_ALERTS = List.of(
            "`id` bigint NOT NULL AUTO_INCREMENT,",
            "`alert_type` varchar(64) NOT NULL,",
            "`subject_id` varchar(80) NOT NULL,",
            "`alert_day` date NOT NULL,",
            "`message` varchar(500) NOT NULL,",
            "`context_json` text,",
            "`occurrences` int NOT NULL DEFAULT 1,",
            "`first_raised_at` datetime(6) NOT NULL,",
            "`last_raised_at` datetime(6) NOT NULL,",
            "`delivery_status` varchar(12) NOT NULL DEFAULT 'PENDING',",
            "PRIMARY KEY (`id`),",
            "UNIQUE KEY `uq_ops_alerts_type_subject_day` (`alert_type`, `subject_id`, `alert_day`),",
            "KEY `ix_ops_alerts_day` (`alert_day`)");

    private static final List<String> ADMIN_ACTIONS = List.of(
            "`id` bigint NOT NULL AUTO_INCREMENT,",
            "`actor` varchar(80) NOT NULL,",
            "`user_id` bigint DEFAULT NULL,",
            "`verb` varchar(64) NOT NULL,",
            "`target_id` varchar(80) DEFAULT NULL,",
            "`before_json` text,",
            "`after_json` text,",
            "`reason` varchar(255) DEFAULT NULL,",
            "`created_at` datetime(6) NOT NULL,",
            "PRIMARY KEY (`id`),",
            "KEY `ix_admin_actions_user_created` (`user_id`, `created_at`),",
            "KEY `ix_admin_actions_created` (`created_at`)");

    @Test
    void createsExactlyTwoTablesIfNotExists() throws IOException {
        String body = statements();
        assertThat(Pattern.compile("(?m)^CREATE TABLE(?! IF NOT EXISTS)").matcher(body).find()).isFalse();
        assertThat(count(body, "CREATE TABLE")).isEqualTo(2);
        assertThat(body).contains("CREATE TABLE IF NOT EXISTS `ops_alerts` (")
                .contains("CREATE TABLE IF NOT EXISTS `admin_actions` (");
        assertThat(count(body, ENGINE)).isEqualTo(2);
    }

    @Test
    void altersDropsAndReferencesNothing() throws IOException {
        String upper = statements().toUpperCase(Locale.ROOT);
        for (String forbidden : List.of("ALTER ", "DROP ", "PREPARE ", "FOREIGN KEY", "REFERENCES ",
                "VIEW ", "INSERT ", "UPDATE ", "DELETE ")) {
            assertThat(upper).as(forbidden.strip()).doesNotContain(forbidden);
        }
    }

    @Test
    void everyColumnAndKeyMatchesTheDesign() throws IOException {
        String body = statements();
        assertThat(lines(table(body, "ops_alerts"))).containsExactlyElementsOf(OPS_ALERTS);
        assertThat(lines(table(body, "admin_actions"))).containsExactlyElementsOf(ADMIN_ACTIONS);
    }

    @Test
    void hasNoEmDash() throws IOException {
        assertThat(sql()).doesNotContain(String.valueOf(EM_DASH));
    }

    @Test
    void versionedMigrationNumbersAreUnique() throws Exception {
        URL dir = getClass().getClassLoader().getResource("db/migration");
        assertThat(dir).as("db/migration on the classpath").isNotNull();
        assertThat(dir.getProtocol()).as("tests read migrations from build/resources/main").isEqualTo("file");
        Map<Integer, List<String>> byVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(Paths.get(dir.toURI()))) {
            files.map(path -> path.getFileName().toString()).sorted().forEach(name -> {
                Matcher m = VERSIONED.matcher(name);
                if (m.matches()) {
                    byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new ArrayList<>()).add(name);
                }
            });
        }
        assertThat(byVersion).containsKey(11);
        byVersion.forEach((version, names) -> assertThat(names).as("Flyway version " + version).hasSize(1));
    }

    private static String sql() throws IOException {
        try (InputStream in = V11OpsAlertsAndAdminActionsMigrationTest.class.getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as(FILE + " must exist on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The file without its comment lines, so the header prose can neither satisfy nor trip a check. */
    private static String statements() throws IOException {
        return sql().lines().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
    }

    private static String table(String body, String name) {
        String head = "CREATE TABLE IF NOT EXISTS `" + name + "` (";
        int start = body.indexOf(head);
        assertThat(start).as(head).isNotNegative();
        int end = body.indexOf(ENGINE, start);
        assertThat(end).as(name + " closes with the house ENGINE line").isGreaterThan(start);
        return body.substring(start + head.length(), end);
    }

    private static List<String> lines(String block) {
        return block.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }
}
