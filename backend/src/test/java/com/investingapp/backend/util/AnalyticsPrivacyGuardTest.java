package com.investingapp.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the guard forbids. The path it sits in is pinned separately, by
 * AnalyticsOutboxForbiddenPayloadTest, and the agreement with the client's copy of these rules by
 * AnalyticsPrivacyGuardParityTest.
 */
class AnalyticsPrivacyGuardTest {

    @Nested
    @DisplayName("the key deny-list")
    class Keys {

        @Test
        @DisplayName("refuses a key named for something that may never be sent")
        void refusesForbiddenKeys() {
            assertRule("email", "email");
            assertRule("user_email", "email");
            assertRule("userEmail", "email");
            assertRule("USER-EMAIL", "email");
            assertRule("phone_number", "phone");
            assertRule("ssn", "government_id");
            assertRule("ssn_last_four", "government_id");
            assertRule("social_security_number", "government_id");
            assertRule("date_of_birth", "date_of_birth");
            assertRule("first_name", "person_name");
            assertRule("institution_name", "institution");
            assertRule("bank_name", "institution");
            assertRule("street_address", "address");
            assertRule("account_number", "bank_account");
            assertRule("routing_number", "bank_account");
            assertRule("access_token", "credential");
            assertRule("plaid_access_token", "credential");
            assertRule("contribution_amount", "raw_money");
            assertRule("annual_income", "raw_money");
            assertRule("ip_address", "network_address");
            assertRule("error_message", "free_text");
            assertRule("rejection_reason_text", "free_text");
        }

        @Test
        @DisplayName("allows a key that is merely unfamiliar: this is a deny-list, not an allow-list")
        void allowsUnfamiliarKeys() {
            assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule("wiggle_count"));
            assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule("cadence"));
        }

        private void assertRule(String key, String expected) {
            AnalyticsPrivacyGuard.Rule rule = AnalyticsPrivacyGuard.forbiddenKeyRule(key);
            assertNotNull(rule, key + " must be refused");
            assertEquals(expected, rule.name(), "the reported rule for " + key);
        }
    }

    @Nested
    @DisplayName("the declared-taxonomy exemption (decision 12)")
    class Exemption {

        @Test
        @DisplayName("lets a declared key through although its name matches a forbidden pattern")
        void declaredKeysSurvive() {
            assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule("amount_band"),
                    "amount_band is the word 'amount' and is also how an amount is sent safely");
            assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule("monthly_income_band"));
            assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule("monthly_invest_band"));
        }

        @Test
        @DisplayName("strips an UNDECLARED key that matches the same pattern")
        void undeclaredKeysDoNot() {
            assertEquals("raw_money", AnalyticsPrivacyGuard.forbiddenKeyRule("amount").name());
            assertEquals("raw_money", AnalyticsPrivacyGuard.forbiddenKeyRule("monthly_income").name());
        }

        @Test
        @DisplayName("every declared and person property key is exempt")
        void everyDeclaredKeyIsExempt() {
            for (String key : ServerAnalyticsEvent.PROPERTY_KEYS) {
                assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule(key), key + " must not be stripped");
            }
            for (String key : ServerAnalyticsEvent.PERSON_PROPERTY_KEYS) {
                assertNull(AnalyticsPrivacyGuard.forbiddenKeyRule(key), key + " must not be stripped");
            }
        }

        @Test
        @DisplayName("exempts the key name only, never the value")
        void theExemptionDoesNotCoverValues() {
            assertEquals("email",
                    AnalyticsPrivacyGuard.forbiddenValueRule("someone@example.com").name(),
                    "a declared key carrying an email is still a leak");
        }
    }

    @Nested
    @DisplayName("the value-shape checks")
    class Values {

        @Test
        @DisplayName("refuse an email, a phone number, an SSN, a long digit run and a JWT")
        void refuseForbiddenShapes() {
            assertEquals("email", rule("someone@example.com"));
            assertEquals("ssn_formatted", rule("123-45-6789"));
            assertEquals("phone_formatted", rule("(555) 123-4567"));
            assertEquals("phone_e164", rule("+15551234567"));
            assertEquals("long_digit_run", rule("123456789"));
            assertEquals("long_digit_run", rule("transfer 987654321 settled"));
            assertEquals("jwt", rule("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"));
        }

        @Test
        @DisplayName("check a number as its decimal text, so a nine-digit number is caught too")
        void numbersAreCheckedAsText() {
            assertEquals("long_digit_run", AnalyticsPrivacyGuard.forbiddenValueRule(123456789L).name());
            assertNull(AnalyticsPrivacyGuard.forbiddenValueRule(14));
            assertNull(AnalyticsPrivacyGuard.forbiddenValueRule(3));
        }

        @Test
        @DisplayName("leave every real server value alone")
        void realValuesSurvive() {
            for (Object value : new Object[]{
                    "1k_2499", "10k_49k", "250k_999k", "same_day", "31_90", "over_90", "unknown",
                    "3_plus", "approved", "needs_more_info", "user_request", "bank_change",
                    "weekly", "guardrails", "core", "plus", null, true, false, 14, 1, 2}) {
                assertNull(AnalyticsPrivacyGuard.forbiddenValueRule(value),
                        "a real value was refused: " + value);
            }
        }

        @Test
        @DisplayName("never judge a boolean or a null, which have no shape")
        void booleansAndNullsAreSkipped() {
            assertNull(AnalyticsPrivacyGuard.forbiddenValueRule(null));
            assertNull(AnalyticsPrivacyGuard.forbiddenValueRule(Boolean.TRUE));
        }

        private String rule(String value) {
            AnalyticsPrivacyGuard.Rule found = AnalyticsPrivacyGuard.forbiddenValueRule(value);
            assertNotNull(found, "must be refused: " + value);
            return found.name();
        }
    }

    @Test
    @DisplayName("strips the offending property, keeps the event, and does not mutate the caller's map")
    void scrubKeepsTheRest() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("outcome", "rejected");
        properties.put("days_to_decision_band", "4_7");
        properties.put("rejection_reason_text", "SSN did not match");
        properties.put("ref", "123-45-6789");

        Map<String, Object> scrubbed = AnalyticsPrivacyGuard.scrub("kyc_decided", "properties", properties);

        assertEquals(Map.of("outcome", "rejected", "days_to_decision_band", "4_7"), scrubbed);
        assertEquals(4, properties.size(), "the caller's map must not be mutated");
    }

    @Test
    @DisplayName("tolerates a null and an empty map rather than throwing into a user flow")
    void toleratesNothing() {
        assertEquals(Map.of(), AnalyticsPrivacyGuard.scrub("kyc_decided", "properties", null));
        assertEquals(Map.of(), AnalyticsPrivacyGuard.scrub("kyc_decided", "properties", Map.of()));
    }
}
