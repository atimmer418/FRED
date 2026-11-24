package com.investingapp.backend.ops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every gauge of spec 12.3 the schema can answer, read in one pass by {@link OpsGaugeService} and
 * sent whole in the heartbeat digest. Values are counts, closed-vocabulary bucket names (detection
 * states, Item statuses, hold reasons) and one boolean. Nothing here is an amount, a descriptor, a
 * payer name, an id or a date, because the context leaves the building through the alert provider.
 *
 * <p>paycheck.users counts schedules carrying a detection mirror (ruling R1), so a shadow user
 * with no schedule row appears under plaid.items only. The spec's paycheck.confirm_lag_days is
 * not here: no institution id is stored, and the shadow report (T19) carries confirm lag per user.
 */
public record OpsGaugeSnapshot(
        Map<String, Long> paycheckUsersByState,
        Map<String, Long> plaidItemsByStatus,
        long plaidItemsBillable,
        Map<String, Long> holdsByReason,
        long inboxBacklog,
        long inboxFailed,
        long inboxOldestAgeSec,
        long webhooksReceived24h,
        long removalsPending,
        long cyclesShadowConfirmed7d,
        long opsAlerts24h,
        long searchingOver24h,
        long manualReviewOpen,
        boolean reconcileRanYesterday) {

    /** The digest's keys, in the order a reader sees them. Spec 12.3 names where it has a name. */
    public static final List<String> GAUGE_KEYS = List.of(
            "paycheck.users",
            "plaid.items",
            "plaid.items.billable",
            "holds.by_reason",
            "inbox.backlog",
            "inbox.failed",
            "inbox.oldest_age_sec",
            "webhooks.received_24h",
            "removals.pending",
            "cycles.shadow_confirmed_7d",
            "ops_alerts.24h",
            "searching.over_24h",
            "manual_review.open",
            "reconcile.ran_yesterday");

    public OpsGaugeSnapshot {
        paycheckUsersByState = sorted(paycheckUsersByState);
        plaidItemsByStatus = sorted(plaidItemsByStatus);
        holdsByReason = sorted(holdsByReason);
    }

    private static Map<String, Long> sorted(Map<String, Long> buckets) {
        return buckets == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(buckets));
    }

    /** The heartbeat's alert context: every gauge under its {@link #GAUGE_KEYS} name, in that order. */
    public Map<String, Object> toContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paycheck.users", paycheckUsersByState);
        context.put("plaid.items", plaidItemsByStatus);
        context.put("plaid.items.billable", plaidItemsBillable);
        context.put("holds.by_reason", holdsByReason);
        context.put("inbox.backlog", inboxBacklog);
        context.put("inbox.failed", inboxFailed);
        context.put("inbox.oldest_age_sec", inboxOldestAgeSec);
        context.put("webhooks.received_24h", webhooksReceived24h);
        context.put("removals.pending", removalsPending);
        context.put("cycles.shadow_confirmed_7d", cyclesShadowConfirmed7d);
        context.put("ops_alerts.24h", opsAlerts24h);
        context.put("searching.over_24h", searchingOver24h);
        context.put("manual_review.open", manualReviewOpen);
        context.put("reconcile.ran_yesterday", reconcileRanYesterday);
        return context;
    }
}
