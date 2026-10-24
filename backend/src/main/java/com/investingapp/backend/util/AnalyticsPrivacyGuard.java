package com.investingapp.backend.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server's half of the Phase 7 backstop, and the half that matters most.
 *
 * The client's payloads are a closed, typed vocabulary: to send an email from the phone you would
 * have to widen a type first. The server has no such protection. Every event here is a
 * hand-written {@code Map<String, Object>} assembled next to the code that holds the real thing:
 * {@code PlaidService} has the access token and the institution in scope, {@code KycSubmissionService}
 * has the Social Security number, {@code AccountStatusService} has the broker's account id and the
 * rejection text. One {@code put} in the wrong place is all it takes, and nothing about the types
 * would complain.
 *
 * So the writer refuses the property rather than trusting the caller. Two independent checks:
 *
 * <ul>
 *   <li><b>The key deny-list</b> catches a property named for something forbidden, whatever it
 *       holds.</li>
 *   <li><b>The value-shape checks</b> catch a property with an innocent name whose value looks
 *       like an email, a phone number, a Social Security number, a long digit run or a JWT.</li>
 * </ul>
 *
 * A violation <b>strips the one property and keeps the event</b>, then logs at error level. The
 * event is the record of money moving, which the outbox is the source of truth for; dropping it
 * to protect a property we already removed would trade a real accounting row for nothing. The log
 * line names the event, the key and the rule, and never the value: writing it to a log is the
 * leak it was stripped to prevent.
 *
 * <p>The patterns are duplicated from the client's {@code analytics-privacy-guard.ts} and held
 * equal by {@code AnalyticsPrivacyGuardParityTest}, which reads that file. A key forbidden on one
 * side and allowed on the other is the worse half of no guard at all.
 */
public final class AnalyticsPrivacyGuard {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsPrivacyGuard.class);

    private AnalyticsPrivacyGuard() {
    }

    /** One rule, named so a violation says which rule fired rather than printing the value. */
    public record Rule(String name, Pattern pattern) {
    }

    /**
     * Property names that may never be written, matched against the key with case and separators
     * removed, so {@code userEmail}, {@code user_email} and {@code USER-EMAIL} are one key here.
     *
     * Order matters only for the report: {@code ip_address} contains "address", so the narrower
     * network rule is judged first to keep the logged reason true.
     */
    public static final List<Rule> FORBIDDEN_KEY_PATTERNS = List.of(
            new Rule("email", Pattern.compile("email")),
            new Rule("phone", Pattern.compile("phone|mobilenumber|telephone")),
            new Rule("government_id", Pattern.compile("ssn|socialsecurity|taxid|passport|driverslicense")),
            new Rule("date_of_birth", Pattern.compile("^dob$|dateofbirth|birthdate|birthday")),
            new Rule("person_name", Pattern.compile(
                    "^name$|firstname|lastname|middlename|fullname|surname|maidenname|legalname|holdername")),
            new Rule("institution", Pattern.compile("institutionname|bankname|employername")),
            new Rule("network_address", Pattern.compile("^ip$|ipaddress")),
            new Rule("address", Pattern.compile("address|street|zipcode|postalcode|^zip$|^postal$")),
            new Rule("bank_account", Pattern.compile("accountnumber|routing|iban|swift|cardnumber|^cvv$")),
            new Rule("credential", Pattern.compile(
                    "password|passcode|secret|apikey|^key$|authorization|bearer|^jwt$|credential|^cookie$|sessionid|accesstoken|refreshtoken|^token$")),
            new Rule("raw_money", Pattern.compile("amount|balance|income|salary|networth|revenue|^price$|^cost$")),
            new Rule("location", Pattern.compile("latitude|longitude|^lat$|^lng$|^geo$|coordinates")),
            new Rule("free_text", Pattern.compile(
                    "^note$|^notes$|comment|message|description|feedback|searchterm|^query$|rawtext|freetext|text$")));

    /**
     * Value shapes that may never be written, whatever the property is called.
     *
     * {@code long_digit_run} is the broad one and the reason the others can be precise: an
     * unformatted Social Security number, a bare phone number, an account number and a broker id
     * are all long runs of digits. The narrow rules sit on top so the log says what it looked like.
     */
    public static final List<Rule> FORBIDDEN_VALUE_PATTERNS = List.of(
            new Rule("email", Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")),
            new Rule("ssn_formatted", Pattern.compile("[0-9]{3}-[0-9]{2}-[0-9]{4}")),
            new Rule("phone_formatted", Pattern.compile("[(]?[0-9]{3}[)]?[ .-][0-9]{3}[ .-][0-9]{4}")),
            new Rule("phone_e164", Pattern.compile("[+][0-9]{10,15}")),
            new Rule("long_digit_run", Pattern.compile("[0-9]{8,}")),
            new Rule("jwt", Pattern.compile("eyJ[a-zA-Z0-9_=-]{8,}\\.[a-zA-Z0-9_=-]{8,}")));

    /**
     * The keys the taxonomy declares, exempt from the key deny-list. This is the discovery's
     * decision 12.
     *
     * It is what makes the deny-list usable at all: {@code amount_band} is the word "amount", and
     * it is also exactly how an amount is sent safely. The exemption covers the NAME only, so a
     * declared property whose value turns out to be an email is still stripped.
     */
    private static final Set<String> EXEMPT_KEYS = buildExemptKeys();

    private static Set<String> buildExemptKeys() {
        Set<String> keys = new java.util.HashSet<>();
        ServerAnalyticsEvent.PROPERTY_KEYS.forEach(key -> keys.add(normaliseKey(key)));
        ServerAnalyticsEvent.PERSON_PROPERTY_KEYS.forEach(key -> keys.add(normaliseKey(key)));
        return Set.copyOf(keys);
    }

    /** Lowercased, with every separator removed: the form the key deny-list is written against. */
    public static String normaliseKey(String key) {
        return key == null ? "" : key.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public static boolean isDeclaredTaxonomyKey(String key) {
        return EXEMPT_KEYS.contains(normaliseKey(key));
    }

    /** The rule a key breaks, or null when the key is allowed. */
    public static Rule forbiddenKeyRule(String key) {
        if (key == null || isDeclaredTaxonomyKey(key)) {
            return null;
        }
        String normalised = normaliseKey(key);
        return FORBIDDEN_KEY_PATTERNS.stream()
                .filter(rule -> rule.pattern().matcher(normalised).find())
                .findFirst()
                .orElse(null);
    }

    /**
     * The rule a value breaks, or null when the value is allowed.
     *
     * Booleans and nulls are never checked: there is no shape to a boolean. A number is checked as
     * its decimal text, so a nine-digit number is caught like a nine-character string, which is
     * how a Social Security number arrives when someone forgot to band it.
     */
    public static Rule forbiddenValueRule(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        String text = String.valueOf(value);
        return FORBIDDEN_VALUE_PATTERNS.stream()
                .filter(rule -> rule.pattern().matcher(text).find())
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the properties with every forbidden entry removed, logging each one.
     *
     * @param eventName   the event being written, for the log line
     * @param payloadKind "properties" or "person properties", for the log line
     * @param properties  the caller's map; never mutated
     */
    public static Map<String, Object> scrub(String eventName, String payloadKind,
                                            Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return properties == null ? Map.of() : properties;
        }

        Map<String, Object> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();

            Rule keyRule = forbiddenKeyRule(key);
            if (keyRule != null) {
                report(eventName, payloadKind, key, "key", keyRule.name());
                continue;
            }

            Rule valueRule = forbiddenValueRule(entry.getValue());
            if (valueRule != null) {
                report(eventName, payloadKind, key, "value", valueRule.name());
                continue;
            }

            kept.put(key, entry.getValue());
        }
        return kept;
    }

    private static void report(String eventName, String payloadKind, String key, String kind,
                               String rule) {
        // error, not warn: this is a bug in the emitting code and it is the one kind of analytics
        // problem that has to be fixed before the next deploy. The value is deliberately absent.
        logger.error("alert.analytics.forbidden_property event={} payload={} key={} matched={}_{} "
                        + "action=stripped",
                eventName, payloadKind, key, kind, rule);
    }
}
