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
 * The client lists the server's events so it can never define a competing version of the same
 * fact; the server lists them because it emits them. Two lists, one truth, checked mechanically.
 */
class ServerAnalyticsEventParityTest {

    private static List<String> clientServerNames() throws IOException {
        Path path = Paths.get("..", "frontend", "src", "app", "services", "analytics",
                "analytics-events.ts").toAbsolutePath().normalize();
        assertTrue(Files.exists(path), "cannot find the client taxonomy at " + path);
        String source = Files.readString(path);

        Matcher list = Pattern
                .compile("export const SERVER_EVENT_NAMES = \\[(.*?)\\] as const;", Pattern.DOTALL)
                .matcher(source);
        assertTrue(list.find(), "SERVER_EVENT_NAMES is no longer declared in analytics-events.ts");

        List<String> names = new ArrayList<>();
        Matcher name = Pattern.compile("'([a-z0-9_]+)'").matcher(list.group(1));
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("the two taxonomies list the same server events, in the same order")
    void namesMatch() throws IOException {
        assertEquals(clientServerNames(), ServerAnalyticsEvent.ALL,
                "SERVER_EVENT_NAMES in analytics-events.ts has drifted from ServerAnalyticsEvent.ALL");
    }

    @Test
    @DisplayName("the client never claims a server event as one of its own")
    void noClientCollision() throws IOException {
        Path path = Paths.get("..", "frontend", "src", "app", "services", "analytics",
                "analytics-events.ts").toAbsolutePath().normalize();
        String source = Files.readString(path);

        Matcher list = Pattern
                .compile("export const CLIENT_EVENT_NAMES = \\[(.*?)\\] as const;", Pattern.DOTALL)
                .matcher(source);
        assertTrue(list.find(), "CLIENT_EVENT_NAMES is no longer declared");

        Matcher name = Pattern.compile("'([a-z0-9_]+)'").matcher(list.group(1));
        while (name.find()) {
            assertFalse(ServerAnalyticsEvent.ALL.contains(name.group(1)),
                    name.group(1) + " is on both the client and the server list");
        }
    }

    @Test
    @DisplayName("the three billing events are defined but emitted by nothing")
    void billingEventsAreInert() throws IOException {
        // If one of these ever gains a real call site, this test is the reminder that the billing
        // session owns it and that the trial record must commit in the same transaction.
        Path services = Paths.get("src", "main", "java", "com", "investingapp", "backend")
                .toAbsolutePath().normalize();
        List<String> offenders = new ArrayList<>();

        Files.walk(services)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("BillingAnalyticsEmitter.java"))
                .filter(p -> !p.getFileName().toString().equals("ServerAnalyticsEvent.java"))
                .forEach(p -> {
                    try {
                        String body = Files.readString(p);
                        for (String event : ServerAnalyticsEvent.AWAITING_BILLING) {
                            String constant = event.toUpperCase();
                            if (body.contains("\"" + event + "\"") || body.contains(constant)) {
                                offenders.add(p.getFileName() + " references " + event);
                            }
                        }
                    } catch (IOException e) {
                        fail("could not read " + p);
                    }
                });

        assertTrue(offenders.isEmpty(),
                "billing events must only be referenced by BillingAnalyticsEmitter: " + offenders);
    }
}
