package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionKeysTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 19);

    @Test
    void scheduledKeyIsSchedPrefixScheduleIdAndIsoDueDate() {
        assertThat(ExecutionKeys.scheduled(7L, NIGHT)).isEqualTo("SCHED:7:2026-10-19");
    }

    @Test
    void nightKeyIsUserIdAndIsoNight() {
        assertThat(ExecutionKeys.night(42L, NIGHT)).isEqualTo("42:2026-10-19");
    }

    @Test
    void lumpKeyIsLumpPrefixUserIdAndClientUuid() {
        assertThat(ExecutionKeys.lump(42L, "0f8fad5b-d9cb-469f-a165-70867728950e"))
                .isEqualTo("LUMP:42:0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void keysFitTheirColumns() {
        // V6: source_key VARCHAR(120), night_key VARCHAR(40)
        assertThat(ExecutionKeys.scheduled(Long.MAX_VALUE, NIGHT)).hasSizeLessThanOrEqualTo(120);
        assertThat(ExecutionKeys.lump(Long.MAX_VALUE, UUID.randomUUID().toString())).hasSizeLessThanOrEqualTo(120);
        assertThat(ExecutionKeys.night(Long.MAX_VALUE, NIGHT)).hasSizeLessThanOrEqualTo(40);
    }
}
