package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.util.ColumnText;

import java.util.Locale;

/** Spec 4.3 payer token (64 characters) and display name (120). */
public final class PayerTokenizer {

    public static final String UNKNOWN = "UNKNOWN";

    public static String token(String originalDescription, String name) {
        String source = isBlank(originalDescription) ? name : originalDescription;
        if (isBlank(source)) { return UNKNOWN; }
        String text = DescriptorSignals.normalize(source).replaceAll("\\d{4,}", " ").replaceAll("\\s+", " ").trim();
        String padded = " " + text + " ";
        for (String word : DescriptorSignals.PAYER_STRIP_WORDS) {
            while (padded.contains(" " + word + " ")) { padded = padded.replace(" " + word + " ", " "); }
        }
        String words = padded.isBlank() ? text : padded.trim();
        if (words.isEmpty()) { return UNKNOWN; }
        String[] parts = words.split(" ");
        return ColumnText.fit(parts.length == 1 ? parts[0] : parts[0] + " " + parts[1], 64);
    }

    public static String displayName(String counterpartyName, String counterpartyType, String merchantName, String token) {
        String chosen = !isBlank(counterpartyName) && "income_source".equals(counterpartyType) ? counterpartyName.trim()
                : !isBlank(merchantName) ? merchantName.trim() : titleCase(token);
        return ColumnText.fit(chosen, 120);
    }

    static String titleCase(String token) {
        if (isBlank(token)) { return "Unknown"; }
        StringBuilder out = new StringBuilder();
        for (String word : token.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
