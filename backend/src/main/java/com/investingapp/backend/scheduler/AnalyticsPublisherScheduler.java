package com.investingapp.backend.scheduler;

import com.investingapp.backend.service.AnalyticsPublisherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the analytics outbox.
 *
 * Every two minutes, at second 20 so it does not land on the same tick as the account-status and
 * ACH pollers. The outbox is the source of truth and rows are never deleted, so a missed pass
 * costs latency and nothing else: the next pass picks up exactly what the last one left.
 */
@Component
public class AnalyticsPublisherScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsPublisherScheduler.class);

    private final AnalyticsPublisherService publisher;

    public AnalyticsPublisherScheduler(AnalyticsPublisherService publisher) {
        this.publisher = publisher;
    }

    @Scheduled(cron = "20 */2 * * * *")
    public void publish() {
        try {
            int sent = publisher.publishPending();
            if (sent > 0) {
                logger.info("analytics: published {} outbox row(s)", sent);
            }
        } catch (Exception e) {
            // Same posture as the other schedulers: log and let the next tick retry. The rows are
            // still PENDING, so nothing is lost by failing here.
            logger.error("analytics: outbox publish pass failed", e);
        }
    }
}
