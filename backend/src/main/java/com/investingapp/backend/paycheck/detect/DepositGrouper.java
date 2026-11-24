package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Spec 4.3 grouping; drops nothing; ppd_id never merges groups in Phase 1. */
public final class DepositGrouper {

    private static final Comparator<PaycheckEvent> OLDEST_FIRST = Comparator.comparing(PaycheckEvent::getPostedDate)
            .thenComparing(PaycheckEvent::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    public static List<DepositGroup> group(List<PaycheckEvent> events) {
        Map<String, Map<String, List<PaycheckEvent>>> byAccount = new TreeMap<>();
        for (PaycheckEvent e : events) {
            byAccount.computeIfAbsent(e.getPlaidAccountId(), k -> new TreeMap<>())
                    .computeIfAbsent(PayerTokenizer.token(e.getRawDescriptor(), e.getMerchantName()), k -> new ArrayList<>())
                    .add(e);
        }
        List<DepositGroup> groups = new ArrayList<>();
        byAccount.forEach((account, byToken) -> byToken.forEach((token, list) -> {
            list.sort(OLDEST_FIRST);
            TreeSet<String> ppdIds = new TreeSet<>();
            list.stream().map(PaycheckEvent::getPpdId).filter(p -> p != null && !p.isBlank()).forEach(ppdIds::add);
            groups.add(new DepositGroup(account, token, List.copyOf(list), Collections.unmodifiableSet(ppdIds)));
        }));
        return groups;
    }
}
