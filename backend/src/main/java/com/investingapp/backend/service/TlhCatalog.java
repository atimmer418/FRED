package com.investingapp.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * In-code TLH reference data: the curated replacement-pair map and the
 * substantially-identical identity groups. In-code (not DB-seeded) because
 * the codebase has no migration tooling (ddl-auto=update cannot seed rows) —
 * same precedent as ELIGIBLE_TIERS and Portfolio.createDefaultPortfolio.
 *
 * Replacement pairs: every pair tracks a DIFFERENT underlying index — the
 * industry-standard "not substantially identical" line. Bidirectional, so a
 * replacement holding is itself harvestable back later. Compliance sign-off
 * on this list is a go-live gate.
 *
 * Identity groups are the negative space: symbols that may NEVER replace one
 * another and across which wash windows and harvest vetoes expand (a window
 * on VOO blocks SPY/IVV/SPLG buys).
 */
@Component
public class TlhCatalog {

    private static final Logger logger = LoggerFactory.getLogger(TlhCatalog.class);

    private static final int MIN_COOLDOWN_DAYS = 32;   // must exceed the 31-day wash horizon
    private static final BigDecimal MIN_BUFFER_PERCENT = new BigDecimal("0.25");

    /**
     * symbol → ranked replacement candidates. Index rationale per pair:
     * VTI (CRSP US Total Market) ↔ ITOT (S&P Total Market), then SCHB (Dow Jones US Broad Market);
     * VXUS (FTSE Global All Cap ex-US) ↔ IXUS (MSCI ACWI ex-USA IMI), then VEU (FTSE All-World ex-US);
     * VBR (CRSP US Small Value) ↔ IJS (S&P SmallCap 600 Value), then IWN (Russell 2000 Value).
     * IJS and SLYV both track S&P SmallCap 600 Value, so they are never each
     * other's candidates (see BASE_IDENTITY_GROUPS).
     */
    private static final Map<String, List<String>> BASE_PAIRS = new LinkedHashMap<>();
    static {
        BASE_PAIRS.put("VTI", List.of("ITOT", "SCHB"));
        BASE_PAIRS.put("ITOT", List.of("VTI", "SCHB"));
        BASE_PAIRS.put("SCHB", List.of("VTI", "ITOT"));
        BASE_PAIRS.put("VXUS", List.of("IXUS", "VEU"));
        BASE_PAIRS.put("IXUS", List.of("VXUS", "VEU"));
        BASE_PAIRS.put("VBR", List.of("IJS", "IWN"));
        BASE_PAIRS.put("IJS", List.of("VBR", "IWN"));
        BASE_PAIRS.put("IWN", List.of("VBR", "IJS"));
    }

    /** symbol → identity-group key (same index or same issuer share classes). */
    private static final Map<String, String> BASE_IDENTITY_GROUPS = new LinkedHashMap<>();
    static {
        BASE_IDENTITY_GROUPS.put("SPY", "SP500");
        BASE_IDENTITY_GROUPS.put("VOO", "SP500");
        BASE_IDENTITY_GROUPS.put("IVV", "SP500");
        BASE_IDENTITY_GROUPS.put("SPLG", "SP500");
        BASE_IDENTITY_GROUPS.put("QQQ", "NASDAQ100");
        BASE_IDENTITY_GROUPS.put("QQQM", "NASDAQ100");
        BASE_IDENTITY_GROUPS.put("ITOT", "US_TOTAL_SP");
        BASE_IDENTITY_GROUPS.put("SPTM", "US_TOTAL_SP");
        BASE_IDENTITY_GROUPS.put("IJS", "SP600_VALUE");
        BASE_IDENTITY_GROUPS.put("SLYV", "SP600_VALUE");
        BASE_IDENTITY_GROUPS.put("VIOV", "SP600_VALUE");
        BASE_IDENTITY_GROUPS.put("GOOG", "ALPHABET");
        BASE_IDENTITY_GROUPS.put("GOOGL", "ALPHABET");
        BASE_IDENTITY_GROUPS.put("BRK.A", "BERKSHIRE");
        BASE_IDENTITY_GROUPS.put("BRK.B", "BERKSHIRE");
    }

    private final String extraPairsRaw;
    private final String extraIdentityRaw;
    private final int configuredCooldownDays;
    private final BigDecimal configuredBufferPercent;

    private final Map<String, List<String>> pairs = new LinkedHashMap<>();
    private final Map<String, String> identityGroups = new LinkedHashMap<>();
    private int effectiveCooldownDays;
    private BigDecimal effectiveBufferPercent;

    public TlhCatalog(
            @Value("${app.tlh.extra-pairs:}") String extraPairsRaw,
            @Value("${app.tlh.extra-identity-groups:}") String extraIdentityRaw,
            @Value("${app.tlh.symbol-cooldown-days:45}") int cooldownDays,
            @Value("${app.tlh.price-guard-buffer-percent:0.5}") BigDecimal bufferPercent) {
        this.extraPairsRaw = extraPairsRaw == null ? "" : extraPairsRaw;
        this.extraIdentityRaw = extraIdentityRaw == null ? "" : extraIdentityRaw;
        this.configuredCooldownDays = cooldownDays;
        this.configuredBufferPercent = bufferPercent;
    }

    @PostConstruct
    void initialize() {
        pairs.putAll(BASE_PAIRS);
        identityGroups.putAll(BASE_IDENTITY_GROUPS);

        // "SYM:ALT,SYM2:ALT2" — extras win over the base map on conflict
        Map<String, List<String>> extraPairs = new LinkedHashMap<>();
        for (String entry : extraPairsRaw.split(",")) {
            String[] kv = entry.trim().split(":");
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) continue;
            String symbol = kv[0].trim().toUpperCase();
            String candidate = kv[1].trim().toUpperCase();
            if (symbol.equals(candidate)) {
                logger.warn("[TLH] Ignoring self-mapped replacement pair {}:{}", symbol, candidate);
                continue;
            }
            extraPairs.computeIfAbsent(symbol, s -> new ArrayList<>()).add(candidate);
        }
        pairs.putAll(extraPairs);

        for (String entry : extraIdentityRaw.split(",")) {
            String[] kv = entry.trim().split(":");
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) continue;
            identityGroups.put(kv[0].trim().toUpperCase(), kv[1].trim().toUpperCase());
        }

        // Fail fast: a replacement pair inside one identity group would harvest
        // into a substantially-identical security — direct tax damage.
        for (Map.Entry<String, List<String>> e : pairs.entrySet()) {
            for (String candidate : e.getValue()) {
                String gA = identityGroups.get(e.getKey());
                String gB = identityGroups.get(candidate);
                if (gA != null && gA.equals(gB)) {
                    throw new IllegalStateException("TLH catalog invalid: replacement pair "
                            + e.getKey() + "→" + candidate + " falls inside identity group " + gA);
                }
            }
        }

        effectiveCooldownDays = configuredCooldownDays;
        if (effectiveCooldownDays < MIN_COOLDOWN_DAYS) {
            logger.warn("[TLH] symbol-cooldown-days {} below statutory-safe minimum, clamping to {}",
                    configuredCooldownDays, MIN_COOLDOWN_DAYS);
            effectiveCooldownDays = MIN_COOLDOWN_DAYS;
        }
        effectiveBufferPercent = configuredBufferPercent;
        if (effectiveBufferPercent == null || effectiveBufferPercent.compareTo(MIN_BUFFER_PERCENT) < 0) {
            logger.warn("[TLH] price-guard-buffer-percent {} below minimum, clamping to {}",
                    configuredBufferPercent, MIN_BUFFER_PERCENT);
            effectiveBufferPercent = MIN_BUFFER_PERCENT;
        }
        logger.info("[TLH] Catalog ready: {} replacement-mapped symbols, {} identity-grouped symbols, cooldown {}d, buffer {}%",
                pairs.size(), identityGroups.size(), effectiveCooldownDays, effectiveBufferPercent);
    }

    /** Ranked replacement candidates for a symbol; empty when unmapped. */
    public List<String> replacementCandidates(String symbol) {
        return pairs.getOrDefault(normalize(symbol), List.of());
    }

    /** Identity-group members excluding the symbol itself; empty when ungrouped. */
    public Set<String> identitySiblings(String symbol) {
        String key = normalize(symbol);
        String group = identityGroups.get(key);
        if (group == null) return Set.of();
        Set<String> siblings = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : identityGroups.entrySet()) {
            if (group.equals(e.getValue()) && !e.getKey().equals(key)) {
                siblings.add(e.getKey());
            }
        }
        return siblings;
    }

    /** The symbol plus all of its identity siblings. */
    public Set<String> expandWithSiblings(String symbol) {
        Set<String> expanded = new LinkedHashSet<>();
        expanded.add(normalize(symbol));
        expanded.addAll(identitySiblings(symbol));
        return expanded;
    }

    public int effectiveCooldownDays() {
        return effectiveCooldownDays;
    }

    public BigDecimal effectiveBufferPercent() {
        return effectiveBufferPercent;
    }

    private String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }
}
