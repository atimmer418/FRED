package com.investingapp.backend.config;

import com.investingapp.backend.security.DevToolsKeyFilter;
import com.investingapp.backend.security.DevToolsKeyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/**
 * Registers the dev-tools request filter. Dev tooling (the {@code /api/dev/**} endpoints and
 * simulated passkey attestation) is gated at bean-registration time by {@link Profile}: outside
 * these profiles the beans are never registered, so the handlers do not exist at all rather than
 * being guarded by a runtime check that one config mistake could defeat.
 *
 * <p>{@code prod} is deliberately absent. {@link DevToolsProdGuard} refuses to start the
 * application if anything dev-related is registered while the prod profile is active.
 */
@Configuration
@Profile({"local", "dev", "test", "ci"})
public class DevToolsConfig {

    /** Profiles where dev tooling may exist. Kept in sync with the @Profile annotations. */
    public static final String[] DEV_TOOL_PROFILES = {"local", "dev", "test", "ci"};

    private static final Logger logger = LoggerFactory.getLogger(DevToolsConfig.class);

    /** Shared by the /api/dev/** filter and by simulated passkey attestation. */
    @Bean
    public DevToolsKeyValidator devToolsKeyValidator(@Value("${app.dev-tools.key:}") String devToolsKey) {
        DevToolsKeyValidator validator = new DevToolsKeyValidator(devToolsKey);
        if (!validator.isConfigured()) {
            logger.warn("Dev tools are registered but app.dev-tools.key is empty: every /api/dev/** "
                    + "request and every simulated registration will be rejected. Set the "
                    + "DEV_TOOLS_KEY environment variable.");
        }
        return validator;
    }

    /**
     * Runs ahead of the Spring Security chain (order -100) so rejected dev-tools requests never
     * reach the application.
     */
    @Bean
    public FilterRegistrationBean<DevToolsKeyFilter> devToolsKeyFilter(DevToolsKeyValidator devToolsKeyValidator) {

        FilterRegistrationBean<DevToolsKeyFilter> registration =
                new FilterRegistrationBean<>(new DevToolsKeyFilter(devToolsKeyValidator));
        registration.addUrlPatterns("/api/dev/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("devToolsKeyFilter");
        return registration;
    }
}
