package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.*;
import org.junit.jupiter.api.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CycleGateChainTest {

    private PaycheckAutopilotProperties props;

    @BeforeEach
    void setUp() {
        props = mock(PaycheckAutopilotProperties.class);
    }

    private CycleContext ctx() {
        return new CycleContext(new User("ada@example.com"), Optional.empty(), new PaycheckStream(), new PaycheckEvent(),
                new BigDecimal("150.00"), props);
    }

    @Test
    void shadowGateRunsFirstBeforeAnyNotice() {
        when(props.isShadow()).thenReturn(true);
        CycleGateChain chain = new CycleGateChain(List.of(new KillSwitchGate(), new ShadowGate())); // sorted by @Order
        assertThat(chain.gates()).extracting(g -> g.getClass().getSimpleName()).containsExactly("ShadowGate", "KillSwitchGate");
        assertThat(chain.firstSkip(ctx())).contains(PaycheckSkipReason.SHADOW_CONFIRMED);
        assertThat(ShadowGate.class.getAnnotation(Order.class).value()).isEqualTo(10);
        assertThat(KillSwitchGate.class.getAnnotation(Order.class).value()).isEqualTo(20);
        assertThat(CycleGateChain.class.getAnnotation(Component.class)).isNotNull();
    }

    @Test
    void killSwitchDecidesWhenShadowIsOff() {
        CycleGateChain chain = new CycleGateChain(List.of(new ShadowGate(), new KillSwitchGate()));
        when(props.isShadow()).thenReturn(false);
        assertThat(chain.firstSkip(ctx())).contains(PaycheckSkipReason.KILL_SWITCH);
        when(props.isPullsEnabled()).thenReturn(true);
        assertThat(chain.firstSkip(ctx())).isEmpty();
    }
}
