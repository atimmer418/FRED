package com.investingapp.backend.model.goals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.service.GoalValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalConfigCodecTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final GoalConfigCodec codec = new GoalConfigCodec(mapper);

    private static final String KIDS_JSON = """
            {"brokerageTarget":50000,"plan529Target":80000,"kids":[
              {"label":"Ava","birthYear":2020,"birthMonth":4,"accounts":{
                "brokerage":{"balance":1200.50,"balanceUpdatedAt":"2026-11-02T10:15:30","anchorBalance":1000,"anchorMonthly":100},
                "plan529":{"balance":300,"balanceUpdatedAt":"2026-11-02T10:15:30","anchorBalance":250,"anchorMonthly":50}}}]}
            """;

    @Test
    void writesStrippedBigDecimalsAsPlainNumbers() {
        String json = codec.write(new TargetConfig(new java.math.BigDecimal("4E+5"), 2030));

        assertTrue(json.contains("\"targetAmount\":400000"), json);
    }

    @Test
    void parsesKidsConfigRoundTrip() {
        Object parsed = codec.parse(GoalType.KIDS, KIDS_JSON);
        assertInstanceOf(KidsConfig.class, parsed);
        Object again = codec.parse(GoalType.KIDS, codec.write(parsed));
        assertEquals(parsed, again);
        KidsConfig cfg = (KidsConfig) again;
        assertEquals(1, cfg.kids().size());
        assertEquals("Ava", cfg.kids().get(0).label());
        assertEquals(2020, cfg.kids().get(0).birthYear());
    }

    @Test
    void parsesFromJsonNodeToo() throws Exception {
        String json = "{\"targetAmount\":25000,\"targetYear\":2031}";
        JsonNode node = mapper.readTree(json);
        assertEquals(codec.parse(GoalType.CUSTOM, json), codec.parse(GoalType.CUSTOM, node));
    }

    @Test
    void rejectsUnknownProperty() {
        assertThrows(GoalValidationException.class,
                () -> codec.parse(GoalType.CUSTOM, "{\"targetAmount\":1,\"targetYear\":2030,\"extra\":1}"));
    }

    @Test
    void rejectsWrongShapeForType() {
        assertThrows(GoalValidationException.class, () -> codec.parse(GoalType.HOUSE, KIDS_JSON));
    }

    @Test
    void houseAndCarShareOneRecord() {
        String json = "{\"price\":400000,\"downPaymentPct\":20,\"targetYear\":2030}";
        assertInstanceOf(HouseCarConfig.class, codec.parse(GoalType.HOUSE, json));
        assertInstanceOf(HouseCarConfig.class, codec.parse(GoalType.CAR, json));
    }

    @Test
    void writeFailureIsServerFaultNotValidation() {
        // An Object with no properties makes Jackson fail on empty beans.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> codec.write(new Object()));
        assertNotNull(ex.getCause());
        assertEquals("Could not serialize goal config", ex.getMessage());
    }

    @Test
    void parseFailureKeepsTheCause() {
        GoalValidationException ex = assertThrows(GoalValidationException.class,
                () -> codec.parse(GoalType.CUSTOM, "{\"extra\":1}"));
        assertEquals("Goal details are not in the expected shape.", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void timestampsWriteAsIsoStrings() {
        String written = codec.write(codec.parse(GoalType.KIDS, KIDS_JSON));
        assertTrue(written.contains("\"2026-11-02T10:15:30\""), written);
        assertFalse(written.contains("[2026"), written);
    }
}
