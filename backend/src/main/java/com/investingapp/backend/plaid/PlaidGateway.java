package com.investingapp.backend.plaid;

import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.plaid.client.request.PlaidApi;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one way Phase 1 code calls Plaid (spec 2.1). A caller builds its retrofit Call from
 * {@link #api()} and hands it to {@link #call(String, Call)}, which:
 * <ul>
 *   <li>refuses to run inside a database transaction (house rule: no Plaid HTTP inside one);</li>
 *   <li>executes the call on the caller's thread (Phase 1 callers already run on plaidExecutor or a
 *       scheduler thread);</li>
 *   <li>returns the body on a 2xx with a body, else a {@link PlaidApiError} classified on the exact
 *       error_code; an IOException is a synthetic TRANSIENT error and nothing is thrown for it;</li>
 *   <li>logs one {@code plaid.call} line with op, Plaid request_id, HTTP status and error_code, never
 *       a body, a Plaid message, a token or a cursor (spec 12.3);</li>
 *   <li>feeds {@link PlaidOutageBreaker} and raises {@link OpsAlerts#PLAID_OUTAGE} once, on the fifth
 *       consecutive TRANSIENT or RATE_LIMIT result.</li>
 * </ul>
 *
 * <p>{@code op} is a short constant naming the endpoint: {@code transactions_sync},
 * {@code transactions_recurring_get}, {@code item_get}, {@code item_webhook_update},
 * {@code sandbox_transactions_create}, {@code sandbox_item_fire_webhook},
 * {@code sandbox_item_reset_login}. Retry policy per class belongs to the caller.
 */
@Component
public class PlaidGateway {

    private static final Logger logger = LoggerFactory.getLogger(PlaidGateway.class);

    static final String NONE = "-";

    private final PlaidApi plaidApi;
    private final PlaidOutageBreaker breaker;
    private final OpsAlertService opsAlertService;

    public PlaidGateway(PlaidApi plaidApi, PlaidOutageBreaker breaker, OpsAlertService opsAlertService) {
        this.plaidApi = plaidApi;
        this.breaker = breaker;
        this.opsAlertService = opsAlertService;
    }

    /** The PlaidApi bean, for building the Call; creating a Call does no I/O, only execute() does. */
    public PlaidApi api() {
        return plaidApi;
    }

    public <T> PlaidCallResult<T> call(String op, Call<T> call) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("plaid.call refused inside a database transaction op=" + op);
        }
        PlaidCallResult<T> result = execute(op, call);
        PlaidErrorClass resultClass = result.ok() ? null : result.error().errorClass();
        if (breaker.record(resultClass)) {
            raiseOutage(op, result.error());
        }
        return result;
    }

    private <T> PlaidCallResult<T> execute(String op, Call<T> call) {
        Response<T> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            logger.warn("plaid.call op={} request_id={} status=0 code={} class={} transport={}",
                    op, NONE, NONE, PlaidErrorClass.TRANSIENT, e.getClass().getSimpleName());
            return PlaidCallResult.failure(PlaidApiError.transport());
        }
        int status = response.code();
        T body = response.body();
        if (response.isSuccessful() && body != null) {
            logger.info("plaid.call op={} request_id={} status={} code={}", op, requestIdOf(body), status, NONE);
            return PlaidCallResult.success(body);
        }
        PlaidApiError apiError = PlaidApiError.fromBody(status, readOnce(response.errorBody()));
        logger.warn("plaid.call op={} request_id={} status={} code={} class={}",
                op, orNone(apiError.requestId()), status, orNone(apiError.errorCode()), apiError.errorClass());
        return PlaidCallResult.failure(apiError);
    }

    /** okhttp drains the body on the first string(); this is the only read. */
    private static String readOnce(ResponseBody errorBody) {
        if (errorBody == null) {
            return null;
        }
        try {
            return errorBody.string();
        } catch (IOException e) {
            return null;
        }
    }

    private void raiseOutage(String op, PlaidApiError last) {
        String code = orNone(last.errorCode());
        String outageMessage = "Plaid returned " + PlaidOutageBreaker.THRESHOLD
                + " consecutive transient or rate-limit results; last op=" + op + " code=" + code;
        Map<String, Object> outageContext = new LinkedHashMap<>();
        outageContext.put("op", op);
        outageContext.put("code", code);
        outageContext.put("http_status", last.httpStatus());
        outageContext.put("consecutive", PlaidOutageBreaker.THRESHOLD);
        logger.error("alert.plaid.outage op={} code={} consecutive={}", op, code, PlaidOutageBreaker.THRESHOLD);
        opsAlertService.raise(OpsAlerts.PLAID_OUTAGE, OpsAlertService.GLOBAL_SUBJECT, outageMessage, outageContext);
    }

    /** Every plaid-java response model has getRequestId(); anything else logs "-". */
    static String requestIdOf(Object body) {
        try {
            Object id = body.getClass().getMethod("getRequestId").invoke(body);
            return id == null ? NONE : id.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return NONE;
        }
    }

    private static String orNone(String value) {
        return value == null ? NONE : value;
    }
}
