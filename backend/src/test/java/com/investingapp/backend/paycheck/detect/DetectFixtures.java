package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.PaycheckCadenceSource;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

final class DetectFixtures {

    private static final AtomicLong IDS = new AtomicLong(1);

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    static List<LocalDate> dates(String... isoDates) {
        return Arrays.stream(isoDates).map(LocalDate::parse).toList();
    }

    static List<BigDecimal> amounts(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    static PaycheckEvent deposit(String account, String isoDate, String amount, String descriptor) {
        PaycheckEvent e = new PaycheckEvent();
        e.setId(IDS.getAndIncrement());
        e.setPlaidAccountId(account);
        e.setAmount(new BigDecimal(amount));
        e.setRawAmount(new BigDecimal(amount).negate());
        e.setPostedDate(d(isoDate));
        e.setRawDescriptor(descriptor);
        return e;
    }

    static DepositGroup biweekly(int count, String amount, String descriptor) {
        List<PaycheckEvent> deposits = new ArrayList<>();
        for (int i = 0; i < count; i++) { deposits.add(deposit("acc-1", d("2026-01-09").plusDays(14L * i).toString(), amount, descriptor)); }
        return new DepositGroup("acc-1", PayerTokenizer.token(descriptor, null), List.copyOf(deposits), Set.of());
    }

    static CadenceResult cadence(InvestmentFrequency frequency, int outsideBand) {
        return new CadenceResult(frequency, PaycheckCadenceSource.OBSERVED, 5, d("2026-03-20"), null, null, 5, outsideBand == 0, outsideBand);
    }

    static PaycheckStream stream(long id, String token, PaycheckStreamState state, InvestmentFrequency cadence, int score, String median, String cv) {
        PaycheckStream s = new PaycheckStream();
        s.setId(id);
        s.setPayerToken(token);
        s.setState(state);
        s.setCadence(cadence);
        s.setScore((short) score);
        s.setTrailingMedian(new BigDecimal(median));
        s.setTrailingCv(new BigDecimal(cv));
        return s;
    }
}
