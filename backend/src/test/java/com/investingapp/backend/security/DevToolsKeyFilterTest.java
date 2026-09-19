package com.investingapp.backend.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * api-dev is on the public internet, so "dev profile only" is not enough on its own: every
 * dev-tools request also needs the shared-secret header. Rejections are 404, not 401, so the
 * endpoints do not advertise that they exist.
 */
class DevToolsKeyFilterTest {

    private static final String KEY = "correct-horse-battery-staple";

    private MockHttpServletRequest devRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/dev/authenticate-as-user");
        request.setRequestURI("/api/dev/authenticate-as-user");
        return request;
    }

    @Test
    @DisplayName("no header: 404 and the request never reaches the handler")
    void missingHeaderIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new DevToolsKeyFilter(new DevToolsKeyValidator(KEY)).doFilter(devRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter chain must not continue").isNull();
    }

    @Test
    @DisplayName("wrong header: 404")
    void wrongHeaderIs404() throws Exception {
        MockHttpServletRequest request = devRequest();
        request.addHeader(DevToolsKeyValidator.HEADER, "not-the-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new DevToolsKeyFilter(new DevToolsKeyValidator(KEY)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("correct header: the request proceeds")
    void correctHeaderPassesThrough() throws Exception {
        MockHttpServletRequest request = devRequest();
        request.addHeader(DevToolsKeyValidator.HEADER, KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new DevToolsKeyFilter(new DevToolsKeyValidator(KEY)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).as("filter chain must continue").isNotNull();
    }

    @Test
    @DisplayName("unset key denies everything, including a request that sends an empty key")
    void unconfiguredKeyDeniesAll() throws Exception {
        DevToolsKeyValidator unset = new DevToolsKeyValidator("");
        assertThat(unset.isConfigured()).isFalse();

        MockHttpServletRequest request = devRequest();
        request.addHeader(DevToolsKeyValidator.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new DevToolsKeyFilter(unset).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("a CORS preflight passes through: it cannot carry the key, and 404ing it would break every browser caller")
    void preflightIsNotKeyChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/dev/authenticate-as-user");
        request.setRequestURI("/api/dev/authenticate-as-user");
        request.addHeader("Origin", "http://localhost:4200");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,x-dev-tools-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new DevToolsKeyFilter(new DevToolsKeyValidator(KEY)).doFilter(request, response, chain);

        assertThat(response.getStatus())
                .as("preflight must reach the CORS filter, not be 404ed")
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("a plain OPTIONS that is not a preflight is still key-checked")
    void nonPreflightOptionsStillChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/dev/authenticate-as-user");
        request.setRequestURI("/api/dev/authenticate-as-user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new DevToolsKeyFilter(new DevToolsKeyValidator(KEY)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("validator rejects null, blank and near-miss keys")
    void validatorRejectsBadInput() {
        DevToolsKeyValidator validator = new DevToolsKeyValidator(KEY);
        assertThat(validator.matches(null)).isFalse();
        assertThat(validator.matches("")).isFalse();
        assertThat(validator.matches(KEY + "x")).isFalse();
        assertThat(validator.matches(KEY.toUpperCase())).isFalse();
        assertThat(validator.matches(KEY)).isTrue();
    }
}
