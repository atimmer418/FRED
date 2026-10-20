### Task 10: bank-change-ach-fix

Closes both ACH re-adoption paths and makes `AchRelationshipService.adopt` the single writer of `User.alpacaAchRelationshipId`, `User.alpacaAchStatus` and `InvestmentSchedule.achRequestId` (spec 2.9, 7.8), adds the MANUAL hold clause (5.2), deletes `update-ach-request-id` (8.9), and implements spec decision 23 (section 15, restated in 5.1 and 7.8): `adopt` clears `is_paused` exactly once, on the first-ever adoption (schedule `achRequestId` null AND `users.previous_ach_relationship_id` null, no hold), never after a bank change and never over a user's pause. This is the only deviation from the contract line "adopt never writes isPaused"; without it every new MANUAL user stays Paused forever once `updateWithAchRequestId` is gone.

`.../` = `backend/src/main/java/com/investingapp/backend/`; new tests go in `backend/src/test/java/com/investingapp/backend/service/`. Line numbers are HEAD 9a09482. `AlpacaApiService`, `AccountStatusService`, `PlaidToAlpacaService`, `InvestmentScheduleController` and `AccountStatusServiceKycDecidedTest` are untouched by T01 to T09, so their numbers hold. `InvestmentScheduleService` and `InvestmentSchedule` are edited by T02, T03 and T04 before this task (imports and fields shift lines), so every anchor there also gives the verbatim text to match on.

One transaction fact the implementer must respect: `PlaidService.exchangePublicTokenAndLinkUser` calls `accountStatusService.handleBankAccountChange(appUser)` at `PlaidService.java:141` with no try/catch, inside the exchange transaction. Any `RuntimeException` that escapes `cancelAllActive` rolls back the user's bank link. `AchRelationshipService` therefore catches `RestClientException` around every Alpaca call and logs an `alert.` line instead of throwing.

**Files:**
- Create: `.../service/AchRelationshipSummary.java`, `.../service/AchRelationshipService.java`
- Modify: `.../service/AlpacaApiService.java:22,453-470`, insert after `:580`; `.../service/AccountStatusService.java:32,43,51,193-207,209-213,235-309`; `.../service/PlaidToAlpacaService.java:15,22,35-37,53,84-93,105-126`; `.../service/InvestmentScheduleService.java:3` (imports), `:236-251` (`resetForBankChange`), `:253-275` (`updateWithAchRequestId`), `:293-312` (`getSchedulesReadyForInvestment`, T02 signature); `.../model/InvestmentSchedule.java:9` (import), `:297-317` (`isReadyForInvestment`, T02 signature); `.../controller/InvestmentScheduleController.java:6,89-131`; `frontend/src/app/services/auth.service.ts:681-689`; `frontend/src/app/services/investment.service.ts:226-246`
- Delete: `.../dto/UpdateAchRequestIdRequest.java`
- Test: create `AlpacaApiServiceAchRelationshipTest.java`, `AchRelationshipServiceTest.java`, `InvestmentScheduleEligibilityTest.java`; modify `AccountStatusServiceKycDecidedTest.java:66,88` plus one case. `AccountStatusServiceKycDecidedRollbackTest` autowires the bean (`:49-50`) and needs no change.

**Interfaces:**
- Consumes: `EasternTime.ZONE` / `EasternTime.now()` (T02); `InvestmentSchedule.getMode()/getHoldReason()/setHoldReason()`, `ScheduleMode`, `HoldReason` (T04); `User.getPreviousAchRelationshipId()/setPreviousAchRelationshipId()/getPlaidAccountMask()/getPlaidItemLinkedAt()` (T04 fields, stamped by T09); `InvestmentExecutionRepository.existsByUserAndStatus(User, ExecutionStatus)` (T06); `InvestmentSchedule.isReadyForInvestment(LocalDate)` and `InvestmentScheduleService.getSchedulesReadyForInvestment(LocalDate)` (T02); `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(User)` (`:19`, returns `Optional`); `PlaidToAlpacaService.createAchRelationshipFromPlaid(String, String, String, User)` (`:46`).
- Produces: `public record AchRelationshipSummary(String id, String status, String createdAt, String bankAccountNumber)`; `boolean AlpacaApiService.deleteAchRelationship(String accountId, String relationshipId)`; `List<AchRelationshipSummary> AlpacaApiService.listAchRelationships(String accountId, List<String> statuses)`; `AchRelationshipService { enum AdoptResult { ADOPTED, REFUSED_PREVIOUS_ID, REFUSED_MASK_MISMATCH, REFUSED_PREDATES_LINK }; AdoptResult adopt(User, AchRelationshipSummary); boolean cancelAllActive(User); void ensureRelationship(User) }` with constructor `(UserRepository, InvestmentScheduleRepository, InvestmentExecutionRepository, AlpacaApiService, EncryptionService, PlaidToAlpacaService)`; `static boolean InvestmentScheduleService.isReady(InvestmentSchedule, LocalDate)`; `AccountStatusService` constructor gains a ninth parameter `AchRelationshipService achRelationshipService` (appended last; `PlaidToAlpacaService` stays in position six although this task leaves it unused, so T11's constructor edit lands where the contract says).

- [ ] **Step 1: Failing test for the Alpaca client (409 is an error, DELETE, filtered list)**

Create `backend/src/test/java/com/investingapp/backend/service/AlpacaApiServiceAchRelationshipTest.java`:

```java
package com.investingapp.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlpacaApiServiceAchRelationshipTest {

    private static final String RELS = "http://broker/v1/accounts/acct-1/ach_relationships";
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private AlpacaApiService service;

    @BeforeEach
    void setUp() {
        service = new AlpacaApiService("key", "secret", "http://broker/v1", "http://trade/v2", mock(AlpacaService.class));
        // restTemplate is private final and built in the constructor; reflection replaces it for the test.
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    private static HttpClientErrorException http(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void conflict409NeverAdoptsOldRelationship() {
        when(restTemplate.exchange(eq(RELS), eq(HttpMethod.POST), any(), eq(String.class))).thenThrow(http(HttpStatus.CONFLICT));

        Map<String, Object> result = service.createAchRelationship("acct-1", "Ada Lovelace", "CHECKING", "000123456789", "021000021", "Checking");

        assertThat(result).containsEntry("conflict", true).containsKey("error").doesNotContainKey("id");
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void deleteTreatsGoneAsSuccessAndOutageAsFailure() {
        when(restTemplate.exchange(eq(RELS + "/rel-1"), eq(HttpMethod.DELETE), any(), eq(String.class))).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));
        when(restTemplate.exchange(eq(RELS + "/rel-2"), eq(HttpMethod.DELETE), any(), eq(String.class))).thenThrow(http(HttpStatus.NOT_FOUND));
        when(restTemplate.exchange(eq(RELS + "/rel-3"), eq(HttpMethod.DELETE), any(), eq(String.class))).thenThrow(new ResourceAccessException("timeout"));

        assertThat(service.deleteAchRelationship("acct-1", "rel-1")).isTrue();
        assertThat(service.deleteAchRelationship("acct-1", "rel-2")).isTrue();
        assertThat(service.deleteAchRelationship("acct-1", "rel-3")).isFalse();
    }

    @Test
    void listFiltersByStatusAndMapsEveryField() {
        String body = "[{\"id\":\"rel-1\",\"status\":\"APPROVED\",\"created_at\":\"2026-10-19T14:00:00Z\",\"bank_account_number\":\"****1234\"},"
                + "{\"id\":\"rel-2\",\"status\":\"CANCEL_REQUESTED\"},"
                + "{\"status\":\"QUEUED\"}]";
        when(restTemplate.exchange(eq(RELS + "?statuses=QUEUED,APPROVED"), eq(HttpMethod.GET), any(), eq(String.class))).thenReturn(ResponseEntity.ok(body));

        assertThat(service.listAchRelationships("acct-1", List.of("QUEUED", "APPROVED"))).containsExactly(
                new AchRelationshipSummary("rel-1", "APPROVED", "2026-10-19T14:00:00Z", "****1234"),
                new AchRelationshipSummary("rel-2", "CANCEL_REQUESTED", null, null));
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AlpacaApiServiceAchRelationshipTest'`. Expected: compilation fails with `cannot find symbol: class AchRelationshipSummary`.

- [ ] **Step 2: Record, 409 branch, DELETE and filtered list**

Create `backend/src/main/java/com/investingapp/backend/service/AchRelationshipSummary.java`:

```java
package com.investingapp.backend.service;

/** One row of Alpaca's GET /accounts/{id}/ach_relationships, as much of it as FRED reads. */
public record AchRelationshipSummary(String id, String status, String createdAt, String bankAccountNumber) {
}
```

`AlpacaApiService.java`: after `:22` (`import java.util.List;`) add `import java.util.ArrayList;` and `import org.springframework.web.client.RestClientException;`. Replace `:453-470`, which is exactly this block (from `} catch (HttpClientErrorException e) {` through the `}` that closes `if (e.getStatusCode().value() == 409) {`; the four lines `:471-474` that log and return `errorResult` stay):

```java
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                logger.info("ACH relationship already exists for account {} (409) — fetching existing one", accountId);
                try {
                    String existing = getAchRelationships(accountId);
                    JsonNode relationships = objectMapper.readTree(existing);
                    for (JsonNode rel : relationships) {
                        String status = rel.has("status") ? rel.get("status").asText() : "";
                        if ("APPROVED".equals(status) || "QUEUED".equals(status)) {
                            Map<String, Object> result = new HashMap<>();
                            result.put("id", rel.get("id").asText());
                            result.put("status", status);
                            result.put("created_at", rel.has("created_at") ? rel.get("created_at").asText() : "");
                            logger.info("Returning existing ACH relationship: {}", result.get("id"));
                            return result;
                        }
                    }
                } catch (Exception fetchEx) {
                    logger.error("Could not fetch existing ACH relationships for account {}: {}", accountId, fetchEx.getMessage());
                }
            }
```

with:

```java
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                // Spec 7.8: a 409 means a relationship still exists. Never adopt it; the 15-minute poll retries.
                logger.warn("ACH relationship create returned 409 for account {}; waiting for cancellation", accountId);
                return Map.<String, Object>of(
                        "error", "Alpaca returned 409: an ACH relationship already exists; waiting for cancellation",
                        "conflict", true);
            }
```

Insert after `:580` (the `}` that closes `public String getAchRelationships(String accountId)`):

```java
    /**
     * DELETE one ACH relationship. 204 and 404 both mean "gone", which is all a caller needs; any
     * other outcome is logged and reported as false so the caller can retry on the next poll.
     */
    public boolean deleteAchRelationship(String accountId, String relationshipId) {
        String url = brokerBaseUrl + "/accounts/" + accountId + "/ach_relationships/" + relationshipId;
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(createHeaders()), String.class);
            logger.info("ACH relationship {} delete requested for account {}", relationshipId, accountId);
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return true;
            }
            logger.error("ACH relationship delete refused rel={} account={}: {}", relationshipId, accountId, e.getMessage());
            return false;
        } catch (RestClientException e) {
            logger.error("ACH relationship delete failed rel={} account={}: {}", relationshipId, accountId, e.getMessage());
            return false;
        }
    }

    /**
     * GET the account's ACH relationships filtered server-side by status. Rows without an id are
     * skipped so a caller can always encrypt and store what it gets back. Transport failures
     * propagate as RestClientException; callers decide whether to swallow them.
     */
    public List<AchRelationshipSummary> listAchRelationships(String accountId, List<String> statuses) {
        String url = brokerBaseUrl + "/accounts/" + accountId + "/ach_relationships?statuses=" + String.join(",", statuses);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(createHeaders()), String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "[]" : response.getBody());
            List<AchRelationshipSummary> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode rel : root) {
                    String id = rel.path("id").asText(null);
                    if (id == null) {
                        continue;
                    }
                    result.add(new AchRelationshipSummary(id, rel.path("status").asText(null),
                            rel.path("created_at").asText(null), rel.path("bank_account_number").asText(null)));
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable ACH relationship list for account " + accountId, e);
        }
    }
```

(`JsonNode.path(...)` returns a MissingNode for an absent field and `MissingNode.asText(String)` returns the default, so absent fields map to null. `JsonProcessingException`, `HttpEntity`, `HttpMethod`, `ResponseEntity`, `JsonNode` and `List` are already imported at `:9-16,22`.)

Run the Step 1 command. Expected: 3 tests pass.

- [ ] **Step 3: Failing tests for AchRelationshipService (adopt, first-adoption unpause, cancel, poll)**

Create `backend/src/test/java/com/investingapp/backend/service/AchRelationshipServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AchRelationshipService.AdoptResult;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AchRelationshipServiceTest {

    private static final String ACCOUNT = "acct-1";
    private final UserRepository users = mock(UserRepository.class);
    private final InvestmentScheduleRepository schedules = mock(InvestmentScheduleRepository.class);
    private final InvestmentExecutionRepository executions = mock(InvestmentExecutionRepository.class);
    private final AlpacaApiService alpaca = mock(AlpacaApiService.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final PlaidToAlpacaService plaidToAlpaca = mock(PlaidToAlpacaService.class);
    private AchRelationshipService service;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        service = new AchRelationshipService(users, schedules, executions, alpaca, encryption, plaidToAlpaca);
        when(encryption.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(encryption.encrypt(anyString())).thenAnswer(i -> "ENC:" + i.getArgument(0));
        user = new User("ach@fredvested.com");
        user.setId(7L);
        user.setAlpacaAccountId(ACCOUNT);
        user.setPlaidAccountId("plaid-acct");
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setPlaidAccountMask("1234");
        user.setPlaidItemLinkedAt(EasternTime.now().minusHours(1));
        schedule = new InvestmentSchedule();
        schedule.setMode(ScheduleMode.MANUAL);
        schedule.setIsPaused(true);
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
    }

    private static AchRelationshipSummary rel(String id, String status, String number) {
        return new AchRelationshipSummary(id, status, OffsetDateTime.now(ZoneOffset.UTC).toString(), number);
    }

    private void alpacaHolds(AchRelationshipSummary... rels) {
        when(alpaca.listAchRelationships(eq(ACCOUNT), anyList())).thenReturn(List.of(rels));
    }

    @Test
    void firstAdoptionUnpausesNewSchedule() {
        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "000000001234"))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(user.getAlpacaAchRelationshipId()).isEqualTo("ENC:rel-new");
        assertThat(user.getAlpacaAchStatus()).isEqualTo("QUEUED");
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
        assertThat(schedule.getIsPaused()).isFalse();
        verify(users).save(user);
        verify(schedules).save(schedule);
    }

    @Test
    void bankChangeHoldClearedOnAdoptManual() {
        user.setPreviousAchRelationshipId("rel-old");
        schedule.setHoldReason(HoldReason.BANK_CHANGE);

        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "****1234"))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
    }

    @Test
    void adoptNeverUnpausesAfterBankChange() {
        user.setPreviousAchRelationshipId("rel-old");
        schedule.setHoldReason(HoldReason.BANK_CHANGE);

        service.adopt(user, rel("rel-new", "QUEUED", null));

        assertThat(schedule.getIsPaused()).isTrue();
    }

    @Test
    void adoptNeverClearsUserPause() {
        schedule.setAchRequestId("rel-first"); // adopted once already, then the user paused

        assertThat(service.adopt(user, rel("rel-second", "APPROVED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getIsPaused()).isTrue();
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-second");
    }

    @Test
    void adoptRefusesMaskMismatch() {
        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "****9999"))).isEqualTo(AdoptResult.REFUSED_MASK_MISMATCH);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getAchRequestId()).isNull();
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);
        assertThat(schedule.getIsPaused()).isTrue();
    }

    @Test
    void adoptRefusesPreviousIdAndAnythingCreatedBeforeTheLink() {
        user.setPreviousAchRelationshipId("rel-old");
        AchRelationshipSummary stale = new AchRelationshipSummary("rel-stale", "APPROVED",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).toString(), null);

        assertThat(service.adopt(user, rel("rel-old", "APPROVED", "1234"))).isEqualTo(AdoptResult.REFUSED_PREVIOUS_ID);
        assertThat(service.adopt(user, stale)).isEqualTo(AdoptResult.REFUSED_PREDATES_LINK);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);
    }

    @Test
    void cancelDeletesEveryActiveRelationshipUnlessATransferIsInFlight() {
        alpacaHolds(rel("rel-a", "QUEUED", null), rel("rel-b", "APPROVED", null));
        when(alpaca.deleteAchRelationship(eq(ACCOUNT), anyString())).thenReturn(true);

        assertThat(service.cancelAllActive(user)).isTrue();
        verify(alpaca).deleteAchRelationship(ACCOUNT, "rel-a");
        verify(alpaca).deleteAchRelationship(ACCOUNT, "rel-b");
        assertThat(user.getAlpacaAchStatus()).isEqualTo("CANCEL_REQUESTED");

        when(executions.existsByUserAndStatus(user, InvestmentExecution.ExecutionStatus.FUNDING_INITIATED)).thenReturn(true);
        assertThat(service.cancelAllActive(user)).isFalse();
        verify(alpaca, times(2)).deleteAchRelationship(anyString(), anyString());
    }

    @Test
    void cancelSurvivesAlpacaOutage() {
        when(alpaca.listAchRelationships(eq(ACCOUNT), anyList())).thenThrow(new ResourceAccessException("alpaca down"));

        assertThat(service.cancelAllActive(user)).isTrue();

        assertThat(user.getAlpacaAchStatus()).isNull();
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
    }

    @Test
    void emptyListCreatesAndAdopts() {
        alpacaHolds();
        when(plaidToAlpaca.createAchRelationshipFromPlaid(ACCOUNT, "plaid-acct", "Ada Lovelace", user))
                .thenReturn(Map.of("id", "rel-new", "status", "QUEUED", "created_at", OffsetDateTime.now(ZoneOffset.UTC).toString()));

        service.ensureRelationship(user);

        assertThat(user.getAlpacaAchRelationshipId()).isEqualTo("ENC:rel-new");
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
        assertThat(schedule.getIsPaused()).isFalse();
    }

    @Test
    void conflictFromCreateIsNotAdopted() {
        alpacaHolds();
        when(plaidToAlpaca.createAchRelationshipFromPlaid(ACCOUNT, "plaid-acct", "Ada Lovelace", user))
                .thenReturn(Map.of("error", "Alpaca returned 409: an ACH relationship already exists; waiting for cancellation", "conflict", true));

        service.ensureRelationship(user);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getAchRequestId()).isNull();
    }

    @Test
    void retryWaitsForCancel() {
        user.setPreviousAchRelationshipId("rel-old");
        alpacaHolds(rel("rel-old", "CANCEL_REQUESTED", null));

        service.ensureRelationship(user);
        user.setPlaidItemLinkedAt(EasternTime.now().minusHours(25)); // older than 24 h: alert log only, still no create
        service.ensureRelationship(user);

        verify(plaidToAlpaca, never()).createAchRelationshipFromPlaid(any(), any(), any(), any());
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
    }

    @Test
    void pollRetriesDeferredCancel() {
        user.setPreviousAchRelationshipId("rel-old");
        alpacaHolds(rel("rel-old", "APPROVED", null));
        when(alpaca.deleteAchRelationship(ACCOUNT, "rel-old")).thenReturn(true);

        service.ensureRelationship(user);

        verify(alpaca).deleteAchRelationship(ACCOUNT, "rel-old");
        verify(plaidToAlpaca, never()).createAchRelationshipFromPlaid(any(), any(), any(), any());
        assertThat(user.getAlpacaAchStatus()).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    void unknownRelationshipMakesThePollWait() {
        alpacaHolds(rel("rel-mystery", "APPROVED", null));

        service.ensureRelationship(user);

        verify(plaidToAlpaca, never()).createAchRelationshipFromPlaid(any(), any(), any(), any());
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AchRelationshipServiceTest'`. Expected: compilation fails with `cannot find symbol: class AchRelationshipService`.

- [ ] **Step 4: Create AchRelationshipService**

Create `backend/src/main/java/com/investingapp/backend/service/AchRelationshipService.java` (explicit constructor; no service in this repo uses Lombok):

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single writer of User.alpacaAchRelationshipId, User.alpacaAchStatus and
 * InvestmentSchedule.achRequestId (spec 7.8). Every Alpaca call is wrapped so that a broker outage
 * never escapes as an exception: cancelAllActive runs inside the Plaid exchange transaction
 * (PlaidService.exchangePublicTokenAndLinkUser calls AccountStatusService.handleBankAccountChange
 * with no try/catch) and a thrown RuntimeException there would roll back the user's bank link.
 */
@Service
public class AchRelationshipService {

    private static final Logger logger = LoggerFactory.getLogger(AchRelationshipService.class);

    /** Alpaca relationship statuses that still move money. */
    static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "APPROVED", "PENDING");
    /** The statuses the 15-minute poll looks at before it dares to create a new relationship. */
    static final List<String> POLL_STATUSES = List.of("QUEUED", "APPROVED", "PENDING", "CANCEL_REQUESTED");
    static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";

    public enum AdoptResult { ADOPTED, REFUSED_PREVIOUS_ID, REFUSED_MASK_MISMATCH, REFUSED_PREDATES_LINK }

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final InvestmentExecutionRepository executionRepository;
    private final AlpacaApiService alpacaApiService;
    private final EncryptionService encryptionService;
    private final PlaidToAlpacaService plaidToAlpacaService;

    public AchRelationshipService(UserRepository userRepository,
            InvestmentScheduleRepository scheduleRepository,
            InvestmentExecutionRepository executionRepository,
            AlpacaApiService alpacaApiService,
            EncryptionService encryptionService,
            PlaidToAlpacaService plaidToAlpacaService) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.executionRepository = executionRepository;
        this.alpacaApiService = alpacaApiService;
        this.encryptionService = encryptionService;
        this.plaidToAlpacaService = plaidToAlpacaService;
    }

    /**
     * Adopt one relationship as the user's funding source. Refuses (hold_reason=MANUAL_REVIEW plus an
     * alert line) the previous relationship, a mask that does not match the linked Plaid account, and
     * anything created before the current Plaid Item was linked. Clears BANK_CHANGE for MANUAL.
     * Writes is_paused in exactly one case (spec decision 23): the first-ever adoption for this user,
     * recognised by schedule.achRequestId null AND users.previous_ach_relationship_id null, with no
     * hold on the schedule. A bank change (previous id set) never unpauses; a user's pause is never
     * cleared.
     */
    @Transactional
    public AdoptResult adopt(User user, AchRelationshipSummary rel) {
        Optional<InvestmentSchedule> scheduleOpt = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        String previousId = encryptionService.decrypt(user.getPreviousAchRelationshipId());

        if (previousId != null && previousId.equals(rel.id())) {
            return refuse(user, scheduleOpt, rel, AdoptResult.REFUSED_PREVIOUS_ID);
        }
        if (!maskMatches(rel.bankAccountNumber(), user.getPlaidAccountMask())) {
            return refuse(user, scheduleOpt, rel, AdoptResult.REFUSED_MASK_MISMATCH);
        }
        if (predatesLink(rel.createdAt(), user.getPlaidItemLinkedAt())) {
            return refuse(user, scheduleOpt, rel, AdoptResult.REFUSED_PREDATES_LINK);
        }

        boolean firstEverAdoption = previousId == null
                && scheduleOpt.map(s -> s.getAchRequestId() == null).orElse(false);

        user.setAlpacaAchRelationshipId(encryptionService.encrypt(rel.id()));
        user.setAlpacaAchStatus(rel.status());
        userRepository.save(user);

        scheduleOpt.ifPresent(schedule -> {
            schedule.setAchRequestId(rel.id());
            if (schedule.getMode() == ScheduleMode.MANUAL && schedule.getHoldReason() == HoldReason.BANK_CHANGE) {
                schedule.setHoldReason(null);
            }
            if (firstEverAdoption && schedule.getHoldReason() == null && Boolean.TRUE.equals(schedule.getIsPaused())) {
                schedule.setIsPaused(false);
            }
            scheduleRepository.save(schedule);
        });

        logger.info("ACH relationship {} adopted for user {} status={} firstAdoption={}",
                rel.id(), user.getId(), rel.status(), firstEverAdoption);
        return AdoptResult.ADOPTED;
    }

    private AdoptResult refuse(User user, Optional<InvestmentSchedule> scheduleOpt, AchRelationshipSummary rel, AdoptResult reason) {
        logger.error("alert.ach.adopt_refused user={} reason={} rel={}", user.getId(), reason, rel.id());
        scheduleOpt.ifPresent(schedule -> {
            schedule.setHoldReason(HoldReason.MANUAL_REVIEW);
            scheduleRepository.save(schedule);
        });
        return reason;
    }

    /**
     * Bank change step 3 (spec 7.8): DELETE every QUEUED, APPROVED or PENDING relationship and mark the
     * user CANCEL_REQUESTED. Returns false only when the cancel is deferred because a transfer is in
     * flight; the 15-minute poll retries it from ensureRelationship. Never throws on a broker failure.
     */
    @Transactional
    public boolean cancelAllActive(User user) {
        if (executionRepository.existsByUserAndStatus(user, InvestmentExecution.ExecutionStatus.FUNDING_INITIATED)) {
            logger.warn("ach.cancel.deferred user={} reason=FUNDING_INITIATED", user.getId());
            return false;
        }
        String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
        if (alpacaAccountId == null) {
            return true;
        }
        List<AchRelationshipSummary> active;
        try {
            active = alpacaApiService.listAchRelationships(alpacaAccountId, ACTIVE_STATUSES);
        } catch (RestClientException | IllegalStateException e) {
            logger.error("alert.ach.cancel_failed user={} stage=list error={}", user.getId(), e.getMessage());
            return true;
        }
        boolean deletedAny = false;
        for (AchRelationshipSummary rel : active) {
            if (alpacaApiService.deleteAchRelationship(alpacaAccountId, rel.id())) {
                deletedAny = true;
            } else {
                logger.error("alert.ach.cancel_failed user={} stage=delete rel={}", user.getId(), rel.id());
            }
        }
        if (deletedAny) {
            // Only when something was actually asked to cancel; an empty list leaves the status alone.
            user.setAlpacaAchStatus(CANCEL_REQUESTED);
            userRepository.save(user);
        }
        return true;
    }

    /**
     * The 15-minute poll (spec 7.8, decision 19). Creates a new relationship only when Alpaca holds
     * nothing in QUEUED, APPROVED, PENDING or CANCEL_REQUESTED. Waits while the previous relationship
     * is still cancelling; retries the cancel when it is still active (a deferred or failed DELETE);
     * alerts when CANCEL_REQUESTED has lasted more than 24 hours or when an unknown relationship sits
     * on the account. Never throws on a broker failure.
     */
    @Transactional
    public void ensureRelationship(User user) {
        String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
        if (alpacaAccountId == null) {
            return;
        }
        List<AchRelationshipSummary> existing;
        try {
            existing = alpacaApiService.listAchRelationships(alpacaAccountId, POLL_STATUSES);
        } catch (RestClientException | IllegalStateException e) {
            logger.error("alert.ach.poll_list_failed user={} error={}", user.getId(), e.getMessage());
            return;
        }
        if (existing.isEmpty()) {
            createAndAdopt(user, alpacaAccountId);
            return;
        }
        String previousId = encryptionService.decrypt(user.getPreviousAchRelationshipId());
        boolean onlyPrevious = previousId != null && existing.stream().allMatch(r -> previousId.equals(r.id()));
        if (!onlyPrevious) {
            logger.error("alert.ach.unknown_relationship user={} ids={}", user.getId(),
                    existing.stream().map(AchRelationshipSummary::id).toList());
            return;
        }
        if (!existing.stream().allMatch(r -> CANCEL_REQUESTED.equals(r.status()))) {
            // The bank-change DELETE was deferred (transfer in flight) or refused: retry it from the poll.
            cancelAllActive(user);
            return;
        }
        // The cancel is requested in the same transaction that stamps plaid_item_linked_at (T09), so
        // that stamp is the cancel-requested clock.
        LocalDateTime cancelRequestedAt = user.getPlaidItemLinkedAt();
        if (cancelRequestedAt == null || cancelRequestedAt.isBefore(EasternTime.now().minusHours(24))) {
            logger.error("alert.ach.cancel_stuck user={} rel={}", user.getId(), previousId);
        } else {
            logger.info("ach.poll.waiting user={} rel={} status=CANCEL_REQUESTED", user.getId(), previousId);
        }
    }

    private void createAndAdopt(User user, String alpacaAccountId) {
        Map<String, Object> result = plaidToAlpacaService.createAchRelationshipFromPlaid(
                alpacaAccountId, user.getPlaidAccountId(), user.getFirstName() + " " + user.getLastName(), user);
        Object id = result == null ? null : result.get("id");
        if (id == null || result.containsKey("error")) {
            // Log only the error text: raw_response carries bank details.
            logger.error("alert.ach.create_failed user={} error={}", user.getId(), result == null ? "null result" : result.get("error"));
            return;
        }
        adopt(user, new AchRelationshipSummary(id.toString(), (String) result.get("status"), (String) result.get("created_at"), null));
    }

    /**
     * Plaid's mask is "the last 2 to 4 characters" of the account number and Alpaca may return the
     * number masked (****1234), so compare alphanumerics by suffix. Missing data on either side cannot
     * be checked and passes.
     */
    static boolean maskMatches(String bankAccountNumber, String plaidMask) {
        if (bankAccountNumber == null || plaidMask == null) {
            return true;
        }
        String number = bankAccountNumber.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
        String mask = plaidMask.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
        if (number.isEmpty() || mask.isEmpty()) {
            return true;
        }
        return number.endsWith(mask);
    }

    /** True when Alpaca's created_at (ISO offset, e.g. 2026-10-19T14:00:00Z) is before the Item link, with two minutes of clock-skew tolerance. */
    static boolean predatesLink(String createdAt, LocalDateTime linkedAtEt) {
        if (createdAt == null || linkedAtEt == null) {
            return false;
        }
        try {
            LocalDateTime createdEt = OffsetDateTime.parse(createdAt).atZoneSameInstant(EasternTime.ZONE).toLocalDateTime();
            return createdEt.isBefore(linkedAtEt.minusMinutes(2));
        } catch (DateTimeParseException e) {
            logger.warn("ach.adopt.unparseable_created_at value={}", createdAt);
            return false;
        }
    }
}
```

Run the Step 3 command. Expected: 13 tests pass.

- [ ] **Step 5: Failing AccountStatusService case (record previous, reset, cancel, no synchronous create)**

`AccountStatusServiceKycDecidedTest.java`: after `:66` (`    private AnalyticsOutboxService analyticsOutboxService;`) add:

```java
    @Mock
    private AchRelationshipService achRelationshipService;
```

At `:88` change `                analyticsOutboxService);` to:

```java
                analyticsOutboxService,
                achRelationshipService);
```

Append before the final `}` of the class:

```java
    @Test
    @DisplayName("a bank change records the previous relationship, resets, cancels, and never creates synchronously")
    void bankChangeResetsThenCancelsAndNeverCreatesSynchronously() {
        user.setAlpacaAchRelationshipId("ENC:rel-old");
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidAccountId("plaid-acct");
        user.setFirstName("Ada");
        user.setLastName("Lovelace");

        service.handleBankAccountChange(user);

        assertThat(user.getPreviousAchRelationshipId()).isEqualTo("ENC:rel-old");
        InOrder order = inOrder(investmentScheduleService, achRelationshipService);
        order.verify(investmentScheduleService).resetForBankChange(user);
        order.verify(achRelationshipService).cancelAllActive(user);
        verify(achRelationshipService, never()).ensureRelationship(any());

        service.attemptAchCreation(user);

        verify(achRelationshipService).ensureRelationship(user);
    }
```

(`InOrder`, `inOrder`, `never`, `verify`, `any`, `DisplayName` and `assertThat` are already imported at `:8,13,22,24,28,29,31`. The class runs under `MockitoExtension` strict stubs; this case adds no `when(...)`, so nothing is flagged unused.)

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AccountStatusServiceKycDecidedTest'`. Expected: compilation fails with `constructor AccountStatusService in class AccountStatusService cannot be applied to given types` (nine arguments given, eight required).

- [ ] **Step 6: Rewire AccountStatusService and strip PlaidToAlpacaService's user write**

`AccountStatusService.java`:
- After `:32` (`    private final AnalyticsOutboxService analyticsOutboxService;`) add `    private final AchRelationshipService achRelationshipService;`.
- Change `:43` from `            AnalyticsOutboxService analyticsOutboxService) {` to two lines `            AnalyticsOutboxService analyticsOutboxService,` and `            AchRelationshipService achRelationshipService) {`.
- After `:51` (`        this.analyticsOutboxService = analyticsOutboxService;`) add `        this.achRelationshipService = achRelationshipService;`.
- `PlaidToAlpacaService plaidToAlpacaService` stays as the sixth parameter and field. After this step nothing in the class reads it; it is kept only so T11 can append `UserDeletionService` as the tenth parameter exactly where the contract puts it.
- Replace `:193-207` (the javadoc `Called when a user changes their linked bank account.` through the `}` closing `handleBankAccountChange`) with:

```java
    /**
     * Bank change (spec 7.8), in order: record the relationship being replaced, hold the schedule,
     * cancel the old relationships, and leave creation to the 15-minute poll. The exchange that calls
     * this already copied previous_ach_relationship_id and nulled alpacaAchRelationshipId (T09), so the
     * copy below is a no-op there and only matters for any other caller.
     */
    @Transactional
    public void handleBankAccountChange(User user) {
        if (user.getAlpacaAchRelationshipId() != null) {
            user.setPreviousAchRelationshipId(user.getAlpacaAchRelationshipId());
        }
        investmentScheduleService.resetForBankChange(user);
        if (!achRelationshipService.cancelAllActive(user)) {
            logger.warn("Bank change for user {}: ACH cancellation deferred while a transfer is in flight; the poll retries", user.getEmail());
        }
    }
```

- Replace the javadoc `:209-213` (`Attempt to create an ACH relationship for a user using their stored Plaid data.` block) with:

```java
    /**
     * Precondition guards (Plaid data, Alpaca account, legal name), then
     * AchRelationshipService.ensureRelationship, which is the only path that creates or adopts.
     */
```

- Replace `:235-309` (from the comment line `        // If an ACH relationship was already created (e.g., user linked bank during onboarding` through the `}` at `:309` that closes the final `catch (Exception e)`; the method's own closing `}` at `:310` stays) with the single line:

```java
        achRelationshipService.ensureRelationship(user);
```

After this, `attemptAchCreation` is the three guards at `:216-233` followed by that line. `JsonNode`, `objectMapper`, `alpacaApiService`, `emailService` and `Map` are still used elsewhere in the class (`:73-74,144,333`), so no import changes.

`PlaidToAlpacaService.java`, deletions only:
- `:105-126`: the javadoc `Save ACH relationship information to user profile for future funding operations` and the whole `private void saveAchRelationshipToUser(...)` method.
- `:84-93`: the comment `// If ACH creation was successful, save the relationship ID to user profile` and the `if (achResult != null && !achResult.containsKey("error")) { ... }` block. `:95 return achResult;` stays.
- `:53`: `        String userEmail = user.getEmail();` (now unused).
- `:35-37`: `    @Autowired`, `    private UserRepository userRepository;` and the blank line.
- `:15`: `import com.investingapp.backend.repository.UserRepository;` and `:22`: `import java.util.Optional;`.

`createAchRelationshipFromPlaid` now returns Alpaca's map and writes nothing; `AchRelationshipService.adopt` is the writer.

Run the Step 5 command. Expected: 12 tests pass (11 existing plus the new case).

- [ ] **Step 7: Failing eligibility tests (hold clause, mode switch, resetForBankChange)**

Create `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleEligibilityTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentScheduleEligibilityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private final InvestmentScheduleRepository repository = mock(InvestmentScheduleRepository.class);
    private final InvestmentScheduleService service = new InvestmentScheduleService(
            repository, mock(PortfolioService.class), mock(StreakService.class), mock(AnalyticsOutboxService.class));

    private static InvestmentSchedule manualDueToday() {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setMode(ScheduleMode.MANUAL);
        s.setIsPaused(false);
        s.setAchRequestId("rel-1");
        s.setStartDate(TODAY.minusMonths(1));
        s.setNextInvestmentDate(TODAY);
        return s;
    }

    private static boolean ready(Consumer<InvestmentSchedule> change, LocalDate today) {
        InvestmentSchedule s = manualDueToday();
        change.accept(s);
        return s.isReadyForInvestment(today);
    }

    @Test
    void fourManualPinsAndTodayComesFromTheArgument() {
        assertThat(ready(s -> { }, TODAY)).isTrue();
        assertThat(ready(s -> s.setNextInvestmentDate(TODAY.minusDays(3)), TODAY)).isTrue();
        assertThat(ready(s -> { s.setStartDate(TODAY); s.setNextInvestmentDate(TODAY.plusWeeks(1)); }, TODAY)).isTrue();
        assertThat(ready(s -> s.setIsPaused(true), TODAY)).isFalse();
        assertThat(ready(s -> s.setAchRequestId(null), TODAY)).isFalse();
        assertThat(ready(s -> { }, TODAY.minusDays(1))).isFalse();
    }

    @Test
    void manualHeldByReturnNotReady() {
        for (HoldReason hold : List.of(HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW)) {
            assertThat(ready(s -> s.setHoldReason(hold), TODAY)).as(hold.name()).isFalse();
        }
        assertThat(ready(s -> s.setHoldReason(HoldReason.BANK_CHANGE), TODAY)).isTrue();
    }

    @Test
    void detectedModeIsNeverReadyInPhaseZero() {
        InvestmentSchedule detected = manualDueToday();
        detected.setMode(ScheduleMode.DETECTED);
        when(repository.findByUser_AccountStatus("ACTIVE")).thenReturn(List.of(detected, manualDueToday()));

        assertThat(service.getSchedulesReadyForInvestment(TODAY)).hasSize(1).allMatch(s -> s.getMode() == ScheduleMode.MANUAL);
    }

    @Test
    void resetForBankChangeHoldsWithoutPausing() {
        User user = new User("bank@fredvested.com");
        InvestmentSchedule s = manualDueToday();
        when(repository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(s));

        service.resetForBankChange(user);

        assertThat(s.getAchRequestId()).isNull();
        assertThat(s.getHoldReason()).isEqualTo(HoldReason.BANK_CHANGE);
        assertThat(s.getIsPaused()).isFalse();
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentScheduleEligibilityTest'`. Expected: it compiles (T02 and T04 already provide `isReadyForInvestment(LocalDate)`, `getSchedulesReadyForInvestment(LocalDate)`, `setMode`, `setHoldReason`); `fourManualPinsAndTodayComesFromTheArgument` passes, and `manualHeldByReturnNotReady`, `detectedModeIsNeverReadyInPhaseZero`, `resetForBankChangeHoldsWithoutPausing` fail.

- [ ] **Step 8: Hold clause, mode switch, resetForBankChange, delete updateWithAchRequestId**

`InvestmentSchedule.java`: add `import java.util.EnumSet;` right after `import java.time.LocalDateTime;` (`:9` at HEAD). Replace the whole `isReadyForInvestment` method, javadoc included: at HEAD it is `:297-317` and starts `    /**` / `     * Check if this schedule is ready for investment execution` / `     */` / `    public boolean isReadyForInvestment() {` / `        LocalDate today = LocalDate.now();`; after T02 the signature is `isReadyForInvestment(LocalDate todayET)` and the `today` line is gone. Replace from that javadoc through the method's closing `}` with:

```java
    /** Holds that stop a MANUAL schedule from investing (spec 5.2); BANK_CHANGE is not one of them. */
    private static final EnumSet<HoldReason> BLOCKING_HOLDS = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);

    /**
     * MANUAL eligibility for the given Eastern date (spec 5.2): not paused, ACH linked, no blocking
     * hold, and either the start date is today or the next investment date is today or past due.
     */
    public boolean isReadyForInvestment(LocalDate todayET) {
        if (!Boolean.FALSE.equals(isPaused) || achRequestId == null) {
            return false;
        }
        if (holdReason != null && BLOCKING_HOLDS.contains(holdReason)) {
            return false;
        }
        return (startDate != null && startDate.equals(todayET))
                || (nextInvestmentDate != null && !nextInvestmentDate.isAfter(todayET));
    }
```

(`HoldReason` is in the same package. `BLOCKING_HOLDS` is static, so Lombok `@Data` and JPA ignore it.)

`InvestmentScheduleService.java`: after `:3` (`import com.investingapp.backend.model.InvestmentSchedule;`) add `import com.investingapp.backend.model.HoldReason;` and `import com.investingapp.backend.model.ScheduleMode;`. Replace `resetForBankChange` with its javadoc (HEAD `:236-251`, starting `    /**` / `     * Clear ACH data and re-pause the schedule when a user changes their bank account.` and ending with the `}` after `logger.info("Investment schedule re-paused for bank account change — user {}", user.getEmail());`) with:

```java
    /**
     * Bank change step 2 (spec 7.8): drop the old relationship id and hold the schedule. is_paused is
     * not written; the hold is cleared by AchRelationshipService.adopt when the new relationship lands.
     */
    public void resetForBankChange(User user) {
        investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user).ifPresent(schedule -> {
            schedule.setAchRequestId(null);
            schedule.setHoldReason(HoldReason.BANK_CHANGE);
            investmentScheduleRepository.save(schedule);
            logger.info("Investment schedule held for bank change, user {}", user.getEmail());
        });
    }
```

Delete `updateWithAchRequestId` with its javadoc (HEAD `:253-275`, from `    /**` / `     * Update investment schedule with ACH request ID` through `        return updatedSchedule;` / `    }`).

Replace `getSchedulesReadyForInvestment(LocalDate todayET)` with its javadoc (HEAD `:293-312`; T02 changed the signature and the filter lambda, and may have added a private `isReady` delegate: replace that too) with:

```java
    /**
     * Schedules the 11:55 cron may queue tonight: user ACTIVE (both modes), then the per-mode clause.
     */
    @Transactional(readOnly = true)
    public List<InvestmentSchedule> getSchedulesReadyForInvestment(LocalDate todayET) {
        List<InvestmentSchedule> readySchedules = investmentScheduleRepository
                .findByUser_AccountStatus("ACTIVE")
                .stream()
                .filter(s -> isReady(s, todayET))
                .toList();
        logger.info("Found {} schedules ready for investment execution on {}", readySchedules.size(), todayET);
        return readySchedules;
    }

    /** Mode switch (spec 5.2). Phase 0 never produces DETECTED rows; the branch exists so the switch is exhaustive. */
    static boolean isReady(InvestmentSchedule s, LocalDate todayET) {
        ScheduleMode mode = s.getMode() == null ? ScheduleMode.MANUAL : s.getMode();
        return switch (mode) {
            case MANUAL -> s.isReadyForInvestment(todayET);
            case DETECTED -> false;
        };
    }
```

`InvestmentScheduleController.java`: delete `:6` (`import com.investingapp.backend.dto.UpdateAchRequestIdRequest;`) and `:89-131` (the javadoc `Update investment schedule with ACH request ID (called when user confirms on investment-confirmation page)` through the `}` closing `updateAchRequestId`). `@Valid` stays in use at `:49`.

Delete the DTO: `git rm backend/src/main/java/com/investingapp/backend/dto/UpdateAchRequestIdRequest.java`.

Frontend: delete `frontend/src/app/services/auth.service.ts:681-689` (`updateAchRequestId(achRequestId: string, userEmail: string): Observable<any> {` through its `}` and the blank line after it) and `frontend/src/app/services/investment.service.ts:226-246` (the `/** Update ACH request ID for investment schedule */` comment, the `updateAchRequestId(achRequestId: string)` method and the blank line after it). No component or spec references either method (grep-verified at HEAD).

Run the Step 7 command. Expected: 4 tests pass.

- [ ] **Step 9: Run everything this task touches**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.*' --tests 'com.investingapp.backend.controller.AlpacaControllerDuplicateAccountTest'
cd frontend && npx ng build --configuration dev
grep -rn 'updateAchRequestId\|update-ach-request-id\|UpdateAchRequestIdRequest\|updateWithAchRequestId\|saveAchRelationshipToUser' backend/src frontend/src
grep -rn 'setAlpacaAchRelationshipId\|setAlpacaAchStatus\|setAchRequestId' backend/src/main
```

Expected: `BUILD SUCCESSFUL` for both builds (the `*RollbackTest` classes skip without a ci database; `AlpacaControllerDuplicateAccountTest` mocks `PlaidToAlpacaService` and must still compile); the first grep prints nothing; the second lists only `service/AchRelationshipService.java` (two writes in `adopt`, one in `cancelAllActive`), `service/InvestmentScheduleService.java` (the `setAchRequestId(null)` in `resetForBankChange`) and `service/PlaidService.java` (the two null writes in `exchangePublicTokenAndLinkUser`, HEAD `:125-126`, shifted by T09). Also confirm no em dash in any new or edited line: `git diff HEAD -- backend/src frontend/src | grep -n '^+.*—'` prints nothing.

- [ ] **Step 10: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (shared dirty tree; stage nothing you did not write), then:

```
git add backend/src/main/java/com/investingapp/backend/service/AchRelationshipSummary.java backend/src/main/java/com/investingapp/backend/service/AchRelationshipService.java backend/src/main/java/com/investingapp/backend/service/AlpacaApiService.java backend/src/main/java/com/investingapp/backend/service/AccountStatusService.java backend/src/main/java/com/investingapp/backend/service/PlaidToAlpacaService.java backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/controller/InvestmentScheduleController.java frontend/src/app/services/auth.service.ts frontend/src/app/services/investment.service.ts backend/src/test/java/com/investingapp/backend/service/AlpacaApiServiceAchRelationshipTest.java backend/src/test/java/com/investingapp/backend/service/AchRelationshipServiceTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleEligibilityTest.java backend/src/test/java/com/investingapp/backend/service/AccountStatusServiceKycDecidedTest.java
git status --short backend/src/main/java/com/investingapp/backend/dto/UpdateAchRequestIdRequest.java
git commit -m "fix(ach): make adopt the single ACH writer and close both bank-change re-adoption paths"
```

The `git rm` in Step 8 already staged the DTO deletion; the `git status` line should show `D ` for it before the commit.