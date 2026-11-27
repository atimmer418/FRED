package com.investingapp.backend.security.ratelimit;

import com.investingapp.backend.security.ratelimit.RateLimiter.Rule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Per-address limits on the routes anyone can call without signing in.
 *
 * Runs before the token filter, so a refused request costs no database work at all.
 * Account recovery is not here: it has its own, stricter limits per account and per address
 * (AccountRecoveryService). Dev and admin tools are not here either: they are locked by a key.
 *
 * Related routes share one budget, so "start" and "finish" cannot be played off against each other.
 */
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final RateLimiter rateLimiter;
    private final ClientAddressResolver addresses;

    public PublicEndpointRateLimitFilter(RateLimiter rateLimiter, ClientAddressResolver addresses) {
        this.rateLimiter = rateLimiter;
        this.addresses = addresses;
    }

    /** The rule for this request, or null when the route is not one this filter limits. */
    static Rule ruleFor(String method, String path) {
        if (path == null || "OPTIONS".equalsIgnoreCase(method)) {
            return null;
        }
        if (path.startsWith("/api/passkey/register/")) {
            return Rule.SIGN_UP_PER_ADDRESS;
        }
        if (path.startsWith("/api/passkey/")) {
            return Rule.SIGN_IN_PER_ADDRESS;
        }
        if (path.equals("/api/auth/refresh")) {
            return Rule.REFRESH_PER_ADDRESS;
        }
        if (path.startsWith("/api/auth/recovery/")) {
            return null;
        }
        if (path.startsWith("/api/auth/")) {
            return Rule.LOGIN_PER_ADDRESS;
        }
        if (path.equals("/api/user/should-prompt-reauth")) {
            return Rule.REAUTH_CHECK_PER_ADDRESS;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The decoded path, the one Spring routes on. The raw request line would let a caller write
        // one letter as %xx: Spring would still find the controller and this filter would not.
        Rule rule = ruleFor(request.getMethod(), PATHS.getPathWithinApplication(request));
        if (rule != null && !rateLimiter.allow(rule, addresses.resolve(request))) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rule.window().toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"" + TooManyRequestsAdvice.MESSAGE + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
