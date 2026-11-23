package com.investingapp.backend.model.goals;

import java.math.BigDecimal;
import java.util.List;

/**
 * The first-year fund fields are nullable so configs stored before the fund existed still parse.
 * A fund is switched on with {@code firstYearFund}; each kid that holds one has a {@code firstYear}
 * account, a snapshotted target and a stored end month.
 */
public record KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids,
                         Boolean firstYearFund, Boolean giftDays) {

    public record Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts,
                      BigDecimal firstYearTarget, Integer firstYearDueYear, Integer firstYearDueMonth) {
    }

    public record Accounts(AccountState brokerage, AccountState plan529, AccountState firstYear) {
    }
}
