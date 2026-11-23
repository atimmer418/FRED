package com.investingapp.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Decides whether a signed-in user may set their own subscription tier by simply asking for it.
 *
 * In development and test that is how a tier gets set at all: there is no billing yet, so the app
 * writes the chosen tier through PUT /user/profile. In production the same request would be a free
 * upgrade to any paid tier, so it is locked there and a tier may only come from the billing path.
 *
 * The rule is written to fail safe. Self-service is allowed only when the environment positively
 * says it is a development one: at least one of the known dev profiles is active and prod is not.
 * Prod, no profile at all, a profile nobody has heard of, or an environment that cannot be read
 * are all treated as production and locked. Forgetting to set a profile therefore locks the tier;
 * it can never unlock it.
 */
@Component
public class SelfServiceTierPolicy {

    private static final Logger logger = LoggerFactory.getLogger(SelfServiceTierPolicy.class);

    private static final String PROD_PROFILE = "prod";

    private final Environment environment;

    public SelfServiceTierPolicy(Environment environment) {
        this.environment = environment;
        logger.info("Self-service tier changes are {} (active profiles: {})",
                isAllowed() ? "ALLOWED: this is a development environment" : "LOCKED",
                describeProfiles());
    }

    /** True only in a known development environment. Everything else, including "unknown", is false. */
    public boolean isAllowed() {
        try {
            if (environment == null) {
                return false;
            }
            String[] active = environment.getActiveProfiles();
            if (active == null || active.length == 0) {
                return false;
            }
            List<String> profiles = Arrays.asList(active);
            if (profiles.contains(PROD_PROFILE)) {
                return false;
            }
            return Arrays.stream(DevToolsConfig.DEV_TOOL_PROFILES).anyMatch(profiles::contains);
        } catch (RuntimeException e) {
            logger.warn("Could not read the active profiles; treating this as production and "
                    + "locking self-service tier changes", e);
            return false;
        }
    }

    private String describeProfiles() {
        try {
            return environment == null ? "unknown" : Arrays.toString(environment.getActiveProfiles());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
