package com.investingapp.backend.security.ratelimit;

import com.investingapp.backend.security.ratelimit.RateLimiter.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();

    private RateLimiter limiter(MockEnvironment environment) {
        return new RateLimiter(environment, nanos::get);
    }

    @Test
    @DisplayName("a key gets its limit, is refused, and gets a fresh budget when the window ends")
    void windowEnds() {
        RateLimiter limiter = limiter(new MockEnvironment().withProperty(Rule.CHAT_MESSAGES_PER_MINUTE.property(), "2"));

        assertThat(limiter.allow(Rule.CHAT_MESSAGES_PER_MINUTE, "7")).isTrue();
        assertThat(limiter.allow(Rule.CHAT_MESSAGES_PER_MINUTE, "7")).isTrue();
        assertThat(limiter.allow(Rule.CHAT_MESSAGES_PER_MINUTE, "7")).isFalse();
        assertThat(limiter.allow(Rule.CHAT_MESSAGES_PER_MINUTE, "8")).isTrue();

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        assertThat(limiter.allow(Rule.CHAT_MESSAGES_PER_MINUTE, "7")).isTrue();
    }

    @Test
    @DisplayName("capitals, spaces and a missing value are not ways round a limit")
    void keysAreNormalised() {
        RateLimiter limiter = limiter(new MockEnvironment().withProperty(Rule.SIGN_UP_PER_EMAIL.property(), "1"));

        assertThat(limiter.allow(Rule.SIGN_UP_PER_EMAIL, "a@example.com")).isTrue();
        assertThat(limiter.allow(Rule.SIGN_UP_PER_EMAIL, " A@Example.COM ")).isFalse();

        assertThat(limiter.allow(Rule.SIGN_UP_PER_EMAIL, null)).isTrue();
        assertThat(limiter.allow(Rule.SIGN_UP_PER_EMAIL, "  ")).isFalse();
    }

    @Test
    @DisplayName("rules do not share counts")
    void rulesAreSeparate() {
        RateLimiter limiter = limiter(new MockEnvironment()
                .withProperty(Rule.LOGIN_PER_ADDRESS.property(), "1")
                .withProperty(Rule.SIGN_IN_PER_ADDRESS.property(), "1"));

        assertThat(limiter.allow(Rule.LOGIN_PER_ADDRESS, "198.51.100.1")).isTrue();
        assertThat(limiter.allow(Rule.SIGN_IN_PER_ADDRESS, "198.51.100.1")).isTrue();
        assertThat(limiter.allow(Rule.LOGIN_PER_ADDRESS, "198.51.100.1")).isFalse();
    }

    @Test
    @DisplayName("every rule has a default, and the switch turns them all off")
    void defaultsAndSwitch() {
        RateLimiter defaults = limiter(new MockEnvironment());
        for (Rule rule : Rule.values()) {
            assertThat(defaults.allow(rule, "k")).as(rule.name()).isTrue();
        }

        RateLimiter off = limiter(new MockEnvironment()
                .withProperty("app.rate-limit.enabled", "false")
                .withProperty(Rule.LOGIN_PER_ADDRESS.property(), "1"));
        assertThat(off.allow(Rule.LOGIN_PER_ADDRESS, "k")).isTrue();
        assertThat(off.allow(Rule.LOGIN_PER_ADDRESS, "k")).isTrue();
    }

    @Test
    @DisplayName("require throws the exception that becomes a 429")
    void requireThrows() {
        RateLimiter limiter = limiter(new MockEnvironment().withProperty(Rule.LOGIN_PER_EMAIL.property(), "1"));
        limiter.require(Rule.LOGIN_PER_EMAIL, "a@example.com");

        assertThatThrownBy(() -> limiter.require(Rule.LOGIN_PER_EMAIL, "a@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("which routes the public filter counts, and under which rule")
    void routes() {
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/passkey/register/start")).isEqualTo(Rule.SIGN_UP_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/passkey/register/finish")).isEqualTo(Rule.SIGN_UP_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/passkey/register/recovery/start")).isEqualTo(Rule.SIGN_UP_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/passkey/authenticate/start")).isEqualTo(Rule.SIGN_IN_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/passkey/authenticate/finish")).isEqualTo(Rule.SIGN_IN_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/passkey/anything-added-later")).isEqualTo(Rule.SIGN_IN_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/auth/login")).isEqualTo(Rule.LOGIN_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("GET", "/api/auth/testuser")).isEqualTo(Rule.LOGIN_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/auth/refresh")).isEqualTo(Rule.REFRESH_PER_ADDRESS);
        assertThat(PublicEndpointRateLimitFilter.ruleFor("GET", "/api/user/should-prompt-reauth")).isEqualTo(Rule.REAUTH_CHECK_PER_ADDRESS);

        // Recovery has its own limits; signed-in routes and the CORS preflight are not counted here.
        assertThat(PublicEndpointRateLimitFilter.ruleFor("POST", "/api/auth/recovery/verify")).isNull();
        assertThat(PublicEndpointRateLimitFilter.ruleFor("GET", "/api/user/progress")).isNull();
        assertThat(PublicEndpointRateLimitFilter.ruleFor("OPTIONS", "/api/passkey/authenticate/start")).isNull();
    }

    @Test
    @DisplayName("the caller's address: the configured headers in order, then the socket")
    void clientAddress() {
        ClientAddressResolver resolver = new ClientAddressResolver("CF-Connecting-IP,X-Forwarded-For");

        MockHttpServletRequest both = new MockHttpServletRequest();
        both.setRemoteAddr("10.0.0.9");
        both.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        both.addHeader("CF-Connecting-IP", "198.51.100.7");
        assertThat(resolver.resolve(both)).isEqualTo("198.51.100.7");

        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        assertThat(resolver.resolve(forwarded)).isEqualTo("203.0.113.5");

        MockHttpServletRequest bare = new MockHttpServletRequest();
        bare.setRemoteAddr("10.0.0.9");
        assertThat(resolver.resolve(bare)).isEqualTo("10.0.0.9");

        MockHttpServletRequest huge = new MockHttpServletRequest();
        huge.addHeader("CF-Connecting-IP", "9".repeat(500));
        assertThat(resolver.resolve(huge)).hasSize(64);

        // IPv6: one /64 block is one caller, however the address is written.
        assertThat(ClientAddressResolver.network("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(ClientAddressResolver.network("2001:DB8:1:2::1")).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(ClientAddressResolver.network("203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(ClientAddressResolver.network("not-an-address:at-all")).isEqualTo("not-an-address:at-all");

        // With no headers trusted, a forged header changes nothing.
        ClientAddressResolver socketOnly = new ClientAddressResolver("");
        assertThat(socketOnly.resolve(both)).isEqualTo("10.0.0.9");
    }
}
