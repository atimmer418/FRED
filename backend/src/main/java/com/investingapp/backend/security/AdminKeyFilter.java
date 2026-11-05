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
 * Guards /api/admin/** in every profile (spec 12.2). Preflight passes (CORS then refuses admin
 * headers); wrong key 404, blank actor 400; then the trimmed actor becomes ACTOR_ATTRIBUTE, which
 * a client cannot set, so a verb reached without this filter fails closed.
 */
public class AdminKeyFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "admin.actor";

    private static final Logger logger = LoggerFactory.getLogger(AdminKeyFilter.class);

    private final AdminKeyValidator keyValidator;

    public AdminKeyFilter(AdminKeyValidator keyValidator) {
        this.keyValidator = keyValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!keyValidator.matches(request.getHeader(AdminKeyValidator.KEY_HEADER))) {
            logger.warn("Admin request rejected (missing or wrong {}): {} {}",
                    AdminKeyValidator.KEY_HEADER, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            return;
        }
        String actor = request.getHeader(AdminKeyValidator.ACTOR_HEADER);
        if (actor == null || actor.isBlank()) {
            logger.warn("Admin request rejected (missing {}): {} {}",
                    AdminKeyValidator.ACTOR_HEADER, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentLength(0);
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, actor.trim());
        filterChain.doFilter(request, response);
    }
}
