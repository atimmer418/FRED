package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A Plaid error body reduced to codes and ids; free text is never kept. */
class PlaidApiErrorTest {

    private static final String LOGIN_BODY = "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"ITEM_LOGIN_REQUIRED\","
            + "\"error_code_reason\":\"OAUTH_INVALID_TOKEN\","
            + "\"error_message\":\"the login details of this item have changed at Bank of Sentinel\","
            + "\"display_message\":null,\"request_id\":\"req-123\"}";

    @Test
    void parsesEveryFieldFromPlaidBody() {
        PlaidApiError error = PlaidApiError.fromBody(400, LOGIN_BODY);

        assertThat(error).isEqualTo(new PlaidApiError("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", "OAUTH_INVALID_TOKEN",
                "req-123", 400, PlaidErrorClass.ITEM_BROKEN));
    }

    @Test
    void keepsNoPlaidFreeText() {
        assertThat(PlaidApiError.fromBody(400, LOGIN_BODY).toString())
                .doesNotContain("login details")
                .doesNotContain("Sentinel");
    }

    @Test
    void unparsableBodyKeepsTheStatusForClassification() {
        assertThat(PlaidApiError.fromBody(503, "<html>Service Unavailable</html>"))
                .isEqualTo(new PlaidApiError(null, null, null, null, 503, PlaidErrorClass.TRANSIENT));
        assertThat(PlaidApiError.fromBody(400, "not json"))
                .isEqualTo(new PlaidApiError(null, null, null, null, 400, PlaidErrorClass.PERMANENT));
        assertThat(PlaidApiError.fromBody(502, null).errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "").errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "{\"error_code\":null}").errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "[]").errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "{\"error_code\":42}").errorCode()).isNull();
    }

    @Test
    void transportErrorIsTransientWithNoCode() {
        assertThat(PlaidApiError.transport()).isEqualTo(new PlaidApiError(
                PlaidApiError.TRANSPORT_ERROR_TYPE, null, null, null, 0, PlaidErrorClass.TRANSIENT));
        assertThat(PlaidApiError.TRANSPORT_ERROR_TYPE).isEqualTo("TRANSPORT");
    }
}
