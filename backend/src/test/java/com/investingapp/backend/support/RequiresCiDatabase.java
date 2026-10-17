package com.investingapp.backend.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Marks a test that needs the real MySQL database the `ci` profile describes, and skips it with a
 * clear reason when that database is not reachable.
 *
 * <p>Context tests are worth keeping: they catch a missing bean or a broken migration that no unit
 * test sees. But they cannot run without a database, and the alternatives were worse. The `test`
 * profile points at the deployed Railway environment (env-var host, requireSSL), so running the
 * suite under it would migrate a live environment from a laptop. The base profile has no datasource
 * at all. The `ci` profile points at a local MySQL that CI provisions as a service container, which
 * is exactly the right target.
 *
 * <p>So: the test runs wherever its database exists (CI, and any machine that provides one), and is
 * skipped rather than failed elsewhere. The datasource can also be overridden from the environment
 * (SPRING_DATASOURCE_URL and friends) to point at any scratch schema.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresCiDatabase.Condition.class)
public @interface RequiresCiDatabase {

    class Condition implements ExecutionCondition {

        private static final int LOGIN_TIMEOUT_SECONDS = 3;

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            Properties ci = load("application-ci.properties");
            if (ci == null) {
                return ConditionEvaluationResult.disabled("application-ci.properties not on the classpath");
            }

            String url = env("SPRING_DATASOURCE_URL", ci.getProperty("spring.datasource.url"));
            String user = env("SPRING_DATASOURCE_USERNAME", ci.getProperty("spring.datasource.username"));
            String password = env("SPRING_DATASOURCE_PASSWORD", ci.getProperty("spring.datasource.password"));

            if (url == null) {
                return ConditionEvaluationResult.disabled("no datasource url configured for the ci profile");
            }

            int previousTimeout = DriverManager.getLoginTimeout();
            try {
                DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    return connection.isValid(LOGIN_TIMEOUT_SECONDS)
                            ? ConditionEvaluationResult.enabled("ci database reachable")
                            : ConditionEvaluationResult.disabled("ci database did not answer");
                }
            } catch (Exception e) {
                // Deliberately not a failure: a developer without this database should still get a
                // green suite. The reason is printed so a skip is never mistaken for a pass.
                return ConditionEvaluationResult.disabled(
                        "ci database not reachable, skipping the context test. Provide one (CI does) or set "
                                + "SPRING_DATASOURCE_URL/USERNAME/PASSWORD. Cause: " + e.getMessage());
            } finally {
                DriverManager.setLoginTimeout(previousTimeout);
            }
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return (value != null && !value.isBlank()) ? value : fallback;
        }

        private static Properties load(String resource) {
            try (InputStream in = Condition.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                Properties properties = new Properties();
                properties.load(in);
                return properties;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
