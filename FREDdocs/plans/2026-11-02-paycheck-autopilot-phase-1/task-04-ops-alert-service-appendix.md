# Task 04 appendix: full code for the compressed steps

This file completes `task-04-ops-alert-service.md`. The main file gives Step 16 (AchRelationshipService, seven alert sites) as one worked site plus a table, and in Steps 15, 17, 18 and 19 it writes some edits as prose ("add the two `ops` imports", "pattern W", "pattern W with `controller` in place of `service`"). Each section below is headed with the main file's step number and gives the exact edit: the text to find at HEAD 137998c (quoted verbatim, unique in its file), then the text that replaces it. Nothing here changes behaviour, names, messages, context keys or the expected test counts of the main file (JobLockServiceTest 7, AchRelationshipServiceTest 20, PlaidItemLifecycleServiceTest 8, PlaidWebhookInboxServiceTest 7, InvestmentExecutionServiceEodBatchTest 11, TradingControllerCloseAccountTest 3). Where the main file already shows a block in full (for example the raise lines in Steps 17 to 19), it is repeated here only as part of a complete before and after so the edit can be applied without reading both files side by side. Paths: `.../` = `backend/src/main/java/com/investingapp/backend/`, `T/` = `backend/src/test/java/com/investingapp/backend/`.

## Step 15: JobLockService and JobLockServiceTest, every edit in full

`.../service/JobLockService.java`

Find:

```java
import com.investingapp.backend.repository.JobLockRepository;
import org.slf4j.Logger;
```

Replace with:

```java
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.JobLockRepository;
import org.slf4j.Logger;
```

Find:

```java
import java.time.ZoneOffset;

/**
```

Replace with:

```java
import java.time.ZoneOffset;
import java.util.Map;

/**
```

Find:

```java
    private final String owner;

    public JobLockService(JobLockRepository jobLockRepository, PlatformTransactionManager transactionManager) {
```

Replace with:

```java
    private final String owner;
    private final OpsAlertService opsAlertService;

    public JobLockService(JobLockRepository jobLockRepository, PlatformTransactionManager transactionManager,
            OpsAlertService opsAlertService) {
```

Find:

```java
        this.owner = runtimeName.length() > 64 ? runtimeName.substring(0, 64) : runtimeName;
    }
```

Replace with:

```java
        this.owner = runtimeName.length() > 64 ? runtimeName.substring(0, 64) : runtimeName;
        this.opsAlertService = opsAlertService;
    }
```

Find:

```java
            logger.error("alert.job_lock.unavailable job={}", name, e);
            return false;
```

Replace with:

```java
            logger.error("alert.job_lock.unavailable job={}", name, e);
            opsAlertService.raise(OpsAlerts.JOB_LOCK_UNAVAILABLE, name, "job_locks unreachable; this run is skipped",
                    Map.of("job", name, "cause", e.getClass().getSimpleName()));
            return false;
```

Find:

```java
            logger.error("alert.job_lock.release_failed job={}", name, e);
        }
```

Replace with:

```java
            logger.error("alert.job_lock.release_failed job={}", name, e);
            opsAlertService.raise(OpsAlerts.JOB_LOCK_RELEASE_FAILED, name, "job_locks release failed; the lease expires on its own",
                    Map.of("job", name, "cause", e.getClass().getSimpleName()));
        }
```

`T/service/JobLockServiceTest.java` (its static imports are the wildcards `org.mockito.ArgumentMatchers.*` and `org.mockito.Mockito.*`, so `eq`, `anyString`, `mock` and `verify` are already in scope)

Find:

```java
import com.investingapp.backend.repository.JobLockRepository;
import org.junit.jupiter.api.BeforeEach;
```

Replace with:

```java
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.JobLockRepository;
import org.junit.jupiter.api.BeforeEach;
```

Find:

```java
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
```

Replace with:

```java
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
```

Find:

```java
    private JobLockService service;

    @BeforeEach
    void setUp() {
        repository = mock(JobLockRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new JobLockService(repository, transactionManager);
    }
```

Replace with:

```java
    private JobLockService service;
    private OpsAlertService opsAlertService;

    @BeforeEach
    void setUp() {
        repository = mock(JobLockRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        opsAlertService = mock(OpsAlertService.class);
        service = new JobLockService(repository, transactionManager, opsAlertService);
    }
```

Find:

```java
        assertFalse(acquired);
        verify(transactionManager).rollback(any());
    }
```

Replace with:

```java
        assertFalse(acquired);
        verify(transactionManager).rollback(any());
        verify(opsAlertService).raise(eq(OpsAlerts.JOB_LOCK_UNAVAILABLE), eq(JobLocks.EOD_BATCH), anyString(),
                eq(Map.of("job", JobLocks.EOD_BATCH, "cause", "DataAccessResourceFailureException")));
    }
```

Find:

```java
        assertDoesNotThrow(() -> service.release(JobLocks.EOD_BATCH));
    }
```

Replace with:

```java
        assertDoesNotThrow(() -> service.release(JobLocks.EOD_BATCH));
        verify(opsAlertService).raise(eq(OpsAlerts.JOB_LOCK_RELEASE_FAILED), eq(JobLocks.EOD_BATCH), anyString(),
                eq(Map.of("job", JobLocks.EOD_BATCH, "cause", "DataAccessResourceFailureException")));
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.JobLockServiceTest' --console=plain`. Expected: `BUILD SUCCESSFUL`, 7 tests, 0 failures.

## Step 16: AchRelationshipService (7 sites) and AchRelationshipServiceTest

`.../service/AchRelationshipService.java` already imports `java.util.Map` (`:24`), so only the two `ops` imports are added.

### 16a. Imports, field, constructor

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
```

Find:

```java
    private final AnalyticsOutboxService analyticsOutboxService;

    public AchRelationshipService(UserRepository userRepository,
```

Replace with:

```java
    private final AnalyticsOutboxService analyticsOutboxService;
    private final OpsAlertService opsAlertService;

    public AchRelationshipService(UserRepository userRepository,
```

Find:

```java
            AnalyticsOutboxService analyticsOutboxService) {
```

Replace with:

```java
            AnalyticsOutboxService analyticsOutboxService,
            OpsAlertService opsAlertService) {
```

Find:

```java
        this.analyticsOutboxService = analyticsOutboxService;
    }
```

Replace with:

```java
        this.analyticsOutboxService = analyticsOutboxService;
        this.opsAlertService = opsAlertService;
    }
```

### 16b. Site `:170`, `alert.ach.adopt_refused` (in `refuse`)

Find:

```java
        logger.error("alert.ach.adopt_refused user={} reason={} rel={}", user.getId(), reason, rel.id());
```

Replace with:

```java
        logger.error("alert.ach.adopt_refused user={} reason={} rel={}", user.getId(), reason, rel.id());
        opsAlertService.raise(OpsAlerts.ACH_ADOPT_REFUSED, String.valueOf(user.getId()),
                "ACH relationship adoption refused; schedule held for MANUAL_REVIEW",
                Map.of("user", String.valueOf(user.getId()), "reason", reason.name()));
```

### 16c. Site `:197`, `alert.ach.cancel_failed` stage list (in `cancelAllActive`, the catch)

Find:

```java
            logger.error("alert.ach.cancel_failed user={} stage=list error={}", user.getId(), e.getMessage());
```

Replace with:

```java
            logger.error("alert.ach.cancel_failed user={} stage=list error={}", user.getId(), e.getMessage());
            opsAlertService.raise(OpsAlerts.ACH_CANCEL_FAILED, String.valueOf(user.getId()),
                    "listing ACH relationships for the bank-change cancel failed",
                    Map.of("user", String.valueOf(user.getId()), "stage", "list"));
```

### 16d. Site `:205`, `alert.ach.cancel_failed` stage delete (in `cancelAllActive`, the loop's `else`)

Find:

```java
                logger.error("alert.ach.cancel_failed user={} stage=delete rel={}", user.getId(), rel.id());
```

Replace with:

```java
                logger.error("alert.ach.cancel_failed user={} stage=delete rel={}", user.getId(), rel.id());
                opsAlertService.raise(OpsAlerts.ACH_CANCEL_FAILED, String.valueOf(user.getId()),
                        "deleting an ACH relationship for the bank-change cancel failed",
                        Map.of("user", String.valueOf(user.getId()), "stage", "delete"));
```

### 16e. Site `:233`, `alert.ach.poll_list_failed` (in `ensureRelationship`, the catch)

Find:

```java
            logger.error("alert.ach.poll_list_failed user={} error={}", user.getId(), e.getMessage());
```

Replace with:

```java
            logger.error("alert.ach.poll_list_failed user={} error={}", user.getId(), e.getMessage());
            opsAlertService.raise(OpsAlerts.ACH_POLL_LIST_FAILED, String.valueOf(user.getId()),
                    "listing ACH relationships for the 15-minute poll failed",
                    Map.of("user", String.valueOf(user.getId()), "stage", "poll_list"));
```

### 16f. Site `:243-244`, `alert.ach.unknown_relationship` (in `ensureRelationship`)

The raise goes under the second line of the two-line `logger.error` call, two lines below the `alert.` line, inside the three-line window `AlertSitesRoutedTest` allows. The context carries the count only, never the relationship ids.

Find:

```java
            logger.error("alert.ach.unknown_relationship user={} ids={}", user.getId(),
                    existing.stream().map(AchRelationshipSummary::id).toList());
```

Replace with:

```java
            logger.error("alert.ach.unknown_relationship user={} ids={}", user.getId(),
                    existing.stream().map(AchRelationshipSummary::id).toList());
            opsAlertService.raise(OpsAlerts.ACH_UNKNOWN_RELATIONSHIP, String.valueOf(user.getId()),
                    "an ACH relationship FRED did not create sits on the account; the poll is waiting",
                    Map.of("user", String.valueOf(user.getId()), "count", existing.size()));
```

### 16g. Site `:256`, `alert.ach.cancel_stuck` (in `ensureRelationship`)

Find:

```java
            logger.error("alert.ach.cancel_stuck user={} rel={}", user.getId(), previousId);
```

Replace with:

```java
            logger.error("alert.ach.cancel_stuck user={} rel={}", user.getId(), previousId);
            opsAlertService.raise(OpsAlerts.ACH_CANCEL_STUCK, String.valueOf(user.getId()),
                    "previous ACH relationship still CANCEL_REQUESTED after 24 hours",
                    Map.of("user", String.valueOf(user.getId())));
```

### 16h. Site `:268`, `alert.ach.create_failed` (in `createAndAdopt`)

Find:

```java
            logger.error("alert.ach.create_failed user={} error={}", user.getId(), result == null ? "null result" : result.get("error"));
```

Replace with:

```java
            logger.error("alert.ach.create_failed user={} error={}", user.getId(), result == null ? "null result" : result.get("error"));
            opsAlertService.raise(OpsAlerts.ACH_CREATE_FAILED, String.valueOf(user.getId()),
                    "creating the ACH relationship failed; the poll retries in 15 minutes",
                    Map.of("user", String.valueOf(user.getId()), "stage", "create"));
```

### 16i. AchRelationshipServiceTest: wiring

`T/service/AchRelationshipServiceTest.java` already imports `java.util.Map`, `anyString`, `eq`, `times` and `verify`.

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
```

Find:

```java
    private final AnalyticsOutboxService analyticsOutboxService = mock(AnalyticsOutboxService.class);
```

Replace with:

```java
    private final AnalyticsOutboxService analyticsOutboxService = mock(AnalyticsOutboxService.class);
    private final OpsAlertService opsAlertService = mock(OpsAlertService.class);
```

Find:

```java
                plaidToAlpaca, analyticsOutboxService);
```

Replace with:

```java
                plaidToAlpaca, analyticsOutboxService, opsAlertService);
```

### 16j. AchRelationshipServiceTest: one check per site that an existing test reaches

No new test method is added (the main file pins the class at 20 tests). Each verification below is appended to the existing test that already drives that site; the user in `setUp` has id 7.

Site `:170`, in `adoptRefusesMaskMismatch` (the main file's anchor line `assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);` occurs three times in the file, so this anchor adds the line above it to make it unique). Find:

```java
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
    }
```

Replace with:

```java
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
        verify(opsAlertService).raise(eq(OpsAlerts.ACH_ADOPT_REFUSED), eq("7"), anyString(),
                eq(Map.of("user", "7", "reason", "REFUSED_MASK_MISMATCH")));
    }
```

Site `:197`, in `cancelSurvivesAlpacaOutage` (the `ResourceAccessException` is a `RestClientException`, so the list catch fires). Find:

```java
        assertThat(user.getAlpacaAchStatus()).isNull();
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
    }
```

Replace with:

```java
        assertThat(user.getAlpacaAchStatus()).isNull();
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        verify(opsAlertService).raise(eq(OpsAlerts.ACH_CANCEL_FAILED), eq("7"), anyString(),
                eq(Map.of("user", "7", "stage", "list")));
    }
```

Site `:205`: no existing test reaches it (every test that lists active relationships stubs `deleteAchRelationship` to `true`). Its test is `AlertSitesRoutedTest.everyAlertLineIsRoutedThroughOpsAlertService`, which fails if the raise is missing under the line.

Site `:233`: no existing test reaches it (no test makes `listAchRelationships` throw inside `ensureRelationship`). Its test is `AlertSitesRoutedTest.everyAlertLineIsRoutedThroughOpsAlertService`.

Site `:243-244`, in `unknownRelationshipMakesThePollWait` (one unknown relationship, so the count is 1). Find:

```java
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
    }
}
```

Replace with:

```java
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        verify(opsAlertService).raise(eq(OpsAlerts.ACH_UNKNOWN_RELATIONSHIP), eq("7"), anyString(),
                eq(Map.of("user", "7", "count", 1)));
    }
}
```

Site `:256`, in `retryWaitsForCancel` (the first poll is inside 24 hours and only logs `ach.poll.waiting`; the second, with the link stamped 25 hours ago, alerts, so exactly one raise). Find:

```java
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
    }

    @Test
    void pollRetriesDeferredCancel() {
```

Replace with:

```java
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        verify(opsAlertService, times(1)).raise(eq(OpsAlerts.ACH_CANCEL_STUCK), eq("7"), anyString(),
                eq(Map.of("user", "7")));
    }

    @Test
    void pollRetriesDeferredCancel() {
```

Site `:268`, in `conflictFromCreateIsNotAdopted` (the create result carries `error` and no `id`). Find:

```java
        service.ensureRelationship(user);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getAchRequestId()).isNull();
    }
```

Replace with:

```java
        service.ensureRelationship(user);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getAchRequestId()).isNull();
        verify(opsAlertService).raise(eq(OpsAlerts.ACH_CREATE_FAILED), eq("7"), anyString(),
                eq(Map.of("user", "7", "stage", "create")));
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AchRelationshipServiceTest' --console=plain`. Expected: `BUILD SUCCESSFUL`, 20 tests, 0 failures.

## Step 17: PlaidItemLifecycleService, PlaidWebhookInboxService and their tests, every edit in full

### 17a. `.../service/PlaidItemLifecycleService.java`

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidItemRemovalRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidItemRemovalRepository;
```

Find:

```java
import java.util.List;

/**
 * Plaid Item lifecycle outside the exchange transaction (spec 2.4 step 6, 2.9).
```

Replace with:

```java
import java.util.List;
import java.util.Map;

/**
 * Plaid Item lifecycle outside the exchange transaction (spec 2.4 step 6, 2.9).
```

Find:

```java
    private final String plaidWebhookUrl;

    public PlaidItemLifecycleService(PlaidItemRemovalRepository removalRepository,
```

Replace with:

```java
    private final String plaidWebhookUrl;
    private final OpsAlertService opsAlertService;

    public PlaidItemLifecycleService(PlaidItemRemovalRepository removalRepository,
```

Find:

```java
            @Value("${plaid.webhook.url}") String plaidWebhookUrl) {
```

Replace with:

```java
            @Value("${plaid.webhook.url}") String plaidWebhookUrl,
            OpsAlertService opsAlertService) {
```

Find:

```java
        this.plaidWebhookUrl = plaidWebhookUrl;
    }
```

Replace with:

```java
        this.plaidWebhookUrl = plaidWebhookUrl;
        this.opsAlertService = opsAlertService;
    }
```

Find:

```java
                logger.error("alert.plaid.item_remove.exhausted id={} item={} attempts={} lastError={}",
                        row.getId(), row.getItemId(), row.getAttempts(), row.getLastError());
```

Replace with:

```java
                logger.error("alert.plaid.item_remove.exhausted id={} item={} attempts={} lastError={}",
                        row.getId(), row.getItemId(), row.getAttempts(), row.getLastError());
                opsAlertService.raise(OpsAlerts.PLAID_ITEM_REMOVE_EXHAUSTED, String.valueOf(row.getId()),
                        "Plaid /item/remove retries exhausted after 7 days; the Item may still bill",
                        Map.of("id", String.valueOf(row.getId()), "item", String.valueOf(row.getItemId()),
                                "attempts", row.getAttempts()));
```

### 17b. `T/service/PlaidItemLifecycleServiceTest.java`

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidItemRemovalRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidItemRemovalRepository;
```

Find:

```java
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
```

Replace with:

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
```

Find:

```java
    private PlaidItemLifecycleService service;

    @BeforeEach
```

Replace with:

```java
    private PlaidItemLifecycleService service;
    private OpsAlertService opsAlertService;

    @BeforeEach
```

Find:

```java
        service = new PlaidItemLifecycleService(removalRepository, userRepository, plaidApi, encryptionService,
                schedulerGate, jobLockService, mock(PlatformTransactionManager.class), WEBHOOK);
```

Replace with:

```java
        opsAlertService = mock(OpsAlertService.class);
        service = new PlaidItemLifecycleService(removalRepository, userRepository, plaidApi, encryptionService,
                schedulerGate, jobLockService, mock(PlatformTransactionManager.class), WEBHOOK, opsAlertService);
```

Find:

```java
        stale.setAttempts(600);
```

Replace with:

```java
        stale.setAttempts(600);
        stale.setId(31L);
```

Find:

```java
        assertThat(stale.getRemovedAt()).isNull();
        verify(jobLockService).release(JobLocks.PLAID_ITEM_REMOVALS);
    }
```

Replace with:

```java
        assertThat(stale.getRemovedAt()).isNull();
        verify(jobLockService).release(JobLocks.PLAID_ITEM_REMOVALS);
        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_ITEM_REMOVE_EXHAUSTED), eq("31"), anyString(),
                eq(Map.of("id", "31", "item", "item-old", "attempts", 600)));
    }
```

### 17c. `.../service/PlaidWebhookInboxService.java`

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
```

Find:

```java
import java.util.HexFormat;
import java.util.Optional;
```

Replace with:

```java
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
```

Find:

```java
    @Autowired
    private UserRepository userRepository;
```

Replace with:

```java
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OpsAlertService opsAlertService;
```

Find:

```java
            logger.error("alert.plaid.webhook.ambiguous_item item_id={}", itemId);
```

Replace with:

```java
            logger.error("alert.plaid.webhook.ambiguous_item item_id={}", itemId);
            opsAlertService.raise(OpsAlerts.PLAID_WEBHOOK_AMBIGUOUS_ITEM, itemId,
                    "webhook item_id matches more than one user; row stored IGNORED",
                    Map.of("item", itemId));
```

### 17d. `T/service/PlaidWebhookInboxServiceTest.java` (pattern W, written out)

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
```

Find:

```java
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
```

Replace with:

```java
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
```

Find:

```java
    private UserRepository userRepository;

    static byte[] body(String type, String code, String itemId, String env) {
```

Replace with:

```java
    private UserRepository userRepository;
    private OpsAlertService opsAlertService;

    static byte[] body(String type, String code, String itemId, String env) {
```

Find:

```java
        ReflectionTestUtils.setField(service, "plaidEnv", "sandbox");
```

Replace with:

```java
        ReflectionTestUtils.setField(service, "plaidEnv", "sandbox");
        opsAlertService = mock(OpsAlertService.class);
        ReflectionTestUtils.setField(service, "opsAlertService", opsAlertService);
```

Find (the end of `ambiguousItemIsIgnoredNotDropped`; `verify(eventRepository).save(event);` alone occurs twice in the file):

```java
        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isNull();
        verify(eventRepository).save(event);
    }
```

Replace with:

```java
        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isNull();
        verify(eventRepository).save(event);
        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_WEBHOOK_AMBIGUOUS_ITEM), eq(ITEM_ID), anyString(),
                eq(Map.of("item", ITEM_ID)));
    }
```

Run both classes, one at a time: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidItemLifecycleServiceTest' --console=plain`, then `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookInboxServiceTest' --console=plain`. Expected: `BUILD SUCCESSFUL`, 8 tests and 7 tests, 0 failures.

## Step 18: InvestmentExecutionService imports and InvestmentExecutionServiceEodBatchTest (pattern W, written out)

### 18a. `.../service/InvestmentExecutionService.java`

`java.util.*` (`:31`) already covers `Map`.

Find:

```java
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
```

Replace with:

```java
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
```

The field and the three raises are as the main file's Step 18 shows them; for completeness, the field edit is: find

```java
    @Autowired
    private PlatformTransactionManager transactionManager;
```

and replace with

```java
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OpsAlertService opsAlertService;
```

### 18b. `T/service/InvestmentExecutionServiceEodBatchTest.java`

`Map`, `eq`, `anyString`, `mock` and `verify` are already imported.

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
```

Find:

```java
    private PlatformTransactionManager transactionManager;
    private User user;
```

Replace with:

```java
    private PlatformTransactionManager transactionManager;
    private OpsAlertService opsAlertService;
    private User user;
```

Find:

```java
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
```

Replace with:

```java
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        opsAlertService = mock(OpsAlertService.class);
        ReflectionTestUtils.setField(service, "opsAlertService", opsAlertService);
```

Find (the end of `nullRelationshipFailsFastWithoutClaimOrPost`):

```java
        verify(emailService).sendEmail(eq("batch@fredvested.com"), anyString(), contains("550.00"));
    }
```

Replace with:

```java
        verify(emailService).sendEmail(eq("batch@fredvested.com"), anyString(), contains("550.00"));
        verify(opsAlertService).raise(eq(OpsAlerts.BATCH_NO_RELATIONSHIP), eq("42"), anyString(),
                eq(Map.of("user", "42", "rows", 2)));
    }
```

The wiring is load-bearing beyond that one check: `applyToFreshRowsStillWritesTheRowsItFindsWhenTheReloadComesBackShort` reaches `alert.batch_rows_missing` and `shortClaimSkipsTheUserAndPostsNothing` reaches `alert.batch_claim_short`, and without the mock both would hit a null `opsAlertService`.

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodBatchTest' --console=plain`. Expected: `BUILD SUCCESSFUL`, 11 tests, 0 failures.

## Step 19: TradingController imports and TradingControllerCloseAccountTest (pattern W with `controller`, written out)

### 19a. `.../controller/TradingController.java` imports

`java.util.*` (`:28`) already covers `Map`. Find:

```java
import com.investingapp.backend.service.UserDeletionService;
```

Replace with:

```java
import com.investingapp.backend.service.UserDeletionService;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
```

The field, `closingUserId` and the raise are as the main file's Step 19 shows them.

### 19b. `T/controller/TradingControllerCloseAccountTest.java`

`Map`, `eq`, `anyString`, `mock` and `verify` are already imported.

Find:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
```

Replace with:

```java
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
```

Find:

```java
    private AnalyticsOutboxService analyticsOutboxService;
    private Authentication authentication;
```

Replace with:

```java
    private AnalyticsOutboxService analyticsOutboxService;
    private OpsAlertService opsAlertService;
    private Authentication authentication;
```

Find:

```java
        ReflectionTestUtils.setField(controller, "analyticsOutboxService", analyticsOutboxService);
```

Replace with:

```java
        ReflectionTestUtils.setField(controller, "analyticsOutboxService", analyticsOutboxService);
        opsAlertService = mock(OpsAlertService.class);
        ReflectionTestUtils.setField(controller, "opsAlertService", opsAlertService);
```

Find (the end of `lateDeferralIs409`):

```java
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }
```

Replace with:

```java
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
        verify(opsAlertService).raise(eq(OpsAlerts.USER_DELETION_DEFERRED), eq("42"), anyString(),
                eq(Map.of("reason", "ACCOUNT_CLOSED", "deferral", "SETTLEMENT_WINDOW")));
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.TradingControllerCloseAccountTest' --console=plain`. Expected: `BUILD SUCCESSFUL`, 3 tests, 0 failures. `TradingControllerScheduleCancelledTest` needs no change (it never reaches the deferral catch) and stays at 9.
