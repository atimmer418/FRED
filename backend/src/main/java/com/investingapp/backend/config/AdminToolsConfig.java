package com.investingapp.backend.config;

import com.investingapp.backend.security.AdminKeyFilter;
import com.investingapp.backend.security.AdminKeyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers the admin key filter in EVERY profile, prod included (no @Profile, unlike DevToolsConfig). */
@Configuration
public class AdminToolsConfig {

    private static final Logger logger = LoggerFactory.getLogger(AdminToolsConfig.class);

    @Bean
    public AdminKeyValidator adminKeyValidator(@Value("${app.admin-tools.key:}") String adminToolsKey) {
        AdminKeyValidator validator = new AdminKeyValidator(adminToolsKey);
        if (!validator.isConfigured()) {
            logger.warn("app.admin-tools.key is empty: every /api/admin/** request will be rejected "
                    + "with 404. Set the ADMIN_TOOLS_KEY environment variable.");
        }
        return validator;
    }

    /** Ahead of Spring Security (-100); constructed here so Boot does not also register it for every URL. */
    @Bean
    public FilterRegistrationBean<AdminKeyFilter> adminKeyFilter(AdminKeyValidator adminKeyValidator) {
        FilterRegistrationBean<AdminKeyFilter> registration =
                new FilterRegistrationBean<>(new AdminKeyFilter(adminKeyValidator));
        registration.addUrlPatterns("/api/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("adminKeyFilter");
        return registration;
    }
}
