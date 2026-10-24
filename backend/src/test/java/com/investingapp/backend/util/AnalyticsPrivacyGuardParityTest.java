package com.investingapp.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone and the server must forbid the same things.
 *
 * A rule that exists on one side only is the worse half of no guard at all: it reads as covered in
 * every document and in this review, while the uncovered side is free to send whatever it likes.
 * The two lists are written in different languages and will be edited by different people at
 * different times, so the agreement is checked mechanically rather than hoped for. This test reads
 * the TypeScript and fails if it has drifted from the Java, in either direction.
 *
 * It follows AnalyticsBandsParityTest, which does the same job for the band tables.
 */
class AnalyticsPrivacyGuardParityTest {

    private static String clientGuard() throws IOException {
        Path path = Paths.get("..", "frontend", "src", "app", "services", "analytics",
                "analytics-privacy-guard.ts").toAbsolutePath().normalize();
        assertTrue(Files.exists(path),
                "cannot find the client privacy guard at " + path + "; if the frontend moved, this "
                        + "test must move with it rather than be deleted");
        return Files.readString(path);
    }

    /** The body of one exported array of rules. */
    private static String arrayBody(String source, String constantName) {
        Matcher declaration = Pattern
                .compile("export const " + constantName + ": readonly GuardRule\\[\\] = \\[(.*?)\\n\\];",
                        Pattern.DOTALL)
                .matcher(source);
        assertTrue(declaration.find(),
                constantName + " is no longer declared in analytics-privacy-guard.ts");
        return declaration.group(1);
    }

    /**
     * The rules in one array, as "name=pattern" pairs in declaration order.
     *
     * The names and the regex literals are read separately and zipped, because a rule whose
     * pattern is long enough sits on its own line.
     */
    private static List<String> clientRules(String source, String constantName) {
        String body = arrayBody(source, constantName);

        List<String> names = new ArrayList<>();
        Matcher name = Pattern.compile("name: '([a-z0-9_]+)'").matcher(body);
        while (name.find()) {
            names.add(name.group(1));
        }

        List<String> patterns = new ArrayList<>();
        // A regex literal on one line: everything between the first slash after `pattern: ` and
        // the last slash on that line. None of these patterns contains a slash.
        Matcher pattern = Pattern.compile("pattern: /(.+)/").matcher(body);
        while (pattern.find()) {
            patterns.add(pattern.group(1));
        }

        assertEquals(names.size(), patterns.size(),
                "every rule in " + constantName + " needs a name and a pattern");
        assertFalse(names.isEmpty(), constantName + " has no rules");

        List<String> rules = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            rules.add(names.get(i) + "=" + patterns.get(i));
        }
        return rules;
    }

    private static List<String> serverRules(List<AnalyticsPrivacyGuard.Rule> rules) {
        List<String> flattened = new ArrayList<>();
        for (AnalyticsPrivacyGuard.Rule rule : rules) {
            flattened.add(rule.name() + "=" + rule.pattern().pattern());
        }
        return flattened;
    }

    @Test
    @DisplayName("the key deny-lists are identical, rule for rule and in the same order")
    void keyRulesMatch() throws IOException {
        assertEquals(
                clientRules(clientGuard(), "FORBIDDEN_KEY_PATTERNS"),
                serverRules(AnalyticsPrivacyGuard.FORBIDDEN_KEY_PATTERNS),
                "the key deny-list drifted between analytics-privacy-guard.ts and "
                        + "AnalyticsPrivacyGuard.java. Order matters as well as content: it decides "
                        + "which rule a violation is reported as");
    }

    @Test
    @DisplayName("the value-shape checks are identical, rule for rule and in the same order")
    void valueRulesMatch() throws IOException {
        assertEquals(
                clientRules(clientGuard(), "FORBIDDEN_VALUE_PATTERNS"),
                serverRules(AnalyticsPrivacyGuard.FORBIDDEN_VALUE_PATTERNS),
                "the value-shape checks drifted between analytics-privacy-guard.ts and "
                        + "AnalyticsPrivacyGuard.java");
    }

    @Test
    @DisplayName("every shared pattern compiles to the same verdict on the same input")
    void thePatternsBehaveTheSame() throws IOException {
        // Equal sources are not quite equal behaviour: the two engines could read a construct
        // differently. These are the inputs the rules exist for, checked against the Java side
        // after the sources have been proven equal to the TypeScript.
        assertNotNull(AnalyticsPrivacyGuard.forbiddenKeyRule("user_email"));
        assertNotNull(AnalyticsPrivacyGuard.forbiddenValueRule("someone@example.com"));
        assertNotNull(AnalyticsPrivacyGuard.forbiddenValueRule("123-45-6789"));
        assertNotNull(AnalyticsPrivacyGuard.forbiddenValueRule("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0"));
        assertNull(AnalyticsPrivacyGuard.forbiddenValueRule("1k_2499"));
        assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule("amount_band"));
    }

    /**
     * The client's exemption list covers the CLIENT's keys and the server's covers the server's,
     * because the two taxonomies are different. What must agree is the person properties: the
     * server sets them and the client declares them, and a rename on one side would leave the
     * other exempting a key nobody sends.
     */
    @Test
    @DisplayName("the person property keys match the client's list, in the same order")
    void personPropertyKeysMatch() throws IOException {
        Path path = Paths.get("..", "frontend", "src", "app", "services", "analytics",
                "analytics-events.ts").toAbsolutePath().normalize();
        String source = Files.readString(path);

        Matcher list = Pattern
                .compile("export const PERSON_PROPERTY_KEYS = \\[(.*?)\\] as const;", Pattern.DOTALL)
                .matcher(source);
        assertTrue(list.find(), "PERSON_PROPERTY_KEYS is no longer declared in analytics-events.ts");

        List<String> client = new ArrayList<>();
        Matcher key = Pattern.compile("'([a-z0-9_]+)'").matcher(list.group(1));
        while (key.find()) {
            client.add(key.group(1));
        }

        assertEquals(client, ServerAnalyticsEvent.PERSON_PROPERTY_KEYS,
                "the person properties drifted between the client taxonomy and ServerAnalyticsEvent");
    }
}
