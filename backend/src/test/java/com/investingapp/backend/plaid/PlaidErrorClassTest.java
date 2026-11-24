package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2.1 classes keyed on the exact error_code; the HTTP status matters only when no code parses. */
class PlaidErrorClassTest {

    @Test
    void classifiesOnExactCodeNeverHttpStatus() {
        for (PlaidErrorClass expected : PlaidErrorClass.values()) {
            for (String code : expected.codes()) {
                for (int status : new int[] {200, 400, 429, 500, 503}) {
                    assertThat(PlaidErrorClass.classify(code, status)).as(code + " at HTTP " + status).isEqualTo(expected);
                }
            }
        }
        // The spec 2.1 rows spelled out once, so a typo in the enum's own sets cannot hide behind the loop above.
        assertThat(PlaidErrorClass.classify("INSTITUTION_DOWN", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_NOT_RESPONDING", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_NOT_AVAILABLE", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INTERNAL_SERVER_ERROR", 500)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("PLANNED_MAINTENANCE", 503)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("PRODUCT_NOT_READY", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("RATE_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
        assertThat(PlaidErrorClass.classify("TRANSACTIONS_SYNC_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
        assertThat(PlaidErrorClass.classify("ITEM_GET_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
        assertThat(PlaidErrorClass.classify("ADDITIONAL_CONSENT_REQUIRED", 400)).isEqualTo(PlaidErrorClass.CONSENT);
        assertThat(PlaidErrorClass.classify("ACCESS_NOT_GRANTED", 400)).isEqualTo(PlaidErrorClass.CONSENT);
        assertThat(PlaidErrorClass.classify("ITEM_LOGIN_REQUIRED", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("ITEM_NOT_FOUND", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("USER_PERMISSION_REVOKED", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("NO_ACCOUNTS", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("PRODUCTS_NOT_SUPPORTED", 400)).isEqualTo(PlaidErrorClass.NOT_SUPPORTED);
        assertThat(PlaidErrorClass.classify("ITEM_NOT_SUPPORTED", 400)).isEqualTo(PlaidErrorClass.NOT_SUPPORTED);
        assertThat(PlaidErrorClass.classify("INVALID_PRODUCT", 400)).isEqualTo(PlaidErrorClass.NOT_SUPPORTED);
        assertThat(PlaidErrorClass.classify("TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED", 400)).isEqualTo(PlaidErrorClass.SYNC_LIMIT);
        assertThat(PlaidErrorClass.classify("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION", 400)).isEqualTo(PlaidErrorClass.MUTATION);
        // A parsed code decides even at 5xx: a known one keeps its class, an unknown one is PERMANENT.
        assertThat(PlaidErrorClass.classify("ITEM_LOGIN_REQUIRED", 500)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("A_CODE_PLAID_ADDS_LATER", 500)).isEqualTo(PlaidErrorClass.PERMANENT);
        // Exact means exact: Plaid sends upper snake case, so nothing else matches.
        assertThat(PlaidErrorClass.classify("item_login_required", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
    }

    @Test
    void unparsable5xxIsTransient() {
        assertThat(PlaidErrorClass.classify(null, 500)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify(null, 502)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify(null, 503)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("", 503)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("   ", 504)).isEqualTo(PlaidErrorClass.TRANSIENT);
        // Below 500 a missing code is PERMANENT, 429 included: the contract keys the fallback on 5xx only.
        assertThat(PlaidErrorClass.classify(null, 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify(null, 429)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify(null, 200)).isEqualTo(PlaidErrorClass.PERMANENT);
    }

    @Test
    void unknownInstitutionCodeIsPermanent() {
        assertThat(PlaidErrorClass.classify("INSTITUTION_NO_LONGER_SUPPORTED", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_NOT_FOUND", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_REGISTRATION_REQUIRED", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        // The INSTITUTION_ prefix decides nothing: these two are listed.
        assertThat(PlaidErrorClass.classify("INSTITUTION_DOWN", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_RATE_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
    }

    @Test
    void everyCodeBelongsToExactlyOneClass() {
        Set<String> seen = new HashSet<>();
        for (PlaidErrorClass errorClass : PlaidErrorClass.values()) {
            for (String code : errorClass.codes()) {
                assertThat(seen.add(code)).as(code + " listed twice").isTrue();
            }
        }
        assertThat(PlaidErrorClass.PERMANENT.codes()).isEmpty();
        assertThat(seen).hasSize(6 + 17 + 2 + 4 + 3 + 1 + 1);
    }
}
