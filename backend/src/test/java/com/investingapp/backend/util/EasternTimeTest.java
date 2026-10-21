package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One clock for money and notifications (spec 5.8, decision 17).
 *
 * <p>BackendApplication.main also pins the JVM default to Eastern, but tests, tools and anything
 * that bypasses main run with the machine zone, so money paths must name the zone themselves. These
 * tests move the JVM default to a zone whose calendar day differs from Eastern right now and prove
 * EasternTime does not follow it.
 */
class EasternTimeTest {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    /** A JVM default whose calendar day differs from Eastern at this moment. */
    static TimeZone aZoneWhoseDayDiffersFromEastern() {
        int easternHour = LocalTime.now(EASTERN).getHour();
        return TimeZone.getTimeZone(easternHour < 6 ? ZoneId.of("-12:00") : ZoneId.of("+14:00"));
    }

    @Test
    void zoneIsAmericaNewYork() {
        assertEquals(EASTERN, EasternTime.ZONE);
    }

    @Test
    void todayIgnoresTheJvmDefaultZone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDate expected = LocalDate.now(EASTERN);
            assertNotEquals(expected, LocalDate.now(),
                    "precondition: the JVM default day must differ from Eastern for this test to bite");
            assertEquals(expected, EasternTime.today());
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void nowReadsTheEasternWallClock() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDateTime expected = LocalDateTime.now(EASTERN);
            LocalDateTime actual = EasternTime.now();
            assertTrue(Math.abs(ChronoUnit.SECONDS.between(expected, actual)) < 5,
                    "EasternTime.now() drifted from the Eastern wall clock: " + actual + " vs " + expected);
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    /**
     * Decision 17: the JVM default moves from UTC to America/New_York. main() boots Spring, so the
     * pin is checked at the source, the way AnalyticsBandsParityTest reads the client band table.
     * Gradle runs tests with backend/ as the working directory, so the path is project-relative.
     */
    @Test
    void jvmDefaultInMainIsPinnedToTheSameZone() throws IOException {
        Path main = Paths.get("src", "main", "java", "com", "investingapp", "backend",
                "BackendApplication.java").toAbsolutePath().normalize();
        assertTrue(Files.exists(main), "cannot find BackendApplication.java at " + main);
        String source = Files.readString(main);
        assertTrue(source.contains("TimeZone.setDefault(TimeZone.getTimeZone(\"America/New_York\"))"),
                "BackendApplication.main must pin the JVM default to America/New_York");
        assertTrue(source.contains("System.setProperty(\"user.timezone\", \"America/New_York\")"),
                "BackendApplication.main must set user.timezone to America/New_York");
        assertFalse(source.contains("\"UTC\""), "BackendApplication.main must no longer mention UTC");
    }
}
