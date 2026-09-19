package com.investingapp.backend.config;

import com.investingapp.backend.controller.DevAuthController;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.service.SimulatedAttestationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Dev tooling must exist in local/dev/test/ci and nowhere else.
 *
 * <p>Bean absence is what produces a 404: with no DevAuthController bean, Spring MVC has no handler
 * mapped to /api/dev/**, so the dispatcher returns 404. Gating at registration rather than with a
 * runtime check means one config mistake cannot expose the handler.
 */
class DevToolsGatingTest {

    private ApplicationContextRunner runnerWithProfile(String profile) {
        return new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        // Spring still injects @Value fields into mocks, so JwtUtils needs these.
                        "jwt.secret=test-secret-not-used",
                        "jwt.expiration.ms=300000")
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(UserSessionRepository.class, () -> mock(UserSessionRepository.class))
                .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class))
                .withBean(JwtUtils.class, () -> mock(JwtUtils.class))
                .withBean(DevToolsKeyValidator.class, () -> new DevToolsKeyValidator("test-key"))
                .withUserConfiguration(DevAuthController.class, SimulatedAttestationService.class);
    }

    @Test
    @DisplayName("prod: no dev endpoints and no simulated attestation are registered, so /api/dev/** 404s")
    void devToolsAbsentInProd() {
        runnerWithProfile("prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DevAuthController.class);
            assertThat(context).doesNotHaveBean(SimulatedAttestationService.class);
        });
    }

    @Test
    @DisplayName("an unrecognized profile is treated like prod: dev tooling stays unregistered")
    void devToolsAbsentInUnknownProfile() {
        runnerWithProfile("staging").run(context -> {
            assertThat(context).doesNotHaveBean(DevAuthController.class);
            assertThat(context).doesNotHaveBean(SimulatedAttestationService.class);
        });
    }

    @Test
    @DisplayName("dev, local, test and ci keep their dev tooling")
    void devToolsPresentInDevProfiles() {
        for (String profile : DevToolsConfig.DEV_TOOL_PROFILES) {
            runnerWithProfile(profile).run(context -> {
                assertThat(context).hasSingleBean(DevAuthController.class);
                assertThat(context).hasSingleBean(SimulatedAttestationService.class);
            });
        }
    }

    @Test
    @DisplayName("the dev-tools filter is registered in dev and never in prod")
    void filterRegistrationFollowsProfile() {
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(DevToolsConfig.class)
                .withPropertyValues("app.dev-tools.key=test-key");

        base.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context).hasBean("devToolsKeyFilter"));

        base.withPropertyValues("spring.profiles.active=prod").run(context ->
                assertThat(context).doesNotHaveBean("devToolsKeyFilter"));
    }

    @Test
    @DisplayName("prod refuses to start when a dev bean is registered anyway")
    void prodGuardFailsStartupWhenDevBeanPresent() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("devAuthController", new RootBeanDefinition(DevAuthController.class));

        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles(DevToolsProdGuard.PROD_PROFILE);
        guard.setEnvironment(prod);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dev tooling must never be registered in the prod profile")
                .hasMessageContaining("devAuthController");
    }

    @Test
    @DisplayName("prod refuses to start when simulated attestation is registered anyway")
    void prodGuardFailsStartupWhenSimulatedAttestationPresent() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("simulatedAttestationService",
                new RootBeanDefinition(SimulatedAttestationService.class));

        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles(DevToolsProdGuard.PROD_PROFILE);
        guard.setEnvironment(prod);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulatedAttestationService");
    }

    @Test
    @DisplayName("the guard stays out of the way in dev")
    void prodGuardAllowsDevProfile() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("devAuthController", new RootBeanDefinition(DevAuthController.class));

        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        guard.setEnvironment(dev);

        guard.postProcessBeanFactory(beanFactory); // must not throw
    }
}
