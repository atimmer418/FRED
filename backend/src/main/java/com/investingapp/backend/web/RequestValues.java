package com.investingapp.backend.web;

import java.util.Map;

/**
 * Reads values out of a loosely typed request body without trusting their type.
 *
 * <p>A plain cast turns a wrong type from the client (a number where text was expected) into a
 * server error. These answer it as the bad request it is.
 */
public final class RequestValues {

    private RequestValues() {
    }

    /** The text under this key, or null when it is absent or null. */
    public static String string(Map<String, ?> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new UserFacingException("Invalid value for " + key + ".");
    }

    /** The true or false value under this key. Absent, null or any other type is a bad request. */
    public static boolean bool(Map<String, ?> body, String key) {
        if (body.get(key) instanceof Boolean flag) {
            return flag;
        }
        throw new UserFacingException("Invalid value for " + key + ".");
    }
}
