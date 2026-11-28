package com.investingapp.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySizeFilterTest {

    private static final long LIMIT = 1000;
    private static final long UPLOAD_LIMIT = 5000;

    private final RequestBodySizeFilter filter = new RequestBodySizeFilter(LIMIT, UPLOAD_LIMIT);

    private static MockHttpServletRequest post(String path, int bytes, boolean statesItsSize) {
        byte[] body = new byte[bytes];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public int getContentLength() {
                return statesItsSize ? bytes : -1;
            }

            @Override
            public long getContentLengthLong() {
                return statesItsSize ? bytes : -1;
            }
        };
        request.setContent(body);
        return request;
    }

    /** A chain that reads the whole body, the way the JSON reader would. */
    private static FilterChain readingChain(AtomicInteger bytesRead) {
        return (request, response) -> {
            var in = request.getInputStream();
            byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer, 0, buffer.length)) >= 0) {
                bytesRead.addAndGet(n);
            }
        };
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, AtomicInteger bytesRead)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, readingChain(bytesRead));
        return response;
    }

    @Test
    @DisplayName("a body that says it is over the limit is refused before a byte is read")
    void statedSizeOverTheLimit() throws Exception {
        AtomicInteger read = new AtomicInteger();
        MockHttpServletResponse response = run(post("/api/user/profile", 1001, true), read);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"message\"");
        assertThat(read.get()).isZero();
    }

    @Test
    @DisplayName("a body that does not say its size is cut off at the limit")
    void unstatedSizeOverTheLimit() throws Exception {
        AtomicInteger read = new AtomicInteger();
        MockHttpServletResponse response = run(post("/api/user/profile", 50_000, false), read);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(read.get()).as("reading stopped near the limit, not at the end").isLessThan(2000);
    }

    @Test
    @DisplayName("a body at the limit goes through untouched")
    void atTheLimit() throws Exception {
        AtomicInteger read = new AtomicInteger();
        MockHttpServletResponse response = run(post("/api/user/profile", 1000, false), read);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(read.get()).isEqualTo(1000);
    }

    @Test
    @DisplayName("only the document upload gets the larger limit")
    void uploadHasItsOwnLimit() throws Exception {
        assertThat(run(post("/api/alpaca/upload-document", 5000, true), new AtomicInteger()).getStatus()).isEqualTo(200);
        assertThat(run(post("/api/alpaca/upload-document", 5001, true), new AtomicInteger()).getStatus()).isEqualTo(413);
        assertThat(run(post("/api/alpaca/upload-document", 6000, false), new AtomicInteger()).getStatus()).isEqualTo(413);
        // A path that only looks like it does not get the larger limit.
        assertThat(run(post("/api/alpaca/upload-document/x", 1500, true), new AtomicInteger()).getStatus()).isEqualTo(413);
        assertThat(run(post("/api/alpaca/%75pload-document", 1500, true), new AtomicInteger()).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a request with no body is left alone")
    void noBody() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/chat/stream");
        assertThat(run(get, new AtomicInteger()).getStatus()).isEqualTo(200);
    }
}
