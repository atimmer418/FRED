package com.investingapp.backend.model.goals;

import java.math.BigDecimal;
import java.util.List;

public record KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids) {

    public record Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts) {
    }

    public record Accounts(AccountState brokerage, AccountState plan529) {
    }
}
