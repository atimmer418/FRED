package com.investingapp.backend.config;

import com.investingapp.backend.controller.DevAuthController;
import com.investingapp.backend.service.SimulatedAttestationService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fails the application start if dev tooling is registered while the prod profile is active.
 *
 * <p>Runs as a {@link BeanFactoryPostProcessor}, so it sees bean definitions before anything is
 * instantiated and its failure aborts startup. A crash on deploy is loud; a silently exposed auth
 * bypass is not.
 */
@Component
public class DevToolsProdGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String PROD_PROFILE = "prod";

    /** Escape hatch is deliberately absent: there is no property that re-enables dev tools in prod. */
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!isProdActive()) {
            return;
        }

        List<String> offenders = new ArrayList<>();
        addIfPresent(beanFactory, DevAuthController.class, offenders);
        addIfPresent(beanFactory, SimulatedAttestationService.class, offenders);
        addIfPresent(beanFactory, DevToolsConfig.class, offenders);

        for (String name : beanFactory.getBeanDefinitionNames()) {
            String className = beanFactory.getBeanDefinition(name).getBeanClassName();
            if (className != null
                    && className.startsWith("com.investingapp.backend")
                    && (className.contains("DevAuth") || className.contains("Simulated"))) {
                offenders.add(name + " (" + className + ")");
            }
        }

        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Dev tooling must never be registered in the prod profile. Refusing to start. "
                            + "Offending beans: " + offenders
                            + ". Dev tools are limited to " + Arrays.toString(DevToolsConfig.DEV_TOOL_PROFILES) + ".");
        }
    }

    private boolean isProdActive() {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    }

    private void addIfPresent(ConfigurableListableBeanFactory beanFactory, Class<?> type, List<String> offenders) {
        // allowEagerInit=false: inspect definitions without instantiating anything.
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            offenders.add(name + " (" + type.getSimpleName() + ")");
        }
    }
}
