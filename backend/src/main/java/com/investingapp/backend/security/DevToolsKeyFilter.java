package com.investingapp.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires a shared-secret header on every dev-tools request.
 *
 * <p>The dev endpoints only exist in the local/dev/test/ci profiles (see
 * {@link com.investingapp.backend.config.DevToolsConfig}), but api-dev.fredvested.com is reachable
 * from the public internet, so "dev only" alone would still let anyone mint a login for any email.
 *
 * <p>A missing or wrong key returns 404 rather than 401 so the endpoints do not advertise that they
 * exist.
 */
public class DevToolsKeyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(DevToolsKeyFilter.class);

    private final DevToolsKeyValidator keyValidator;

    public DevToolsKeyFilter(DevToolsKeyValidator keyValidator) {
        this.keyValidator = keyValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // A CORS preflight never carries custom headers, so checking the key here would 404 the
        // OPTIONS and the browser would never send the real request. Let it through to the CORS
        // filter; the actual request that follows is still key-checked.
        if (CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!keyValidator.matches(request.getHeader(DevToolsKeyValidator.HEADER))) {
            logger.warn("Dev-tools request rejected (missing or wrong {}): {} {}",
                    DevToolsKeyValidator.HEADER, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
