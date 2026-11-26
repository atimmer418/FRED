// src/main/java/com/investingapp/backend/security/jwt/AuthTokenFilter.java
package com.investingapp.backend.security.jwt;

import com.investingapp.backend.security.services.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter; // Use OncePerRequestFilter

import java.io.IOException;

// AuthTokenFilter - processes JWT authentication for each request
public class AuthTokenFilter extends OncePerRequestFilter { // Extend OncePerRequestFilter
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private com.investingapp.backend.repository.UserSessionRepository userSessionRepository;

    @Autowired
    private com.investingapp.backend.security.TokenCutoffRegistry tokenCutoffs;

    private static final Logger logger = LoggerFactory.getLogger(AuthTokenFilter.class);

    /**
     * The account recovery steps. They never use the signed-in user, and a device going through
     * recovery may still be sending a token that recovery itself just ended. That dead token must
     * not be able to block the way back in, so these paths skip this filter altogether.
     */
    private static final java.util.Set<String> RECOVERY_PATHS = java.util.Set.of(
            "/api/auth/recovery/initiate",
            "/api/auth/recovery/verify",
            "/api/passkey/register/recovery/start",
            "/api/passkey/register/finish");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (uri != null && contextPath != null && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length()) : uri;
        return path != null && RECOVERY_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        logger.debug("AuthTokenFilter: Processing request to {}", request.getRequestURI());

        // If already authenticated from a previous pass of this filter in the same
        // request, skip
        Authentication existingAuthentication = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuthentication != null && existingAuthentication.isAuthenticated() &&
                !(existingAuthentication instanceof AnonymousAuthenticationToken)) {
            logger.debug(
                    "AuthTokenFilter: Already authenticated as '{}' for request {}. Skipping.",
                    existingAuthentication.getName(), request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String jwt = parseJwt(request);

            if (jwt != null) {
                boolean isValid = jwtUtils.validateJwtToken(jwt);
                if (isValid) {
                    String email = jwtUtils.getUserNameFromJwtToken(jwt);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                    String refusal = whyRefused(jwt, userDetails);
                    if (refusal != null) {
                        // Treated exactly like a request with no token: protected routes answer 401,
                        // and the public sign-in routes still work, so a device holding a dead
                        // token can always sign in again.
                        logger.warn("AuthTokenFilter: token not honoured for {} ({}).", request.getRequestURI(), refusal);
                    } else {
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        logger.debug("AuthTokenFilter: User '{}' authenticated for {}.", email, request.getRequestURI());
                    }
                } else {
                    logger.debug("AuthTokenFilter: Invalid JWT token for request {}.", request.getRequestURI());
                }
            } else {
                logger.debug("AuthTokenFilter: No JWT token in request to {}.", request.getRequestURI());
            }
        } catch (Exception e) {
            logger.error("AuthTokenFilter: Error processing JWT for {}: {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Why a correctly signed, unexpired token is still not good enough, or null when it is.
     *
     * <p>A token must belong to a session, that session must be active and belong to the same
     * user, the session must not have reached its end, and account recovery must not have ended
     * the token.
     */
    private String whyRefused(String jwt, UserDetails userDetails) {
        Long sessionId = jwtUtils.getSessionIdFromJwtToken(jwt);
        if (sessionId == null) {
            return "token has no session";
        }
        if (!(userDetails instanceof com.investingapp.backend.security.services.UserDetailsImpl known)) {
            return "user could not be identified";
        }
        if (!userSessionRepository.existsByIdAndUserIdAndActiveTrue(sessionId, known.getId())) {
            return "session " + sessionId + " is revoked or is not this user's";
        }
        if (jwtUtils.sessionHasEnded(jwt)) {
            return "session " + sessionId + " has reached its end";
        }
        if (tokenCutoffs.isEnded(known.getId(), jwtUtils.getIssuedAtFromToken(jwt))) {
            return "token was ended by account recovery";
        }
        return null;
    }

    private String parseJwt(HttpServletRequest request) {
        // This method can be moved to JwtUtils if preferred, or kept here
        String headerAuth = request.getHeader("Authorization");

        if (headerAuth != null && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }
}