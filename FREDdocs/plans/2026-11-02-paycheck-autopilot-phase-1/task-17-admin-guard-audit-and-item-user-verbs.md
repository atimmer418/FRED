### Task 17: admin-guard-audit-and-item-user-verbs

Builds the guard and audit trail of the prod-registered admin surface (spec 8.10, 12.2, R12) and the three verbs needing no paycheck code: `GET /api/admin/plaid/items`, `POST /api/admin/plaid/{userId}/remove-item`, `POST /api/admin/users/{id}/delete`. The dev deletion verb (`DevUserController`) moves here and is deleted. T18 builds the paycheck verbs on this.

Facts this task depends on:

- Guard (decision 13): `X-Admin-Key` must match `app.admin-tools.key` (env `ADMIN_TOOLS_KEY`), else 404 with an empty body (the `DevToolsKeyFilter` convention: the surface does not advertise itself); a blank `X-Admin-Actor` is 400. The filter runs in every profile, prod included; `/api/admin/**` is `permitAll` because the filter is the guard. Curl only: no CORS header is added and no admin controller carries `@CrossOrigin` (against the house rule on purpose: it would allow every header).
- Every admin call, reads and refusals included, writes one `admin_actions` row (V10, T01: `actor` 80 NN, `user_id` with no foreign key so it outlives the user, `verb` 64 NN, `target_id` 80, `before_json`, `after_json`, `reason` 255, `created_at` NN in ET). A mutating verb writes it inside the transaction that changes the state.
- Prod refuses to start with a blank `app.admin-tools.key` (spec 12.1); this task also refuses the ci default, which is in git.
- `UserDeletionService.delete` is the single deletion path (refuses while money is in flight, Alpaca outside any transaction, then one `TransactionTemplate` that requests the analytics person deletion and queues `/item/remove`); its three-argument overload runs a hook first inside that transaction, where the audit row goes.
- `PlaidItemLifecycleService.enqueueRemoval(User)` is `MANDATORY`; the drainer calls Plaid later, outside any transaction. FRED's removal is `enqueueRemoval` plus `plaid_item_status = REMOVED` (decision 15); this write stays here, not in T13 (decision 55).
- Nothing here moves money, writes `InvestmentSchedule.status` (PENDING_BANK, ACTIVE, PAUSED_BY_USER) or `hold_reason`, touches `mode` or `isReadyForInvestment`, calls Plaid or Alpaca, or emits an analytics event. INFO logs carry verb, actor handle and user id only, never a token, cursor, descriptor, amount, email or the free-text reason.

`.../` = `backend/src/main/java/com/investingapp/backend/`; `T/` = `backend/src/test/java/com/investingapp/backend/`. Commands run from the worktree root; a leading `cd backend &&` applies to that line only. Lines are HEAD 137998c; T04 edits `BrokerEnvironmentGuard`, its test and the property files first, so anchors there are quoted text.

**Files:**
- Create: `.../security/AdminKeyValidator.java`, `.../security/AdminKeyFilter.java`, `.../config/AdminToolsConfig.java`, `.../service/AdminAuditService.java`, `.../controller/admin/AdminPlaidController.java`, `.../controller/admin/AdminUserController.java`, `.../dto/admin/PlaidItemRow.java`
- Modify: `.../config/DevToolsProdGuard.java:5,47`; `.../config/SecurityConfig.java:117` (one matcher after it; the CORS header list at `:93` is NOT extended); `.../config/BrokerEnvironmentGuard.java:91,136-142,243`; `.../service/UserDeletionService.java:26-27` (javadoc); `backend/src/main/resources/application.properties` after `app.dev-tools.key=${DEV_TOOLS_KEY:}` (`:111`), `application-ci.properties` after `:87`, `application-prod.properties` header (`:1-6`); `FREDdocs/API_ENDPOINTS.md:232,237`
- Delete: `.../controller/DevUserController.java`, `T/controller/DevUserControllerTest.java`
- Test: create `T/security/AdminKeyFilterTest.java`, `T/service/AdminAuditServiceTest.java`, `T/controller/admin/AdminPlaidControllerTest.java`, `T/controller/admin/AdminUserControllerTest.java`, `T/controller/admin/AdminVerbsAuditTest.java`, `T/controller/admin/AdminPlaidItemsDatabaseTest.java` (`@RequiresCiDatabase`); modify `T/config/BrokerEnvironmentGuardTest.java` and `T/config/DevToolsGatingTest.java:5,110-124`

**Interfaces:**
- Consumes (T01): `model.AdminAction` (Lombok accessors for `actor`, `Long userId`, `verb`, `targetId`, `beforeJson`, `afterJson`, `reason`, `LocalDateTime createdAt`), `repository.AdminActionRepository`. (T04): no type; its guard, guard-test fixture and property edits land first. (HEAD): `PlaidItemLifecycleService.enqueueRemoval(User)` (`:87-88`, MANDATORY); `UserRepository.findByIdForUpdate(Long)` (`:81-83`), `findById`; `UserDeletionService.delete(User, DeletionReason, Runnable)`, `DeletionReason.ADMIN` (`:45`); `DeletionDeferredException.reason`; `ErrorCodeResponse(String code, String message)`; `PlaidItemStatus.REMOVED`; `EasternTime.now()`; the `@Primary ObjectMapper` (`JacksonConfig`).
- Produces: exactly the list in the Produces section at the end of this file.

**Imports.** For the size limit, code blocks omit `import` lines (the only mechanical omission). Each file imports exactly what it names, resolved from this map (same package needs none):
- `java.io` InputStream, IOException; `java.lang.reflect` Method, RecordComponent; `java.nio.charset` StandardCharsets; `java.security` MessageDigest; `java.sql` ResultSet, SQLException; `java.time` LocalDateTime; `java.util` ArrayList, Arrays, LinkedHashMap, List, Locale, Map, Optional, Properties, UUID; `java.util.regex` Pattern; `java.util.stream` Stream.
- `jakarta.servlet` FilterChain, ServletException; `jakarta.servlet.http` HttpServletRequest, HttpServletResponse; `org.slf4j` Logger, LoggerFactory.
- Jackson: `com.fasterxml.jackson.core` JsonProcessingException; `.databind` JsonNode, ObjectMapper; `.databind.node` ArrayNode, ObjectNode; `.databind.json` JsonMapper; `com.fasterxml.jackson.datatype.jsr310` JavaTimeModule.
- Spring: `org.springframework.beans.factory.annotation` Autowired, Value; `.boot.web.servlet` FilterRegistrationBean; `.boot.test.context` SpringBootTest; `.boot.test.context.runner` ApplicationContextRunner; `.context.annotation` Bean, Configuration; `.core` Ordered; `.dao` DataAccessResourceFailureException; `.http` HttpStatus, ResponseEntity; `.jdbc.core` JdbcTemplate, RowMapper; `.mock.web` MockFilterChain, MockHttpServletRequest, MockHttpServletResponse; `.stereotype` Service; `.test.context` ActiveProfiles; `.transaction` IllegalTransactionStateException, PlatformTransactionManager, TransactionDefinition, TransactionSystemException; `.transaction.annotation` Propagation, Transactional; `.transaction.support` SimpleTransactionStatus, TransactionTemplate; `.web.bind.annotation` GetMapping, PathVariable, PostMapping, RequestMapping, RequestParam, RestController; `.web.cors` CorsUtils; `.web.filter` OncePerRequestFilter.
- Tests: `org.junit.jupiter.api` AfterEach, BeforeEach, DisplayName (guard test only), Test; `org.mockito` ArgumentCaptor, InOrder; static `org.mockito.Mockito` doAnswer, doThrow, inOrder, mock, never, reset, times, verify, verifyNoInteractions, when; static `org.mockito.ArgumentMatchers` any, eq, isNull; static `org.assertj.core.api.Assertions` assertThat, assertThatCode, assertThatThrownBy.
- FRED (`com.investingapp.backend.`): `model` AdminAction, PlaidItemStatus, User; `repository` AdminActionRepository, UserRepository; `service` AdminAuditService, DeletionDeferredException, PlaidItemLifecycleService, UserDeletionService; `security` AdminKeyFilter, AdminKeyValidator; `config` AdminToolsConfig; `dto` ErrorCodeResponse; `dto.admin` PlaidItemRow; `support` RequiresCiDatabase; `util` EasternTime.

Read counts from the XML, never the console: `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.<package>.<Class>.xml` ("XML for `<package>.<Class>`" below).

- [ ] **Step 1: Confirm T01 and T04 landed; record the guard-test baseline**

```
ls backend/src/main/java/com/investingapp/backend/model/AdminAction.java backend/src/main/java/com/investingapp/backend/repository/AdminActionRepository.java
grep -cE "private .* (actor|userId|verb|targetId|beforeJson|afterJson|reason|createdAt);" backend/src/main/java/com/investingapp/backend/model/AdminAction.java
grep -n "checkProdOpsAlertProvider" backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java
cd backend && ./gradlew test --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.config.BrokerEnvironmentGuardTest.xml
```

Expected: both files listed; `8`; the call and the method; `failures="0" errors="0"`. Write down its `tests` as N (28 at HEAD plus T04's). If any check fails, stop: T01 or T04 has not landed.

- [ ] **Step 2: Failing test for the filter, validator and every-profile registration**

Create `T/security/AdminKeyFilterTest.java`:

```java
package com.investingapp.backend.security;

class AdminKeyFilterTest {

    private static final String KEY = "admin-key-for-tests-only";

    private MockHttpServletRequest adminRequest(String key, String actor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users/7/delete");
        request.setRequestURI("/api/admin/users/7/delete");
        if (key != null) {
            request.addHeader(AdminKeyValidator.KEY_HEADER, key);
        }
        if (actor != null) {
            request.addHeader(AdminKeyValidator.ACTOR_HEADER, actor);
        }
        return request;
    }

    private MockFilterChain run(MockHttpServletRequest request, MockHttpServletResponse response,
            AdminKeyValidator validator) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new AdminKeyFilter(validator).doFilter(request, response, chain);
        return chain;
    }

    @Test
    void missingKeyIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest(null, "andy"), response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.getHeader("Content-Length")).isEqualTo("0");
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter chain must not continue").isNull();
    }

    @Test
    void missingActorIs400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest(KEY, null), response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void keyAndActorPassAndSetActorAttribute() throws Exception {
        MockHttpServletRequest request = adminRequest(KEY, "  andy  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(request, response, new AdminKeyValidator(KEY));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE)).isEqualTo("andy");
    }

    @Test
    void unconfiguredKeyDeniesAll() throws Exception {
        AdminKeyValidator unset = new AdminKeyValidator("");
        assertThat(unset.isConfigured()).isFalse();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = run(adminRequest("", "andy"), response, unset);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void preflightIsNotKeyChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/plaid/items");
        request.setRequestURI("/api/admin/plaid/items");
        request.addHeader("Origin", "http://localhost:4200");
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "x-admin-key,x-admin-actor");

        assertThat(run(request, new MockHttpServletResponse(), new AdminKeyValidator(KEY)).getRequest()).isNotNull();
    }

    @Test
    void validatorRejectsBadInput() {
        AdminKeyValidator validator = new AdminKeyValidator(KEY);
        assertThat(validator.matches(null)).isFalse();
        assertThat(validator.matches("")).isFalse();
        assertThat(validator.matches(KEY + "x")).isFalse();
        assertThat(validator.matches(KEY.toUpperCase())).isFalse();
        assertThat(validator.matches(KEY)).isTrue();
    }

    @Test
    void filterRegisteredInEveryProfileIncludingProd() {
        for (String profile : List.of("prod", "dev", "local", "test", "ci", "staging")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(AdminToolsConfig.class)
                    .withPropertyValues("spring.profiles.active=" + profile, "app.admin-tools.key=" + KEY)
                    .run(context -> {
                        FilterRegistrationBean<?> registration =
                                context.getBean("adminKeyFilter", FilterRegistrationBean.class);
                        assertThat(registration.getUrlPatterns()).containsExactly("/api/admin/*");
                        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                    });
        }
    }
}
```

Two more methods follow a shown body exactly, differing only in the request: `wrongKeyIs404` is `missingKeyIs404` with `adminRequest("not-the-key", "andy")`, asserting only the 404 status and the null chain request; `blankActorIs400` is `missingActorIs400` with `adminRequest(KEY, "   ")`. Each carries `@Test`. The class then has 9 tests.

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.AdminKeyFilterTest' --console=plain`. Expected: `BUILD FAILED`, `cannot find symbol` with `symbol:   class AdminKeyValidator` (and `class AdminToolsConfig`).

- [ ] **Step 3: Validator, filter and registration**

Create `.../security/AdminKeyValidator.java`:

```java
package com.investingapp.backend.security;

/** Compares a caller's admin key with app.admin-tools.key (spec 12.2); never shares the dev-tools secret; unset never matches. */
public class AdminKeyValidator {

    public static final String KEY_HEADER = "X-Admin-Key";

    /** Names the person behind the call; it becomes admin_actions.actor. */
    public static final String ACTOR_HEADER = "X-Admin-Actor";

    private final String expectedKey;

    public AdminKeyValidator(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    public boolean isConfigured() {
        return expectedKey != null && !expectedKey.isBlank();
    }

    /** Constant-time comparison; false when no key is configured or none was sent. */
    public boolean matches(String provided) {
        if (!isConfigured() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8));
    }
}
```

Create `.../security/AdminKeyFilter.java`:

```java
package com.investingapp.backend.security;

/**
 * Guards /api/admin/** in every profile (spec 12.2). Preflight passes (CORS then refuses admin
 * headers); wrong key 404, blank actor 400; then the trimmed actor becomes ACTOR_ATTRIBUTE, which
 * a client cannot set, so a verb reached without this filter fails closed.
 */
public class AdminKeyFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "admin.actor";

    private static final Logger logger = LoggerFactory.getLogger(AdminKeyFilter.class);

    private final AdminKeyValidator keyValidator;

    public AdminKeyFilter(AdminKeyValidator keyValidator) {
        this.keyValidator = keyValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!keyValidator.matches(request.getHeader(AdminKeyValidator.KEY_HEADER))) {
            logger.warn("Admin request rejected (missing or wrong {}): {} {}",
                    AdminKeyValidator.KEY_HEADER, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            return;
        }
        String actor = request.getHeader(AdminKeyValidator.ACTOR_HEADER);
        if (actor == null || actor.isBlank()) {
            logger.warn("Admin request rejected (missing {}): {} {}",
                    AdminKeyValidator.ACTOR_HEADER, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentLength(0);
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, actor.trim());
        filterChain.doFilter(request, response);
    }
}
```

Create `.../config/AdminToolsConfig.java`:

```java
package com.investingapp.backend.config;

/** Registers the admin key filter in EVERY profile, prod included (no @Profile, unlike DevToolsConfig). */
@Configuration
public class AdminToolsConfig {

    private static final Logger logger = LoggerFactory.getLogger(AdminToolsConfig.class);

    @Bean
    public AdminKeyValidator adminKeyValidator(@Value("${app.admin-tools.key:}") String adminToolsKey) {
        AdminKeyValidator validator = new AdminKeyValidator(adminToolsKey);
        if (!validator.isConfigured()) {
            logger.warn("app.admin-tools.key is empty: every /api/admin/** request will be rejected "
                    + "with 404. Set the ADMIN_TOOLS_KEY environment variable.");
        }
        return validator;
    }

    /** Ahead of Spring Security (-100); constructed here so Boot does not also register it for every URL. */
    @Bean
    public FilterRegistrationBean<AdminKeyFilter> adminKeyFilter(AdminKeyValidator adminKeyValidator) {
        FilterRegistrationBean<AdminKeyFilter> registration =
                new FilterRegistrationBean<>(new AdminKeyFilter(adminKeyValidator));
        registration.addUrlPatterns("/api/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("adminKeyFilter");
        return registration;
    }
}
```

Run the Step 2 command. Expected, XML for `security.AdminKeyFilterTest`: `tests="9" skipped="0" failures="0" errors="0"`.

- [ ] **Step 4: Failing test for the audit service**

Create `T/service/AdminAuditServiceTest.java`:

```java
package com.investingapp.backend.service;

class AdminAuditServiceTest {

    private static final Class<?>[] ARGS =
            {String.class, Long.class, String.class, String.class, Object.class, Object.class, String.class};

    private AdminActionRepository repository;
    private PlatformTransactionManager txManager;
    private AdminAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminActionRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AdminAuditService(repository,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(), txManager);
    }

    private AdminAction savedRow() {
        ArgumentCaptor<AdminAction> row = ArgumentCaptor.forClass(AdminAction.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    @Test
    void recordWritesOneRowInItsOwnTransaction() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("itemStatus", "OK");

        service.record("andy", 7L, "plaid.remove-item", "item-1", before, Map.of("outcome", "NO_ITEM"), "beta cleanup");

        AdminAction row = savedRow();
        assertThat(row.getActor()).isEqualTo("andy");
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getVerb()).isEqualTo("plaid.remove-item");
        assertThat(row.getTargetId()).isEqualTo("item-1");
        assertThat(row.getBeforeJson()).isEqualTo("{\"itemStatus\":\"OK\"}");
        assertThat(row.getAfterJson()).isEqualTo("{\"outcome\":\"NO_ITEM\"}");
        assertThat(row.getReason()).isEqualTo("beta cleanup");
        assertThat(row.getCreatedAt()).isNotNull();
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(txManager).commit(any());
    }

    @Test
    void recordNeverThrows() {
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        assertThatCode(() -> service.record("andy", 7L, "plaid.items", null, null, null, null))
                .doesNotThrowAnyException();

        reset(repository);
        doThrow(new TransactionSystemException("commit failed")).when(txManager).commit(any());
        assertThatCode(() -> service.record("andy", 7L, "plaid.items", null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void auditJsonNeverCarriesTokenCursorOrDescriptor() {
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("payerToken", "ACME PAYROLL");
        stream.put("rawDescriptor", "ACME PAYROLL PPD 123");
        stream.put("state", "PRIMARY");
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("plaidAccessToken", "ENC:secret-token");
        before.put("plaid_sync_cursor", "cursor-value");
        before.put("accessTokenEnc", "ENC:queued-token");
        before.put("ssnHash", "abc");
        before.put("itemStatus", "OK");
        before.put("streams", List.of(stream));

        service.record("andy", 7L, "plaid.items", null, before, null, null);

        AdminAction row = savedRow();
        assertThat(row.getBeforeJson()).isEqualTo("{\"itemStatus\":\"OK\",\"streams\":[{\"state\":\"PRIMARY\"}]}");
        assertThat(row.getAfterJson()).isNull();
    }

    @Test
    void overlongFieldsAreCutToTheirColumns() {
        service.record("a".repeat(100), 7L, "v".repeat(70), "t".repeat(90), null, null, "r".repeat(300));

        AdminAction row = savedRow();
        assertThat(row.getActor()).hasSize(80);
        assertThat(row.getVerb()).hasSize(64);
        assertThat(row.getTargetId()).hasSize(80);
        assertThat(row.getReason()).hasSize(255);
    }

    @Test
    void recordInTransactionIsMandatory() throws Exception {
        Transactional joined = AdminAuditService.class.getMethod("recordInTransaction", ARGS)
                .getAnnotation(Transactional.class);
        assertThat(joined).isNotNull();
        assertThat(joined.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(AdminAuditService.class.getMethod("record", ARGS).getAnnotation(Transactional.class)).isNull();
        assertThat(AdminAuditService.class.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void recordInTransactionFailureRollsTheVerbBack() {
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> service.recordInTransaction("andy", 7L, "users.delete", "pub-7", null, null, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(txManager);
    }

    @Test
    void actorComesOnlyFromTheFilterAttribute() {
        MockHttpServletRequest filtered = new MockHttpServletRequest();
        filtered.setAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE, "andy");
        assertThat(service.actor(filtered)).isEqualTo("andy");

        MockHttpServletRequest unfiltered = new MockHttpServletRequest("POST", "/api/admin/users/7/delete");
        unfiltered.addHeader(AdminKeyValidator.ACTOR_HEADER, "mallory");
        assertThatThrownBy(() -> service.actor(unfiltered))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admin.actor");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AdminAuditServiceTest' --console=plain`. Expected: `BUILD FAILED`, `cannot find symbol` with `symbol:   class AdminAuditService`.

- [ ] **Step 5: AdminAuditService**

Create `.../service/AdminAuditService.java`:

```java
package com.investingapp.backend.service;

/**
 * admin_actions (spec 12.2). record: refusals and GETs, REQUIRES_NEW via a template (so a failed
 * commit is caught too), never throws. recordInTransaction: mutating verbs, MANDATORY, commits or
 * rolls back with the state change. JSON keys containing token, cursor, descriptor, secret or
 * password, or starting with ssn, are dropped at every depth (a backstop).
 */
@Service
public class AdminAuditService {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuditService.class);

    static final int ACTOR_MAX = 80;
    static final int VERB_MAX = 64;
    static final int TARGET_MAX = 80;
    static final int REASON_MAX = 255;

    static final List<String> FORBIDDEN_KEY_FRAGMENTS = List.of("token", "cursor", "descriptor", "secret", "password");

    private final AdminActionRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AdminAuditService(AdminActionRepository repository, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String actor, Long userId, String verb, String targetId,
            Object before, Object after, String reason) {
        try {
            requiresNew.executeWithoutResult(status ->
                    repository.save(build(actor, userId, verb, targetId, before, after, reason)));
            logger.info("admin.action actor={} verb={} user={}", cut(actor, ACTOR_MAX), cut(verb, VERB_MAX), userId);
        } catch (RuntimeException e) {
            logger.error("admin.action audit write failed verb={} user={} cause={}",
                    cut(verb, VERB_MAX), userId, e.getClass().getSimpleName());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(String actor, Long userId, String verb, String targetId,
            Object before, Object after, String reason) {
        repository.save(build(actor, userId, verb, targetId, before, after, reason));
        logger.info("admin.action actor={} verb={} user={}", cut(actor, ACTOR_MAX), cut(verb, VERB_MAX), userId);
    }

    /** The actor AdminKeyFilter authenticated; throws when the filter did not run, so a verb fails closed. */
    public String actor(HttpServletRequest request) {
        Object actor = request.getAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE);
        if (actor instanceof String handle && !handle.isBlank()) {
            return handle;
        }
        throw new IllegalStateException(AdminKeyFilter.ACTOR_ATTRIBUTE
                + " is missing: AdminKeyFilter did not run for " + request.getRequestURI());
    }

    private AdminAction build(String actor, Long userId, String verb, String targetId,
            Object before, Object after, String reason) {
        AdminAction row = new AdminAction();
        row.setActor(cut(actor, ACTOR_MAX));
        row.setUserId(userId);
        row.setVerb(cut(verb, VERB_MAX));
        row.setTargetId(cut(targetId, TARGET_MAX));
        row.setBeforeJson(toJson(before));
        row.setAfterJson(toJson(after));
        row.setReason(cut(reason, REASON_MAX));
        row.setCreatedAt(EasternTime.now());
        return row;
    }

    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            scrub(tree);
            return objectMapper.writeValueAsString(tree);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return "{\"unserializable\":\"" + value.getClass().getSimpleName() + "\"}";
        }
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isForbidden(name)) {
                    object.remove(name);
                } else {
                    scrub(object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AdminAuditService::scrub);
        }
    }

    static boolean isForbidden(String propertyName) {
        String lower = propertyName.toLowerCase(Locale.ROOT);
        return lower.startsWith("ssn") || FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    static String cut(String value, int width) {
        return value == null || value.length() <= width ? value : value.substring(0, width);
    }
}
```

Run the Step 4 command. Expected, XML for `service.AdminAuditServiceTest`: `tests="7" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Failing tests for the three verbs**

Create `T/controller/admin/AdminPlaidControllerTest.java`:

```java
package com.investingapp.backend.controller.admin;

class AdminPlaidControllerTest {

    private static final String ACTOR = "andy";

    private JdbcTemplate jdbc;
    private UserRepository userRepository;
    private PlaidItemLifecycleService lifecycle;
    private AdminAuditService audit;
    private PlatformTransactionManager txManager;
    private AdminPlaidController controller;
    private User user;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        userRepository = mock(UserRepository.class);
        lifecycle = mock(PlaidItemLifecycleService.class);
        audit = mock(AdminAuditService.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(audit.actor(any())).thenReturn(ACTOR);
        controller = new AdminPlaidController(jdbc, userRepository, lifecycle, audit, txManager);
        user = new User("item-owner@fredvested.test");
        user.setId(7L);
        user.setPlaidAccessToken("ENC:tok");
        user.setPlaidItemId("item-1");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
    }

    @Test
    void removeItemQueuesMarksRemovedAndAuditsInOneTransaction() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.removeItem(7L, "beta cleanup", new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "QUEUED"));
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.REMOVED);
        InOrder order = inOrder(txManager, userRepository, lifecycle, audit);
        order.verify(txManager).getTransaction(any());
        order.verify(userRepository).findByIdForUpdate(7L);
        order.verify(lifecycle).enqueueRemoval(user);
        order.verify(userRepository).save(user);
        order.verify(audit).recordInTransaction(ACTOR, 7L, AdminPlaidController.VERB_REMOVE_ITEM, "item-1",
                Map.of("itemStatus", "OK", "hasItem", true, "removalQueued", false),
                Map.of("itemStatus", "REMOVED", "hasItem", true, "removalQueued", true), "beta cleanup");
        order.verify(txManager).commit(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void removeItemRefusalsWriteNothingAndAreAudited() {
        when(userRepository.findByIdForUpdate(8L)).thenReturn(Optional.empty());
        assertThat(controller.removeItem(8L, null, new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(audit).record(ACTOR, 8L, AdminPlaidController.VERB_REMOVE_ITEM, null, null,
                Map.of("outcome", "NOT_FOUND"), null);

        User noItem = new User("no-item@fredvested.test");
        noItem.setId(9L);
        when(userRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(noItem));
        ResponseEntity<?> conflict = controller.removeItem(9L, null, new MockHttpServletRequest());
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ErrorCodeResponse) conflict.getBody()).code()).isEqualTo("NO_PLAID_ITEM");

        user.setPlaidItemStatus(PlaidItemStatus.REMOVED);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        assertThat(((ErrorCodeResponse) controller.removeItem(7L, null, new MockHttpServletRequest()).getBody()).code())
                .isEqualTo("ITEM_ALREADY_REMOVED");

        verify(lifecycle, never()).enqueueRemoval(any());
        verify(userRepository, never()).save(any());
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
        verify(audit, times(3)).record(eq(ACTOR), any(), eq(AdminPlaidController.VERB_REMOVE_ITEM),
                any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void itemsListingIsAudited() {
        PlaidItemRow row = new PlaidItemRow("pub-1", "OK", Boolean.TRUE, true, false, null, null, false);
        when(jdbc.query(eq(AdminPlaidController.ITEMS_SQL), any(RowMapper.class))).thenReturn(List.of(row));

        ResponseEntity<List<PlaidItemRow>> response = controller.items(new MockHttpServletRequest());

        assertThat(response.getBody()).containsExactly(row);
        verify(audit).record(ACTOR, null, AdminPlaidController.VERB_ITEMS, null, null, Map.of("rows", 1), null);
    }

    @Test
    void listingRowHasNoTokenOrCursorComponent() {
        assertThat(Arrays.stream(PlaidItemRow.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("publicId", "itemStatus", "transactionsConsented", "hasCursor",
                        "historyComplete", "lastSyncAt", "linkedAt", "pendingRemoval");
        assertThat(Pattern.compile("plaid_(access_token|sync_cursor)(?! IS NOT NULL)")
                .matcher(AdminPlaidController.ITEMS_SQL).find()).isFalse();
    }

    @Test
    void verbsRefuseWithoutFilterActor() {
        doThrow(new IllegalStateException("admin.actor is missing")).when(audit).actor(any());

        assertThatThrownBy(() -> controller.items(new MockHttpServletRequest())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.removeItem(7L, null, new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc, userRepository, lifecycle, txManager);
    }
}
```

Create `T/controller/admin/AdminUserControllerTest.java`:

```java
package com.investingapp.backend.controller.admin;

class AdminUserControllerTest {

    private static final String ACTOR = "andy";
    private static final Map<String, Object> BEFORE =
            Map.of("accountStatus", "ACTIVE", "hasPlaidItem", false, "plaidItemStatus", "OK");

    private UserRepository userRepository;
    private UserDeletionService userDeletionService;
    private AdminAuditService audit;
    private AdminUserController controller;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userDeletionService = mock(UserDeletionService.class);
        audit = mock(AdminAuditService.class);
        when(audit.actor(any())).thenReturn(ACTOR);
        controller = new AdminUserController(userRepository, userDeletionService, audit);
        user = new User("admin-delete@fredvested.test");
        user.setId(7L);
        user.setPublicId("pub-7");
        user.setAccountStatus("ACTIVE");
    }

    @Test
    void unknownUserIs404AndAudited() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller.deleteUser(7L, "cleanup", new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(userDeletionService);
        verify(audit).record(ACTOR, 7L, AdminUserController.VERB_DELETE, null, null,
                Map.of("outcome", "NOT_FOUND"), "cleanup");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesAsAdminWithAuditInsideTheDeletionTransaction() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.deleteUser(7L, null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("deleted", true).containsEntry("userId", 7L);
        ArgumentCaptor<Runnable> hook = ArgumentCaptor.forClass(Runnable.class);
        verify(userDeletionService).delete(eq(user), eq(UserDeletionService.DeletionReason.ADMIN), hook.capture());
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());

        hook.getValue().run();

        verify(audit).recordInTransaction(ACTOR, 7L, AdminUserController.VERB_DELETE, "pub-7", BEFORE,
                Map.of("outcome", "DELETED"), null);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deferredIs409AndAudited() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW")).when(userDeletionService)
                .delete(eq(user), eq(UserDeletionService.DeletionReason.ADMIN), any(Runnable.class));

        ResponseEntity<?> response = controller.deleteUser(7L, null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
        verify(audit).record(ACTOR, 7L, AdminUserController.VERB_DELETE, "pub-7", BEFORE,
                Map.of("outcome", "DEFERRED", "deferral", "SETTLEMENT_WINDOW"), null);
    }

    @Test
    void failureIsAuditedAndRethrown() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        IllegalStateException failure = new IllegalStateException("database down");
        doThrow(failure).when(userDeletionService)
                .delete(eq(user), eq(UserDeletionService.DeletionReason.ADMIN), any(Runnable.class));

        assertThatThrownBy(() -> controller.deleteUser(7L, null, new MockHttpServletRequest())).isSameAs(failure);
        verify(audit).record(ACTOR, 7L, AdminUserController.VERB_DELETE, "pub-7", BEFORE,
                Map.of("outcome", "FAILED"), null);
    }
}
```

Create `T/controller/admin/AdminVerbsAuditTest.java`:

```java
package com.investingapp.backend.controller.admin;

class AdminVerbsAuditTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyVerbWritesAdminAction() {
        AdminAuditService audit = mock(AdminAuditService.class);
        when(audit.actor(any())).thenReturn("andy");
        UserRepository users = mock(UserRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(AdminPlaidController.ITEMS_SQL), any(RowMapper.class))).thenReturn(List.of());
        UserDeletionService deletion = mock(UserDeletionService.class);
        AdminPlaidController plaid = new AdminPlaidController(jdbc, users, mock(PlaidItemLifecycleService.class), audit, tx);
        AdminUserController userVerbs = new AdminUserController(users, deletion, audit);
        User linked = new User("linked@fredvested.test");
        linked.setId(7L);
        linked.setPlaidAccessToken("ENC:tok");
        User leaving = new User("leaving@fredvested.test");
        leaving.setId(8L);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(linked));
        when(users.findById(8L)).thenReturn(Optional.of(leaving));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(deletion).delete(eq(leaving), eq(UserDeletionService.DeletionReason.ADMIN), any(Runnable.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        plaid.items(request);
        plaid.removeItem(7L, null, request);
        userVerbs.deleteUser(8L, null, request);

        verify(audit).record(eq("andy"), isNull(), eq(AdminPlaidController.VERB_ITEMS), any(), any(), any(), any());
        verify(audit).recordInTransaction(eq("andy"), eq(7L), eq(AdminPlaidController.VERB_REMOVE_ITEM),
                any(), any(), any(), any());
        verify(audit).recordInTransaction(eq("andy"), eq(8L), eq(AdminUserController.VERB_DELETE),
                any(), any(), any(), any());
        List<String> handlers = Stream.of(AdminPlaidController.class, AdminUserController.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class))
                .map(Method::getName).sorted().toList();
        assertThat(handlers).as("a new admin verb needs its audit assertion in this test")
                .containsExactly("deleteUser", "items", "removeItem");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminPlaidControllerTest' --console=plain`. Expected: `BUILD FAILED`, `cannot find symbol` with `symbol:   class AdminPlaidController`, `class PlaidItemRow` and `class AdminUserController`.

- [ ] **Step 7: PlaidItemRow and AdminPlaidController**

Create `.../dto/admin/PlaidItemRow.java`:

```java
package com.investingapp.backend.dto.admin;

/** One row of GET /api/admin/plaid/items, keyed by public_id; never the token or cursor as values. Times are ET. */
public record PlaidItemRow(String publicId, String itemStatus, Boolean transactionsConsented,
        boolean hasCursor, boolean historyComplete, LocalDateTime lastSyncAt, LocalDateTime linkedAt,
        boolean pendingRemoval) {
}
```

Create `.../controller/admin/AdminPlaidController.java`:

```java
package com.investingapp.backend.controller.admin;

/**
 * Admin Plaid Item verbs (spec 12.2); no @CrossOrigin. remove-item queues /item/remove and marks
 * REMOVED with its audit row in one transaction under the row lock; schedule, status, holds and the
 * ACH relationship are untouched, and a user still waiting for an ACH relationship cannot get one.
 */
@RestController
@RequestMapping("/api/admin/plaid")
public class AdminPlaidController {

    static final String VERB_ITEMS = "plaid.items";
    static final String VERB_REMOVE_ITEM = "plaid.remove-item";

    /** Selects no secret: the token and the cursor appear only inside IS NOT NULL tests. */
    static final String ITEMS_SQL = """
            SELECT u.public_id, u.plaid_item_status, u.plaid_transactions_consented,
                   u.plaid_sync_cursor IS NOT NULL AS has_cursor, u.plaid_history_complete,
                   u.plaid_last_sync_at, u.plaid_item_linked_at,
                   EXISTS (SELECT 1 FROM plaid_item_removals r
                           WHERE r.user_id = u.id AND r.removed_at IS NULL) AS pending_removal
            FROM users u
            WHERE u.plaid_access_token IS NOT NULL
            ORDER BY u.id
            """;

    enum RemoveOutcome { QUEUED, NOT_FOUND, NO_ITEM, ALREADY_REMOVED }

    record RemoveResult(RemoveOutcome outcome, String itemId, Map<String, Object> before) {
    }

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final PlaidItemLifecycleService plaidItemLifecycleService;
    private final AdminAuditService adminAuditService;
    private final TransactionTemplate writeTx;

    public AdminPlaidController(JdbcTemplate jdbc, UserRepository userRepository,
            PlaidItemLifecycleService plaidItemLifecycleService, AdminAuditService adminAuditService,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.plaidItemLifecycleService = plaidItemLifecycleService;
        this.adminAuditService = adminAuditService;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    @GetMapping("/items")
    public ResponseEntity<List<PlaidItemRow>> items(HttpServletRequest request) {
        String actor = adminAuditService.actor(request);
        List<PlaidItemRow> rows = jdbc.query(ITEMS_SQL, (rs, rowNum) -> toRow(rs));
        adminAuditService.record(actor, null, VERB_ITEMS, null, null, Map.of("rows", rows.size()), null);
        return ResponseEntity.ok(rows);
    }

    /** 200 QUEUED; 404 unknown user; 409 NO_PLAID_ITEM or ITEM_ALREADY_REMOVED; every outcome audited. */
    @PostMapping("/{userId}/remove-item")
    public ResponseEntity<?> removeItem(@PathVariable("userId") Long userId,
            @RequestParam(value = "reason", required = false) String reason, HttpServletRequest request) {
        String actor = adminAuditService.actor(request);
        RemoveResult result = writeTx.execute(status -> userRepository.findByIdForUpdate(userId)
                .map(user -> removeLocked(user, actor, reason))
                .orElseGet(() -> new RemoveResult(RemoveOutcome.NOT_FOUND, null, null)));
        if (result.outcome() != RemoveOutcome.QUEUED) {
            // A refusal wrote nothing, so its audit row commits on its own.
            adminAuditService.record(actor, userId, VERB_REMOVE_ITEM, result.itemId(), result.before(),
                    Map.of("outcome", result.outcome().name()), reason);
        }
        return switch (result.outcome()) {
            case QUEUED -> ResponseEntity.ok(Map.of("result", "QUEUED"));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NO_ITEM -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("NO_PLAID_ITEM", "This user has no linked Plaid Item."));
            case ALREADY_REMOVED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("ITEM_ALREADY_REMOVED", "This Item is already REMOVED and queued."));
        };
    }

    private RemoveResult removeLocked(User user, String actor, String reason) {
        Map<String, Object> before = itemState(user, false);
        if (user.getPlaidAccessToken() == null || user.getPlaidAccessToken().isBlank()) {
            return new RemoveResult(RemoveOutcome.NO_ITEM, user.getPlaidItemId(), before);
        }
        if (user.getPlaidItemStatus() == PlaidItemStatus.REMOVED) {
            return new RemoveResult(RemoveOutcome.ALREADY_REMOVED, user.getPlaidItemId(), before);
        }
        plaidItemLifecycleService.enqueueRemoval(user);
        user.setPlaidItemStatus(PlaidItemStatus.REMOVED);
        userRepository.save(user);
        adminAuditService.recordInTransaction(actor, user.getId(), VERB_REMOVE_ITEM, user.getPlaidItemId(),
                before, itemState(user, true), reason);
        return new RemoveResult(RemoveOutcome.QUEUED, user.getPlaidItemId(), before);
    }

    /** Closed vocabulary only: the status name and two flags, never the token. */
    private static Map<String, Object> itemState(User user, boolean removalQueued) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("itemStatus", user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name());
        state.put("hasItem", user.getPlaidAccessToken() != null && !user.getPlaidAccessToken().isBlank());
        state.put("removalQueued", removalQueued);
        return state;
    }

    private static PlaidItemRow toRow(ResultSet rs) throws SQLException {
        return new PlaidItemRow(
                rs.getString("public_id"),
                rs.getString("plaid_item_status"),
                nullableBoolean(rs, "plaid_transactions_consented"),
                rs.getBoolean("has_cursor"),
                rs.getBoolean("plaid_history_complete"),
                rs.getObject("plaid_last_sync_at", LocalDateTime.class),
                rs.getObject("plaid_item_linked_at", LocalDateTime.class),
                rs.getBoolean("pending_removal"));
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
```

- [ ] **Step 8: AdminUserController; run the three verb classes**

Create `.../controller/admin/AdminUserController.java`:

```java
package com.investingapp.backend.controller.admin;

/** The admin delete verb (spec 7.9, 12.2); the audit row rides the deletion transaction's hook and outlives the user. No @CrossOrigin. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    static final String VERB_DELETE = "users.delete";

    private final UserRepository userRepository;
    private final UserDeletionService userDeletionService;
    private final AdminAuditService adminAuditService;

    public AdminUserController(UserRepository userRepository, UserDeletionService userDeletionService,
            AdminAuditService adminAuditService) {
        this.userRepository = userRepository;
        this.userDeletionService = userDeletionService;
        this.adminAuditService = adminAuditService;
    }

    /** 200 deleted, 404 unknown id, 409 DELETION_DEFERRED; every outcome audited. */
    @PostMapping("/{id}/delete")
    public ResponseEntity<?> deleteUser(@PathVariable("id") Long id,
            @RequestParam(value = "reason", required = false) String reason, HttpServletRequest request) {
        String actor = adminAuditService.actor(request);
        Optional<User> found = userRepository.findById(id);
        if (found.isEmpty()) {
            adminAuditService.record(actor, id, VERB_DELETE, null, null, Map.of("outcome", "NOT_FOUND"), reason);
            return ResponseEntity.notFound().build();
        }
        User user = found.get();
        String publicId = user.getPublicId();
        Map<String, Object> before = snapshot(user);
        try {
            userDeletionService.delete(user, UserDeletionService.DeletionReason.ADMIN,
                    () -> adminAuditService.recordInTransaction(actor, id, VERB_DELETE, publicId, before,
                            Map.of("outcome", "DELETED"), reason));
        } catch (DeletionDeferredException deferred) {
            adminAuditService.record(actor, id, VERB_DELETE, publicId, before,
                    Map.of("outcome", "DEFERRED", "deferral", deferred.reason), reason);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("DELETION_DEFERRED", deferred.reason));
        } catch (RuntimeException failed) {
            adminAuditService.record(actor, id, VERB_DELETE, publicId, before, Map.of("outcome", "FAILED"), reason);
            throw failed;
        }
        return ResponseEntity.ok(Map.of("deleted", true, "userId", id));
    }

    /** Closed vocabulary only: no email, no name, no token. */
    private static Map<String, Object> snapshot(User user) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("accountStatus", user.getAccountStatus());
        state.put("hasPlaidItem", user.getPlaidAccessToken() != null);
        state.put("plaidItemStatus", user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name());
        return state;
    }
}
```

Run, one class per line:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminPlaidControllerTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminUserControllerTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminVerbsAuditTest' --console=plain
```

Expected, XML for each: `tests="5"`, `tests="4"`, `tests="1"`, each `skipped="0" failures="0" errors="0"`.

- [ ] **Step 9: Delete the dev verb and every reference to it**

```
git rm backend/src/main/java/com/investingapp/backend/controller/DevUserController.java backend/src/test/java/com/investingapp/backend/controller/DevUserControllerTest.java
```

`.../config/DevToolsProdGuard.java`: delete `:5` (`import com.investingapp.backend.controller.DevUserController;`) and `:47` (`        addIfPresent(beanFactory, DevUserController.class, offenders);`).

`T/config/DevToolsGatingTest.java`: delete `:5` (the same import) and `:110-125`: method `prodGuardFailsStartupWhenDevUserControllerPresent` with its `@Test` and `@DisplayName` lines and the blank line after it.

`.../service/UserDeletionService.java:26-27`: replace

```java
 * The one path that removes a FRED user (spec 7.9). Callers: AccountStatusService.handleRejected,
 * TradingController.closeAccount and the dev-profile admin verb in DevUserController.
```

with

```java
 * The one path that removes a FRED user (spec 7.9). Callers: TradingController.closeAccount and
 * the admin verb in AdminUserController (POST /api/admin/users/{id}/delete). A KYC rejection keeps
 * the row: AccountStatusService.handleRejected sets a MANUAL_REVIEW hold instead.
```

Run:

```
grep -rnE "DevUserController|api/dev/users" backend/src frontend/src test
cd backend && ./gradlew test --tests 'com.investingapp.backend.config.DevToolsGatingTest' --console=plain
```

Expected: the grep prints nothing; XML for `config.DevToolsGatingTest`: `tests="8" skipped="0" failures="0" errors="0"`.

- [ ] **Step 10: Failing guard tests for the admin key**

`T/config/BrokerEnvironmentGuardTest.java`:

1. Add `import java.io.InputStream;`, `import java.util.Properties;` and `import static org.assertj.core.api.Assertions.assertThat;` beside the existing imports.
2. After the quoted constant `    private static final String PROD_JWT_SECRET = "prod-signing-key-not-the-dev-one";` add `    private static final String PROD_ADMIN_TOOLS_KEY = "prod-admin-key-not-the-ci-one";`.
3. In the six-argument `env(...)` helper, inside `if (prod) {`, after its last statement (HEAD ends with `.withProperty("jwt.secret", PROD_JWT_SECRET);`; T04 added its ops-alert properties there), add `            env.withProperty("app.admin-tools.key", PROD_ADMIN_TOOLS_KEY);` so `prodLiveStarts` stays green.
4. Before the final `}` of the class add:

```java
    @Test
    @DisplayName("prod with a blank admin tools key refuses to start")
    void prodRefusesUnsetAdminKey() {
        for (String blank : new String[] {"", "   "}) {
            assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                    .withProperty("app.admin-tools.key", blank)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.admin-tools.key is not set");
        }
    }

    @Test
    @DisplayName("prod on the ci admin key committed to this repo refuses to start, and never echoes it")
    void prodRefusesCiAdminKey() throws Exception {
        Properties ci = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application-ci.properties")) {
            ci.load(in);
        }
        assertThat(ci.getProperty("app.admin-tools.key"))
                .isEqualTo("${ADMIN_TOOLS_KEY:" + BrokerEnvironmentGuard.CI_ADMIN_TOOLS_KEY + "}");

        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("app.admin-tools.key", BrokerEnvironmentGuard.CI_ADMIN_TOOLS_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin-tools.key is the ci default")
                .hasMessageNotContaining(BrokerEnvironmentGuard.CI_ADMIN_TOOLS_KEY);
    }
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest' --console=plain`. Expected: `BUILD FAILED`, `cannot find symbol` with `symbol:   variable CI_ADMIN_TOOLS_KEY`.

- [ ] **Step 11: The guard rule and the ci key**

`.../config/BrokerEnvironmentGuard.java`:

1. Before the quoted line `    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");` add:

```java
    /** The throwaway admin key committed in application-ci.properties; never acceptable in prod. */
    static final String CI_ADMIN_TOOLS_KEY = "ci-local-admin-tools";

```

2. Inside `if (prod) {` in `postProcessBeanFactory`, as the block's last call (after `checkProdJwtSecret(violations);` and T04's `checkProdOpsAlertProvider(violations);`), add `            checkProdAdminKey(violations);`.
3. Before the javadoc whose second line is the quoted `     * Shared override hygiene for {@code plaid.known-sandbox-secret-sha256} and`, add:

```java
    /**
     * The admin surface is registered in prod and guarded only by ADMIN_TOOLS_KEY (spec 12.1):
     * blank fails closed, leaving support without tools in an incident; the ci default is in git
     * history. The value is never echoed.
     */
    private void checkProdAdminKey(List<String> violations) {
        String value = environment.getProperty("app.admin-tools.key", "");
        if (value.isBlank()) {
            violations.add("app.admin-tools.key is not set; set ADMIN_TOOLS_KEY");
        } else if (CI_ADMIN_TOOLS_KEY.equals(value.trim())) {
            violations.add("app.admin-tools.key is the ci default committed to this repo; set ADMIN_TOOLS_KEY");
        }
    }

```

`backend/src/main/resources/application-ci.properties`, after the quoted `app.dev-tools.key=${DEV_TOOLS_KEY:ci-local-dev-tools}` (HEAD `:87`, last in the file):

```properties

# --- Admin tools: throwaway key for the ephemeral CI container; prod refuses this exact value ---
app.admin-tools.key=${ADMIN_TOOLS_KEY:ci-local-admin-tools}
```

Run the Step 10 command. Expected, XML for `config.BrokerEnvironmentGuardTest`: `tests="N+2"` (N from Step 1), `skipped="0" failures="0" errors="0"`.

- [ ] **Step 12: Security matcher, base and prod properties, local note**

`.../config/SecurityConfig.java`: after `:117`, the quoted line `                .requestMatchers("/api/dev/**").permitAll() // 🧪 DEV ONLY: registered only in local/dev/test/ci, and DevToolsKeyFilter requires X-Dev-Tools-Key`, add:

```java
                .requestMatchers("/api/admin/**").permitAll() // every profile; AdminKeyFilter is the guard; curl only (no CORS header)
```

`backend/src/main/resources/application.properties`, after the quoted `app.dev-tools.key=${DEV_TOOLS_KEY:}`:

```properties

# --- Admin tools (/api/admin/**, spec 12.2): every profile, prod included -----
# X-Admin-Key must match (404 otherwise), X-Admin-Actor is required (400); unset denies everything.
app.admin-tools.key=${ADMIN_TOOLS_KEY:}
```

`backend/src/main/resources/application-prod.properties`: one line at the end of the header comment (after T04's `OPS_ALERT_*` line), directly above `spring.datasource.url=${DATASOURCE_URL_PROD}`:

```properties
#   ADMIN_TOOLS_KEY (a long random value: the /api/admin surface is registered in prod and the guard refuses blank).
```

The prod file gets no `app.admin-tools.key` line: it inherits the base default, so an unset variable reaches the guard's message rather than an unresolved-placeholder failure.

**Local-properties note.** `application-local.properties` is gitignored, so this commit cannot reach it. Local boots without it (blank key, every admin call 404). To use the verbs locally, add by hand, beside its `app.dev-tools.key` line, with a long random value of your own:

```properties
# --- Admin tools: local key (this file is gitignored) ---
app.admin-tools.key=${ADMIN_TOOLS_KEY:<your own long random value>}
```

Andy sets `ADMIN_TOOLS_KEY` on Railway dev himself (R16).

Run `cd backend && ./gradlew compileJava --console=plain`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13: The real-database test, on a scratch MySQL**

Create `T/controller/admin/AdminPlaidItemsDatabaseTest.java`:

```java
package com.investingapp.backend.controller.admin;

/** Real SQL on the V6 columns, one commit for remove-item's three writes, and MANDATORY through the real proxy. */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class AdminPlaidItemsDatabaseTest {

    private static final String ACTOR = "t17-database-test";

    @Autowired
    private AdminPlaidController controller;

    @Autowired
    private AdminAuditService adminAuditService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM admin_actions WHERE actor = ?", ACTOR);
        for (Long id : userIds) {
            jdbc.update("DELETE FROM plaid_item_removals WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private Long seedLinkedUser(String publicId, String token, String cursor) {
        String email = "admin-items-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, plaid_access_token, "
                        + "plaid_item_id, plaid_sync_cursor, plaid_item_status, plaid_history_complete) "
                        + "VALUES (?, ?, ?, 0, ?, ?, ?, 'OK', 1)",
                email, EasternTime.now(), publicId, token, "item-" + publicId.substring(0, 8), cursor);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        userIds.add(id);
        return id;
    }

    private MockHttpServletRequest adminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE, ACTOR);
        return request;
    }

    private int auditRows(String verb) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_actions WHERE actor = ? AND verb = ?",
                Integer.class, ACTOR, verb);
    }

    private int pendingRemovals(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM plaid_item_removals WHERE user_id = ? AND removed_at IS NULL",
                Integer.class, userId);
    }

    @Test
    void listingNeverCarriesTokenOrCursor() throws Exception {
        String publicId = UUID.randomUUID().toString();
        seedLinkedUser(publicId, "ENC:token-that-must-not-leak", "cursor-that-must-not-leak");

        ResponseEntity<List<PlaidItemRow>> response = controller.items(adminRequest());

        PlaidItemRow row = response.getBody().stream()
                .filter(r -> publicId.equals(r.publicId())).findFirst().orElseThrow();
        assertThat(row.itemStatus()).isEqualTo("OK");
        assertThat(row.hasCursor()).isTrue();
        assertThat(row.historyComplete()).isTrue();
        assertThat(row.transactionsConsented()).isNull();
        assertThat(row.pendingRemoval()).isFalse();
        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(json).doesNotContain("token-that-must-not-leak").doesNotContain("cursor-that-must-not-leak");
        assertThat(auditRows(AdminPlaidController.VERB_ITEMS)).isEqualTo(1);
    }

    @Test
    void removeItemCommitsQueueStatusAndAuditTogether() {
        Long id = seedLinkedUser(UUID.randomUUID().toString(), "ENC:token-to-remove", null);

        assertThat(controller.removeItem(id, "beta cleanup", adminRequest()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(pendingRemovals(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plaid_item_status FROM users WHERE id = ?", String.class, id))
                .isEqualTo("REMOVED");
        String after = jdbc.queryForObject("SELECT after_json FROM admin_actions WHERE actor = ? AND user_id = ?",
                String.class, ACTOR, id);
        assertThat(after).contains("\"itemStatus\":\"REMOVED\"").doesNotContain("token-to-remove");

        assertThat(controller.removeItem(id, null, adminRequest()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pendingRemovals(id)).isEqualTo(1);
        assertThat(auditRows(AdminPlaidController.VERB_REMOVE_ITEM)).isEqualTo(2);
    }

    @Test
    void recordInTransactionRefusesWithoutTransaction() {
        assertThatThrownBy(() -> adminAuditService.recordInTransaction(ACTOR, null, "test.verb", null, null, null, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(auditRows("test.verb")).isZero();
    }
}
```

It pins the real boundary of written code, so it passes first time; a failure is a finding about Steps 3 to 8, not a test to adjust. Run the block as ONE shell from the worktree root (variables do not survive separate ones); it reads the MySQL login from the gitignored local properties unprinted and also runs the context test (the new beans boot under `ci`):

```
L=backend/src/main/resources/application-local.properties
U=$(sed -n 's/^spring\.datasource\.username=//p' $L); P=$(sed -n 's/^spring\.datasource\.password=//p' $L)
db() { MYSQL_PWD="$P" mysql -h 127.0.0.1 -u "$U" -e "$1"; }
db "DROP DATABASE IF EXISTS fred_t17; CREATE DATABASE fred_t17"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t17?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$U" SPRING_DATASOURCE_PASSWORD="$P" PLAID_LINK_CUSTOMIZATION=default
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook' PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
(cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.controller.admin.AdminPlaidItemsDatabaseTest' --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-*.{controller.admin.AdminPlaidItemsDatabaseTest,BackendApplicationTests}.xml
db "DROP DATABASE fred_t17"
unset L U P SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION
```

Expected (from the XML; the console says `BUILD SUCCESSFUL` for skipped tests too): `tests="3" skipped="0" failures="0" errors="0"` and `tests="1" skipped="0" failures="0" errors="0"`. `skipped="3"` means the scratch database was unreachable: fix the credentials and re-run.

- [ ] **Step 14: API_ENDPOINTS.md**

Directly before the quoted heading `## Authentication Notes` (keep the `---` above it), insert:

```markdown
## 15. Admin (`/api/admin`), every profile, curl only

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/admin/plaid/items` | One row per user holding a Plaid token, by `publicId`; never the token or cursor | `X-Admin-Key` + `X-Admin-Actor` | None | `PlaidItemRow[]` |
| `POST` | `/api/admin/plaid/{userId}/remove-item?reason=` | Queue `/item/remove`, mark `REMOVED`; schedule, holds and ACH untouched | `X-Admin-Key` + `X-Admin-Actor` | None | 200 `{ result: "QUEUED" }`; 404; 409 `NO_PLAID_ITEM` or `ITEM_ALREADY_REMOVED` |
| `POST` | `/api/admin/users/{id}/delete?reason=` | Delete the user (reason ADMIN); replaces `POST /api/dev/users/{id}/delete` | `X-Admin-Key` + `X-Admin-Actor` | None | 200 `{ deleted: true, userId }`; 404; 409 `DELETION_DEFERRED` |

> **Every profile, prod included.** A missing or wrong `X-Admin-Key` (env `ADMIN_TOOLS_KEY`; prod
> refuses to start without it) gets **404 with an empty body**; a blank `X-Admin-Actor` gets **400**.
> Every call writes one `admin_actions` row. Curl only (no CORS). A write verb's user id comes from
> `SELECT id FROM users WHERE public_id = ?`.

---

```

After the quoted line ``- `/api/dev/*` endpoints are development-only, disabled in production.`` add ``- `/api/admin/*` endpoints exist in every profile and take `X-Admin-Key` plus `X-Admin-Actor`, never a JWT (section 15).``. T16 and T18 edit this file too: anchor on quoted text and reorder nothing.

- [ ] **Step 15: Run everything this task touches**

Run the Verification commands below and compare every count. Then:

```
grep -rnE "setStatus\([[:space:]]*(InvestmentSchedule\.)?Status\.|setMode\(|getIsPaused\(|isPausedForAnyReason\(|setHoldReason\(" backend/src/main/java/com/investingapp/backend/controller/admin backend/src/main/java/com/investingapp/backend/service/AdminAuditService.java
grep -rn "CrossOrigin" backend/src/main/java/com/investingapp/backend/controller/admin
git diff HEAD -- backend/src FREDdocs/API_ENDPOINTS.md | grep -n "^+.*$(printf '\342\200\224')"
```

Expected: all three print nothing (no money-state write, no `@CrossOrigin` on an admin controller, no em dash on any added line).

- [ ] **Step 16: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (shared dirty tree: stage only your hunks; never `git checkout`, `git restore`, `git stash` or `git reset`; never stage `application-local.properties` or `ITPM/agent-memory/*`), then:

```
M=backend/src/main/java/com/investingapp/backend; T=backend/src/test/java/com/investingapp/backend; R=backend/src/main/resources
git add $M/security/AdminKeyValidator.java $M/security/AdminKeyFilter.java $M/config/AdminToolsConfig.java $M/service/AdminAuditService.java $M/controller/admin/AdminPlaidController.java $M/controller/admin/AdminUserController.java $M/dto/admin/PlaidItemRow.java $M/config/DevToolsProdGuard.java $M/config/SecurityConfig.java $M/config/BrokerEnvironmentGuard.java $M/service/UserDeletionService.java $R/application.properties $R/application-ci.properties $R/application-prod.properties $T/security/AdminKeyFilterTest.java $T/service/AdminAuditServiceTest.java $T/controller/admin/AdminPlaidControllerTest.java $T/controller/admin/AdminUserControllerTest.java $T/controller/admin/AdminVerbsAuditTest.java $T/controller/admin/AdminPlaidItemsDatabaseTest.java $T/config/BrokerEnvironmentGuardTest.java $T/config/DevToolsGatingTest.java FREDdocs/API_ENDPOINTS.md
git status --short $M/controller/DevUserController.java $T/controller/DevUserControllerTest.java
git commit -m "feat(admin): guard and audit the admin surface, add the Plaid item and user deletion verbs"
```

Append your session's attribution trailer. Step 9's `git rm` staged both deletions (`git status` shows `D `).

### Verification

Each class is one command, `cd backend && ./gradlew test --tests 'com.investingapp.backend.<class>' --console=plain`, read from its XML; every line must show `skipped="0" failures="0" errors="0"`:

- `security.AdminKeyFilterTest` 9; `security.DevToolsKeyFilterTest` 7 (unchanged); `service.AdminAuditServiceTest` 7; `controller.admin.AdminPlaidControllerTest` 5; `controller.admin.AdminUserControllerTest` 4; `controller.admin.AdminVerbsAuditTest` 1; `config.BrokerEnvironmentGuardTest` N+2 (Step 1); `config.DevToolsGatingTest` 8; `PhaseOneMoneyInvarianceTest` unchanged, only if T03 has landed (its walk covers `controller/admin/**` and `service/Admin*`).
- The Step 13 block: `controller.admin.AdminPlaidItemsDatabaseTest` 3 and `BackendApplicationTests` 1; then that block with both `--tests` filters removed (the whole suite) and its grep over `backend/build/test-results/test/*.xml`: summed `tests` = pre-task total + 27 (31 new, minus DevUserControllerTest's 3 and DevToolsGatingTest's 1).
- The three Step 15 greps and `grep -rnE "DevUserController|api/dev/users" backend/src frontend/src test` print nothing. No frontend change, so no Karma run.

### Produces

- `security.AdminKeyValidator`: `KEY_HEADER = "X-Admin-Key"`, `ACTOR_HEADER = "X-Admin-Actor"`, `boolean matches(String)` (constant time, false when unset), `boolean isConfigured()`.
- `security.AdminKeyFilter extends OncePerRequestFilter`: `ACTOR_ATTRIBUTE = "admin.actor"`; preflight passthrough; 404 with `Content-Length: 0` on a missing or wrong key; 400 on a blank actor; request attribute `admin.actor` (trimmed).
- `config.AdminToolsConfig`: beans `adminKeyValidator` (`app.admin-tools.key`) and `adminKeyFilter` (`/api/admin/*`, `Ordered.HIGHEST_PRECEDENCE + 10`, every profile).
- `service.AdminAuditService(AdminActionRepository, ObjectMapper, PlatformTransactionManager)`: `void record(String actor, Long userId, String verb, String targetId, Object before, Object after, String reason)` (REQUIRES_NEW, JSON via `ObjectMapper` scrubbed of token, cursor and descriptor keys, never throws; refusals and GET verbs); `@Transactional(propagation = MANDATORY) void recordInTransaction(...)` (same arguments; mutating verbs here and in T18); `String actor(HttpServletRequest)` (throws when the filter did not run).
- `GET /api/admin/plaid/items` returning `List<dto.admin.PlaidItemRow>`, `record PlaidItemRow(String publicId, String itemStatus, Boolean transactionsConsented, boolean hasCursor, boolean historyComplete, LocalDateTime lastSyncAt, LocalDateTime linkedAt, boolean pendingRemoval)`.
- `POST /api/admin/plaid/{userId}/remove-item` (`enqueueRemoval` plus status REMOVED with its audit row in one transaction under `findByIdForUpdate`; 200, 404, 409 `NO_PLAID_ITEM`, 409 `ITEM_ALREADY_REMOVED`).
- `POST /api/admin/users/{id}/delete` (`UserDeletionService.delete(user, DeletionReason.ADMIN, hook)`; 404, 409 `DELETION_DEFERRED`, 200 `{deleted, userId}`); `DevUserController` and `POST /api/dev/users/{id}/delete` are gone.
- Verb names `AdminPlaidController.VERB_ITEMS = "plaid.items"`, `VERB_REMOVE_ITEM = "plaid.remove-item"`, `AdminUserController.VERB_DELETE = "users.delete"`.
- `BrokerEnvironmentGuard.checkProdAdminKey`: prod refuses a blank `app.admin-tools.key` and `CI_ADMIN_TOOLS_KEY = "ci-local-admin-tools"`.
- Property `app.admin-tools.key` (base `${ADMIN_TOOLS_KEY:}`, ci `${ADMIN_TOOLS_KEY:ci-local-admin-tools}`, prod header names `ADMIN_TOOLS_KEY`); `/api/admin/**` in `SecurityConfig`'s `permitAll`, CORS header list unchanged.
