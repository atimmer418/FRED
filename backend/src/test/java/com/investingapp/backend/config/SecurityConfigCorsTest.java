package com.investingapp.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app calls the API from another origin (capacitor://localhost on device, local.fredvested.com in
 * the dev loop), so the browser only lets it read a custom response header the CORS config exposes.
 */
class SecurityConfigCorsTest {

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CorsFilter(new SecurityConfig().corsConfigurationSource()).doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("a cross-origin ledger POST exposes X-Fred-Capped to the app")
    void ledgerPostExposesCappedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/goals/1/ledger");
        request.addHeader("Origin", "capacitor://localhost");

        MockHttpServletResponse response = run(request);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("capacitor://localhost");
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("X-Fred-Capped");
    }

    @Test
    @DisplayName("the ledger preflight is allowed for the app's POST")
    void ledgerPreflightIsAllowed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/goals/1/ledger");
        request.addHeader("Origin", "https://local.fredvested.com");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization,content-type,x-device-id");

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://local.fredvested.com");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
    }
}
