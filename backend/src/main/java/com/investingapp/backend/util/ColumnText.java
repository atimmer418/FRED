package com.investingapp.backend.util;

/**
 * Fits a string from an outside source (Plaid) to the width of the VARCHAR column it is stored in.
 *
 * <p>MySQL strict mode rejects an over-long value rather than truncating it, and the transaction
 * rolls back. For a sync that sticks the Item on the same page forever, so every Plaid string is cut
 * to its V6 column width before it is set on an entity. A cut never splits a surrogate pair: a
 * character at the boundary is dropped whole instead of being stored as half a character.
 */
public final class ColumnText {

    private ColumnText() {
    }

    /** {@code value} unchanged when it fits (null included), else its first {@code width} chars. */
    public static String fit(String value, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("width must be at least 1, was " + width);
        }
        if (value == null || value.length() <= width) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(width - 1)) ? width - 1 : width;
        return value.substring(0, end);
    }
}
