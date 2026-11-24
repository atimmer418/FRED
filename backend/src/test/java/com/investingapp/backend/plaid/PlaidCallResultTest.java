package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaidCallResultTest {

    @Test
    void okOnlyWhenErrorIsNull() {
        PlaidCallResult<String> success = PlaidCallResult.success("body");
        assertThat(success.ok()).isTrue();
        assertThat(success.body()).isEqualTo("body");
        assertThat(success.error()).isNull();

        PlaidApiError error = PlaidApiError.transport();
        PlaidCallResult<String> failure = PlaidCallResult.failure(error);
        assertThat(failure.ok()).isFalse();
        assertThat(failure.body()).isNull();
        assertThat(failure.error()).isSameAs(error);
    }

    @Test
    void exactlyOneOfBodyAndError() {
        assertThatThrownBy(() -> new PlaidCallResult<>(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaidCallResult<>("body", PlaidApiError.transport()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
