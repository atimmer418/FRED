package com.investingapp.backend.config;

import com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilter;
import com.investingapp.backend.security.plaid.PlaidWebhookVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the Plaid webhook verification filter on its single path, ahead of the Spring Security
 * chain (order -100), so an unverified webhook never reaches the application. Registered in every
 * profile; only the verifier implementation behind it is profile specific.
 */
@Configuration
public class PlaidWebhookFilterConfig {

    @Bean
    public FilterRegistrationBean<PlaidWebhookVerificationFilter> plaidWebhookVerificationFilter(PlaidWebhookVerifier verifier) {
        FilterRegistrationBean<PlaidWebhookVerificationFilter> registration =
                new FilterRegistrationBean<>(new PlaidWebhookVerificationFilter(verifier));
        registration.addUrlPatterns("/api/plaid/webhook");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("plaidWebhookVerificationFilter");
        return registration;
    }
}
