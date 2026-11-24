package com.investingapp.backend.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default outside prod: the alert goes to the log at WARN only. BrokerEnvironmentGuard refuses it in prod. */
@Component
@ConditionalOnProperty(name = "app.ops.alert.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingOpsAlertSender implements OpsAlertSender {

    private static final Logger logger = LoggerFactory.getLogger(LoggingOpsAlertSender.class);

    @Override
    public String send(OpsAlertMessage alert) {
        logger.warn("ops.alert type={} subject={} message={} context={} at={}",
                alert.type(), alert.subjectId(), alert.message(), alert.context(), alert.raisedAt());
        return OpsAlertService.DELIVERY_LOGGED;
    }
}
