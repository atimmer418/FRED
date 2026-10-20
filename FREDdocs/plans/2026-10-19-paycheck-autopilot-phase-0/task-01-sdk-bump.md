### Task 1: sdk-bump (plaid-java 18.0.0 to 46.0.0, drop the Development adapter)

Spec: 2.1 first paragraph (`FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md:29-31`), 13 first paragraph (`:650-652`), 14 Phase 0 (`:676-678`). The bump is its own commit; the ci build (`CiStubConfig`) is the compile gate. Nothing user-visible changes: every profile already sets `plaid.env=sandbox` (`application-local.properties:19`, `-dev:39`, `-test:33`, `-ci:52`, `-prod:18`), so the deleted `development` branch has no live caller.

**Files:**
- Create: `backend/src/test/java/com/investingapp/backend/config/PlaidConfigTest.java`
- Modify: `backend/build.gradle:32` (`18.0.0` to `46.0.0`)
- Modify: `backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java:53-66` (delete the `development` else-if at `:57-59` and reword the comment at `:53`; keep `:54-56` sandbox, `:60-62` production, `:63-66` default-to-Sandbox)
- Leave untouched: `backend/src/main/java/com/investingapp/backend/config/CiStubConfig.java:55-64` (`new ApiClient(Map)` + `setPlaidAdapter(ApiClient.Sandbox)` + `createService(PlaidApi.class)` all exist unchanged in 46; do not stage it)
- Test: `PlaidConfigTest` (new, 6 cases), `PlaidLinkedAnalyticsTest` (8 cases, `backend/src/test/java/com/investingapp/backend/service/PlaidLinkedAnalyticsTest.java`), `BrokerEnvironmentGuardTest` (11 cases, `backend/src/test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java`), `BackendApplicationTests` (`backend/src/test/java/com/investingapp/backend/BackendApplicationTests.java:20-27`, the ci context gate)

**Interfaces:**
- Consumes: nothing (first task, no dependencies).
- Produces:
  - `backend/build.gradle:32` = `implementation 'com.plaid:plaid-java:46.0.0'` (consumers T07, T08, T09). Verified on the cached 46 jar with javap: `com.plaid.client.ApiClient` has only `public static final String Production = "https://production.plaid.com"` and `Sandbox = "https://sandbox.plaid.com"` (18 also had `Development = "https://development.plaid.com"`); `ApiClient(Map<String,String>)` still accepts the auth names `clientId` and `secret`; `setPlaidAdapter(String)` and `createService(Class)` are unchanged; `PlaidApi.linkTokenCreate/itemPublicTokenExchange/accountsGet/institutionsGetById/authGet` keep their request and response types; `LinkTokenCreateRequestUser.clientUserId`, `CountryCode.US`, `Products.AUTH/IDENTITY`, `AccountBase.getAccountId/getMask/getName/getOfficialName/getType/getSubtype`, `AccountsGetResponse.getAccounts/getItem():Item`, `Item.getInstitutionId`, `InstitutionsGetByIdRequest.institutionId/countryCodes`, `InstitutionsGetByIdResponse.getInstitution`, `Institution.getName`, `AuthGetResponse.getAccounts/getNumbers`, `AuthGetNumbers.getAch():List<NumbersACH>`, `NumbersACH.getAccountId/getAccount/getRouting`, `ItemPublicTokenExchangeResponse.getAccessToken/getItemId` and the fluent `accessToken(String)/itemId(String)` the tests use, `LinkTokenCreateResponse.getLinkToken/getExpiration():OffsetDateTime` are all present; none of these members carries a `Deprecated` attribute in 46.
  - New surface later tasks rely on (verified present in 46): `PlaidApi.webhookVerificationKeyGet(WebhookVerificationKeyGetRequest)` with `.keyId(String)` and `WebhookVerificationKeyGetResponse.getKey():JWKPublicKey{getAlg,getCrv,getKid,getKty,getUse,getX,getY,getCreatedAt():Integer,getExpiredAt():Integer}`; `PlaidApi.itemGet/itemRemove/itemWebhookUpdate`; `LinkTokenCreateRequest.additionalConsentedProducts(List<Products>)/webhook(String)/redirectUri(String)/linkCustomizationName(String)/accessToken(String)`; `Products.TRANSACTIONS` and `Products.RECURRING_TRANSACTIONS`; note `ItemGetResponse.getItem()` returns `ItemWithConsentFields` in 46 (it returned `Item` in 18) while `AccountsGetResponse.getItem()` still returns `Item`.
  - `PlaidConfig.plaidApi()` keeps its signature `@Bean public PlaidApi plaidApi()` (`PlaidConfig.java:31-32`); after this task `plaid.env` values map: `sandbox` to `ApiClient.Sandbox`, `production` to `ApiClient.Production`, anything else (including `development`) to `ApiClient.Sandbox` with the existing warn log (consumer T14).
  - `PlaidConfigTest` pins that mapping offline so T14 can extend it.
  - Dependency facts for the record (from `./gradlew dependencyInsight --dependency com.squareup.okhttp3:okhttp --configuration runtimeClasspath` on today's tree): okhttp already resolves to 4.12.0 by conflict resolution, requested by `com.anthropic:anthropic-java-client-okhttp:2.48.0` and `io.pinecone:pinecone-client:3.0.0`, so plaid 18's 4.9.2 is upgraded today; retrofit is 2.9.0. After the bump retrofit, converter-gson and converter-scalars move to 2.11.0 and okhttp stays 4.12.0. Spring Boot 3.5.0 does not manage okhttp or retrofit. `okhttp3.ResponseBody.create(String, MediaType)` used at `PlaidLinkedAnalyticsTest.java:224` is therefore unchanged. Every 46 transitive (retrofit, converter-gson, converter-scalars 2.11.0; okhttp and logging-interceptor 4.12.0; gson-fire 1.9.0; swagger-annotations 1.5.22; oltu client 1.0.1; jsr305 3.0.2) is already in `~/.gradle/caches/modules-2/files-2.1/`.

- [ ] **Step 1: Confirm the starting state**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED && git status --short -- backend/build.gradle backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java backend/src/main/java/com/investingapp/backend/config/CiStubConfig.java && sed -n '32p' backend/build.gradle && sed -n '53,66p' backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java
```

Expected: `git status` prints nothing for the three files (they are clean at HEAD; the rest of the tree is dirty with other sessions' work, leave it alone). Line 32 prints `	implementation 'com.plaid:plaid-java:18.0.0'` (tab-indented). Lines 53-66 print the adapter block with the `"development"` else-if at 57-59. Do not run `git stash`, `git checkout` or `git restore` at any point in this task.

- [ ] **Step 2: Write the failing test `PlaidConfigTest`**

Create `backend/src/test/java/com/investingapp/backend/config/PlaidConfigTest.java`. It never touches the network: a Retrofit proxy builds its okhttp `Request` lazily on `Call.request()`, and that request carries the host the `ApiClient` was pointed at (verified by running exactly this call against both the cached 18.0.0 and 46.0.0 jars: `linkTokenCreate(new LinkTokenCreateRequest()).request().url().host()` returns `sandbox.plaid.com` / `production.plaid.com`, and on 18 also `development.plaid.com`, with no connection made). The `@Value` fields `plaidClientId`, `plaidSecret`, `plaidEnv` (`PlaidConfig.java:22-29`) are plain private Strings, set by reflection as the house pattern does (`PlaidLinkedAnalyticsTest.java:71-75`). The two refusal messages are the literals at `PlaidConfig.java:38` and `:42`.

```java
package com.investingapp.backend.config;

import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlaidConfig picks the Plaid host from plaid.env. plaid-java 46 dropped the Development
 * environment, so sandbox and production are the only real targets and anything else must fall
 * back to sandbox rather than reach a host that no longer exists.
 *
 * Nothing here touches the network. A Retrofit proxy builds its okhttp Request lazily on
 * Call.request(), and that request carries the host the ApiClient was pointed at.
 */
class PlaidConfigTest {

    private static final String CLIENT_ID = "client-id-for-test";
    private static final String SECRET = "secret-for-test";

    private static PlaidConfig configFor(String clientId, String secret, String plaidEnv) {
        PlaidConfig config = new PlaidConfig();
        ReflectionTestUtils.setField(config, "plaidClientId", clientId);
        ReflectionTestUtils.setField(config, "plaidSecret", secret);
        ReflectionTestUtils.setField(config, "plaidEnv", plaidEnv);
        return config;
    }

    private static String hostFor(String plaidEnv) {
        PlaidApi plaidApi = configFor(CLIENT_ID, SECRET, plaidEnv).plaidApi();
        return plaidApi.linkTokenCreate(new LinkTokenCreateRequest()).request().url().host();
    }

    @Test
    @DisplayName("sandbox points the client at sandbox.plaid.com")
    void sandboxHost() {
        assertThat(hostFor("sandbox")).isEqualTo("sandbox.plaid.com");
    }

    @Test
    @DisplayName("production points the client at production.plaid.com")
    void productionHost() {
        assertThat(hostFor("production")).isEqualTo("production.plaid.com");
    }

    @Test
    @DisplayName("development is no longer a Plaid environment and falls back to sandbox")
    void developmentFallsBackToSandbox() {
        assertThat(hostFor("development")).isEqualTo("sandbox.plaid.com");
    }

    @Test
    @DisplayName("the env value is matched case-insensitively")
    void envIsCaseInsensitive() {
        assertThat(hostFor("PRODUCTION")).isEqualTo("production.plaid.com");
        assertThat(hostFor("Sandbox")).isEqualTo("sandbox.plaid.com");
    }

    @Test
    @DisplayName("a missing client id refuses to build a client")
    void missingClientIdRefuses() {
        assertThatThrownBy(() -> configFor("", SECRET, "sandbox").plaidApi())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAID_CLIENT_ID is not configured.");
    }

    @Test
    @DisplayName("a missing secret refuses to build a client")
    void missingSecretRefuses() {
        assertThatThrownBy(() -> configFor(CLIENT_ID, "", "sandbox").plaidApi())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAID_SECRET_KEY is not configured.");
    }
}
```

- [ ] **Step 3: Run the new test and watch the development case fail on 18.0.0**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew test --tests 'com.investingapp.backend.config.PlaidConfigTest' ; grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' build/test-results/test/TEST-com.investingapp.backend.config.PlaidConfigTest.xml
```

Expected: `BUILD FAILED` with one failing case, `developmentFallsBackToSandbox`. Gradle prints its display name: `PlaidConfigTest > development is no longer a Plaid environment and falls back to sandbox FAILED`, followed by an AssertJ message on two lines, `expected: "sandbox.plaid.com"` and ` but was: "development.plaid.com"`. The other five pass. The XML line reads `tests="6" skipped="0" failures="1" errors="0"`. This is the behaviour the SDK bump changes: on 18.0.0 the `development` branch at `PlaidConfig.java:57-59` still routes to `development.plaid.com`.

- [ ] **Step 4: Bump plaid-java in build.gradle**

Edit `backend/build.gradle:32`. Old line (tab-indented):

```groovy
	implementation 'com.plaid:plaid-java:18.0.0'
```

New line:

```groovy
	implementation 'com.plaid:plaid-java:46.0.0'
```

Nothing else in the file changes. The 46.0.0 jar and pom are already in `~/.gradle/caches/modules-2/files-2.1/com.plaid/plaid-java/46.0.0/`, and so are all of its transitives (retrofit, converter-gson, converter-scalars 2.11.0; okhttp and logging-interceptor 4.12.0; gson-fire 1.9.0; swagger-annotations 1.5.22; oltu client 1.0.1; jsr305 3.0.2), so the resolution in Step 5 needs no download. Do not pass `--offline` anyway; a normal run lets Gradle refresh any metadata it wants.

- [ ] **Step 5: Compile and watch PlaidConfig fail on the retired Development adapter**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew compileJava
```

Expected: `BUILD FAILED` with exactly one compiler error, whose location is the absolute path ending in `backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java:58`:

```
.../config/PlaidConfig.java:58: error: cannot find symbol
            apiClient.setPlaidAdapter(ApiClient.Development);
                                               ^
  symbol:   variable Development
  location: class ApiClient
1 error
```

No other main source references `ApiClient.Development` or the string `"development"` (`grep -rn 'Development\|"development"' backend/src/main/java` finds only `PlaidConfig.java:57-59` plus a prose line in `FredKnowledge.java:371` that is not code), so this is the only break the bump introduces.

- [ ] **Step 6: Delete the development branch in PlaidConfig**

Edit `backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java`. Replace this block (lines 53-66):

```java
        // Set the environment adapter
        if ("sandbox".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Sandbox);
            logger.info("Plaid client configured for Sandbox environment.");
        } else if ("development".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Development);
            logger.info("Plaid client configured for Development environment.");
        } else if ("production".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Production);
            logger.info("Plaid client configured for Production environment.");
        } else {
            apiClient.setPlaidAdapter(ApiClient.Sandbox); // Default to sandbox
            logger.warn("Warning: Invalid PLAID_ENV value ('{}') in properties, defaulting to Sandbox.", plaidEnv);
        }
```

with this block (the three remaining branches are byte-identical to before; only the `development` else-if is gone and the leading comment is reworded):

```java
        // Set the environment adapter. plaid-java 46 knows only Sandbox and Production: Plaid
        // retired the Development environment, so any other value falls back to Sandbox below.
        if ("sandbox".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Sandbox);
            logger.info("Plaid client configured for Sandbox environment.");
        } else if ("production".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Production);
            logger.info("Plaid client configured for Production environment.");
        } else {
            apiClient.setPlaidAdapter(ApiClient.Sandbox); // Default to sandbox
            logger.warn("Warning: Invalid PLAID_ENV value ('{}') in properties, defaulting to Sandbox.", plaidEnv);
        }
```

Imports (`PlaidConfig.java:4-14`), the `@Profile("!ci")` at `:17`, the `@Value` fields at `:22-29`, the null guards at `:36-43`, the credentials map at `:47-51` and `createService` at `:73` stay as they are. `CiStubConfig.java` needs no edit: its `plaidApi()` at `:55-64` uses only `new ApiClient(Map)`, `ApiClient.Sandbox` and `createService`, all unchanged in 46.

- [ ] **Step 7: Compile main and test sources**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew compileJava compileTestJava
```

Expected: `BUILD SUCCESSFUL`. No deprecation warnings from Plaid classes: none of the members FRED calls (`PlaidService.java:61-72`, `:92-93`, `:165-166`, `:176-190`, `:195-203`; `PlaidToAlpacaService.java:137-141`, `:157-191`; `PlaidController.java:9-10` imports and `:57-60`) carries a `Deprecated` attribute in 46. `PlaidLinkedAnalyticsTest.java:7-10` and `PlaidLinkedAnalyticsAtomicityTest.java:9-12` import `AccountsGetResponse`, `ItemPublicTokenExchangeRequest/Response` and `PlaidApi`, all present with the same shape, and the fluent `new ItemPublicTokenExchangeResponse().accessToken(..).itemId(..)` they build (`PlaidLinkedAnalyticsTest.java:92-94`) still exists. `PlaidService.java:9` wildcard-imports `com.plaid.client.model.*`; that is the file's only on-demand import besides the implicit `java.lang.*`, single-type imports and same-package classes shadow it, and 46's model package has no class named `String`, `Object` or `Exception` (the only `java.lang` names PlaidService uses unqualified), so no ambiguity error can appear.

- [ ] **Step 8: Run the Plaid-adjacent unit tests and see them all pass**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew test --tests 'com.investingapp.backend.config.PlaidConfigTest' --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsTest' --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest' ; for c in config.PlaidConfigTest service.PlaidLinkedAnalyticsTest config.BrokerEnvironmentGuardTest; do echo "$c"; grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "build/test-results/test/TEST-com.investingapp.backend.$c.xml"; done
```

Expected: `BUILD SUCCESSFUL` and the three XML lines read `tests="6" skipped="0" failures="0" errors="0"`, `tests="8" skipped="0" failures="0" errors="0"`, `tests="11" skipped="0" failures="0" errors="0"`. The `developmentFallsBackToSandbox` case now passes because the only path left for an unknown value is the Sandbox default at the former `:63-66`.

- [ ] **Step 9: Confirm the resolved dependency graph**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew -q dependencies --configuration runtimeClasspath | grep -E 'plaid-java|retrofit2:(retrofit|converter-gson|converter-scalars)|okhttp3:okhttp:' | sort -u
```

Expected lines include `+--- com.plaid:plaid-java:46.0.0`, `com.squareup.retrofit2:retrofit:2.11.0`, `com.squareup.retrofit2:converter-gson:2.11.0`, `com.squareup.retrofit2:converter-scalars:2.11.0`, and `com.squareup.okhttp3:okhttp:4.12.0` (some entries carry `(*)` or an arrow such as `4.9.2 -> 4.12.0`; that is Gradle conflict resolution, not a problem). No `18.0.0` and no `2.9.0` anywhere in the output.

- [ ] **Step 10: Run the ci context gate (the compile gate the spec names)**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew test --tests 'com.investingapp.backend.BackendApplicationTests' ; grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml ; grep -c '<skipped/>' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
```

Expected on this laptop: `BUILD SUCCESSFUL` with `tests="1" skipped="1" failures="0" errors="0"` and the second grep printing `1`. Gradle records a skipped JUnit Platform test as a bare `<skipped/>` element with no reason text, and the console (even with `--info`) shows only `BackendApplicationTests > contextLoads() SKIPPED`; the reason comes from `RequiresCiDatabase.java:64-69` (the ci datasource at `application-ci.properties:17-19` is `root`/`ci` on `127.0.0.1:3306/fred`, which the local MySQL refuses). A skip here still proves the point of this step for T01: the test class and `CiStubConfig` compiled against 46. In GitHub Actions (`.github/workflows/verify.yml:24-31` provisions that MySQL; `:60-66` runs `./gradlew test`) the same command runs the context for real and must print `tests="1" skipped="0" failures="0" errors="0"`.

To run it for real locally, point it at a scratch schema on the running local MySQL (password literal is at `backend/src/main/resources/application-local.properties:3`; never use the schema named at `:1`):

```bash
/opt/homebrew/bin/mysql -h 127.0.0.1 -u root -p -e "CREATE DATABASE IF NOT EXISTS fred_ci_scratch"
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_ci_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD='<password from application-local.properties:3>' ./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' ; grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml ; grep -c 'Providing stub PlaidApi client' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
/opt/homebrew/bin/mysql -h 127.0.0.1 -u root -p -e "DROP DATABASE fred_ci_scratch"
```

Expected: `BUILD SUCCESSFUL`, XML `tests="1" skipped="0" failures="0" errors="0"`, and the last grep prints `1` because Gradle captures the boot log into the XML `<system-out>` block and it contains `[CI] Providing stub PlaidApi client (offline; no network at boot).` from `CiStubConfig.java:57`.

- [ ] **Step 11: Run the whole backend suite**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED && cd backend && ./gradlew test ; grep -l '<failure' build/test-results/test/*.xml ; echo "(no file listed above means zero failures)"
```

Expected: `BUILD SUCCESSFUL`; the `grep -l` prints nothing. Fourteen classes carry `@RequiresCiDatabase` and show as skipped locally, as in Step 10: `BackendApplicationTests`, `PlaidLinkedAnalyticsAtomicityTest`, the five `service/*RollbackTest` classes (`KycSubmittedOutboxRollbackTest`, `AccountStatusServiceKycDecidedRollbackTest`, `RegistrationAnalyticsOutboxRollbackTest`, `InvestmentScheduleCreatedOutboxRollbackTest`, `InvestmentExecutionAchAnalyticsRollbackTest`) and the seven `metrics/*ViewTest` classes. If a failure appears in a class that imports nothing from `com.plaid` (check with `grep -l 'com.plaid' backend/src/test/java/com/investingapp/backend/**/<Class>.java`; today only `PlaidLinkedAnalyticsTest`, `PlaidLinkedAnalyticsAtomicityTest` and the new `PlaidConfigTest` do), it belongs to another session's uncommitted work in this shared tree: note the class name in your report and do not touch that file; this task's gate is Steps 7, 8 and 10.

- [ ] **Step 12: Commit the bump on its own (exactly two files)**

Hunk-verify first, then stage by path only (the tree carries other sessions' changes):

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED && git diff HEAD -- backend/build.gradle backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java
```

Expected diff: one hunk in `build.gradle` (`-	implementation 'com.plaid:plaid-java:18.0.0'` / `+	implementation 'com.plaid:plaid-java:46.0.0'`) and one hunk in `PlaidConfig.java` removing the three `development` lines and rewording the `// Set the environment adapter` comment into the two-line comment from Step 6. If any other hunk shows in either file, it is not yours: stop and report it rather than committing it. Then:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED && git add backend/build.gradle backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java && git commit -m "build(plaid): bump plaid-java to 46.0.0 and drop the development adapter"
```

Expected: a commit containing exactly `backend/build.gradle` and `backend/src/main/java/com/investingapp/backend/config/PlaidConfig.java` (`git show --stat HEAD` lists two files, `2 files changed`). `CiStubConfig.java` is not in it because it did not change.

- [ ] **Step 13: Commit the pin test as a second commit**

The spec and the plan contract keep the bump commit to `build.gradle`, `PlaidConfig.java` and (if touched) `CiStubConfig.java`, so the test lands right behind it:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED && git add backend/src/test/java/com/investingapp/backend/config/PlaidConfigTest.java && git commit -m "test(plaid): pin PlaidConfig host selection on plaid-java 46"
```

Expected: `git show --stat HEAD` lists one file, `backend/src/test/java/com/investingapp/backend/config/PlaidConfigTest.java`, and `git log --oneline -2` shows the test commit directly above `build(plaid): bump plaid-java to 46.0.0 and drop the development adapter`. T07, T08 and T09 may now import `com.plaid.client.model.WebhookVerificationKeyGetRequest`, `WebhookVerificationKeyGetResponse`, `JWKPublicKey`, `ItemGetRequest`, `ItemGetResponse`, `ItemWithConsentFields`, `ItemRemoveRequest`, `ItemRemoveResponse`, `ItemWebhookUpdateRequest` and `ItemWebhookUpdateResponse`, which exist only from this commit on.