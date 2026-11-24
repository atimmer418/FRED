package com.investingapp.backend.plaid;

import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.support.RecordingTransactionManager;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PlaidGateway: typed result, classification on the exact code, one read of the error body,
 * IOException as TRANSIENT, the five-strike outage alert, and no call inside a transaction.
 */
class PlaidGatewayTest {

    private static final MediaType JSON = MediaType.parse("application/json");

    private PlaidOutageBreaker breaker;
    private OpsAlertService opsAlertService;
    private PlaidGateway gateway;

    @BeforeEach
    void setUp() {
        breaker = new PlaidOutageBreaker();
        opsAlertService = mock(OpsAlertService.class);
        gateway = new PlaidGateway(mock(PlaidApi.class), breaker, opsAlertService);
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> answering(Response<T> response) {
        Call<T> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(response);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return call;
    }

    /** error_type is ITEM_ERROR for every code on purpose: classification must ignore it. */
    private static <T> Call<T> plaidError(int status, String errorCode) {
        return answering(Response.error(status, ResponseBody.create(
                "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"" + errorCode + "\","
                        + "\"error_message\":\"Bank of Sentinel is down\",\"request_id\":\"req-" + errorCode + "\"}",
                JSON)));
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> transportFailure() {
        Call<T> call = mock(Call.class);
        try {
            when(call.execute()).thenThrow(new IOException("connection reset"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return call;
    }

    /** okhttp's ResponseBody.string() opens source() once per read, so source() calls count reads. */
    private static final class CountingBody extends ResponseBody {
        private final String json;
        private int sources;

        CountingBody(String json) {
            this.json = json;
        }

        @Override
        public MediaType contentType() {
            return JSON;
        }

        @Override
        public long contentLength() {
            return json.length();
        }

        @Override
        public BufferedSource source() {
            sources++;
            return new Buffer().writeUtf8(json);
        }
    }

    @Test
    void successReturnsTheBodyAndResetsTheBreaker() {
        for (int i = 0; i < 4; i++) {
            breaker.record(PlaidErrorClass.TRANSIENT);
        }
        ItemGetResponse body = new ItemGetResponse().requestId("req-ok-1");

        PlaidCallResult<ItemGetResponse> result = gateway.call("item_get", answering(Response.success(body)));

        assertThat(result.ok()).isTrue();
        assertThat(result.body()).isSameAs(body);
        assertThat(result.error()).isNull();
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("the success reset the count").isFalse();
        }
        assertThat(breaker.tripped()).isFalse();
        verifyNoInteractions(opsAlertService);
    }

    @Test
    void classifiesOnExactCodeNeverHttpStatus() {
        PlaidCallResult<TransactionsSyncResponse> broken = gateway.call("transactions_sync", plaidError(500, "ITEM_LOGIN_REQUIRED"));

        assertThat(broken.ok()).isFalse();
        assertThat(broken.error()).isEqualTo(new PlaidApiError("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", null,
                "req-ITEM_LOGIN_REQUIRED", 500, PlaidErrorClass.ITEM_BROKEN));
        PlaidCallResult<Object> down = gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));
        assertThat(down.error().errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        PlaidCallResult<Object> mutation = gateway.call("transactions_sync", plaidError(400, "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"));
        assertThat(mutation.error().errorClass()).isEqualTo(PlaidErrorClass.MUTATION);
        PlaidCallResult<Object> gone = gateway.call("transactions_sync", plaidError(429, "INSTITUTION_NO_LONGER_SUPPORTED"));
        assertThat(gone.error().errorClass()).isEqualTo(PlaidErrorClass.PERMANENT);
    }

    @Test
    void unparsable5xxIsTransient() {
        PlaidCallResult<Object> result = gateway.call("transactions_sync", answering(Response.error(503,
                ResponseBody.create("<html>Service Unavailable</html>", MediaType.parse("text/html")))));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(new PlaidApiError(null, null, null, null, 503, PlaidErrorClass.TRANSIENT));
    }

    @Test
    void errorBodyReadOnce() {
        CountingBody body = new CountingBody("{\"error_type\":\"TRANSACTIONS_ERROR\","
                + "\"error_code\":\"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION\",\"request_id\":\"req-m\"}");

        PlaidCallResult<Object> result = gateway.call("transactions_sync", answering(Response.error(400, body)));

        assertThat(result.error().errorCode()).isEqualTo("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION");
        assertThat(result.error().requestId()).isEqualTo("req-m");
        assertThat(body.sources).as("errorBody().string() called once").isEqualTo(1);
    }

    @Test
    void ioExceptionIsTransient() {
        PlaidCallResult<Object> result = gateway.call("item_get", transportFailure());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(PlaidApiError.transport());

        for (int i = 0; i < 4; i++) {
            gateway.call("item_get", transportFailure());
        }
        verify(opsAlertService, times(1)).raise(eq(OpsAlerts.PLAID_OUTAGE), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
    }

    @Test
    void successWithEmptyBodyIsAPermanentError() {
        PlaidCallResult<ItemGetResponse> result = gateway.call("item_get", answering(Response.success((ItemGetResponse) null)));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(new PlaidApiError(null, null, null, null, 200, PlaidErrorClass.PERMANENT));
    }

    @Test
    void tripsAfterFiveConsecutiveTransientOrRateLimit() {
        for (int i = 0; i < 3; i++) {
            gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));
        }
        gateway.call("transactions_sync", plaidError(429, "RATE_LIMIT"));
        verifyNoInteractions(opsAlertService);
        assertThat(breaker.tripped()).isFalse();

        gateway.call("transactions_sync", plaidError(429, "TRANSACTIONS_SYNC_LIMIT"));
        assertThat(breaker.tripped()).isTrue();
        gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));

        verify(opsAlertService, times(1)).raise(eq(OpsAlerts.PLAID_OUTAGE), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void outageAlertCarriesCodesNeverPlaidText() {
        for (int i = 0; i < 5; i++) {
            gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));
        }
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);

        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_OUTAGE), eq(OpsAlertService.GLOBAL_SUBJECT), message.capture(), context.capture());

        assertThat(message.getValue()).contains("op=transactions_sync").contains("code=INSTITUTION_DOWN").doesNotContain("Sentinel");
        assertThat(context.getValue()).containsExactly(entry("op", "transactions_sync"), entry("code", "INSTITUTION_DOWN"),
                entry("http_status", 400), entry("consecutive", 5));
        assertThat(context.getValue().toString()).doesNotContain("Sentinel");
    }

    @Test
    void refusesToCallInsideATransaction() throws Exception {
        RecordingTransactionManager tx = new RecordingTransactionManager();
        Call<ItemGetResponse> call = answering(Response.success(new ItemGetResponse().requestId("req-never")));

        new TransactionTemplate(tx).executeWithoutResult(status ->
                assertThatThrownBy(() -> gateway.call("item_get", call))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("op=item_get"));

        assertThat(tx.begins.get()).isEqualTo(1);
        verify(call, never()).execute();
        verifyNoInteractions(opsAlertService);
        assertThat(breaker.tripped()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsOnTheCallersThreadOutsideAnyTransaction() throws Exception {
        RecordingTransactionManager tx = new RecordingTransactionManager();
        new TransactionTemplate(tx).executeWithoutResult(status -> { });
        Thread caller = Thread.currentThread();
        AtomicReference<String> seen = new AtomicReference<>();
        Call<ItemGetResponse> call = mock(Call.class);
        when(call.execute()).thenAnswer(invocation -> {
            seen.set((Thread.currentThread() == caller) + " " + tx.open + " "
                    + TransactionSynchronizationManager.isActualTransactionActive());
            return Response.success(new ItemGetResponse().requestId("req-thread"));
        });

        assertThat(gateway.call("item_get", call).ok()).isTrue();

        assertThat(seen.get()).as("same thread, recorder closed, no actual transaction").isEqualTo("true false false");
        assertThat(tx.begins.get()).as("the recorder did run a transaction before the call").isEqualTo(1);
    }

    @Test
    void requestIdReadFromAnyPlaidResponse() {
        assertThat(PlaidGateway.requestIdOf(new ItemGetResponse().requestId("req-7"))).isEqualTo("req-7");
        assertThat(PlaidGateway.requestIdOf(new ItemGetResponse())).isEqualTo("-");
        assertThat(PlaidGateway.requestIdOf("a body with no request id")).isEqualTo("-");
    }
}
