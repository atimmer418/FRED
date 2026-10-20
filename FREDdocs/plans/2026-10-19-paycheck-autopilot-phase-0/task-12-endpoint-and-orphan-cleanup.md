### Task 12: endpoint-and-orphan-cleanup

Closes backlog story FRED-218 (FREDdocs/backlog.md:383-385): `GET /api/plaid/primary-bank-account` decrypts the Plaid access token and sends it to the phone, and two uncalled endpoints do the same. After this task the server returns display fields only (plus the new `mask` and `itemStatus` from T04/T09), the two token endpoints and the two dead `*_anonymous` permitAll entries are gone, and the frontend's orphaned `plaid-data.service.ts` plus `models/plaid/*` are deleted (spec 8.8 last bullet, 8.9, 9.8 last bullet).

Scope note on spec 8.8: the spec also lists `consentExpiresAt` on this response. That field reads `users.plaid_disconnect_at`, which no Phase 0 entity maps (the T04 contract maps only mask, consented, item status, error code, linked-at, acked-at, previous ACH id), and it drives the PENDING_DISCONNECT card that is Phase 1 (spec 5.7). Phase 0 ships `mask` + `itemStatus` only, exactly as the binding contract says. Do not add `consentExpiresAt` here.

Depends on T09 (which brings T04's `User.plaidAccountMask` / `User.plaidItemStatus` and T07/T08's edits to `PlaidController` and `SecurityConfig`). Line numbers below are HEAD-accurate (9a09482); because T07, T08 and T09 edit `PlaidController.java` (:44-102) and `SecurityConfig.java` (:113) before this task runs, locate every anchor by the quoted text, not by the number alone.

**Files:**
- Create: `backend/src/test/java/com/investingapp/backend/controller/PlaidControllerPrimaryBankAccountTest.java`
- Create: `frontend/src/app/services/plaid.service.spec.ts`
- Modify: `backend/src/main/java/com/investingapp/backend/controller/PlaidController.java` (delete `getUserPlaidData` :104-130 and `getAccessToken` :132-154; rewrite the body of `getPrimaryBankAccount` :156-184; delete the `encryptionService` field :39-40 and the imports `EncryptionService` :7 and `UUID` :24; keep `HashMap` :21, `Map` :22, `Optional` :23)
- Modify: `backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java` (delete the two `*_anonymous` permitAll lines, :114-115 at HEAD, :115-116 once T07 has inserted the `/api/plaid/webhook` line after :113)
- Modify: `frontend/src/app/services/plaid.service.ts:83` (`mask: '****'` becomes `mask: response.mask ?? '****'`)
- Delete: `frontend/src/app/services/plaid-data.service.ts`
- Delete: `frontend/src/app/models/plaid/paycheck-source.model.ts`
- Delete: `frontend/src/app/models/plaid/selected-paycheck.model.ts` (then the empty `frontend/src/app/models/plaid/` directory)
- Modify: `FREDdocs/API_ENDPOINTS.md:133-135` (drop the two endpoint rows, describe the new response shape)
- Modify: `FREDdocs/AUTHENTICATION_AND_JWT.md:167-168` (drop the two `*_anonymous` lines from the public list)
- Test: `backend/src/test/java/com/investingapp/backend/controller/PlaidControllerPrimaryBankAccountTest.java`, `frontend/src/app/services/plaid.service.spec.ts`, `cd frontend && npx ng build --configuration dev` as the AOT gate for the deletions

**Interfaces:**

Consumes:
- [T04] `User.getPlaidAccountMask()` / `setPlaidAccountMask(String)` on `@Column(name = "plaid_account_mask", length = 4) private String plaidAccountMask;` (Lombok `@Getter @Setter`, User.java:27-28; field inserted after the Alpaca block at :176)
- [T04] `User.getPlaidItemStatus()` / `setPlaidItemStatus(PlaidItemStatus)` on `@Enumerated(EnumType.STRING) @Column(name = "plaid_item_status", length = 24, nullable = false) private PlaidItemStatus plaidItemStatus = PlaidItemStatus.OK;`
- [T04] `public enum com.investingapp.backend.model.PlaidItemStatus { OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED }`
- [T09] `PlaidService.populateAccountAndInstitutionDetails` stores `user.setPlaidAccountMask(primaryAccount.getMask())`, so new links answer with a real mask; links made before T09 answer with `null`
- [T07] `SecurityConfig.java` gains `.requestMatchers("/api/plaid/webhook").permitAll()` after :113 (shifts the two lines this task deletes by one)
- Existing: `Optional<User> UserRepository.findByEmail(String email)` (UserRepository.java:21); `UserDetailsImpl(Long id, String email, Collection<? extends GrantedAuthority> authorities)` (UserDetailsImpl.java:38-41, delegating to the 4-arg ctor at :43-49 which stores the email) and `getUsername()` returning the email (:82)
- Existing: `JwtTokenUtils.getValidJwtToken(): string | null` (frontend/src/app/utils/jwt-token.utils.ts:118) and `JwtTokenUtils.isJwtExpired(): boolean` (:45), both static on `export class JwtTokenUtils` (:2), which `PlaidService.loadBankAccounts` consults before calling the backend (plaid.service.ts:58-68)

Produces:
- `GET /api/plaid/primary-bank-account` (signature unchanged: `public ResponseEntity<?> getPrimaryBankAccount(Authentication authentication)`) answers 200 with `{ accountId, institutionName, accountName, accountType, accountSubtype, mask, itemStatus }` where `mask = user.getPlaidAccountMask()` (nullable) and `itemStatus = user.getPlaidItemStatus().name()`; the `accessToken` key is gone; 400 `"User not found"` and 500 `"Error retrieving bank account"` branches unchanged; `consentExpiresAt` is Phase 1 (see the scope note above)
- Deleted: `GET /api/plaid/user-data`, `GET /api/plaid/access-token`, `PlaidController.encryptionService`, the `/api/plaid/create_link_token_anonymous` and `/api/plaid/exchange_public_token_anonymous` permitAll entries
- `frontend/src/app/services/plaid.service.ts` `BankAccount.mask = response.mask ?? '****'`
- Deleted frontend files: `services/plaid-data.service.ts`, `models/plaid/paycheck-source.model.ts`, `models/plaid/selected-paycheck.model.ts`

- [ ] **Step 1: Write the failing controller test**

Create `backend/src/test/java/com/investingapp/backend/controller/PlaidControllerPrimaryBankAccountTest.java`. It follows `AnalyticsConsentEndpointTest.java:39-53` (plain `new Controller()`, `ReflectionTestUtils.setField`, a `UsernamePasswordAuthenticationToken`), but the principal is a real `UserDetailsImpl` because `getPrimaryBankAccount` casts to it (PlaidController.java:159); `AnalyticsConsentEndpointTest` uses Spring's `userdetails.User`, which would fail that cast. It deliberately never sets an `encryptionService` field: after this task that field no longer exists.

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/plaid/primary-bank-account is the one bank endpoint the app still calls
 * (plaid.service.ts and change-bank-account.page.ts). It must hand the UI display fields only:
 * the Plaid access token never leaves the server, and mask plus itemStatus replace it
 * (FRED-218, spec 8.8). No EncryptionService is wired here on purpose: answering must not
 * need one.
 */
class PlaidControllerPrimaryBankAccountTest {

    private static final String EMAIL = "bank@fredvested.com";

    private PlaidController controller;
    private UserRepository userRepository;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController();
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        user = new User(EMAIL);
        user.setId(7L);
        user.setPlaidAccountId("plaid-acct-1");
        user.setPlaidInstitutionName("Chase");
        user.setPlaidAccountName("Chase Checking");
        user.setPlaidAccountType("depository");
        user.setPlaidAccountSubtype("checking");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private Authentication authentication() {
        UserDetailsImpl principal = new UserDetailsImpl(7L, EMAIL, List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("the response carries the display fields plus mask and itemStatus")
    void carriesMaskAndItemStatus() {
        user.setPlaidAccountMask("1234");
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response))
                .containsEntry("accountId", "plaid-acct-1")
                .containsEntry("institutionName", "Chase")
                .containsEntry("accountName", "Chase Checking")
                .containsEntry("accountType", "depository")
                .containsEntry("accountSubtype", "checking")
                .containsEntry("mask", "1234")
                .containsEntry("itemStatus", "LOGIN_REQUIRED");
    }

    @Test
    @DisplayName("the Plaid access token never leaves the server, and no decryption is needed to answer")
    void accessTokenNeverLeavesTheServer() {
        user.setPlaidAccessToken("ENC:encrypted-access-token");

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).doesNotContainKey("accessToken");
        assertThat(body(response).values()).doesNotContain("ENC:encrypted-access-token");
    }

    @Test
    @DisplayName("a link made before the mask was stored still answers: null mask, OK status")
    void olderLinksAnswerWithNullMaskAndOkStatus() {
        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).containsKey("mask");
        assertThat(body(response).get("mask")).isNull();
        assertThat(body(response)).containsEntry("itemStatus", "OK");
    }

    @Test
    @DisplayName("an unknown user is a 400, as before")
    void unknownUserIs400() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run the controller test and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PlaidControllerPrimaryBankAccountTest'
```

Expected: `BUILD FAILED`, `4 tests completed, 3 failed`.
- `carriesMaskAndItemStatus`: AssertJ reports the map does not contain the entry `mask=1234` (today's body has `accountId, accessToken, institutionName, accountName, accountType, accountSubtype`, PlaidController.java:172-177; the first five `containsEntry` checks pass, `mask` is the first to fail).
- `accessTokenNeverLeavesTheServer`: `expected: 200 OK but was: 500 INTERNAL_SERVER_ERROR` (the old code calls `encryptionService.decrypt(...)` at :170 on the unset field, the NPE is caught by `catch (Exception e)` at :180).
- `olderLinksAnswerWithNullMaskAndOkStatus`: the map does not contain key `mask`.
- `unknownUserIs400` passes (the 400 branch at :162-164 is unchanged).

If the file does not compile because `setPlaidAccountMask` or `PlaidItemStatus` is missing, T04 has not landed: stop and finish T04/T09 first.

- [ ] **Step 3: Delete the two token endpoints and rewrite getPrimaryBankAccount**

In `backend/src/main/java/com/investingapp/backend/controller/PlaidController.java`:

1. Delete the whole `@GetMapping("/user-data") ... getUserPlaidData(...)` method (HEAD :104-130, from the `@GetMapping("/user-data")` line through its closing `}`).
2. Delete the whole `@GetMapping("/access-token") ... getAccessToken(...)` method (HEAD :132-154).
3. Replace the `getPrimaryBankAccount` method (HEAD :156-184, from `@GetMapping("/primary-bank-account")` through its closing `}`) with:

```java
    @GetMapping("/primary-bank-account")
    public ResponseEntity<?> getPrimaryBankAccount(Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            Optional<User> userOpt = userRepository.findByEmail(userDetails.getUsername());

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("User not found");
            }

            User user = userOpt.get();

            // Display fields only. The Plaid access token never leaves the server (FRED-218).
            // HashMap rather than Map.of: mask is null for links made before it was stored.
            Map<String, Object> bankAccount = new HashMap<>();
            bankAccount.put("accountId", user.getPlaidAccountId());
            bankAccount.put("institutionName", user.getPlaidInstitutionName());
            bankAccount.put("accountName", user.getPlaidAccountName());
            bankAccount.put("accountType", user.getPlaidAccountType());
            bankAccount.put("accountSubtype", user.getPlaidAccountSubtype());
            bankAccount.put("mask", user.getPlaidAccountMask());
            bankAccount.put("itemStatus", user.getPlaidItemStatus().name());

            return ResponseEntity.ok(bankAccount);
        } catch (Exception e) {
            logger.error("Error retrieving primary bank account", e);
            return ResponseEntity.status(500).body("Error retrieving bank account");
        }
    }
```

4. Confirm the `encryptionService` field has no other reader in this file, then remove it:

```bash
grep -n "encryptionService\|EncryptionService\|UUID" backend/src/main/java/com/investingapp/backend/controller/PlaidController.java
```

Expected after steps 1 to 3: exactly three matching lines, the import `import com.investingapp.backend.service.EncryptionService;` (HEAD :7), the import `import java.util.UUID;` (HEAD :24, unused even today), and the field declaration `private EncryptionService encryptionService;` (HEAD :40; its `@Autowired` at :39 does not match the grep). If T08 or T09 added another `encryptionService.` use inside this controller, keep the field and only drop the `UUID` import. Otherwise delete all three (the `@Autowired` line goes with the field):

```java
import com.investingapp.backend.service.EncryptionService;
```

```java
import java.util.UUID;
```

```java
    @Autowired
    private EncryptionService encryptionService;

```

Keep `import java.util.HashMap;`, `import java.util.Map;` and `import java.util.Optional;` (still used by `getPrimaryBankAccount`, and `Map` by the link-token and exchange endpoints at :57 and :69).

- [ ] **Step 4: Run the controller test and see it pass**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PlaidControllerPrimaryBankAccountTest'
```

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Delete the two dead permitAll entries**

In `backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java`, inside `authorizeHttpRequests` (HEAD :111-121), delete exactly these two lines (HEAD :114-115; :115-116 after T07's webhook line):

```java
                .requestMatchers("/api/plaid/create_link_token_anonymous").permitAll()
                .requestMatchers("/api/plaid/exchange_public_token_anonymous").permitAll()
```

No controller has mapped either path (a grep of `backend/src` for `_anonymous` at HEAD hits only these two lines). The `/api/plaid/webhook` line T07 added stays; everything under `/api/plaid/**` other than the webhook now needs a JWT via `.anyRequest().authenticated()` (:120).

- [ ] **Step 6: Compile the backend and prove the names are gone from backend/src**

```bash
cd backend && ./gradlew compileJava compileTestJava
grep -rn '"/user-data"\|"/access-token"\|plaid/user-data\|plaid/access-token\|_anonymous\|getUserPlaidData\|getAccessToken(Authentication' /Users/andrewtimmer/PersonalTypeshit/FRED/backend/src
```

Expected: `BUILD SUCCESSFUL` and the grep prints nothing. (The pattern is deliberately narrow: `access-token` alone would hit the literal `ENC:encrypted-access-token` in `PlaidLinkedAnalyticsTest.java:77,178` and the new controller test, and a bare `getAccessToken(` would hit the legitimate Plaid SDK getter `exchangeResponse.getAccessToken()` at `PlaidService.java:102` plus T08's `.accessToken(...)` link-token code.)

- [ ] **Step 7: Write the failing frontend spec for the mask pass-through**

Create `frontend/src/app/services/plaid.service.spec.ts`. `PlaidService` is constructed directly with a spy `HttpClient` (the house idiom in `linkplaid.component.analytics.spec.ts` and `change-bank-account.page.spec.ts:43-55`); the two static `JwtTokenUtils` reads that gate the backend call (`getValidJwtToken` at plaid.service.ts:50 and :60, `isJwtExpired` at :61) are spied the way `portfolio-store.service.spec.ts:244` does it, so no JWT has to be minted.

```ts
import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { BankAccount, PlaidService } from './plaid.service';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { environment } from '../../environments/environment';

/**
 * GET /api/plaid/primary-bank-account now returns the account mask (and itemStatus) instead of the
 * Plaid access token. The service used to hardcode '****' because the backend stored no mask; it
 * must pass the real one through and keep '****' only for links made before the mask was stored.
 */
describe('PlaidService primary bank account mask', () => {
  const backendAccount = {
    accountId: 'plaid-acct-1',
    institutionName: 'Chase',
    accountName: 'Chase Checking',
    accountType: 'depository',
    accountSubtype: 'checking',
    itemStatus: 'OK',
  };

  let get: jasmine.Spy;
  let service: PlaidService;

  beforeEach(() => {
    localStorage.clear();
    spyOn(JwtTokenUtils, 'getValidJwtToken').and.returnValue('jwt-token');
    spyOn(JwtTokenUtils, 'isJwtExpired').and.returnValue(false);
    get = jasmine.createSpy('get');
    service = new PlaidService({ get } as unknown as HttpClient);
  });

  afterEach(() => localStorage.clear());

  /** BehaviorSubject replays synchronously, so one subscribe/unsubscribe reads the current value. */
  function currentAccount(): BankAccount | null {
    const seen: (BankAccount | null)[] = [];
    service.getCurrentAccount().subscribe(account => seen.push(account)).unsubscribe();
    return seen[0] ?? null;
  }

  it('reads the primary bank account from the backend when a valid JWT is present', () => {
    get.and.returnValue(of({ ...backendAccount, mask: '1234' }));

    service.refreshBankAccountData();

    expect(get.calls.mostRecent().args[0]).toBe(`${environment.backendApiUrl}/plaid/primary-bank-account`);
  });

  it('passes the backend mask through to the BankAccount', () => {
    get.and.returnValue(of({ ...backendAccount, mask: '1234' }));

    service.refreshBankAccountData();

    expect(currentAccount()?.mask).toBe('1234');
    expect(service.getAccountSummary()).toBe('Chase •••• 1234');
  });

  it('falls back to **** for a link made before the mask was stored', () => {
    get.and.returnValue(of({ ...backendAccount, mask: null }));

    service.refreshBankAccountData();

    expect(currentAccount()?.mask).toBe('****');
  });

  it('never carries an accessToken field, whatever the backend sends', () => {
    get.and.returnValue(of({ ...backendAccount, mask: '1234', accessToken: 'should-never-be-read' }));

    service.refreshBankAccountData();

    const account = currentAccount() as unknown as Record<string, unknown>;
    expect(account['accessToken']).toBeUndefined();
  });
});
```

- [ ] **Step 8: Run the frontend spec and watch it fail**

```bash
cd frontend && npx ng test --include='**/plaid.service.spec.ts' --watch=false
```

Expected: `Executed 4 of 4 (1 FAILED)`; the failing case is `passes the backend mask through to the BankAccount` with `Expected '****' to be '1234'.` (plaid.service.ts:83 hardcodes `'****'`). The other three pass already. Note: `--include` still type-checks the whole app, so an unrelated compile error means the shared tree is broken, not this spec (see the scratch-worktree note in memory if that happens).

- [ ] **Step 9: Pass the mask through in plaid.service.ts**

In `frontend/src/app/services/plaid.service.ts`, inside `loadBankAccountFromBackend` (:71-105), replace line 83:

```ts
            mask: '****', // Backend doesn't store mask for security
```

with:

```ts
            mask: response.mask ?? '****', // null for links made before the mask was stored (T09)
```

Nothing else in this file changes; `BankAccount.mask` is already typed `string` (:13) and `response` is `any` (:72), so no interface edit is needed.

- [ ] **Step 10: Run the frontend spec and see it pass**

```bash
cd frontend && npx ng test --include='**/plaid.service.spec.ts' --watch=false
```

Expected: `Executed 4 of 4 SUCCESS`.

- [ ] **Step 11: Delete the orphaned client service and models**

`plaid-data.service.ts` is the only caller of `/plaid/user-data` and `/plaid/access-token` (plaid-data.service.ts:38-44), its `/income/*` calls (:26-36) have no backend, and nothing imports `PlaidDataService`, `PaycheckSource` or `SelectedPaycheck` (a grep of `frontend/src` at HEAD hits only these three files; `angular.json` and `tsconfig*.json` carry no `models/plaid` path mapping). `git rm` stages the deletions and removes the emptied directory; the `rmdir` is a belt-and-braces fallback.

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git rm frontend/src/app/services/plaid-data.service.ts \
       frontend/src/app/models/plaid/paycheck-source.model.ts \
       frontend/src/app/models/plaid/selected-paycheck.model.ts
rmdir frontend/src/app/models/plaid 2>/dev/null || true
ls frontend/src/app/models
```

Expected: `rm 'frontend/src/app/services/plaid-data.service.ts'` and the two model lines; `ls` prints only `passkey`.

- [ ] **Step 12: Prove the names are gone from both trees and the app still builds**

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
grep -rn 'plaid/user-data\|plaid/access-token\|"/user-data"\|"/access-token"\|plaid-data.service\|PlaidDataService\|PaycheckSource\|SelectedPaycheck\|_anonymous\|models/plaid' backend/src frontend/src
cd frontend && npx ng build --configuration dev
```

Expected: the grep prints nothing (T15's archived `PaycheckDetectionService.java` lives under `FREDdocs/archive/`, outside both trees, so it does not trip this); the build ends with `Application bundle generation complete.` and no `Cannot find module` error (the AOT build is the gate that no template or lazy route still referenced the deleted files).

- [ ] **Step 13: Update the two docs that list the deleted endpoints**

In `FREDdocs/API_ENDPOINTS.md` (section 6, :129-135) delete the two rows at :133-134:

```markdown
| `GET` | `/api/plaid/user-data` | Get user's Plaid data | Yes | None | Bank data |
| `GET` | `/api/plaid/access-token` | Get stored access token | Yes | None | Access token info |
```

and replace the row at :135:

```markdown
| `GET` | `/api/plaid/primary-bank-account` | Get primary bank account | Yes | None | Bank account details |
```

with:

```markdown
| `GET` | `/api/plaid/primary-bank-account` | Primary bank account, display fields only (never the Plaid access token) | Yes | None | `{ accountId, institutionName, accountName, accountType, accountSubtype, mask, itemStatus }` |
```

In `FREDdocs/AUTHENTICATION_AND_JWT.md` (section 6, "Public (no JWT needed)" block at :163-169) delete exactly the two lines at :167-168 and nothing else (the neighbouring lines carry pre-existing em dashes in their comments; leave them alone, they are out of this task's scope):

```
/api/plaid/create_link_token_anonymous
/api/plaid/exchange_public_token_anonymous
```

Then confirm:

```bash
grep -rn 'plaid/user-data\|plaid/access-token\|_anonymous' FREDdocs --include='*.md' | grep -v 'FREDdocs/specs/\|FREDdocs/backlog.md\|FREDdocs/archive/'
```

Expected: nothing. (`backlog.md` is left alone: it is modified by another session; mark FRED-218 done afterwards with `/triage --done=FRED-218`. `FREDdocs/specs/` keeps the spec's own mention in 8.9, and `FREDdocs/archive/` is T15's verbatim 2025 recovery.)

- [ ] **Step 14: Hunk-verify and commit only this task's files**

The tree is shared and dirty. Inspect every hunk before staging; anything outside this task (for example T08/T09's link-token or exchange changes in `PlaidController.java` that were already committed, or a foreign WIP hunk) must not ride along. Never use `git add -A` or `git add backend/`: `db/migration` holds untracked `R__*.sql` files from other work.

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git diff HEAD -- backend/src/main/java/com/investingapp/backend/controller/PlaidController.java \
                 backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java \
                 frontend/src/app/services/plaid.service.ts \
                 FREDdocs/API_ENDPOINTS.md FREDdocs/AUTHENTICATION_AND_JWT.md
git status --short -- backend/src/test/java/com/investingapp/backend/controller/ frontend/src/app/services/ frontend/src/app/models/
```

Expected diff: in `PlaidController.java` only the two method deletions, the `getPrimaryBankAccount` rewrite, the field and two import removals; in `SecurityConfig.java` only the two deleted lines; in `plaid.service.ts` only line 83; in the two docs only the rows and lines from Step 13. `git status` shows `D` for the three deleted files (already staged by Step 11) and `??` for the two new test files.

```bash
git add backend/src/main/java/com/investingapp/backend/controller/PlaidController.java \
        backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java \
        backend/src/test/java/com/investingapp/backend/controller/PlaidControllerPrimaryBankAccountTest.java \
        frontend/src/app/services/plaid.service.ts \
        frontend/src/app/services/plaid.service.spec.ts \
        FREDdocs/API_ENDPOINTS.md \
        FREDdocs/AUTHENTICATION_AND_JWT.md
git status --short | grep -v '^??' | grep -v '^ M'
git commit -m "fix(plaid): stop returning the access token and delete the dead Plaid endpoints"
```

Expected: the filtered status shows exactly 10 staged paths (7 added/modified above plus the 3 `D` deletions from Step 11); if another session has staged something of its own it appears here too, unstage it with `git restore --staged <path>` (index only, never the working tree) before committing. The commit lands on `develop` with that file list and nothing else.