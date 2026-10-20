package com.investingapp.backend.security.plaid;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The verification filter consumes the body to hash it; the controller must still be able to read
 * it. Spring's ContentCachingRequestWrapper only caches what was already read and leaves a second
 * reader with an empty stream, so FRED buffers the bytes itself. The maxBytes cap is enforced by
 * counting bytes off the stream, not trusted from a Content-Length header, so a chunked request
 * (no such header at all) is still capped.
 */
class RereadableRequestWrapperTest {

    private static final byte[] BODY = "{\"webhook_type\":\"ITEM\"}".getBytes(StandardCharsets.UTF_8);

    /** Generous enough that the existing tests below never trip the cap. */
    private static final int GENEROUS_CAP = 1_000_000;

    private static RereadableRequestWrapper wrap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setContent(BODY);
        return new RereadableRequestWrapper(request, GENEROUS_CAP);
    }

    @Test
    void bodyIsReadableMoreThanOnce() throws Exception {
        RereadableRequestWrapper wrapped = wrap();

        assertThat(wrapped.body()).isEqualTo(BODY);
        assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(wrapped.getReader().readLine()).isEqualTo(new String(BODY, StandardCharsets.UTF_8));
    }

    @Test
    void streamReportsFinishedOnlyAfterTheLastByte() throws Exception {
        ServletInputStream stream = wrap().getInputStream();

        assertThat(stream.isFinished()).isFalse();
        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();
    }

    @Test
    void bodyOverMaxBytesThrowsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setContent(new byte[11]);

        assertThatThrownBy(() -> new RereadableRequestWrapper(request, 10))
                .isInstanceOf(PlaidWebhookRejectedException.class)
                .hasMessageContaining("exceeds 10 bytes");
    }

    @Test
    void bodyExactlyAtMaxBytesIsAccepted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setContent(new byte[10]);

        RereadableRequestWrapper wrapped = new RereadableRequestWrapper(request, 10);

        assertThat(wrapped.body()).hasSize(10);
    }
}
