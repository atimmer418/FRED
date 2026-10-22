package com.investingapp.backend.controller;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilter;
import com.investingapp.backend.service.PlaidWebhookInboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The controller never reads @RequestBody: it stores the bytes the filter verified. Any failure to
 * store is a 503 so Plaid retries; a 4xx would be permanent.
 */
class PlaidWebhookControllerTest {

    private static final byte[] BODY =
            "{\"webhook_type\":\"ITEM\",\"webhook_code\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8);

    private PlaidWebhookInboxService inboxService;
    private PlaidWebhookController controller;

    @BeforeEach
    void setUp() {
        inboxService = mock(PlaidWebhookInboxService.class);
        controller = new PlaidWebhookController();
        ReflectionTestUtils.setField(controller, "inboxService", inboxService);
    }

    private static MockHttpServletRequest verifiedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setAttribute(PlaidWebhookVerificationFilter.ATTR_RAW_BODY, BODY);
        request.setAttribute(PlaidWebhookVerificationFilter.ATTR_JWT_IAT, 1_700_000_000L);
        return request;
    }

    @Test
    void storedEventAnswers200WithNoBody() {
        when(inboxService.record(eq(BODY), eq(1_700_000_000L))).thenReturn(new PlaidWebhookEvent());

        var response = controller.receive(verifiedRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void mappingFailureReturns503() {
        when(inboxService.record(eq(BODY), eq(1_700_000_000L)))
                .thenThrow(new IllegalArgumentException("webhook body is missing webhook_code"));

        assertThat(controller.receive(verifiedRequest()).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void databaseFailureReturns503() {
        when(inboxService.record(eq(BODY), eq(1_700_000_000L)))
                .thenThrow(new RuntimeException("could not execute statement"));

        assertThat(controller.receive(verifiedRequest()).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void requestWithoutFilterAttributesIs503AndNeverStored() {
        MockHttpServletRequest unverified = new MockHttpServletRequest("POST", "/api/plaid/webhook");

        assertThat(controller.receive(unverified).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(inboxService);
    }
}
