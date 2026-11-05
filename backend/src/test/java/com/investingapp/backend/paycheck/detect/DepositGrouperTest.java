package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.investingapp.backend.paycheck.detect.DetectFixtures.biweekly;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.dates;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.deposit;
import static org.assertj.core.api.Assertions.assertThat;

class DepositGrouperTest {

    @Test
    void groupsByAccountAndPayerTokenOldestFirst() {
        PaycheckEvent first = deposit("acc-1", "2026-01-02", "2418.00", "ACME CORP PAYROLL 2418 PPD");
        PaycheckEvent second = deposit("acc-1", "2026-01-16", "2418.00", "ACME CORP DIR DEP");
        first.setPpdId("1234567890");
        second.setPpdId("1234567890");
        List<DepositGroup> groups = DepositGrouper.group(List.of(second, deposit("acc-2", "2026-01-09", "500.00", "ACME CORP PAYROLL"),
                first, deposit("acc-1", "2026-01-05", "300.00", "GUSTO PAYROLL")));

        assertThat(groups).extracting(g -> g.plaidAccountId() + "/" + g.payerToken()).containsExactly("acc-1/ACME CORP", "acc-1/GUSTO", "acc-2/ACME CORP");
        assertThat(groups.get(0).postedDatesAsc()).isEqualTo(dates("2026-01-02", "2026-01-16"));
        assertThat(groups.get(0).ppdIds()).containsExactly("1234567890");
        assertThat(biweekly(8, "1000.00", "ACME CORP PAYROLL").lastSixAmounts()).hasSize(6);
    }
}
