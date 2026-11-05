package com.investingapp.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** R11: every alert.* line is also raised, and no raise carries money or free-text error text. */
class AlertSitesRoutedTest {

    private static final Path MAIN = Paths.get("src", "main", "java");
    private static final Set<String> LOG_ONLY = Set.of("OpsAlertService.java", "AnalyticsPrivacyGuard.java");
    private static final Pattern ALERT_LINE = Pattern.compile("logger\\.error\\(\"alert\\.");
    private static final String RAISE = "opsAlertService.raise(";
    private static final Pattern FORBIDDEN_ARGUMENT = Pattern.compile("\\b(total|amount|getLastError|getMessage|error)\\b");
    private static final int PHASE_ZERO_SITES = 15;

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void everyAlertLineIsRoutedThroughOpsAlertService() throws IOException {
        List<String> unrouted = new ArrayList<>();
        int sites = 0;
        for (Path file : sources()) {
            if (LOG_ONLY.contains(file.getFileName().toString())) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!ALERT_LINE.matcher(lines.get(i)).find()) {
                    continue;
                }
                sites++;
                boolean routed = false;
                for (int j = i + 1; j <= Math.min(i + 3, lines.size() - 1); j++) {
                    if (lines.get(j).contains(RAISE)) {
                        routed = true;
                        break;
                    }
                }
                if (!routed) {
                    unrouted.add(MAIN.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertThat(unrouted).as("alert.* lines with no " + RAISE + " within the next 3 lines").isEmpty();
        assertThat(sites).as("alert.* sites found (a moved file must not pass by vanishing)")
                .isGreaterThanOrEqualTo(PHASE_ZERO_SITES);
    }

    @Test
    void routedRaisesCarryNoAmountOrErrorText() throws IOException {
        List<String> offenders = new ArrayList<>();
        int raises = 0;
        for (Path file : sources()) {
            if (file.getFileName().toString().equals("OpsAlertService.java")) {
                continue;
            }
            String text = Files.readString(file);
            int from = 0;
            while ((from = text.indexOf(RAISE, from)) >= 0) {
                int open = from + RAISE.length() - 1;
                int close = matchingParen(text, open);
                raises++;
                Matcher m = FORBIDDEN_ARGUMENT.matcher(text.substring(open + 1, close));
                if (m.find()) {
                    int line = (int) text.substring(0, from).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(MAIN.relativize(file) + ":" + line + " names " + m.group(1));
                }
                from = close;
            }
        }
        assertThat(raises).as(RAISE + " calls found").isGreaterThanOrEqualTo(PHASE_ZERO_SITES);
        assertThat(offenders).as("raise arguments naming an amount or free-text error").isEmpty();
    }

    /** Final review PAL-2: the alert.* log line itself is held to the same rule as its raise (spec 12.3). */
    @Test
    void alertLogLinesCarryNoAmountOrErrorText() throws IOException {
        Pattern alertCall = Pattern.compile("logger\\.(error|warn)\\(\"alert\\.");
        List<String> offenders = new ArrayList<>();
        int lines = 0;
        for (Path file : sources()) {
            if (LOG_ONLY.contains(file.getFileName().toString())) {
                continue;
            }
            String text = Files.readString(file);
            Matcher call = alertCall.matcher(text);
            while (call.find()) {
                int open = text.indexOf('(', call.start());
                int close = matchingParen(text, open);
                lines++;
                Matcher m = FORBIDDEN_ARGUMENT.matcher(text.substring(open + 1, close));
                if (m.find()) {
                    int line = (int) text.substring(0, call.start()).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(MAIN.relativize(file) + ":" + line + " names " + m.group(1));
                }
            }
        }
        assertThat(lines).as("alert.* log lines found").isGreaterThanOrEqualTo(PHASE_ZERO_SITES);
        assertThat(offenders).as("alert.* log lines naming an amount or free-text error").isEmpty();
    }

    @Test
    void scanCatchesAnAmountOrErrorTextInAnAlertLine() {
        String bad = "logger.error(\"alert.x user={} total={}\", id, batch.totalAmount());";
        int open = bad.indexOf('(');
        assertThat(FORBIDDEN_ARGUMENT.matcher(bad.substring(open + 1, matchingParen(bad, open))).find()).isTrue();
        String lastError = "logger.error(\"alert.y id={} last={}\", row.getId(), row.getLastError());";
        open = lastError.indexOf('(');
        assertThat(FORBIDDEN_ARGUMENT.matcher(lastError.substring(open + 1, matchingParen(lastError, open))).find()).isTrue();
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced " + RAISE + " call");
    }
}
