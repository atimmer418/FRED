package com.investingapp.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TlhCatalogTest {

    private TlhCatalog catalog(String extraPairs, String extraIdentity, int cooldown, String buffer) {
        TlhCatalog catalog = new TlhCatalog(extraPairs, extraIdentity, cooldown, new BigDecimal(buffer));
        catalog.initialize();
        return catalog;
    }

    @Test
    void baseSeedPairsAreBidirectionalAndRanked() {
        TlhCatalog catalog = catalog("", "", 45, "0.5");
        assertEquals(List.of("ITOT", "SCHB"), catalog.replacementCandidates("VTI"));
        assertEquals(List.of("VTI", "SCHB"), catalog.replacementCandidates("ITOT"));
        assertEquals(List.of("IJS", "IWN"), catalog.replacementCandidates("VBR"));
        assertTrue(catalog.replacementCandidates("AAPL").isEmpty());
    }

    @Test
    void identitySiblingsExcludeSelfAndCoverShareClasses() {
        TlhCatalog catalog = catalog("", "", 45, "0.5");
        assertEquals(Set.of("VOO", "IVV", "SPLG"), catalog.identitySiblings("SPY"));
        assertEquals(Set.of("GOOGL"), catalog.identitySiblings("GOOG"));
        assertTrue(catalog.identitySiblings("VTI").isEmpty());
        assertEquals(Set.of("QQQ", "QQQM"), catalog.expandWithSiblings("qqq"));
    }

    @Test
    void sameIndexSmallCapValueFundsAreNeverEachOthersCandidates() {
        // IJS and SLYV both track S&P SmallCap 600 Value — the judged seed-data
        // fatal. They share an identity group and no pair may cross it.
        TlhCatalog catalog = catalog("", "", 45, "0.5");
        assertTrue(catalog.identitySiblings("IJS").contains("SLYV"));
        assertFalse(catalog.replacementCandidates("IJS").contains("SLYV"));
        assertFalse(catalog.replacementCandidates("VBR").contains("SLYV"));
    }

    @Test
    void extraPairsWinOverBaseAndSelfMapsAreRejected() {
        TlhCatalog catalog = catalog("VTI:SCHB,VTI:VTI", "", 45, "0.5");
        assertEquals(List.of("SCHB"), catalog.replacementCandidates("VTI"));
    }

    @Test
    void pairInsideAnIdentityGroupFailsStartup() {
        assertThrows(IllegalStateException.class,
                () -> catalog("SPY:VOO", "", 45, "0.5"));
    }

    @Test
    void statutorySafetyClamps() {
        TlhCatalog catalog = catalog("", "", 10, "0.05");
        assertEquals(32, catalog.effectiveCooldownDays());
        assertEquals(0, catalog.effectiveBufferPercent().compareTo(new BigDecimal("0.25")));
    }
}
