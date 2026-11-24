package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaycheckAutopilotPropertiesTest {

    private static final Map<String, String> EXPRESSIONS = Map.of(
            "enabled", "${paycheck.autopilot.enabled:false}",
            "shadow", "${paycheck.autopilot.shadow:true}",
            "pullsEnabled", "${paycheck.autopilot.pulls.enabled:false}",
            "scoreProvisional", "${paycheck.autopilot.score.provisional:55}",
            "scoreConfirmed", "${paycheck.autopilot.score.confirmed:70}",
            "affordabilityShare", "${paycheck.autopilot.affordability.share:0.90}",
            "daysRequested", "${paycheck.autopilot.days-requested:365}");

    private static final Map<String, String> BASE_LINES = Map.of(
            "paycheck.autopilot.enabled", "${PAYCHECK_AUTOPILOT_ENABLED:false}",
            "paycheck.autopilot.shadow", "${PAYCHECK_AUTOPILOT_SHADOW:true}",
            "paycheck.autopilot.pulls.enabled", "${PAYCHECK_AUTOPILOT_PULLS_ENABLED:false}",
            "paycheck.autopilot.score.provisional", "${PAYCHECK_SCORE_PROVISIONAL:55}",
            "paycheck.autopilot.score.confirmed", "${PAYCHECK_SCORE_CONFIRMED:70}",
            "paycheck.autopilot.affordability.share", "${PAYCHECK_AFFORDABILITY_SHARE:0.90}",
            "paycheck.autopilot.days-requested", "${PAYCHECK_DAYS_REQUESTED:365}");

    private static PaycheckAutopilotProperties props(int provisional, int confirmed, String share, int days) {
        return new PaycheckAutopilotProperties(true, true, false, provisional, confirmed, new BigDecimal(share), days);
    }

    private static Properties load(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = PaycheckAutopilotPropertiesTest.class.getResourceAsStream("/" + resource)) {
            assertThat(in).as(resource + " on the test classpath").isNotNull();
            p.load(in);
        }
        return p;
    }

    @Test
    void everyFieldCarriesItsKeyAndDefault() throws Exception {
        for (Map.Entry<String, String> e : EXPRESSIONS.entrySet()) {
            Value value = PaycheckAutopilotProperties.class.getDeclaredField(e.getKey()).getAnnotation(Value.class);
            assertThat(value).as(e.getKey() + " carries @Value").isNotNull();
            assertThat(value.value()).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void bareInstanceIsTheSafeState() {
        PaycheckAutopilotProperties p = new PaycheckAutopilotProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.isShadow()).isTrue();
        assertThat(p.isPullsEnabled()).isFalse();
        assertThat(p.getScoreProvisional()).isEqualTo(55);
        assertThat(p.getScoreConfirmed()).isEqualTo(70);
        assertThat(p.getAffordabilityShare()).isEqualByComparingTo("0.90");
        assertThat(p.getDaysRequested()).isEqualTo(365);
    }

    @Test
    void baseProfileCarriesEveryKeyWithAnEnvDefault() throws IOException {
        Properties base = load("application.properties");
        BASE_LINES.forEach((key, line) -> assertThat(base.getProperty(key)).as(key).isEqualTo(line));
    }

    @Test
    void ciProfileTurnsAutopilotOff() throws IOException {
        assertThat(load("application-ci.properties").getProperty("paycheck.autopilot.enabled")).isEqualTo("false");
    }

    @Test
    void inconsistentTunablesRefuseToStart() {
        assertThatThrownBy(() -> props(70, 70, "0.90", 365)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paycheck.autopilot.score.provisional");
        assertThatThrownBy(() -> props(55, 101, "0.90", 365)).hasMessageContaining("paycheck.autopilot.score.provisional");
        assertThatThrownBy(() -> props(-1, 70, "0.90", 365)).hasMessageContaining("paycheck.autopilot.score.provisional");
        assertThatThrownBy(() -> props(55, 70, "0", 365)).hasMessageContaining("paycheck.autopilot.affordability.share");
        assertThatThrownBy(() -> props(55, 70, "1.01", 365)).hasMessageContaining("paycheck.autopilot.affordability.share");
        assertThatThrownBy(() -> props(55, 70, "0.90", 0)).hasMessageContaining("paycheck.autopilot.days-requested");
        assertThatThrownBy(() -> props(55, 70, "0.90", 731)).hasMessageContaining("paycheck.autopilot.days-requested");
        assertThatCode(() -> props(0, 100, "1.00", 730)).doesNotThrowAnyException();
        PaycheckAutopilotProperties p =
                new PaycheckAutopilotProperties(true, false, true, 50, 80, new BigDecimal("0.85"), 1);
        assertThat(p.isEnabled() && !p.isShadow() && p.isPullsEnabled()).as("all-args carries the flags").isTrue();
        assertThat(p.getScoreProvisional() + "/" + p.getScoreConfirmed() + "/" + p.getAffordabilityShare() + "/"
                + p.getDaysRequested()).isEqualTo("50/80/0.85/1");
    }
}
