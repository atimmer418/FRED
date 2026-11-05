# Task 19 appendix: full code for the compressed parts

This file fills in what `task-19-shadow-report-runbook-and-dev-tools.md` names but does not write out. Step 1 gets the test's import block and its four fixture builders. Step 2 gets the full `ShadowReport.java`, plus the import block and constructor for `ShadowReportService`. Step 3 gets the import block for `DevPaycheckControllerTest`. Step 4 gets the import block and constructor for `DevPaycheckController`. Steps 5 and 7 get the import blocks their test classes leave out. Step 8 gets the exact text of each edit to `AdminPaycheckController` and to T18's `AdminPaycheckControllerTest`. Step 9 gets the whole of `paycheck_lost_webhook.sh`. Everything else in those files is the main file's code, word for word. Where this file says "replace" or "insert", the anchor is quoted from the main file or from T18's code. Nothing here changes behaviour or adds scope. The last section lists the places where the main file's prose and its own code disagree.

## Step 1: `ShadowReportServiceTest` imports and fixture builders

File: `backend/src/test/java/com/investingapp/backend/service/ShadowReportServiceTest.java`. Use this header in place of the main file's bare `package com.investingapp.backend.service;` line. The class body that follows is the main file's Step 1 block, unchanged.

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.dto.admin.ShadowReport.CycleRow;
import com.investingapp.backend.dto.admin.ShadowReport.UserRow;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

The four fixture builders go inside the class, after the `cycles(PaycheckCycle... rows)` helper and before the class's closing brace. Each one sets exactly the `field value` pairs the main file lists, in the same order, through the Lombok setters (T02 for the three paycheck entities and the two new `User` fields; HEAD `User` has `@Getter @Setter`).

```java
    private static User user(Long id, String publicId, String email) {
        User user = new User(email);
        user.setId(id);
        user.setPublicId(publicId);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("ENC:token-" + id);
        user.setPlaidItemId("item-" + id);
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setMonthlyInvestment(650.0);
        user.setPlaidHistoryComplete(true);
        user.setPaycheckAutopilotOptOut(false);
        return user;
    }

    private static PaycheckStream primaryStream() {
        PaycheckStream stream = new PaycheckStream();
        stream.setId(41L);
        stream.setUserId(7L);
        stream.setPlaidAccountId("acc-7");
        stream.setPayerToken("ACME CORP");
        stream.setPayerDisplayName("Acme Corp");
        stream.setRawDescriptor("ACME CORP PAYROLL DIR DEP");
        stream.setState(PaycheckStreamState.PROVISIONAL);
        stream.setRole(PaycheckStreamRole.PRIMARY);
        stream.setCadence(InvestmentFrequency.BIWEEKLY);
        stream.setScore((short) 64);
        stream.setScoreBreakdownJson("{\"pfc\":20,\"plaidStatus\":10,\"median\":10,\"cadenceFit\":14,\"stability\":5,\"payrollToken\":5,\"total\":64}");
        stream.setDepositsFound((short) 6);
        stream.setMissedCount((short) 1);
        stream.setTrailingMedian(new BigDecimal("2450.00"));
        stream.setTrailingCv(new BigDecimal("0.0100"));
        stream.setLastDate(LocalDate.of(2026, 10, 9));
        return stream;
    }

    private static PaycheckCycle cycle(Long eventId, String pull, LocalDateTime confirmedAt) {
        PaycheckCycle cycle = new PaycheckCycle();
        cycle.setUserId(7L);
        cycle.setStreamId(41L);
        cycle.setPeriodKey("2026-10-23");
        cycle.setState(PaycheckCycleState.SKIPPED);
        cycle.setSkipReason(PaycheckSkipReason.SHADOW_CONFIRMED);
        cycle.setExpectedDate(LocalDate.of(2026, 10, 23));
        cycle.setEventId(eventId);
        cycle.setDepositAmount(new BigDecimal("2450.00"));
        cycle.setConfirmedAt(confirmedAt);
        cycle.setAmountToPull(new BigDecimal(pull));
        return cycle;
    }

    private static PaycheckEvent event(LocalDate posted) {
        PaycheckEvent event = new PaycheckEvent();
        event.setPostedDate(posted);
        return event;
    }
```

Why the fixtures meet the test's expectations (so a failing count points at the service, not the fixtures). `shasum -a 256` of `ACME CORP`, cut to 16 characters, is `d2683906516246b0`. The breakdown parts add up to 64. The deposit `2450.00` and the descriptor appear only in fields the report never reads into a row. `dan` has no stubbed schedule or stream, and Mockito's default answer for an `Optional` return is `Optional.empty()` and for a `List` return is an empty list, so he lands on `SEARCHING` with no cycles. The `cycles(...)` stub matches only `eq(7L)`.

## Step 2: `ShadowReport.java` in full

File: `backend/src/main/java/com/investingapp/backend/dto/admin/ShadowReport.java`.

```java
package com.investingapp.backend.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Spec 12.4 / R13 shadow report. Keyed by public_id; no email, descriptor, deposit amount or trailing median. */
public record ShadowReport(LocalDate since, LocalDate generatedOn, boolean shadow, Summary summary, List<UserRow> users) {
    public record Summary(int users, int provisional, int confirmed, int undetectable, int shadowCycles,
            double meanConfirmLagDays) {}
    public record UserRow(String publicId, String detectionState, String itemStatus, String primaryPayerTokenHash,
            Integer score, Map<String, Integer> breakdown, String cadence, int depositsFound, int misses,
            boolean lowConfidence, List<CycleRow> shadowCycles) {}
    /** confirmLagDays is -1 when an Item break purged the event. */
    public record CycleRow(String periodKey, LocalDate expectedDate, LocalDate postedDate, LocalDate confirmedOnET,
            int confirmLagDays, BigDecimal wouldHavePulled, String pullShareBand, boolean passesAffordability) {}
}
```

## Step 2: `ShadowReportService` imports and constructor

File: `backend/src/main/java/com/investingapp/backend/service/ShadowReportService.java`. Use this header in place of the main file's bare `package com.investingapp.backend.service;` line. `DetectionEligibility` is in the same package, so only its nested enum needs an import.

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.dto.admin.ShadowReport.CycleRow;
import com.investingapp.backend.dto.admin.ShadowReport.Summary;
import com.investingapp.backend.dto.admin.ShadowReport.UserRow;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import com.investingapp.backend.paycheck.detect.PrimarySelector;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
```

Replace the line `    // Constructor: mechanical repetition (2) in the header.` with this constructor. It takes one parameter per `private final` field, in declaration order. `objectMapper` is left out because its field initializer assigns it. The order matches the test's `new ShadowReportService(userRepository, scheduleRepository, streamRepository, cycleRepository, eventRepository, props)`.

```java
    public ShadowReportService(UserRepository userRepository, InvestmentScheduleRepository scheduleRepository,
                               PaycheckStreamRepository streamRepository, PaycheckCycleRepository cycleRepository,
                               PaycheckEventRepository eventRepository, PaycheckAutopilotProperties props) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.cycleRepository = cycleRepository;
        this.eventRepository = eventRepository;
        this.props = props;
    }
```

## Step 3: `DevPaycheckControllerTest` imports

File: `backend/src/test/java/com/investingapp/backend/controller/DevPaycheckControllerTest.java`. Use this header in place of the main file's bare `package com.investingapp.backend.controller;` line. Each Plaid model is a single-type import, never `com.plaid.client.model.*`.

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.controller.DevPaycheckController.FireRequest;
import com.investingapp.backend.controller.DevPaycheckController.SandboxPaycheckRequest;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.PaycheckReconcileService;
import com.investingapp.backend.service.PaycheckReconcileService.ReconcileReport;
import com.investingapp.backend.service.PaycheckSyncPipeline;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
```

If Step 0 found `SyncReason`, `SyncStatus`, `SyncOutcome`, `PipelineOutcome` or `ReconcileReport` as a top-level type instead of a nested one, change only that import line to `com.investingapp.backend.service.<Type>` (main file, "Type locations").

## Step 4: `DevPaycheckController` imports and constructor

File: `backend/src/main/java/com/investingapp/backend/controller/DevPaycheckController.java`. Use this header in place of the main file's bare `package com.investingapp.backend.controller;` line. The class uses `outcome.evaluation().state()` through a method chain only, so `EvaluationOutcome` needs no import.

```java
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
```

Replace the line `    // Constructor: mechanical repetition (2) in the header.` with this constructor. It takes one parameter per `private final` field, in declaration order, matching the test's `new DevPaycheckController(userRepository, encryption, plaidApi, gateway, pipeline, reconcile, shadowReports, environment)`. It is the class's only constructor, so Spring injects it without `@Autowired` (the `DevPlaidController` shape at HEAD).

```java
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
```

Step 0's "change only its import" rule applies here too, for `PipelineOutcome` and `SyncReason`.

## Step 5: `DevToolsProdGuardTest` imports

File: `backend/src/test/java/com/investingapp/backend/config/DevToolsProdGuardTest.java`. Use this header in place of the main file's bare `package com.investingapp.backend.config;` line. `DevToolsProdGuard`, its package-private `PROD_PROFILE` and `DevToolsConfig.DEV_TOOL_PROFILES` (HEAD `DevToolsConfig.java:28`) are in the same package and need no import.

```java
package com.investingapp.backend.config;

import com.investingapp.backend.controller.DevPaycheckController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

## Step 7: `AdminPaycheckShadowReportTest` imports

File: `backend/src/test/java/com/investingapp/backend/controller/admin/AdminPaycheckShadowReportTest.java`. Use this header in place of the main file's bare `package com.investingapp.backend.controller.admin;` line. `AdminPaycheckController` and its package-private `VERB_SHADOW_REPORT` are in the same package.

```java
package com.investingapp.backend.controller.admin;

import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.ShadowReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

## Step 8: the exact edits to `AdminPaycheckController` and its T18 test

The anchors below are quoted from T18 Step 9's code. Check them first with `grep -n 'import org.springframework.web.bind.annotation\|import jakarta.servlet.http.HttpServletRequest\|private final AdminAuditService adminAuditService;\|public AdminPaycheckController(' backend/src/main/java/com/investingapp/backend/controller/admin/AdminPaycheckController.java`. The command should print four lines, and the first should be the wildcard `import org.springframework.web.bind.annotation.*;`.

**8a. Imports.** T18 already imports `jakarta.servlet.http.HttpServletRequest`, and its wildcard `org.springframework.web.bind.annotation.*` already covers `GetMapping` and `RequestParam`. Of the main file's list, only the five below are missing. Insert this line after `import com.investingapp.backend.service.AdminPaycheckService.VerbResult;`:

```java
import com.investingapp.backend.service.ShadowReportService;
```

Insert this line before `import com.investingapp.backend.service.AdminAuditService;`:

```java
import com.investingapp.backend.dto.admin.ShadowReport;
```

Insert this line after `import jakarta.servlet.http.HttpServletRequest;`:

```java
import org.springframework.format.annotation.DateTimeFormat;
```

After the last `import org.springframework...` line (the wildcard), add a blank line and then:

```java
import java.time.LocalDate;
import java.util.Map;
```

Only if the grep above shows explicit `org.springframework.web.bind.annotation` imports instead of the wildcard, also add `import org.springframework.web.bind.annotation.GetMapping;` and `import org.springframework.web.bind.annotation.RequestParam;` next to them, if they are not already there.

**8b. The verb constant.** Directly after the line `public class AdminPaycheckController {`, insert:

```java

    static final String VERB_SHADOW_REPORT = "paycheck.shadow-report";
```

**8c. The field.** Replace:

```java
    private final AdminPaycheckService service;
    private final AdminAuditService adminAuditService;
```

with:

```java
    private final AdminPaycheckService service;
    private final AdminAuditService adminAuditService;
    private final ShadowReportService shadowReportService;
```

**8d. The constructor.** Replace:

```java
    public AdminPaycheckController(AdminPaycheckService service, AdminAuditService adminAuditService) {
        this.service = service;
        this.adminAuditService = adminAuditService;
    }
```

with:

```java
    public AdminPaycheckController(AdminPaycheckService service, AdminAuditService adminAuditService,
                                   ShadowReportService shadowReportService) {
        this.service = service;
        this.adminAuditService = adminAuditService;
        this.shadowReportService = shadowReportService;
    }
```

**8e. The method.** Put the main file's `shadowReport(LocalDate since, HttpServletRequest request)` block, unchanged, directly after the closing brace of whichever `@GetMapping` method comes last in the file. In T18's shown order that is `webhooks(...)`, so the method lands just before `    private String actor(HttpServletRequest request) {`.

**8f. T18's `AdminPaycheckControllerTest`.** `grep -rn 'new AdminPaycheckController(' backend/src/test` should print one line, in `controller/admin/AdminPaycheckControllerTest.java`. The new `AdminPaycheckShadowReportTest` builds the controller by reflection, so the grep does not find it. In that file, insert this line after `import com.investingapp.backend.service.AdminPaycheckService.VerbResult;`:

```java
import com.investingapp.backend.service.ShadowReportService;
```

Then replace:

```java
        mvc = MockMvcBuilders.standaloneSetup(new AdminPaycheckController(service, audit)).build();
```

with:

```java
        mvc = MockMvcBuilders.standaloneSetup(new AdminPaycheckController(service, audit, mock(ShadowReportService.class))).build();
```

`mock` already comes from that file's `import static org.mockito.Mockito.*;`. If the grep prints any other line, give that call the same trailing `mock(ShadowReportService.class)` argument and the same import.

## Step 9: `test/api/paycheck_lost_webhook.sh` in full

This is `paycheck_shadow.sh` with the changes Step 9 lists. The shebang, the directory line, the `source` line, the counters and the precondition line are kept as they are. The `shadow=true` check and the `mysql_scalar` block are dropped. Both fixtures use `"amount":1900.00` and `"description":"GLOBEX PAYROLL"`. The second fixture adds `"sync":false`, and its single check becomes two. The last check and the summary line are relabelled.

```bash
#!/bin/bash
# Spec 13.2 paycheck_lost_webhook.sh: "sync":false, then only the reconcile confirms. A true lost-webhook test only without the tunnel.
PAYCHECK_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PAYCHECK_API_DIR/lib/sandbox.sh"
PASS=0; FAIL=0
[ "$(report_field shadowCycles)" = "0" ] || sandbox_skip "user has shadow cycles; relink or use another user."
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":1900.00,"count":6,"description":"GLOBEX PAYROLL","cadenceDays":14,"endDaysAgo":14}'
check "history accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
check "a primary qualifies (PROVISIONAL or CONFIRMED)" wait_for detectionState 'PROVISIONAL|CONFIRMED' 120 reconcile
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":1900.00,"count":1,"description":"GLOBEX PAYROLL","cadenceDays":14,"endDaysAgo":0,"sync":false}'
check "today's deposit created without a sync" [ "$DEV_STATUS" = "200" ]
check "the response says no sync ran" [ "$(printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("syncStatus"))')" = "NOT_RUN" ]
check "the reconcile alone confirms one shadow cycle" wait_for shadowCycles '1' 120 reconcile
echo "---"
echo "Paycheck lost webhook: $PASS passed, $FAIL failed"
exit $FAIL
```

It has five `check` lines, which matches the Verification's `Paycheck lost webhook: 5 passed, 0 failed`. With `FRED_API_BASE` unset, `lib/sandbox.sh` prints `[SKIP] FRED_API_BASE is not set.` and exits 0 before any of these lines run, which matches Step 9's expected output. The `chmod +x`, `bash -n` and skip commands in Step 9 cover this file as written.

## Where the main file's prose and its code disagree

1. Step 8's import list names `jakarta.servlet.http.HttpServletRequest`, `GetMapping` and `RequestParam` as missing. T18's controller code already imports `HttpServletRequest` and the wildcard `org.springframework.web.bind.annotation.*`. Adding them again would still compile (Java ignores a duplicate single-type import of the same type, and a single-type import next to a wildcard is legal), but they are not missing. Section 8a follows the code and adds only `ShadowReport`, `ShadowReportService`, `DateTimeFormat`, `LocalDate` and `Map`.
2. Step 8 describes `VERB_SHADOW_REPORT` as being in "T18's verb style". T18's `VERB_*` constants are `public static final String` on `AdminPaycheckService`. Step 8's own code, and the test that reads it, put a package-private `static final String` on `AdminPaycheckController`, the T17 `AdminPlaidController` and `AdminUserController` shape. The value follows T18's `paycheck.<verb>` naming. Section 8b follows the main file's code.

No other disagreement was found. The constructor parameter orders, test names, error codes (`INVALID_FIXTURE`, `NO_ITEM`, `UNSUPPORTED_CODE`, `PLAID_ERROR`), endpoint paths, `PROFILES_HEADER`, the counts (7, 6, 2, 1, total 16) and the script check counts (shadow 7, webhooks 5, lost webhook 5) are the same in the main file's prose, its code and this appendix.
