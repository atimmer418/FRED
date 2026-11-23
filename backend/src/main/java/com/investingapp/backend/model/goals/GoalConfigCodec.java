package com.investingapp.backend.model.goals;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.service.GoalValidationException;
import org.springframework.stereotype.Component;

/** Parses and serializes the per-type goal config JSON stored in the goals TEXT column. */
@Component
public class GoalConfigCodec {

    private static final String BAD_SHAPE = "Goal details are not in the expected shape.";

    private final ObjectMapper mapper;

    public GoalConfigCodec(ObjectMapper appMapper) {
        this.mapper = appMapper.copy()
                .registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Object parse(GoalType type, String json) {
        if (json == null) {
            throw new GoalValidationException(BAD_SHAPE);
        }
        try {
            return requirePresent(mapper.readValue(json, configClass(type)));
        } catch (JsonProcessingException e) {
            throw new GoalValidationException(BAD_SHAPE, e);
        }
    }

    public Object parse(GoalType type, JsonNode node) {
        if (node == null) {
            throw new GoalValidationException(BAD_SHAPE);
        }
        try {
            return requirePresent(mapper.treeToValue(node, configClass(type)));
        } catch (JsonProcessingException e) {
            throw new GoalValidationException(BAD_SHAPE, e);
        }
    }

    /** A JSON null parses to a null record; that is a bad shape, not a server error. */
    private static Object requirePresent(Object parsed) {
        if (parsed == null) {
            throw new GoalValidationException(BAD_SHAPE);
        }
        return parsed;
    }

    public String write(Object config) {
        try {
            return mapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize goal config", e);
        }
    }

    private static Class<?> configClass(GoalType type) {
        return switch (type) {
            case KIDS -> KidsConfig.class;
            case HOUSE, CAR -> HouseCarConfig.class;
            case VACATION -> VacationConfig.class;
            case EMERGENCY_FUND -> EmergencyFundConfig.class;
            case WEDDING, CUSTOM -> TargetConfig.class;
        };
    }
}
