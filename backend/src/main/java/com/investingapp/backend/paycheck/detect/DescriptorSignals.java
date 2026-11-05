package com.investingapp.backend.paycheck.detect;

import java.util.List;
import java.util.Locale;

/** Spec 4.2 (6), 4.3, 4.5 token lists, matched on whole words; UNEMPLOY and DISAB are stems. */
public final class DescriptorSignals {

    public static final List<String> BENEFITS_TOKENS = List.of("UI BENEFIT", "UNEMPLOY", "EDD", "TREAS 310", "SSA",
            "VA BENEFIT", "DISAB", "PFL", "WORKERS COMP", "CHILD SUPPORT", "IRS");
    public static final List<String> NON_PAYROLL_CHANNEL_TOKENS = List.of("VENMO", "ZELLE", "PAYPAL", "CASH APP", "CASHAPP",
            "MOBILE DEPOSIT", "ATM", "INSTANT PAY", "CASHOUT");
    public static final List<String> PAYROLL_TOKENS = List.of("ADP", "GUSTO", "PAYCHEX", "WORKDAY", "DFAS", "PAYROLL",
            "DIR DEP", "DIRECT DEP", "SALARY", "PAYRL", "PAYCHK");
    public static final List<String> PAYER_STRIP_WORDS = List.of("DIRECT DEPOSIT", "DIRECT DEP", "DIR DEP", "PAYROLL",
            "DEPOSIT", "DEP", "DES", "ACH", "CREDIT", "TRANSFER", "XFER", "PPD", "ID", "CO", "INC", "LLC");
    private static final List<String> STEMS = List.of("UNEMPLOY", "DISAB");

    public static boolean hasBenefitsToken(String descriptor) {
        return containsAny(descriptor, BENEFITS_TOKENS);
    }

    public static boolean hasNonPayrollChannelToken(String descriptor) {
        return containsAny(descriptor, NON_PAYROLL_CHANNEL_TOKENS);
    }

    public static boolean hasPayrollToken(String descriptor) {
        return containsAny(descriptor, PAYROLL_TOKENS);
    }

    static String normalize(String descriptor) {
        return descriptor == null ? "" : descriptor.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    static boolean containsToken(String descriptor, String token) {
        return (" " + normalize(descriptor) + " ").contains(" " + token + (STEMS.contains(token) ? "" : " "));
    }

    private static boolean containsAny(String descriptor, List<String> tokens) {
        return tokens.stream().anyMatch(token -> containsToken(descriptor, token));
    }
}
