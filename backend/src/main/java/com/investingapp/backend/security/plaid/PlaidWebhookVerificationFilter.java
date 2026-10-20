package com.investingapp.backend.security.plaid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs ahead of the Spring Security chain on POST /api/plaid/webhook (see PlaidWebhookFilterConfig).
 * Buffers the body, verifies the Plaid-Verification JWT against the raw bytes, then hands the
 * controller the bytes and the JWT iat as request attributes. 401 on a failed verification (Plaid
 * never retries a 4xx), 503 when FRED cannot verify (Plaid retries).
 */
public class PlaidWebhookVerificationFilter extends OncePerRequestFilter {

    public static final String HEADER = "Plaid-Verification";
    public static final String ATTR_RAW_BODY = "plaid.webhook.rawBody";
    public static final String ATTR_JWT_IAT = "plaid.webhook.iat";

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookVerificationFilter.class);

    private final PlaidWebhookVerifier verifier;

    public PlaidWebhookVerificationFilter(PlaidWebhookVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String jwt = request.getHeader(HEADER);
        if (jwt == null || jwt.isBlank()) {
            logger.warn("plaid.webhook.rejected reason=header_missing");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        RereadableRequestWrapper wrapped = new RereadableRequestWrapper(request);
        long iat;
        try {
            iat = verifier.verifyAndReturnIat(wrapped.body(), jwt);
        } catch (PlaidWebhookRejectedException e) {
            logger.warn("plaid.webhook.rejected reason=verification detail=\"{}\"", e.getMessage());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        } catch (PlaidWebhookUnavailableException e) {
            logger.warn("plaid.webhook.rejected reason=unavailable detail=\"{}\"", e.getMessage());
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        } catch (RuntimeException e) {
            logger.error("plaid.webhook.rejected reason=verifier_error", e);
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        wrapped.setAttribute(ATTR_RAW_BODY, wrapped.body());
        wrapped.setAttribute(ATTR_JWT_IAT, iat);
        filterChain.doFilter(wrapped, response);
    }

    private static void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentLength(0);
    }
}
