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
 * The client and the server must band a number the same way.
 *
 * This is not a style preference. A client event and a server event that bucket $2,400 into
 * different bands cannot be read as one funnel, and the funnel is the entire reason both exist.
 * The two tables are written in different languages and will be edited by different people at
 * different times, so the agreement is checked mechanically rather than hoped for: this test reads
 * the TypeScript source and fails if it has drifted from the Java.
 */
class AnalyticsBandsParityTest {

    /** The one source both sides mirror. */
    private static String clientBands() throws IOException {
        Path path = Paths.get("..", "frontend", "src", "app", "services", "analytics",
                "analytics-bands.ts").toAbsolutePath().normalize();
        assertTrue(Files.exists(path),
                "cannot find the client band table at " + path + "; if the frontend moved, this "
                        + "test must move with it rather than be deleted");
        return Files.readString(path);
    }

    /** The members of one exported string-literal union, in declaration order. */
    private static List<String> union(String source, String typeName) {
        Matcher declaration = Pattern
                .compile("export type " + typeName + " =([^;]*);", Pattern.DOTALL)
                .matcher(source);
        assertTrue(declaration.find(), typeName + " is no longer declared in analytics-bands.ts");
        List<String> members = new ArrayList<>();
        Matcher member = Pattern.compile("'([a-z0-9_]+)'").matcher(declaration.group(1));
        while (member.find()) {
            members.add(member.group(1));
        }
        assertFalse(members.isEmpty(), typeName + " has no members");
        return members;
    }

    @Test
    @DisplayName("every band name matches the client, in the same order")
    void bandNamesMatch() throws IOException {
        String source = clientBands();

        assertEquals(union(source, "AmountBand"), AnalyticsBands.AMOUNT_BANDS,
                "AmountBand drifted between analytics-bands.ts and AnalyticsBands.java");
        assertEquals(union(source, "CountBand"), AnalyticsBands.COUNT_BANDS,
                "CountBand drifted");
        assertEquals(union(source, "YearsBand"), AnalyticsBands.YEARS_BANDS,
                "YearsBand drifted");
        assertEquals(union(source, "PercentBand"), AnalyticsBands.PERCENT_BANDS,
                "PercentBand drifted");
    }

    @Test
    @DisplayName("every money boundary matches the client")
    void amountBoundsMatch() throws IOException {
        String source = clientBands();
        Matcher table = Pattern
                .compile("const AMOUNT_BOUNDS[^=]*=\\s*\\[(.*?)\\];", Pattern.DOTALL)
                .matcher(source);
        assertTrue(table.find(), "AMOUNT_BOUNDS is no longer declared in analytics-bands.ts");

        List<Long> bounds = new ArrayList<>();
        Matcher row = Pattern.compile("\\[\\s*([0-9_]+),\\s*'([a-z0-9_]+)'\\s*\\]")
                .matcher(table.group(1));
        List<String> names = new ArrayList<>();
        while (row.find()) {
            bounds.add(Long.parseLong(row.group(1).replace("_", "")));
            names.add(row.group(2));
        }

        assertEquals(bounds, AnalyticsBands.AMOUNT_BOUNDS,
                "the money boundaries drifted; $2,400 would land in different bands on the two sides");
        assertEquals(names, AnalyticsBands.AMOUNT_BANDS.subList(0, names.size()),
                "the boundary-to-band mapping drifted");
    }

    @Test
    @DisplayName("the same inputs produce the same bands as the client's own spec cases")
    void sameInputsSameOutputs() {
        // These are lifted from analytics-bands.spec.ts, so a change to the client's behaviour
        // that leaves the tables intact is still caught here.
        assertEquals("under_50", AnalyticsBands.amount(0.0));
        assertEquals("under_50", AnalyticsBands.amount(49.99));
        assertEquals("50_99", AnalyticsBands.amount(50.0));
        assertEquals("100_249", AnalyticsBands.amount(249.0));
        assertEquals("250_499", AnalyticsBands.amount(250.0));
        assertEquals("1k_2499", AnalyticsBands.amount(2_000.0));
        assertEquals("5k_9999", AnalyticsBands.amount(7_500.0));
        assertEquals("1m_plus", AnalyticsBands.amount(1_500_000.0));
        assertEquals("50_99", AnalyticsBands.amount(99.0));
        assertEquals("100_249", AnalyticsBands.amount(100.0));
        assertEquals("500_999", AnalyticsBands.amount(999.0));
        assertEquals("1k_2499", AnalyticsBands.amount(1_000.0));

        assertEquals("0", AnalyticsBands.count(0));
        assertEquals("1", AnalyticsBands.count(1));
        assertEquals("2_3", AnalyticsBands.count(3));
        assertEquals("4_9", AnalyticsBands.count(9));
        assertEquals("10_plus", AnalyticsBands.count(42));

        assertEquals("under_1", AnalyticsBands.years(0.5));
        assertEquals("1_2", AnalyticsBands.years(2.0));
        assertEquals("6_10", AnalyticsBands.years(7.0));
        assertEquals("over_20", AnalyticsBands.years(25.0));
        assertEquals("never", AnalyticsBands.years(null));

        assertEquals("0_9", AnalyticsBands.percent(0.0));
        assertEquals("75_99", AnalyticsBands.percent(99.9));
        assertEquals("100", AnalyticsBands.percent(100.0));
    }

    @Test
    @DisplayName("broken input never throws, because analytics must not break a caller")
    void brokenInputIsSafe() {
        assertEquals("under_50", AnalyticsBands.amount(Double.NaN));
        assertEquals("under_50", AnalyticsBands.amount(-100.0));
        assertEquals("1m_plus", AnalyticsBands.amount(Double.POSITIVE_INFINITY));
        assertEquals("under_50", AnalyticsBands.amount((Double) null));
        assertEquals("never", AnalyticsBands.years(Double.POSITIVE_INFINITY));
        assertEquals("0_9", AnalyticsBands.percent(Double.NaN));
        assertEquals("unknown", AnalyticsBands.daysBetween(null, null));
        assertEquals("unknown", AnalyticsBands.days(-3));
    }

    @Test
    @DisplayName("day bands, which are server-only and have no client counterpart")
    void dayBands() {
        assertEquals("same_day", AnalyticsBands.days(0));
        assertEquals("1_3", AnalyticsBands.days(1));
        assertEquals("1_3", AnalyticsBands.days(3));
        assertEquals("4_7", AnalyticsBands.days(4));
        assertEquals("8_14", AnalyticsBands.days(14));
        assertEquals("15_30", AnalyticsBands.days(30));
        assertEquals("31_90", AnalyticsBands.days(90));
        assertEquals("over_90", AnalyticsBands.days(91));
    }
}
