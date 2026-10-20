package com.investingapp.backend.security.plaid;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 401 means Plaid stops retrying; 503 means it retries for 24 hours. Only a request that fails
 * verification gets the 401. Everything the filter cannot verify is a 503, and nothing reaches the
 * controller unless the body hash and signature passed.
 */
class PlaidWebhookVerificationFilterTest {

    private static final byte[] BODY = "{\"webhook_type\":\"TRANSACTIONS\"}".getBytes(StandardCharsets.UTF_8);

    private final PlaidWebhookVerifier verifier = mock(PlaidWebhookVerifier.class);
    private final PlaidWebhookVerificationFilter filter = new PlaidWebhookVerificationFilter(verifier);

    private static MockHttpServletRequest webhookRequest(String jwt) {
        return webhookRequest(jwt, BODY);
    }

    private static MockHttpServletRequest webhookRequest(String jwt, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setRequestURI("/api/plaid/webhook");
        request.setContentType("application/json");
        request.setContent(body);
        if (jwt != null) {
            request.addHeader(PlaidWebhookVerificationFilter.HEADER, jwt);
        }
        return request;
    }

    /** Runs the filter and asserts the chain was NOT continued; returns the status it wrote. */
    private int blockedStatus(String jwt) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(webhookRequest(jwt), response, chain);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter chain must not continue").isNull();
        return response.getStatus();
    }

    @Test
    void missingHeaderIs401() throws Exception {
        assertThat(blockedStatus(null)).isEqualTo(401);
        assertThat(blockedStatus("")).isEqualTo(401);
    }

    @Test
    void rejectedVerificationIs401() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("bad"))).thenThrow(new PlaidWebhookRejectedException("hash"));

        assertThat(blockedStatus("bad")).isEqualTo(401);
    }

    @Test
    void unavailableVerifierIs503() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("down"))).thenThrow(new PlaidWebhookUnavailableException("key fetch"));

        assertThat(blockedStatus("down")).isEqualTo(503);
    }

    @Test
    void unexpectedVerifierErrorIs503() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("boom"))).thenThrow(new IllegalStateException("boom"));

        assertThat(blockedStatus("boom")).isEqualTo(503);
    }

    @Test
    void bodyOverTheCapIs401WithoutCallingTheVerifier() throws Exception {
        byte[] oversized = new byte[PlaidWebhookVerificationFilter.MAX_BODY_BYTES + 1];
        MockHttpServletRequest request = webhookRequest("jwt", oversized);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter chain must not continue").isNull();
        verifyNoInteractions(verifier);
    }

    @Test
    void bodyExactlyAtTheCapIsAccepted() throws Exception {
        byte[] exact = new byte[PlaidWebhookVerificationFilter.MAX_BODY_BYTES];
        when(verifier.verifyAndReturnIat(eq(exact), eq("jwt"))).thenReturn(1_700_000_000L);
        MockHttpServletRequest request = webhookRequest("jwt", exact);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void controllerReceivesFullBodyAfterFilter() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("jwt"))).thenReturn(1_700_000_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(webhookRequest("jwt"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isInstanceOf(RereadableRequestWrapper.class);
        assertThat((byte[]) chain.getRequest().getAttribute(PlaidWebhookVerificationFilter.ATTR_RAW_BODY)).isEqualTo(BODY);
        assertThat(chain.getRequest().getAttribute(PlaidWebhookVerificationFilter.ATTR_JWT_IAT)).isEqualTo(1_700_000_000L);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(BODY);
    }
}
