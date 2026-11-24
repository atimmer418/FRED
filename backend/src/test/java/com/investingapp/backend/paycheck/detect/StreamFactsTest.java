package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamFactsTest {

    @Test
    void emptyUntilMatched() {
        PaycheckStream s = new PaycheckStream();
        s.setPlaidStatus("MATURE");
        assertThat(StreamFacts.of(null)).isEmpty();
        assertThat(StreamFacts.of(s)).isEmpty();
        s.setPlaidStreamId("ps-1");
        assertThat(StreamFacts.of(s)).contains(new StreamFacts("MATURE", null, null, null, null, null));
        assertThat(StreamFacts.of(s).orElseThrow().mature()).isTrue();
    }
}
