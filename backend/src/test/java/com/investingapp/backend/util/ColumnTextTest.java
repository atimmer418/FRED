package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL strict mode rejects an over-long value instead of truncating it, and the whole transaction
 * rolls back; for a sync that means the same page is refused on every retry. T07 and T10 pass every
 * Plaid string through ColumnText.fit at its V6 width, so the cut has to be exact and safe.
 */
class ColumnTextTest {

    @Test
    void nullStaysNull() {
        assertThat(ColumnText.fit(null, 10)).isNull();
    }

    @Test
    void valueWithinWidthIsReturnedUnchanged() {
        assertThat(ColumnText.fit("ACME PAYROLL", 12)).isEqualTo("ACME PAYROLL");
        assertThat(ColumnText.fit("ACME", 255)).isEqualTo("ACME");
        assertThat(ColumnText.fit("", 5)).isEmpty();
    }

    @Test
    void longValueIsCutToWidth() {
        assertThat(ColumnText.fit("X".repeat(300), 255)).hasSize(255);
        assertThat(ColumnText.fit("ACME PAYROLL", 4)).isEqualTo("ACME");
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        // U+1F4B0 is two UTF-16 units at index 2 and 3. A cut at 3 would keep half of it.
        // Written as escapes so the test does not depend on the compiler's source encoding.
        String value = "ab💰cd";
        assertThat(ColumnText.fit(value, 3)).isEqualTo("ab");
        assertThat(ColumnText.fit(value, 4)).isEqualTo("ab💰");
    }

    @Test
    void widthBelowOneIsRejected() {
        assertThatThrownBy(() -> ColumnText.fit("a", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
