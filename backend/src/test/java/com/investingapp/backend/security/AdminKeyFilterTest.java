package com.investingapp.backend.security;

import com.investingapp.backend.config.AdminToolsConfig;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminKeyFilterTest {

    private static final String KEY = "admin-key-for-tests-only";

    private MockHttpServletRequest adminRequest(String key, String actor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users/7/delete");
        request.setRequestURI("/api/admin/users/7/delete");
        if (key != null) {
            request.addHeader(AdminKeyValidator.KEY_HEADER, key);
        }
        if (actor != null) {
            request.addHeader(AdminKeyValidator.ACTOR_HEADER, actor);
        }
        return request;
    }

    private MockFilterChain run(MockHttpServletRequest request, MockHttpServletResponse response,
            AdminKeyValidator validator) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new AdminKeyFilter(validator).doFilter(request, response, chain);
        return chain;
    }

    @Test
    void missingKeyIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest(null, "andy"), response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.getHeader("Content-Length")).isEqualTo("0");
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter chain must not continue").isNull();
    }

    @Test
    void wrongKeyIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest("not-the-key", "andy"), response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingActorIs400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest(KEY, null), response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void blankActorIs400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest(KEY, "   "), response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void keyAndActorPassAndSetActorAttribute() throws Exception {
        MockHttpServletRequest request = adminRequest(KEY, "  andy  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(request, response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE)).isEqualTo("andy");
    }

    @Test
    void unconfiguredKeyDeniesAll() throws Exception {
        AdminKeyValidator unset = new AdminKeyValidator("");
        assertThat(unset.isConfigured()).isFalse();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest("", "andy"), response, unset);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void preflightIsNotKeyChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/plaid/items");
        request.setRequestURI("/api/admin/plaid/items");
        request.addHeader("Origin", "http://localhost:4200");
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "x-admin-key,x-admin-actor");

        assertThat(run(request, new MockHttpServletResponse(), new AdminKeyValidator(KEY)).getRequest()).isNotNull();
    }

    @Test
    void validatorRejectsBadInput() {
        AdminKeyValidator validator = new AdminKeyValidator(KEY);
        assertThat(validator.matches(null)).isFalse();
        assertThat(validator.matches("")).isFalse();
        assertThat(validator.matches(KEY + "x")).isFalse();
        assertThat(validator.matches(KEY.toUpperCase())).isFalse();
        assertThat(validator.matches(KEY)).isTrue();
    }

    @Test
    void filterRegisteredInEveryProfileIncludingProd() {
        for (String profile : List.of("prod", "dev", "local", "test", "ci", "staging")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(AdminToolsConfig.class)
                    .withPropertyValues("spring.profiles.active=" + profile, "app.admin-tools.key=" + KEY)
                    .run(context -> {
                        FilterRegistrationBean<?> registration =
                                context.getBean("adminKeyFilter", FilterRegistrationBean.class);
                        assertThat(registration.getUrlPatterns()).containsExactly("/api/admin/*");
                        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                    });
        }
    }
}
