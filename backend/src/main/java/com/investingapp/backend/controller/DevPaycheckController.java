package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.PaycheckReconcileService;
import com.investingapp.backend.service.PaycheckSyncPipeline;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.ShadowReportService;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.CustomSandboxTransaction;
import com.plaid.client.model.SandboxItemFireWebhookRequest;
import com.plaid.client.model.SandboxItemFireWebhookResponse;
import com.plaid.client.model.SandboxItemResetLoginRequest;
import com.plaid.client.model.SandboxItemResetLoginResponse;
import com.plaid.client.model.SandboxTransactionsCreateRequest;
import com.plaid.client.model.SandboxTransactionsCreateResponse;
import com.plaid.client.model.WebhookType;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiFunction;

/** Spec 12.2 dev verbs. No method opens a transaction; Plaid first, then T14's pipeline outside any. */
@RestController
@RequestMapping("/api/dev/paycheck")
@CrossOrigin(origins = "*")
@Profile({"local", "dev", "test", "ci"})
public class DevPaycheckController {

    private static final Logger logger = LoggerFactory.getLogger(DevPaycheckController.class);
    static final String PROFILES_HEADER = "X-Fred-Profiles";
    static final int MAX_PER_CALL = 10;
    static final int MAX_DAYS_BACK = 730;
    static final int MAX_DESCRIPTION = 255;
    static final Map<String, WebhookType> FIREABLE = Map.of(
            "SYNC_UPDATES_AVAILABLE", WebhookType.TRANSACTIONS, "RECURRING_TRANSACTIONS_UPDATE", WebhookType.TRANSACTIONS,
            "LOGIN_REPAIRED", WebhookType.ITEM, "PENDING_DISCONNECT", WebhookType.ITEM,
            "USER_PERMISSION_REVOKED", WebhookType.ITEM, "ERROR", WebhookType.ITEM);

    public record SandboxPaycheckRequest(BigDecimal amount, Integer count, String description, Integer cadenceDays,
                                         Integer endDaysAgo, Boolean sync) {
    }

    public record FireRequest(String code) {
    }

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final PlaidApi plaidApi;
    private final PlaidGateway plaidGateway;
    private final PaycheckSyncPipeline pipeline;
    private final PaycheckReconcileService reconcileService;
    private final ShadowReportService shadowReportService;
    private final Environment environment;

    public DevPaycheckController(UserRepository userRepository, EncryptionService encryptionService, PlaidApi plaidApi,
                                 PlaidGateway plaidGateway, PaycheckSyncPipeline pipeline,
                                 PaycheckReconcileService reconcileService, ShadowReportService shadowReportService,
                                 Environment environment) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.plaidApi = plaidApi;
        this.plaidGateway = plaidGateway;
        this.pipeline = pipeline;
        this.reconcileService = reconcileService;
        this.shadowReportService = shadowReportService;
        this.environment = environment;
    }

    @PostMapping("/{userId}/sandbox/paycheck")
    public ResponseEntity<?> sandboxPaycheck(@PathVariable Long userId, @RequestBody SandboxPaycheckRequest body) {
        String invalid = validate(body);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(new ErrorCodeResponse("INVALID_FIXTURE", invalid));
        }
        return withItem(userId, (user, token) -> {
            SandboxTransactionsCreateRequest request = new SandboxTransactionsCreateRequest().accessToken(token)
                    .transactions(fixture(body, user.getPlaidAccountId(), EasternTime.today()));
            PlaidCallResult<SandboxTransactionsCreateResponse> created =
                    plaidGateway.call("sandbox_transactions_create", plaidApi.sandboxTransactionsCreate(request));
            if (!created.ok()) {
                return plaidRefused(created.error());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", body.count());
            if (Boolean.FALSE.equals(body.sync())) {
                result.put("syncStatus", "NOT_RUN");
            } else {
                PipelineOutcome outcome = pipeline.run(userId, user.getPlaidItemId(), SyncReason.MANUAL);
                result.put("syncStatus", outcome == null || outcome.sync() == null ? null : outcome.sync().status());
                result.put("detectionState", outcome == null || outcome.evaluation() == null ? null : outcome.evaluation().state());
            }
            logger.info("dev.paycheck.sandbox_fixture count={} sync_status={}", body.count(), result.get("syncStatus"));
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/{userId}/sandbox/fire")
    public ResponseEntity<?> fire(@PathVariable Long userId, @RequestBody FireRequest body) {
        String code = body == null || body.code() == null ? "" : body.code().trim().toUpperCase(Locale.ROOT);
        WebhookType type = FIREABLE.get(code);
        if (type == null) {
            return ResponseEntity.badRequest().body(new ErrorCodeResponse("UNSUPPORTED_CODE", "code must be one of " + new TreeSet<>(FIREABLE.keySet())));
        }
        return withItem(userId, (user, token) -> {
            PlaidCallResult<SandboxItemFireWebhookResponse> fired = plaidGateway.call("sandbox_item_fire_webhook",
                    plaidApi.sandboxItemFireWebhook(new SandboxItemFireWebhookRequest().accessToken(token)
                            .webhookType(type).webhookCode(SandboxItemFireWebhookRequest.WebhookCodeEnum.valueOf(code))));
            if (!fired.ok()) {
                return plaidRefused(fired.error());
            }
            logger.info("dev.paycheck.sandbox_fire code={}", code);
            return ResponseEntity.ok(Map.of("code", code, "fired", Boolean.TRUE.equals(fired.body().getWebhookFired())));
        });
    }

    @PostMapping("/{userId}/sandbox/reset-login")
    public ResponseEntity<?> resetLogin(@PathVariable Long userId) {
        return withItem(userId, (user, token) -> {
            PlaidCallResult<SandboxItemResetLoginResponse> reset = plaidGateway.call("sandbox_item_reset_login",
                    plaidApi.sandboxItemResetLogin(new SandboxItemResetLoginRequest().accessToken(token)));
            if (!reset.ok()) {
                return plaidRefused(reset.error());
            }
            return ResponseEntity.ok(Map.of("resetLogin", Boolean.TRUE.equals(reset.body().getResetLogin())));
        });
    }

    @PostMapping("/{userId}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable Long userId, @RequestParam(value = "date", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (userRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reconcileService.runForUser(userId, date != null ? date : EasternTime.today()));
    }

    @GetMapping("/shadow-report")
    public ResponseEntity<ShadowReport> shadowReport(@RequestParam(value = "since", required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return ResponseEntity.ok().header(PROFILES_HEADER, String.join(",", environment.getActiveProfiles()))
                .body(shadowReportService.build(since));
    }

    private ResponseEntity<?> withItem(Long userId, BiFunction<User, String, ResponseEntity<?>> action) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = found.get();
        if (user.getPlaidAccessToken() == null || user.getPlaidItemId() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse("NO_ITEM", "no linked Plaid Item"));
        }
        return action.apply(user, encryptionService.decrypt(user.getPlaidAccessToken()));
    }

    static String validate(SandboxPaycheckRequest body) {
        if (body == null) {
            return "body is required";
        }
        if (body.amount() == null || body.amount().signum() <= 0 || body.amount().scale() > 2) {
            return "amount: positive, at most 2 decimals";
        }
        if (body.count() == null || body.count() < 1 || body.count() > MAX_PER_CALL) {
            return "count: 1 to 10";
        }
        if (body.description() == null || body.description().isBlank() || body.description().length() > MAX_DESCRIPTION) {
            return "description: 1 to 255 characters";
        }
        if (body.cadenceDays() == null || body.cadenceDays() < 1 || body.cadenceDays() > 31) {
            return "cadenceDays: 1 to 31";
        }
        int end = body.endDaysAgo() == null ? 0 : body.endDaysAgo();
        if (end < 0 || end + (long) (body.count() - 1) * body.cadenceDays() > MAX_DAYS_BACK) {
            return "endDaysAgo: 0 or more, oldest deposit at most 730 days back";
        }
        return null;
    }

    /** Oldest first; a Plaid inflow is negative. */
    static List<CustomSandboxTransaction> fixture(SandboxPaycheckRequest body, String accountId, LocalDate todayET) {
        LocalDate newest = todayET.minusDays(body.endDaysAgo() == null ? 0 : body.endDaysAgo());
        List<CustomSandboxTransaction> rows = new ArrayList<>();
        for (int i = body.count() - 1; i >= 0; i--) {
            LocalDate date = newest.minusDays((long) i * body.cadenceDays());
            CustomSandboxTransaction row = new CustomSandboxTransaction().dateTransacted(date).datePosted(date)
                    .amount(body.amount().negate().doubleValue()).description(body.description()).isoCurrencyCode("USD");
            if (accountId != null) {
                row.accountId(accountId);
            }
            rows.add(row);
        }
        return rows;
    }

    private static ResponseEntity<ErrorCodeResponse> plaidRefused(PlaidApiError error) {
        String code = error == null || error.errorCode() == null ? "PLAID_ERROR" : error.errorCode();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorCodeResponse(code, "Plaid refused; see the plaid.call log line"));
    }
}
