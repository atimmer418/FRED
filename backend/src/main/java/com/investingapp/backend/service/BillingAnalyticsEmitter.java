package com.investingapp.backend.service;

import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.util.AnalyticsBands;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three billing events, defined and tested but not yet emitted by anything.
 *
 * Billing does not exist in this repository. The advisory fee is collected outside Apple, by ACH
 * from the user's Plaid-linked bank, and a separate billing session owns that work. This class is
 * the contract that work will call: the event names, the exact properties, the banding, and the
 * rule that each must be written inside the transaction that persists the billing state change.
 *
 * There is deliberately NO billing logic here, not even a stub. Nothing creates a trial, charges a
 * fee or cancels anything; these methods only record that it happened.
 *
 * TODO(billing): call trialStarted from the billing session's trial-creation path, in the same
 * transaction that persists the trial record. Likewise subscriptionBilled from the path that
 * records a successful fee collection, and subscriptionCancelled from the one that records a
 * cancellation. Do not call any of them from the profile PUT or from a tier selection: picking a
 * tier is not a trial, and Checkpoint E deliberately left that path alone.
 */
@Service
public class BillingAnalyticsEmitter {

    private final AnalyticsOutboxService outbox;

    public BillingAnalyticsEmitter(AnalyticsOutboxService outbox) {
        this.outbox = outbox;
    }

    /**
     * A trial began. Every trial is {@value ServerAnalyticsEvent#TRIAL_LENGTH_DAYS} days, on every
     * tier, so the length is a constant here rather than a number the caller can get wrong.
     *
     * @param tier the tier the trial is for, as the user chose it
     */
    public AnalyticsOutboxEntry trialStarted(User user, String tier) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tier", AnalyticsOutboxService.tier(userWithTier(user, tier)));
        props.put("trial_length_days", ServerAnalyticsEvent.TRIAL_LENGTH_DAYS);
        return outbox.record(ServerAnalyticsEvent.TRIAL_STARTED, user, props);
    }

    /**
     * A fee was collected. No amount, no currency, no payment reference: the funnel question is
     * who converts and who keeps paying, and the ledger for the money itself is the billing
     * session's own.
     */
    public AnalyticsOutboxEntry subscriptionBilled(User user, String tier, boolean isFirstBilling) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tier", AnalyticsOutboxService.tier(userWithTier(user, tier)));
        props.put("is_first_billing", isFirstBilling);
        return outbox.record(ServerAnalyticsEvent.SUBSCRIPTION_BILLED, user, props);
    }

    /**
     * A subscription was cancelled.
     *
     * @param trialStartedAt when the trial began, for the banded time-to-cancel. Null is reported
     *                       as 'unknown' rather than guessed at.
     */
    public AnalyticsOutboxEntry subscriptionCancelled(User user, String tier,
                                                      LocalDateTime trialStartedAt) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tier", AnalyticsOutboxService.tier(userWithTier(user, tier)));
        props.put("days_since_trial_start_band",
                AnalyticsBands.daysBetween(trialStartedAt, LocalDateTime.now()));
        return outbox.record(ServerAnalyticsEvent.SUBSCRIPTION_CANCELLED, user, props);
    }

    /**
     * The tier narrowing lives in one place, and it reads the User. When billing passes an
     * explicit tier (the one being billed, which may differ from the one on the profile), this
     * borrows that narrowing without mutating the stored user.
     */
    private User userWithTier(User user, String tier) {
        if (tier == null) {
            return user;
        }
        User view = new User();
        view.setSelectedTier(tier);
        return view;
    }
}
