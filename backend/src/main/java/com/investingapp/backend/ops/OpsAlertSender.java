package com.investingapp.backend.ops;

/** Chosen by app.ops.alert.provider (the EmailSender port shape); called only on opsAlertExecutor. */
public interface OpsAlertSender {

    /** Returns OpsAlertService.DELIVERY_SENT or DELIVERY_LOGGED; throws when the receiver was not reached. */
    String send(OpsAlertMessage alert) throws Exception;
}
