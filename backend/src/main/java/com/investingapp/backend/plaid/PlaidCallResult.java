package com.investingapp.backend.plaid;

/**
 * What {@link PlaidGateway#call} returns: the response body on a 2xx with a body, otherwise the
 * classified error. Exactly one of the two is non-null.
 */
public record PlaidCallResult<T>(T body, PlaidApiError error) {

    public PlaidCallResult {
        if ((body == null) == (error == null)) {
            throw new IllegalArgumentException("a Plaid call result carries exactly one of body and error");
        }
    }

    public static <T> PlaidCallResult<T> success(T body) {
        return new PlaidCallResult<>(body, null);
    }

    public static <T> PlaidCallResult<T> failure(PlaidApiError error) {
        return new PlaidCallResult<>(null, error);
    }

    public boolean ok() {
        return error == null;
    }
}
