package com.investingapp.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Caps how large a request body may be.
 *
 * <p>Nothing limited the size of a JSON body before: the server would read whatever it was sent
 * into memory. Every body the app sends is a few kilobytes, except one, the identity-document
 * upload. So there are two limits: a small one for everything, and a larger one for that route.
 *
 * <p>A request that states its size is refused straight away when the size is over. One that does
 * not state it (or lies) is counted as it is read, and reading stops at the limit. Either way the
 * answer is 413.
 */
public class RequestBodySizeFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestBodySizeFilter.class);

    /** The one route that takes a file. */
    static final String UPLOAD_PATH = "/api/alpaca/upload-document";

    public static final String MESSAGE = "That is too large to send. Please try something smaller.";

    private final long maxBytes;
    private final long maxUploadBytes;
    private final UrlPathHelper paths = new UrlPathHelper();

    public RequestBodySizeFilter(long maxBytes, long maxUploadBytes) {
        this.maxBytes = maxBytes;
        this.maxUploadBytes = maxUploadBytes;
    }

    long limitFor(HttpServletRequest request) {
        return UPLOAD_PATH.equals(paths.getPathWithinApplication(request)) ? maxUploadBytes : maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        if (request.getContentLengthLong() > limit) {
            logger.warn("Request body refused: {} bytes stated, limit {} ({} {})",
                    request.getContentLengthLong(), limit, request.getMethod(), request.getRequestURI());
            writeTooLarge(response);
            return;
        }
        try {
            chain.doFilter(new CountingRequest(request, limit), response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!causedByBodyTooLarge(e) || response.isCommitted()) {
                throw e;
            }
            logger.warn("Request body refused while reading: over {} bytes ({} {})",
                    limit, request.getMethod(), request.getRequestURI());
            response.resetBuffer();
            writeTooLarge(response);
        }
    }

    public static boolean causedByBodyTooLarge(Throwable error) {
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof BodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + MESSAGE + "\",\"error\":\"" + MESSAGE + "\"}");
    }

    /** Hands out the real body, counting it, and stops once the limit is passed. */
    private static final class CountingRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        CountingRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CountingStream extends ServletInputStream {
        private final ServletInputStream source;
        private final long limit;
        private long read;

        CountingStream(ServletInputStream source, long limit) {
            this.source = source;
            this.limit = limit;
        }

        private void count(long bytes) throws IOException {
            if (bytes > 0) {
                read += bytes;
                if (read > limit) {
                    throw new BodyTooLargeException(limit);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = source.read(buffer, offset, length);
            count(count);
            return count;
        }

        @Override
        public boolean isFinished() {
            return source.isFinished();
        }

        @Override
        public boolean isReady() {
            return source.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            source.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
