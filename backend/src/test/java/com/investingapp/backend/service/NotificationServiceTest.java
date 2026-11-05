package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final LocalDate INCIDENT = LocalDate.of(2026, 11, 4);
    private static final String MANUAL_LINE = "Reconnect your Chase account so your investing keeps running.";
    private static final String DETECTED_LINE = "Reconnect your Chase account so FRED can keep spotting your paycheck."
            + " Paycheck investing is paused until then.";

    /** Every DETECTED-only emailType of spec 10. Phase 1 has no caller for any of them. */
    private static final List<String> DETECTED_ONLY_TYPES = List.of("PAYDAY_D1_DETECTED", "PAYCHECK_FOUND",
            "SKIP_AFFORDABILITY", "SKIP_CATCHUP", "SKIP_HOLD_ACH", "SKIP_HOLD", "SKIP_NO_AUTH", "DORMANT",
            "INTERRUPTION", "WELCOME_BACK", "NEW_EMPLOYER", "CADENCE_CHANGE", "AUTHORIZATION_NEEDED",
            "DORMANT_120");

    private EmailService emailService;
    private InvestmentScheduleRepository schedules;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        schedules = mock(InvestmentScheduleRepository.class);
        user = new User("saver@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("access-sandbox-token");
        user.setPlaidInstitutionName("Chase");
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        user.setPaycheckAutopilotOptOut(false);
        schedule = new InvestmentSchedule();
        schedule.setUser(user);
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        when(emailService.hasBeenSent(anyString(), anyLong(), anyString())).thenReturn(false);
    }

    private static PaycheckAutopilotProperties props(boolean enabled, boolean shadow) {
        return new PaycheckAutopilotProperties(enabled, shadow, false, 55, 70, new BigDecimal("0.90"), 365);
    }

    private NotificationService service(PaycheckAutopilotProperties props) {
        return new NotificationService(emailService, schedules, new NotificationGate(props), props);
    }

    /** Phase 1's configuration: enabled, shadow, no pulls. */
    private NotificationService phaseOne() {
        return service(props(true, true));
    }

    private static Supplier<EmailMessage> content() {
        return () -> new EmailMessage(null, null, "Subject", "<p>Body</p>", "Body");
    }

    private EmailMessage sent(String type, String key) {
        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).sendEmail(cap.capture(), eq(42L), eq(type), eq(key));
        return cap.getValue();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = NotificationServiceTest.class.getResourceAsStream("/templates/email/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void periodKeyLongerThanTenRejected() {
        NotificationService s = phaseOne();
        assertThat(NotificationService.PERIOD_KEY_MAX).isEqualTo(10);
        assertThatThrownBy(() -> s.sendOnce(user, "RECONNECT", "2026-11-04X", content()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodKey");
        assertThatThrownBy(() -> s.sendOnce(user, "RECONNECT", null, content()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.sendOnce(user, "X".repeat(41), "2026-11-04", content()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("emailType");
        assertThatThrownBy(() -> s.sendDetectedOnce(user, schedule, "PAYCHECK_FOUND", "12345678901", content()))
                .as("checked before the gate").isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(emailService);
    }

    @Test
    void alreadySentSendsNothingAndNeverComposes() {
        when(emailService.hasBeenSent("RECONNECT", 42L, "2026-11-04")).thenReturn(true);
        boolean result = phaseOne().sendOnce(user, "RECONNECT", "2026-11-04",
                () -> { throw new AssertionError("must not compose a notice already in the ledger"); });
        assertThat(result).isFalse();
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), any(), any());
    }

    @Test
    void sendOnceAddressesTheUserAndRecordsTypeAndKey() {
        assertThat(phaseOne().sendOnce(user, "RECONNECT", "2026-11-04", content())).isTrue();
        EmailMessage m = sent("RECONNECT", "2026-11-04");
        assertThat(m.getTo()).isEqualTo("saver@fredvested.com");
        assertThat(m.getSubject()).isEqualTo("Subject");
    }

    @Test
    void composeFailureRecordsFailedAttemptAndNeverThrows() {
        boolean result = phaseOne().sendOnce(user, "RECONNECT", "2026-11-04",
                () -> { throw new IllegalStateException("Unresolved placeholder"); });
        assertThat(result).isFalse();
        verify(emailService).recordFailedAttempt(42L, "saver@fredvested.com", "RECONNECT", "2026-11-04", "compose_failed");
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), any(), any());
    }

    @Test
    void noRecipientSendsNothing() {
        user.setEmail(" ");
        assertThat(phaseOne().sendOnce(user, "RECONNECT", "2026-11-04", content())).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void ledgerFailureSendsNothing() {
        when(emailService.hasBeenSent(anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(phaseOne().sendOnce(user, "RECONNECT", "2026-11-04", content())).isFalse();
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), any(), any());
    }

    @Test
    void reconnectManualCopy() {
        schedule.setMode(ScheduleMode.MANUAL);
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isTrue();
        EmailMessage m = sent(NotificationService.TYPE_RECONNECT, "2026-11-04");
        assertThat(m.getTo()).isEqualTo("saver@fredvested.com");
        assertThat(m.getSubject()).isEqualTo("Reconnect your bank account");
        assertThat(m.getTextBody()).startsWith(MANUAL_LINE);
        assertThat(m.getHtmlBody()).contains(MANUAL_LINE);
        assertThat(m.getTextBody()).doesNotContain("paused");
        assertThat(m.getHtmlBody()).doesNotContain("{{");
    }

    @Test
    void reconnectDetectedCopy() {
        schedule.setMode(ScheduleMode.DETECTED);
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isTrue();
        EmailMessage m = sent("RECONNECT", "2026-11-04");
        assertThat(m.getTextBody()).startsWith(DETECTED_LINE);
        assertThat(m.getHtmlBody()).contains(DETECTED_LINE);
    }

    @Test
    void reconnectEscapesInstitutionNameInHtmlOnly() {
        user.setPlaidInstitutionName("Smith & Sons <Bank>");
        phaseOne().sendReconnect(user, INCIDENT);
        EmailMessage m = sent("RECONNECT", "2026-11-04");
        assertThat(m.getHtmlBody()).contains("Smith &amp; Sons &lt;Bank&gt;").doesNotContain("<Bank>");
        assertThat(m.getTextBody()).startsWith("Reconnect your Smith & Sons <Bank> account");
    }

    @Test
    void reconnectFallsBackToYourBank() {
        user.setPlaidInstitutionName(null);
        phaseOne().sendReconnect(user, INCIDENT);
        assertThat(sent("RECONNECT", "2026-11-04").getTextBody())
                .startsWith("Reconnect your bank account so your investing keeps running.");
    }

    @Test
    void reconnectSkippedForRejectedApplicant() {
        user.setAccountStatus("REJECTED");
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void reconnectSkippedWithoutScheduleRow() {
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void reconnectSkippedWhenAutopilotDisabled() {
        assertThat(service(props(false, true)).sendReconnect(user, INCIDENT)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void reconnectStillSentUnderShadowForOptedOutAndBrokenItem() {
        user.setPaycheckAutopilotOptOut(true);
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isTrue();
        sent("RECONNECT", "2026-11-04");
    }

    @Test
    void shadowSuppressesEveryDetectedNotice() {
        NotificationService s = phaseOne();
        for (String type : DETECTED_ONLY_TYPES) {
            assertThat(s.sendDetectedOnce(user, schedule, type, "2026-11",
                    () -> { throw new AssertionError("composed under shadow: " + type); }))
                    .as(type).isFalse();
        }
        verifyNoInteractions(emailService);
    }

    @Test
    void detectedNoticeSendsWhenGateOpen() {
        assertThat(service(props(true, false)).sendDetectedOnce(user, schedule, "PAYCHECK_FOUND", "917", content()))
                .isTrue();
        sent("PAYCHECK_FOUND", "917");
    }

    @Test
    void reconnectCopyAndTemplatesHaveNoEmDash() throws IOException {
        String emDash = String.valueOf((char) 0x2014);
        List<String> texts = List.of(NotificationService.RECONNECT_SUBJECT, NotificationService.RECONNECT_LINE_MANUAL,
                NotificationService.RECONNECT_LINE_DETECTED, resource("reconnect.html"), resource("reconnect.txt"),
                NotificationService.composeReconnect("Chase", ScheduleMode.MANUAL).getHtmlBody(),
                NotificationService.composeReconnect("Chase", ScheduleMode.DETECTED).getTextBody());
        for (String text : texts) {
            assertThat(text).doesNotContain(emDash).doesNotContain("&mdash;");
        }
    }
}
