package com.investingapp.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.model.InvestmentSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentScheduleResponseTest {

    @Test
    @DisplayName("the schedule answer never carries the broker's bank-link id")
    void brokerBankLinkIdStaysOnTheServer() throws Exception {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("100.00"));
        schedule.setMonthlyAmount(new BigDecimal("200.00"));
        schedule.setStartDate(LocalDate.of(2031, 3, 3));
        schedule.setNextInvestmentDate(LocalDate.of(2031, 3, 3));
        // The Alpaca ACH relationship id (relationship_id on a Broker API transfer).
        schedule.setAchRequestId("LEAKMARK-ach-relationship");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String written = mapper.writeValueAsString(new InvestmentScheduleResponse(schedule));
        JsonNode body = mapper.readTree(written);

        assertThat(written).doesNotContain("LEAKMARK");
        assertThat(body.has("achRequestId")).isFalse();
        // What the app does use is still there.
        assertThat(body.path("investmentAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(body.has("nextInvestmentDate")).isTrue();
        assertThat(body.has("frequency")).isTrue();
    }
}
