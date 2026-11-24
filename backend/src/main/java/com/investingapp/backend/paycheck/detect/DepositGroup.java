package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** One (plaid_account_id, payer_token) group, oldest first (spec 4.3). */
public record DepositGroup(String plaidAccountId, String payerToken, List<PaycheckEvent> depositsAsc, Set<String> ppdIds) {

    public List<LocalDate> postedDatesAsc() {
        return depositsAsc.stream().map(PaycheckEvent::getPostedDate).toList();
    }

    public List<BigDecimal> lastSixAmounts() {
        int from = Math.max(0, depositsAsc.size() - DepositStats.TRAILING_COUNT);
        return depositsAsc.subList(from, depositsAsc.size()).stream().map(PaycheckEvent::getAmount).toList();
    }
}
