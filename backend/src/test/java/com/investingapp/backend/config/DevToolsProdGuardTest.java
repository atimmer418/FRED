package com.investingapp.backend.config;

import com.investingapp.backend.controller.DevPaycheckController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevToolsProdGuardTest {

    private static DevToolsProdGuard guard(String profile) {
        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        guard.setEnvironment(environment);
        return guard;
    }

    private static DefaultListableBeanFactory withDevPaycheckController() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("devPaycheckController", new RootBeanDefinition(DevPaycheckController.class));
        return beanFactory;
    }

    @Test
    void devControllerRefusedInProdByGuard() {
        assertThatThrownBy(() -> guard(DevToolsProdGuard.PROD_PROFILE).postProcessBeanFactory(withDevPaycheckController()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dev tooling must never be registered in the prod profile")
                .hasMessageContaining("devPaycheckController (DevPaycheckController)");
    }

    @Test
    void devControllerAllowedOutsideProd() {
        for (String profile : DevToolsConfig.DEV_TOOL_PROFILES) {
            assertThatCode(() -> guard(profile).postProcessBeanFactory(withDevPaycheckController())).doesNotThrowAnyException();
        }
    }
}
