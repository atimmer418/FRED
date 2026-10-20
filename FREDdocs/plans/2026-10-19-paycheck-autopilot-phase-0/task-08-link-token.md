### Task 8: link-token

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/dto/CreateLinkTokenRequest.java`, `backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java`, `backend/src/main/java/com/investingapp/backend/service/UpdateModeUnavailableException.java`, `frontend/src/app/utils/plaid-link-session.ts`
- Modify: `backend/src/main/java/com/investingapp/backend/service/PlaidService.java:47-48,59-80`; `backend/src/main/java/com/investingapp/backend/controller/PlaidController.java:3,8,44-66`; `backend/src/main/resources/application-local.properties`, `application-dev.properties`, `application-test.properties`, `application-ci.properties` (append below the `plaid.webhook.url` line T07 added after HEAD `plaid.env=sandbox` at local `:19` / dev `:39` / test `:33` / ci `:52`); `frontend/src/app/components/linkplaid/linkplaid.component.ts:15,75-76,79-81,91-93,95,97,120,137,157,208-210`; `frontend/src/app/change-bank-account/change-bank-account.page.ts:9,120-126,128,130,142,158,195-199`
- Test: `backend/src/test/java/com/investingapp/backend/service/PlaidServiceLinkTokenTest.java` (new), `frontend/src/app/components/linkplaid/linkplaid.component.oauth.spec.ts` (new), `frontend/src/app/change-bank-account/change-bank-account.page.oauth.spec.ts` (new); regression runs of `backend/src/test/java/com/investingapp/backend/service/PlaidLinkedAnalyticsTest.java`, `frontend/src/app/components/linkplaid/linkplaid.component.analytics.spec.ts`, `frontend/src/app/change-bank-account/change-bank-account.page.spec.ts`

**Interfaces:**
- Consumes: `plaid.webhook.url` (T07, present in all four non-prod profiles); `User.getPlaidAccessToken()` (`User.java:145`, `ENC:` at rest); `User.getPlaidTransactionsConsented()` tri-state `Boolean` (T04); `EncryptionService.decrypt(String)` (`EncryptionService.java:107`, null in gives null out); `UserRepository.findById(Long)` (JpaRepository); `UserDetailsImpl.getId()` (`UserDetailsImpl.java:77`); plaid-java 46 `LinkTokenCreateRequest` fluent setters `user, clientName, countryCodes, language, products, additionalConsentedProducts, webhook, accessToken, linkCustomizationName, redirectUri` (its `products`, `additionalConsentedProducts` and `accessToken` fields default to `null`, so an unset field is omitted from the JSON); `LinkTokenCreateResponse.getExpiration()` is `java.time.OffsetDateTime`.
- Produces: `public record com.investingapp.backend.dto.ErrorCodeResponse(String code, String message)`; `public class com.investingapp.backend.dto.CreateLinkTokenRequest { String mode }` (Lombok `@Data`); `public enum PlaidService.LinkMode { LINK, UPDATE; public static LinkMode fromRequest(String raw) }` (null or blank gives `LINK`; `link` / `update` case-insensitive; anything else throws `IllegalArgumentException`); `public LinkTokenCreateResponse PlaidService.createLinkToken(User user, LinkMode mode) throws IOException, UpdateModeUnavailableException` (replaces `createLinkTokenForAuthenticatedUser(String)`); `public class com.investingapp.backend.service.UpdateModeUnavailableException extends RuntimeException`; `POST /api/plaid/create_link_token` body `{ "mode": "link" | "update" }` (no body or `{}` means link; unknown mode gives 400 `MessageResponse`) returning 200 `{ link_token, expiration }`, 409 `ErrorCodeResponse("UPDATE_MODE_UNAVAILABLE", ...)`, 401 / 404 / 500 `MessageResponse`; properties `plaid.redirect.uri`, `plaid.link.customization`; `frontend/src/app/utils/plaid-link-session.ts` exporting `PLAID_LINK_TOKEN_KEY = 'fred.plaid.linkToken'`, `PLAID_LINK_RETURN_KEY = 'fred.plaid.linkReturn'`, `storePlaidLinkSession(linkToken, 'onboarding' | 'change-bank')`, `readPlaidLinkToken()`, `clearPlaidLinkSession()`; both components post `{ mode: 'link' }`, own `private oauthReturnHref(): string | null`, and on an OAuth return re-create Link with `receivedRedirectUri` and call `open()` at once; `LinkPlaidComponent.goBack()` navigates back to `/kyc-verification`.

- [ ] **Step 1: Write the failing service test**

Create `backend/src/test/java/com/investingapp/backend/service/PlaidServiceLinkTokenTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 2.2 and 2.3: one endpoint mints both kinds of Link token. LINK carries products plus the
 * Transactions consent; UPDATE carries the stored access token, no products, and the consent only
 * while the tri-state flag is not already true. Every token carries redirect_uri, webhook and the
 * customization name. PlaidService is field-injected, so collaborators go in by reflection as in
 * PlaidLinkedAnalyticsTest.
 */
class PlaidServiceLinkTokenTest {

    private static final String WEBHOOK_URL = "https://api-test.fredvested.com/api/plaid/webhook";
    private static final String REDIRECT_URI = "https://fredvested.com/plaid/oauth";
    private static final String CUSTOMIZATION = "paycheck_detection";
    private static final String ENC_TOKEN = "ENC:encrypted-access-token";
    private static final String RAW_TOKEN = "access-sandbox-raw-token";

    private PlaidService plaidService;
    private PlaidApi plaidApi;

    @BeforeEach
    void setUp() {
        plaidService = new PlaidService();
        plaidApi = mock(PlaidApi.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        ReflectionTestUtils.setField(plaidService, "plaidApi", plaidApi);
        ReflectionTestUtils.setField(plaidService, "userRepository", mock(UserRepository.class));
        ReflectionTestUtils.setField(plaidService, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(plaidService, "plaidClientName", "FRED (Test)");
        ReflectionTestUtils.setField(plaidService, "plaidWebhookUrl", WEBHOOK_URL);
        ReflectionTestUtils.setField(plaidService, "plaidRedirectUri", REDIRECT_URI);
        ReflectionTestUtils.setField(plaidService, "plaidLinkCustomization", CUSTOMIZATION);
        when(encryptionService.decrypt(ENC_TOKEN)).thenReturn(RAW_TOKEN);
    }

    private User user() {
        User user = new User("link-token@fredvested.com");
        user.setId(99L);
        return user;
    }

    private User linkedUser(Boolean consented) {
        User user = user();
        user.setPlaidAccessToken(ENC_TOKEN);
        user.setPlaidTransactionsConsented(consented);
        return user;
    }

    @SuppressWarnings("unchecked")
    private void plaidAnswers(Response<LinkTokenCreateResponse> answer) {
        Call<LinkTokenCreateResponse> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(answer);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(plaidApi.linkTokenCreate(any(LinkTokenCreateRequest.class))).thenReturn(call);
    }

    private LinkTokenCreateRequest requestFor(User user, PlaidService.LinkMode mode) throws IOException {
        plaidAnswers(Response.success(new LinkTokenCreateResponse().linkToken("link-sandbox-abc")));
        plaidService.createLinkToken(user, mode);
        ArgumentCaptor<LinkTokenCreateRequest> captor = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidApi).linkTokenCreate(captor.capture());
        return captor.getValue();
    }

    @Test
    void linkModeRequest() throws IOException {
        LinkTokenCreateRequest sent = requestFor(user(), PlaidService.LinkMode.LINK);

        assertThat(sent.getUser().getClientUserId()).isEqualTo("99");
        assertThat(sent.getProducts()).containsExactly(Products.AUTH, Products.IDENTITY);
        assertThat(sent.getAdditionalConsentedProducts()).containsExactly(Products.TRANSACTIONS);
        assertThat(sent.getWebhook()).isEqualTo(WEBHOOK_URL);
        assertThat(sent.getRedirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(sent.getLinkCustomizationName()).isEqualTo(CUSTOMIZATION);
        assertThat(sent.getAccessToken()).isNull();
    }

    @Test
    void updateModeConsentUnknown() throws IOException {
        LinkTokenCreateRequest sent = requestFor(linkedUser(null), PlaidService.LinkMode.UPDATE);

        assertThat(sent.getAccessToken()).isEqualTo(RAW_TOKEN);
        assertThat(sent.getProducts()).isNull();
        assertThat(sent.getAdditionalConsentedProducts()).containsExactly(Products.TRANSACTIONS);
        assertThat(sent.getWebhook()).isEqualTo(WEBHOOK_URL);
        assertThat(sent.getRedirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(sent.getLinkCustomizationName()).isEqualTo(CUSTOMIZATION);
    }

    @Test
    void updateModeAlreadyConsented() throws IOException {
        LinkTokenCreateRequest sent = requestFor(linkedUser(true), PlaidService.LinkMode.UPDATE);

        assertThat(sent.getAccessToken()).isEqualTo(RAW_TOKEN);
        assertThat(sent.getProducts()).isNull();
        assertThat(sent.getAdditionalConsentedProducts()).isNull();
    }

    @Test
    void updateModeWithoutAnItemIsUnavailable() {
        assertThatThrownBy(() -> plaidService.createLinkToken(user(), PlaidService.LinkMode.UPDATE))
                .isInstanceOf(UpdateModeUnavailableException.class);
        verify(plaidApi, never()).linkTokenCreate(any(LinkTokenCreateRequest.class));
    }

    /** A fresh error body per call: okhttp drains a ResponseBody on the first read. */
    private void plaidRefuses() {
        plaidAnswers(Response.error(400, ResponseBody.create(
                "{\"error_code\":\"INVALID_ACCESS_TOKEN\"}", MediaType.parse("application/json"))));
    }

    @Test
    void refusalMapsByMode() {
        plaidRefuses();
        assertThatThrownBy(() -> plaidService.createLinkToken(linkedUser(false), PlaidService.LinkMode.UPDATE))
                .isInstanceOf(UpdateModeUnavailableException.class);
        plaidRefuses();
        assertThatThrownBy(() -> plaidService.createLinkToken(user(), PlaidService.LinkMode.LINK))
                .isInstanceOf(IOException.class);
    }

    @Test
    void modeParsing() {
        assertThat(PlaidService.LinkMode.fromRequest(null)).isEqualTo(PlaidService.LinkMode.LINK);
        assertThat(PlaidService.LinkMode.fromRequest("  ")).isEqualTo(PlaidService.LinkMode.LINK);
        assertThat(PlaidService.LinkMode.fromRequest("link")).isEqualTo(PlaidService.LinkMode.LINK);
        assertThat(PlaidService.LinkMode.fromRequest("Update")).isEqualTo(PlaidService.LinkMode.UPDATE);
        assertThatThrownBy(() -> PlaidService.LinkMode.fromRequest("refresh"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceLinkTokenTest'`

Expected: `compileTestJava FAILED` with `cannot find symbol` for `UpdateModeUnavailableException`, `PlaidService.LinkMode` and `createLinkToken`.

- [ ] **Step 3: Create the DTOs and the exception**

`backend/src/main/java/com/investingapp/backend/dto/CreateLinkTokenRequest.java`:

```java
package com.investingapp.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /api/plaid/create_link_token body: "link" (default) for a new Item, "update" for the stored one. */
@Data
@NoArgsConstructor
public class CreateLinkTokenRequest {
    private String mode;
}
```

`backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java`:

```java
package com.investingapp.backend.dto;

/** Machine-readable 409 body: clients branch on code and show message. */
public record ErrorCodeResponse(String code, String message) {
}
```

`backend/src/main/java/com/investingapp/backend/service/UpdateModeUnavailableException.java`:

```java
package com.investingapp.backend.service;

/** Plaid will not mint an update-mode token, or there is no Item to update: the client falls back to link. */
public class UpdateModeUnavailableException extends RuntimeException {
    public UpdateModeUnavailableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Replace the link-token method in PlaidService**

Replace `PlaidService.java:47-48` (the `@Value("${plaid.client.name}")` field) with:

```java
    @Value("${plaid.client.name}")
    private String plaidClientName;

    @Value("${plaid.webhook.url}")
    private String plaidWebhookUrl;

    @Value("${plaid.redirect.uri}")
    private String plaidRedirectUri;

    @Value("${plaid.link.customization}")
    private String plaidLinkCustomization;

    /** Which kind of Link session a token opens. */
    public enum LinkMode {
        LINK, UPDATE;

        /** Request-body value to mode: null or blank is LINK; only link and update are accepted. */
        public static LinkMode fromRequest(String raw) {
            if (raw == null || raw.isBlank()) {
                return LINK;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "link" -> LINK;
                case "update" -> UPDATE;
                default -> throw new IllegalArgumentException("mode must be \"link\" or \"update\"");
            };
        }
    }
```

Replace `PlaidService.java:59-80` (the whole `createLinkTokenForAuthenticatedUser` method) with:

```java
    /**
     * Spec 2.2 and 2.3: every token carries redirect_uri, webhook and the DTM customization name.
     * LINK asks for auth + identity and collects the Transactions consent; UPDATE reopens the stored
     * Item (no products) and asks for that consent only while the tri-state flag is not yet true.
     */
    public LinkTokenCreateResponse createLinkToken(User user, LinkMode mode)
            throws IOException, UpdateModeUnavailableException {
        logger.info("Creating Plaid Link token (mode={}) for user ID: {}", mode, user.getId());
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(new LinkTokenCreateRequestUser().clientUserId(String.valueOf(user.getId())))
                .clientName(plaidClientName)
                .countryCodes(List.of(CountryCode.US))
                .language("en")
                .webhook(plaidWebhookUrl)
                .redirectUri(plaidRedirectUri)
                .linkCustomizationName(plaidLinkCustomization);

        if (mode == LinkMode.UPDATE) {
            if (!isPresent(user.getPlaidAccessToken())) {
                throw new UpdateModeUnavailableException("No Plaid Item on file to update");
            }
            request.accessToken(encryptionService.decrypt(user.getPlaidAccessToken()));
            if (!Boolean.TRUE.equals(user.getPlaidTransactionsConsented())) {
                request.additionalConsentedProducts(List.of(Products.TRANSACTIONS));
            }
        } else {
            request.products(List.of(Products.AUTH, Products.IDENTITY))
                    .additionalConsentedProducts(List.of(Products.TRANSACTIONS));
        }

        Response<LinkTokenCreateResponse> response = plaidApi.linkTokenCreate(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
            logger.error("Plaid Link Token creation failed (mode={}): {} - {}", mode, response.code(), errorBody);
            if (mode == LinkMode.UPDATE) {
                throw new UpdateModeUnavailableException("Plaid refused an update-mode token: " + errorBody);
            }
            throw new IOException("Plaid Link Token creation failed: " + errorBody);
        }
        return response.body();
    }
```

Nothing else in the file changes: `List` (`:27`), the `com.plaid.client.model.*` wildcard (`:9`, which covers `LinkTokenCreateRequest`, `LinkTokenCreateRequestUser`, `CountryCode`, `Products`), `Arrays` (still used at `:197`) and the private static `isPresent` (`:156`) are already in scope.

- [ ] **Step 5: Rewrite the controller endpoint**

In `PlaidController.java` add after `:3` (`import com.investingapp.backend.dto.MessageResponse;`):

```java
import com.investingapp.backend.dto.CreateLinkTokenRequest;
import com.investingapp.backend.dto.ErrorCodeResponse;
```

and after `:8` (`import com.investingapp.backend.service.PlaidService;`):

```java
import com.investingapp.backend.service.UpdateModeUnavailableException;
```

Replace `:44-66` (the whole `createLinkTokenAuthenticated` method, from the `@PostMapping("/create_link_token")` line to its closing brace) with:

```java
    @PostMapping("/create_link_token")
    public ResponseEntity<?> createLinkTokenAuthenticated(@RequestBody(required = false) CreateLinkTokenRequest body) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            logger.warn("/create_link_token: User not authenticated properly.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated"));
        }
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        PlaidService.LinkMode mode;
        try {
            mode = PlaidService.LinkMode.fromRequest(body == null ? null : body.getMode());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
        logger.info("Request received for /create_link_token (mode={}). User ID: {}", mode, userDetails.getId());

        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null) {
            logger.error("/create_link_token: Authenticated user ID {} not found in database.", userDetails.getId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found in database"));
        }

        try {
            LinkTokenCreateResponse response = plaidService.createLinkToken(user, mode);
            return ResponseEntity.ok(Map.of(
                    "link_token", response.getLinkToken(),
                    "expiration", response.getExpiration().toString()));
        } catch (UpdateModeUnavailableException e) {
            logger.warn("/create_link_token: update mode unavailable for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse(
                    "UPDATE_MODE_UNAVAILABLE", "Plaid could not reopen this bank connection. Link the bank again."));
        } catch (IOException e) {
            logger.error("Error creating Plaid link token for user {}: {}", user.getId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error creating Plaid link token: " + e.getMessage()));
        }
    }
```

`User` (`:4`), `userRepository` (`:37`), `Map` (`:22`), `IOException` (`:20`) and `HttpStatus` (`:14`) are already imported or declared in the controller.

- [ ] **Step 6: Add the two properties to every non-prod profile**

Directly below T07's `plaid.webhook.url=...` line (which sits under `plaid.env=sandbox` at HEAD local `:19`, dev `:39`, test `:33`), append the same two lines in `application-local.properties`, `application-dev.properties` and `application-test.properties`:

```properties
plaid.redirect.uri=${PLAID_REDIRECT_URI}
plaid.link.customization=${PLAID_LINK_CUSTOMIZATION:default}
```

Directly below T07's `plaid.webhook.url=https://ci.invalid/api/plaid/webhook` line in `application-ci.properties` (under `plaid.env=sandbox` at HEAD `:52`), append:

```properties
plaid.redirect.uri=https://ci.invalid/plaid/oauth
plaid.link.customization=default
```

`PLAID_REDIRECT_URI` has no default on purpose: Plaid answers 400 to a redirect_uri that is not registered in the dashboard, so a silent placeholder would break every Link session. Local boot now needs `export PLAID_REDIRECT_URI=https://fredvested.com/plaid/oauth` (the registered universal link from the manual prerequisites) next to T07's `PLAID_WEBHOOK_URL`; set both on Railway dev and test before deploying. `PLAID_LINK_CUSTOMIZATION` falls back to Plaid's built-in `default` customization until the DTM customization is created. `application-prod.properties` is not touched here (T14).

- [ ] **Step 7: Run the backend tests to green**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceLinkTokenTest' --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsTest'`

Expected: `BUILD SUCCESSFUL`; 6 tests in `PlaidServiceLinkTokenTest` and 8 in `PlaidLinkedAnalyticsTest` pass. Then `grep -rn "createLinkTokenForAuthenticatedUser" backend/src` prints nothing. CI's `BackendApplicationTests` (`.github/workflows/verify.yml:60-66`) boots the ci profile with the literal ci values, so a missing property key would fail there, not silently.

- [ ] **Step 8: Commit the backend**

Hunk-verify `git diff HEAD -- <file>` on the six modified backend files first (shared dirty tree: stage only the hunks this task made).

```bash
git add backend/src/main/java/com/investingapp/backend/dto/CreateLinkTokenRequest.java backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java backend/src/main/java/com/investingapp/backend/service/UpdateModeUnavailableException.java backend/src/main/java/com/investingapp/backend/service/PlaidService.java backend/src/main/java/com/investingapp/backend/controller/PlaidController.java backend/src/main/resources/application-local.properties backend/src/main/resources/application-dev.properties backend/src/main/resources/application-test.properties backend/src/main/resources/application-ci.properties backend/src/test/java/com/investingapp/backend/service/PlaidServiceLinkTokenTest.java
git commit -m "feat(plaid): mode-aware link tokens with OAuth redirect, webhook and transactions consent"
```

- [ ] **Step 9: Write the failing linkplaid OAuth spec**

Create `frontend/src/app/components/linkplaid/linkplaid.component.oauth.spec.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../../utils/plaid-link-session';
import { LinkPlaidComponent } from './linkplaid.component';

/** The onboarding route is /link-bank (app.routes.ts:38); the OAuth return lands there with oauth_state_id. */
const RETURN_HREF = 'https://fredvested.com/link-bank?oauth_state_id=abc123';

describe('LinkPlaidComponent OAuth round trip', () => {
  let post: jasmine.Spy;
  let open: jasmine.Spy;
  let navCtrl: jasmine.SpyObj<NavController>;
  let component: LinkPlaidComponent;
  let plaidConfig: any;

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    post = jasmine.createSpy('post').and.returnValue(of({ link_token: 'link-sandbox-token', expiration: '' }));
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    component = new LinkPlaidComponent(
      { post } as unknown as HttpClient,
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      navCtrl,
      { completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})) } as unknown as AuthService,
      jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['trackOnboardingStepCompleted', 'trackErrorShown']),
    );
    (component as any).isUserAuthenticated = true;
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode link and stores the token for the round trip', async () => {
    await component.initializePlaidLink();

    expect(post.calls.mostRecent().args[1]).toEqual({ mode: 'link' });
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('onboarding');
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });

  it('on the OAuth return it resumes Link with the stored token and receivedRedirectUri', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(RETURN_HREF);
    expect(open).toHaveBeenCalled();
  });

  it('an OAuth return with no stored token starts Link over with a fresh token', async () => {
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
  });

  it('success and exit both clear the stored link session', async () => {
    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onExit(null, {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();

    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('goBack returns to KYC verification', () => {
    component.goBack();

    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/kyc-verification');
  });
});
```

- [ ] **Step 10: Run it and watch it fail**

Run: `cd frontend && npx ng test --include='**/linkplaid.component.oauth.spec.ts' --watch=false`

Expected: the build fails before any spec runs with `error TS2307: Cannot find module '../../utils/plaid-link-session'` (the helper does not exist yet).

- [ ] **Step 11: Create the shared link-session helper**

Create `frontend/src/app/utils/plaid-link-session.ts`:

```ts
/**
 * Plaid OAuth round trip. When a bank uses OAuth, Link leaves the app for the bank's page and the
 * universal link brings the user back with ?oauth_state_id=... in the URL. Link must then be
 * re-created with the SAME link_token it left with, so the token is parked in sessionStorage for
 * the duration of one Link session. Nothing else is stored: no public token, no account data.
 */
export const PLAID_LINK_TOKEN_KEY = 'fred.plaid.linkToken';
export const PLAID_LINK_RETURN_KEY = 'fred.plaid.linkReturn';

export type PlaidLinkReturnTo = 'onboarding' | 'change-bank';

export function storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo): void {
  try {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, linkToken);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, returnTo);
  } catch {
    // Storage blocked: the OAuth return starts Link over with a fresh token.
  }
}

export function readPlaidLinkToken(): string | null {
  try {
    return sessionStorage.getItem(PLAID_LINK_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function clearPlaidLinkSession(): void {
  try {
    sessionStorage.removeItem(PLAID_LINK_TOKEN_KEY);
    sessionStorage.removeItem(PLAID_LINK_RETURN_KEY);
  } catch {
    // Nothing to clear.
  }
}
```

- [ ] **Step 12: Teach linkplaid the mode, the stored token and the OAuth return**

In `frontend/src/app/components/linkplaid/linkplaid.component.ts`, after `:15` (`import { httpStatusOf } from '../../services/analytics/analytics-error.utils';`) add:

```ts
import { clearPlaidLinkSession, readPlaidLinkToken, storePlaidLinkSession } from '../../utils/plaid-link-session';
```

Replace `:75-76` (`let linkTokenData: LinkTokenAuthenticatedResponse;` and `let linkToken: string;`) with:

```ts
      // Back from the bank's OAuth page: Link must resume with the SAME link_token it left with.
      // Without a stored token (storage cleared, new browser) Link simply starts over.
      const returnHref = this.oauthReturnHref();
      const storedToken = returnHref ? readPlaidLinkToken() : null;
      const resuming = returnHref !== null && storedToken !== null;
      let linkToken: string;
```

Replace `:79-81` (the three statements inside `if (this.isUserAuthenticated) {`: the `await ... toPromise()` assignment, `linkToken = linkTokenData.link_token;` and the `console.log('Received authenticated link_token.');`) with:

```ts
        if (resuming) {
          linkToken = storedToken as string;
        } else {
          const linkTokenData = await this.getLinkTokenAuthenticated().toPromise() as LinkTokenAuthenticatedResponse;
          linkToken = linkTokenData.link_token;
          if (!linkToken) {
            throw new Error('Failed to retrieve link_token from backend.');
          }
          storePlaidLinkSession(linkToken, 'onboarding');
        }
```

The `else` branch at `:82-89` (not authenticated) stays as is. Delete `:91-93` (the `if (linkToken === "") { throw ... }` block; the check now lives inside the non-resuming branch above).

Change `:95` from `this.plaidHandler = Plaid.create({` to `const config: any = {`. Insert `clearPlaidLinkSession();` as the first statement inside `onSuccess` (right after `:97`, `onSuccess: (public_token: string, metadata: any) => {`) and inside `onExit` (right after `:120`, `onExit: (err: any, metadata: any) => {`). Replace `:137` (the `});` that closes `Plaid.create`) with:

```ts
      };
      if (resuming) {
        config.receivedRedirectUri = returnHref;
      }
      this.plaidHandler = Plaid.create(config);
      if (resuming) {
        // Plaid requires open() straight after an OAuth return so Link picks up where it left off.
        this.plaidHandler.open();
      }
```

At `:157` change the posted body from `{}` to `{ mode: 'link' }`:

```ts
    return this.http.post<LinkTokenAuthenticatedResponse>(`${BACKEND_API_URL}/plaid/create_link_token`, { mode: 'link' }, { headers: this.getAuthHeaders() })
```

Replace `goBack` (`:208-210`) with:

```ts
  goBack() {
    this.navCtrl.navigateBack('/kyc-verification');
  }

  /** The current URL when it carries Plaid's oauth_state_id, else null. Stubbed in specs. */
  private oauthReturnHref(): string | null {
    return window.location.search.includes('oauth_state_id') ? window.location.href : null;
  }
```

Two facts the implementer should know and not try to fix here: the router uses path location (no hash), so `window.location.search` does carry the query string; and the frontend has no `appUrlOpen` listener today, so the universal link opening the app on `/link-bank?oauth_state_id=...` (and `/change-bank-account?...`) is the decision 1 manual prerequisite, not part of this task. `goBack` is not bound in `linkplaid.component.html` (only `openPlaid()` at `:90`), so the `/kyc-verification` correction is proven by the spec, not the UI.

- [ ] **Step 13: Run the linkplaid specs to green**

Run: `cd frontend && npx ng test --include='**/linkplaid.component.oauth.spec.ts' --watch=false` then `cd frontend && npx ng test --include='**/linkplaid.component.analytics.spec.ts' --watch=false`

Expected: `TOTAL: 5 SUCCESS` for the OAuth spec and `TOTAL: 7 SUCCESS` for the analytics spec (unchanged behaviour: in Karma `window.location.search` has no `oauth_state_id`, so every existing path takes the non-resuming branch).

- [ ] **Step 14: Commit linkplaid**

```bash
git add frontend/src/app/utils/plaid-link-session.ts frontend/src/app/components/linkplaid/linkplaid.component.ts frontend/src/app/components/linkplaid/linkplaid.component.oauth.spec.ts
git commit -m "feat(linkplaid): send mode link, keep the token for the OAuth return and go back to kyc"
```

- [ ] **Step 15: Write the failing change-bank-account OAuth spec**

Create `frontend/src/app/change-bank-account/change-bank-account.page.oauth.spec.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of } from 'rxjs';
import { ChangeBankAccountPage } from './change-bank-account.page';
import { PlaidService } from '../services/plaid.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../utils/plaid-link-session';

/** The route is /change-bank-account (app.routes.ts:82); the OAuth return lands there with oauth_state_id. */
const RETURN_HREF = 'https://fredvested.com/change-bank-account?oauth_state_id=abc123';

describe('ChangeBankAccountPage OAuth round trip', () => {
  let http: jasmine.SpyObj<HttpClient>;
  let open: jasmine.Spy;
  let page: ChangeBankAccountPage;
  let plaidConfig: any;

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    http.post.and.returnValue(of({ link_token: 'link-sandbox-token' }));
    http.get.and.returnValue(of({}));
    page = new ChangeBankAccountPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      http,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<PlaidService>('PlaidService', ['refreshBankAccountData']),
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown', 'screen']),
    );
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode link and stores the token with the change-bank marker', async () => {
    await page['initializePlaidLink']();

    expect(http.post.calls.mostRecent().args[1]).toEqual({ mode: 'link' });
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('change-bank');
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });

  it('on the OAuth return it resumes Link with the stored token and opens it without a new token request', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    spyOn(page as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await page['initializePlaidLink']();

    expect(http.post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(RETURN_HREF);
    expect(open).toHaveBeenCalled();
  });

  it('success and exit both clear the stored link session', async () => {
    await page['initializePlaidLink']();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onExit(null, {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();

    await page['initializePlaidLink']();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });
});
```

- [ ] **Step 16: Run it and watch it fail**

Run: `cd frontend && npx ng test --include='**/change-bank-account.page.oauth.spec.ts' --watch=false`

Expected: `TOTAL: 3 FAILED`. The first spec reports `Expected Object({  }) to equal Object({ mode: 'link' })` and `Expected null to be 'link-sandbox-token'`; the second reports `oauthReturnHref() method does not exist`; the third reports `Expected null to be 'link-sandbox-token'` because nothing stores the session yet. (The helper module already exists from Step 11, so this file compiles.)

- [ ] **Step 17: Teach change-bank-account the same round trip**

In `frontend/src/app/change-bank-account/change-bank-account.page.ts`, after `:9` (`import { JwtTokenUtils } from '../utils/jwt-token.utils';`) add:

```ts
import { clearPlaidLinkSession, readPlaidLinkToken, storePlaidLinkSession } from '../utils/plaid-link-session';
```

Replace `:120-126` (from the `// Get link token for updating bank account` comment through the closing `}` of `if (!linkToken) { throw ... }`) with:

```ts
      // Back from the bank's OAuth page: Link must resume with the SAME link_token it left with.
      const returnHref = this.oauthReturnHref();
      const storedToken = returnHref ? readPlaidLinkToken() : null;
      const resuming = returnHref !== null && storedToken !== null;
      let linkToken: string;
      if (resuming) {
        linkToken = storedToken as string;
      } else {
        const linkTokenData = await this.getLinkTokenAuthenticated().toPromise();
        linkToken = linkTokenData.link_token;
        if (!linkToken) {
          throw new Error('Failed to retrieve link_token from backend.');
        }
        storePlaidLinkSession(linkToken, 'change-bank');
      }
```

Change `:128` from `this.plaidHandler = Plaid.create({` to `const config: any = {`. Insert `clearPlaidLinkSession();` as the first statement inside `onSuccess` (right after `:130`, `onSuccess: (public_token: string, metadata: any) => {`) and inside `onExit` (right after `:142`, `onExit: (err: any, metadata: any) => {`). Replace `:158` (the `});` that closes `Plaid.create`) with:

```ts
      };
      if (resuming) {
        config.receivedRedirectUri = returnHref;
      }
      this.plaidHandler = Plaid.create(config);
      if (resuming) {
        // The PIN step-up already passed before Link left for the bank; Plaid requires open() straight away.
        this.plaidHandler.open();
      }
```

Replace `getLinkTokenAuthenticated` (`:195-199`) with:

```ts
  private getLinkTokenAuthenticated() {
    return this.http.post<any>(`${BACKEND_API_URL}/plaid/create_link_token`, { mode: 'link' }, {
      headers: this.getAuthHeaders()
    });
  }

  /** The current URL when it carries Plaid's oauth_state_id, else null. Stubbed in specs. */
  private oauthReturnHref(): string | null {
    return window.location.search.includes('oauth_state_id') ? window.location.href : null;
  }
```

- [ ] **Step 18: Run the change-bank-account specs to green**

Run: `cd frontend && npx ng test --include='**/change-bank-account.page.oauth.spec.ts' --watch=false` then `cd frontend && npx ng test --include='**/change-bank-account.page.spec.ts' --watch=false`

Expected: `TOTAL: 3 SUCCESS` for the OAuth spec and `TOTAL: 9 SUCCESS` for the existing analytics spec.

- [ ] **Step 19: Commit change-bank-account**

```bash
git add frontend/src/app/change-bank-account/change-bank-account.page.ts frontend/src/app/change-bank-account/change-bank-account.page.oauth.spec.ts
git commit -m "feat(change-bank): send mode link and keep the token for the OAuth return"
```