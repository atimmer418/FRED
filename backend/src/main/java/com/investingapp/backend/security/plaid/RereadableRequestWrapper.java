package com.investingapp.backend.security.plaid;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body once and serves a fresh stream on every read, so the verification
 * filter can hash the bytes and the controller can still read them. Spring's
 * ContentCachingRequestWrapper is not re-readable and would leave the controller an empty body,
 * a 400, and a permanently dropped webhook (Plaid never retries a 4xx).
 *
 * <p>The maxBytes cap is enforced against the stream itself, one byte past the limit, rather than
 * trusted from a Content-Length header: a chunked request carries no such header at all, so the
 * filter's Content-Length precheck alone would miss it.
 */
public class RereadableRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public RereadableRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        byte[] read = request.getInputStream().readNBytes(maxBytes + 1);
        if (read.length > maxBytes) {
            throw new PlaidWebhookRejectedException("body exceeds " + maxBytes + " bytes");
        }
        this.body = read;
    }

    /** The buffered bytes, shared with every caller: read them, never mutate them. */
    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("async reads are not supported");
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return source.read(b, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
