package com.investingapp.backend.dto.admin;

import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PlaidWebhookEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** GET /{userId}/status: never the token, cursor or a payload; never logged or audited. */
public record AdminUserStatusView(PaycheckDetectionStatusResponse detection, List<StreamRow> streams,
                                  List<CycleRow> cycles, List<InboxRow> inbox) {

    public record StreamRow(Long id, String state, String role, String payerToken, String payerDisplayName,
                            String rawDescriptor, String plaidAccountId, String cadence, int score,
                            String scoreBreakdownJson, int depositsFound, int missedCount, LocalDate lastDate,
                            BigDecimal trailingMedian, LocalDate predictedNextDate, String rejectReason,
                            String retiredReason, LocalDateTime retiredAt) {
        public static StreamRow of(PaycheckStream s) {
            return new StreamRow(s.getId(), name(s.getState()), name(s.getRole()), s.getPayerToken(),
                    s.getPayerDisplayName(), s.getRawDescriptor(), s.getPlaidAccountId(), name(s.getCadence()),
                    s.getScore(), s.getScoreBreakdownJson(), s.getDepositsFound(), s.getMissedCount(),
                    s.getLastDate(), s.getTrailingMedian(), s.getPredictedNextDate(), name(s.getRejectReason()),
                    name(s.getRetiredReason()), s.getRetiredAt());
        }
    }

    public record CycleRow(Long id, Long streamId, String periodKey, String state, LocalDate expectedDate,
                           LocalDate windowEnd, Long eventId, BigDecimal amountToPull, LocalDateTime confirmedAt,
                           String skipReason, String voidedReason, Long executionId) {
        public static CycleRow of(PaycheckCycle c) {
            return new CycleRow(c.getId(), c.getStreamId(), c.getPeriodKey(), name(c.getState()), c.getExpectedDate(),
                    c.getWindowEnd(), c.getEventId(), c.getAmountToPull(), c.getConfirmedAt(),
                    name(c.getSkipReason()), name(c.getVoidedReason()), c.getExecutionId());
        }
    }

    public record InboxRow(Long id, Long userId, String plaidItemId, String webhookType, String webhookCode,
                           String status, int attempts, String lastError, LocalDateTime receivedAt,
                           LocalDateTime processedAt) {
        public static InboxRow of(PlaidWebhookEvent e) {
            return new InboxRow(e.getId(), e.getUserId(), e.getPlaidItemId(), e.getWebhookType(), e.getWebhookCode(),
                    name(e.getStatus()), e.getAttempts(), e.getLastError(), e.getReceivedAt(), e.getProcessedAt());
        }
    }

    public static AdminUserStatusView of(PaycheckDetectionStatusResponse detection, List<PaycheckStream> streams,
                                         List<PaycheckCycle> cycles, List<PlaidWebhookEvent> inbox) {
        return new AdminUserStatusView(detection, streams.stream().map(StreamRow::of).toList(),
                cycles.stream().map(CycleRow::of).toList(), inbox.stream().map(InboxRow::of).toList());
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
