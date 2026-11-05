package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationGateTest {

    private User user;

    private static NotificationGate gate(boolean enabled, boolean shadow) {
        return new NotificationGate(
                new PaycheckAutopilotProperties(enabled, shadow, false, 55, 70, new BigDecimal("0.90"), 365));
    }

    private static InvestmentSchedule schedule(HoldReason hold) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setHoldReason(hold);
        return s;
    }

    @BeforeEach
    void setUp() {
        user = new User("saver@fredvested.com");
        user.setId(42L);
    }

    @Test
    void shadowSuppresses() {
        assertThat(gate(true, true).suppressed(user, schedule(null))).isTrue();
        assertThat(gate(true, true).suppressed(user, null)).as("no schedule row yet").isTrue();
    }

    @Test
    void disabledSuppressesEvenOutsideShadow() {
        assertThat(gate(false, false).suppressed(user, schedule(null))).isTrue();
    }

    @Test
    void killSwitchHoldSuppresses() {
        assertThat(gate(true, false).suppressed(user, schedule(HoldReason.KILL_SWITCH))).isTrue();
    }

    @Test
    void openWhenEnabledLiveAndNotKillSwitched() {
        NotificationGate open = gate(true, false);
        assertThat(open.suppressed(user, null)).isFalse();
        assertThat(open.suppressed(user, schedule(null))).isFalse();
        for (HoldReason hold : EnumSet.complementOf(EnumSet.of(HoldReason.KILL_SWITCH))) {
            assertThat(open.suppressed(user, schedule(hold))).as(hold.name()).isFalse();
        }
    }

    @Test
    void reasonNamesTheFirstMatch() {
        InvestmentSchedule killed = schedule(HoldReason.KILL_SWITCH);
        assertThat(gate(false, true).reason(killed)).contains(NotificationGate.REASON_DISABLED);
        assertThat(gate(true, true).reason(killed)).contains(NotificationGate.REASON_SHADOW);
        assertThat(gate(true, false).reason(killed)).contains(NotificationGate.REASON_KILL_SWITCH);
        assertThat(NotificationGate.REASON_DISABLED).isEqualTo("disabled");
        assertThat(NotificationGate.REASON_SHADOW).isEqualTo("shadow");
        assertThat(NotificationGate.REASON_KILL_SWITCH).isEqualTo("kill_switch");
    }
}
