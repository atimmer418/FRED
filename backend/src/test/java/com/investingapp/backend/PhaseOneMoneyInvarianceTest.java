package com.investingapp.backend;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1 runs in shadow: it must not move money, write a schedule's status or mode, read the
 * deprecated pause getters, or alter the runnable rule. A task adding a source outside
 * PHASE_ONE_SOURCES extends the list in its own change.
 */
class PhaseOneMoneyInvarianceTest {

    static final Path MAIN = Paths.get("src", "main", "java", "com", "investingapp", "backend")
            .toAbsolutePath().normalize();

    /**
     * Every file a Phase 1 task creates or modifies, relative to MAIN. A trailing '/' walks a
     * directory (it may not exist yet); a trailing '*' is a file-name prefix in that directory.
     */
    static final List<String> PHASE_ONE_SOURCES = List.of(
            "service/Paycheck*", "service/Plaid*", "service/Admin*",
            "service/InitSyncEnqueuer.java", "service/NotificationService.java", "service/NotificationGate.java",
            "service/DetectionEligibility.java", "service/ShadowReportService.java",
            "config/PaycheckAutopilotProperties.java",
            "controller/admin/", "controller/PaycheckDetectionController.java", "controller/DevPaycheckController.java",
            "paycheck/", "plaid/", "ops/");

    /** An InvestmentSchedule.Status write; PlaidWebhookEvent.setStatus(status) stays legal. */
    static final Pattern SCHEDULE_STATUS_WRITE = Pattern.compile("setStatus\\(\\s*(InvestmentSchedule\\.)?Status\\.");
    static final List<String> FORBIDDEN_CALLS = List.of("setMode(", "getIsPaused(", "isPausedForAnyReason(");

    private static final String READY_SIGNATURE = "public boolean isReadyForInvestment(LocalDate todayET)";
    private static final String PINNED_READY_RULE = """
            public boolean isReadyForInvestment(LocalDate todayET) {
                if (status != Status.ACTIVE || achRequestId == null) {
                    return false;
                }
                if (holdReason != null && BLOCKING_HOLDS.contains(holdReason)) {
                    return false;
                }
                return (startDate != null && startDate.equals(todayET))
                        || (nextInvestmentDate != null && !nextInvestmentDate.isAfter(todayET));
            }""";

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Test
    void isReadyForInvestmentBodyUnchanged() throws IOException {
        String source = Files.readString(MAIN.resolve("model/InvestmentSchedule.java"));
        assertThat(normalize(methodText(source, READY_SIGNATURE)))
                .as("Phase 1 never changes the runnable rule")
                .isEqualTo(normalize(PINNED_READY_RULE));
    }

    @Test
    void processorStillRefusesDetectedMode() {
        InvestmentScheduleRepository schedules = mock(InvestmentScheduleRepository.class);
        InvestmentExecutionRepository executions = mock(InvestmentExecutionRepository.class);
        User user = new User("shadow@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");
        InvestmentSchedule s = runnable();
        s.setId(7L);
        s.setUser(user);
        s.setMode(ScheduleMode.DETECTED);
        when(schedules.findById(7L)).thenReturn(Optional.of(s));

        assertThat(s.isReadyForInvestment(TODAY)).as("the MANUAL rule alone would run this row").isTrue();
        assertThat(new ScheduledInvestmentProcessor(schedules, executions).processOne(7L, TODAY))
                .isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executions, never()).save(any());
        verify(schedules, never()).save(any());
    }

    @Test
    void blockingHoldsStillThree() {
        EnumSet<HoldReason> blocking = EnumSet.noneOf(HoldReason.class);
        for (HoldReason hold : HoldReason.values()) {
            InvestmentSchedule s = runnable();
            s.setHoldReason(hold);
            if (!s.isReadyForInvestment(TODAY)) {
                blocking.add(hold);
            }
        }
        assertThat(blocking).containsExactlyInAnyOrder(
                HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);
        assertThat(HoldReason.values()).as("a new hold reason needs a blocking decision and a view change").hasSize(10);
    }

    @Test
    void phaseOneSourcesNeverCallSetStatusOrSetMode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : phaseOneFiles()) {
            offenders.addAll(forbiddenCalls(MAIN.relativize(file).toString().replace('\\', '/'), Files.readString(file)));
        }
        assertThat(offenders).as("Phase 1 never writes status or mode, nor reads the pause getters").isEmpty();
    }

    @Test
    void walkSeesThisTasksFilesAndThePhaseZeroPlaidServices() throws IOException {
        // A walk that matched nothing would pass vacuously.
        assertThat(phaseOneFiles()).extracting(p -> p.getFileName().toString()).contains(
                "PlaidService.java", "PlaidWebhookInboxService.java", "PlaidItemLifecycleService.java",
                "DetectionEligibility.java", "NotificationGate.java", "NotificationService.java",
                "PaycheckAutopilotProperties.java");
    }

    @Test
    void matcherCatchesScheduleWritesButNotWebhookStatus() {
        assertThat(forbiddenCalls("a.java", "event.setStatus(status);\nevent.setStatus(PlaidWebhookEventStatus.PROCESSED);"))
                .isEmpty();
        assertThat(forbiddenCalls("b.java", "s.setStatus(InvestmentSchedule.Status.ACTIVE);"))
                .containsExactly("b.java:1 writes InvestmentSchedule.status");
        assertThat(forbiddenCalls("c.java", "x();\ns.setStatus(\n        Status.PAUSED_BY_USER);"))
                .containsExactly("c.java:2 writes InvestmentSchedule.status");
        assertThat(forbiddenCalls("d.java", "s.setMode(ScheduleMode.DETECTED);\nif (s.getIsPaused()) {}\ns.isPausedForAnyReason();"))
                .containsExactly("d.java:1 calls setMode(", "d.java:2 calls getIsPaused(", "d.java:3 calls isPausedForAnyReason(");
    }

    /** An ACTIVE, linked MANUAL schedule due today, with no hold. */
    private static InvestmentSchedule runnable() {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setStatus(InvestmentSchedule.Status.ACTIVE);
        s.setAchRequestId("rel-1");
        s.setFrequency("BIWEEKLY");
        s.setInvestmentAmount(new BigDecimal("150.00"));
        s.setMonthlyAmount(new BigDecimal("325.00"));
        s.setStartDate(TODAY.minusWeeks(6));
        s.setNextInvestmentDate(TODAY);
        return s;
    }

    static List<Path> phaseOneFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String entry : PHASE_ONE_SOURCES) {
            if (entry.endsWith("/")) {
                Path dir = MAIN.resolve(entry);
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                    }
                }
            } else if (entry.endsWith("*")) {
                int slash = entry.lastIndexOf('/');
                Path dir = MAIN.resolve(entry.substring(0, slash));
                String prefix = entry.substring(slash + 1, entry.length() - 1);
                try (Stream<Path> list = Files.list(dir)) {
                    list.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".java"))
                            .forEach(files::add);
                }
            } else {
                Path file = MAIN.resolve(entry);
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    static List<String> forbiddenCalls(String relPath, String source) {
        List<String> found = new ArrayList<>();
        Matcher m = SCHEDULE_STATUS_WRITE.matcher(source);
        while (m.find()) {
            found.add(relPath + ":" + lineOf(source, m.start()) + " writes InvestmentSchedule.status");
        }
        for (String call : FORBIDDEN_CALLS) {
            for (int i = source.indexOf(call); i >= 0; i = source.indexOf(call, i + 1)) {
                found.add(relPath + ":" + lineOf(source, i) + " calls " + call);
            }
        }
        return found;
    }

    static String methodText(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as("signature not found: " + signature).isGreaterThanOrEqualTo(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * T11: detection writes its own stream rows and the schedule's detection mirror, never money,
     * a stop or Item state. Read from source so a later edit that reaches for one of these fails
     * here, by name.
     */
    @org.junit.jupiter.api.Test
    void detectionEvaluateWritesOnlyTheMirror() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java", "com", "investingapp", "backend");
        for (String relative : java.util.List.of(
                "service/PaycheckDetectionService.java",
                "paycheck/EvaluationTrigger.java",
                "paycheck/EvaluationOutcome.java")) {
            String source = java.nio.file.Files.readString(root.resolve(relative));
            for (String forbidden : java.util.List.of(
                    "setHoldReason(", "setInvestmentAmount(", "setMonthlyAmount(",
                    "setNextInvestmentDate(", "setAchRequestId(", "setFrequency(",
                    "setPlaidItemStatus(", "setPlaidHistoryComplete(", "setPlaidSyncCursor(",
                    "isReadyForInvestment(", "userRepository.save")) {
                org.junit.jupiter.api.Assertions.assertFalse(source.contains(forbidden),
                        relative + " must not contain " + forbidden);
            }
        }
    }

    /** Decision 41 (T13): hold_reason has a closed writer list; Phase 0 keeps its four sites. */
    @org.junit.jupiter.api.Test
    void onlyAllowlistedFilesCallSetHoldReason() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java/com/investingapp/backend");
        java.util.Set<String> allowed = java.util.Set.of(
                "service/InvestmentScheduleService.java",
                "service/AccountStatusService.java",
                "service/AchRelationshipService.java",
                "service/PlaidItemStateService.java",
                "service/AdminPaycheckService.java");
        java.util.Map<String, Integer> sites = new java.util.TreeMap<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = java.nio.file.Files.readString(file);
                int count = text.split("setHoldReason\\(", -1).length - 1;
                if (count > 0) {
                    sites.put(root.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(sites.keySet())
                .as("files calling setHoldReason( outside the allowlist")
                .isSubsetOf(allowed);
        org.assertj.core.api.Assertions.assertThat(
                sites.getOrDefault("service/InvestmentScheduleService.java", 0)
                        + sites.getOrDefault("service/AccountStatusService.java", 0)
                        + sites.getOrDefault("service/AchRelationshipService.java", 0))
                .as("Phase 0 hold-writer sites")
                .isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(sites).containsKey("service/PlaidItemStateService.java");
    }
}
