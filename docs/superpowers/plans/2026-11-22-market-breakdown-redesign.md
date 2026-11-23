# Monthly Market Breakdown Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plain Monthly Market Breakdown email with the approved welcome-email-style layout, written in FRED's pig voice, where the AI returns labeled text pieces and a fixed template draws everything.

**Architecture:** Claude returns one JSON object (headline, intro, 1 to 6 stories, one line per fund, closing). A strict parser and a field-by-field validator gate it, with one in-run retry. The validated JSON is stored in a new `market_breakdown.content_json` column. `MarketBreakdownEmailComposer` renders it into fixed templates with HTML escaping, deterministic fund bars, and the member's numbers.

**Tech Stack:** Java 17, Spring Boot, Gradle, JUnit 5 + Mockito, Jackson, Flyway (MySQL), Anthropic Java SDK (web search tool), hand-written HTML email templates rendered by `EmailTemplateRenderer`.

**Spec:** `docs/superpowers/specs/2026-11-22-market-breakdown-redesign-design.md`. Visual reference: `docs/superpowers/specs/2026-11-22-market-breakdown-redesign-mockup.html` (the spec wins where they differ; the mockup embeds a large base64 image, so grep it, do not read it whole).

## Global Constraints

- No em dashes or en dashes (`—`, `–`, `&mdash;`, `&ndash;`) in any template, fixed copy, prompt example, or generated content.
- All AI-supplied text is plain text and is HTML-escaped at render time. The AI never controls markup.
- Numbers shown to the member come from code only: `etf_returns_json` and `UserMonthlyNumbers`. The member-number math in `resolveNumbers` is not changed.
- Email bodies are never stored in the database.
- Unchanged: subject line (`FRED's Monthly Market Breakdown - <Month YYYY>`), scheduler, recipients, `email_log` idempotency, endpoint paths.
- Schema changes go through Flyway only (`ddl-auto=validate`). Next free version is V11; re-check right before creating the file.
- This repo never touches the landing-page repo or any lpapi host.
- Git: stage files by explicit path only. Never `git add -A`, `git add .`, or `commit -a`. Never `git checkout`, `git restore`, or `git stash` on this tree (it holds Andy's uncommitted work). Before staging an existing file, read `git diff HEAD -- <file>` and confirm every hunk is yours. Never commit `application-local.properties`. Never push.
- Commit trailer on every commit:
  ```
  Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016NWfpfCXkgzDridmBPy84t
  ```
- Tests run from `backend/`: `./gradlew test --tests "<pattern>"`.

## Review Focus

1. **Wrong-shaped JSON** (a string where an array is expected, an unknown extra field, `stories` missing): generation fails with a clear message and a FAILED row, never a NullPointerException. Pinned in Task 1.
2. **AI text with `&`, `"`, `'`, or `<`** (for example "don't" or "AT&T"): `&`, quotes and apostrophes render escaped and intact; `<` is rejected by the validator. Pinned in Tasks 1 and 5.
3. **A return that rounds to zero** (for example -0.04%): shows a gray `0.0%` with no bar, never `-0.0%` or `+0.0%`. Pinned in Task 5.
4. **Empty-string or whitespace `joke`**: treated as no joke; no empty 🐷 line is drawn. Pinned in Tasks 1 and 5.
5. **A stored row whose `etf_returns_json` is missing a fund or unreadable**: `compose` throws (the send is recorded FAILED and retried) instead of drawing blank bars. Pinned in Task 5.

---

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/investingapp/backend/dto/MarketBreakdownContent.java` (new) | The month's labeled pieces, as records. |
| `backend/src/main/java/com/investingapp/backend/util/MarketBreakdownContentParser.java` (new) | Strict JSON to `MarketBreakdownContent` and back. |
| `backend/src/main/java/com/investingapp/backend/util/MarketNarrativeValidator.java` (rework) | Field-by-field compliance gate on parsed content. |
| `backend/src/main/java/com/investingapp/backend/service/LLMService.java` (modify) | `GroundedSummary.text` becomes only the text after the last search result. |
| `backend/src/main/resources/db/migration/V11__market_breakdown_content_json.sql` (new) | Adds `content_json`. |
| `backend/src/main/java/com/investingapp/backend/model/MarketBreakdown.java` (modify) | `contentJson` field, `hasContent()`. |
| `backend/src/main/java/com/investingapp/backend/service/MarketBreakdownService.java` (modify) | New prompts, JSON flow, one retry, old-row regeneration. |
| `backend/src/main/resources/static/email-assets/fred-chart.png` (new) | The pig. |
| `backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java` (modify) | Public access to `/email-assets/**`. |
| `backend/src/main/resources/application.properties` (modify) | `app.email.asset-base-url`, `app.email.privacy-url`. |
| `backend/src/main/resources/templates/email/*.html` (rewrite) | The new layout. |
| `backend/src/main/java/com/investingapp/backend/util/EmailTemplateRenderer.java` (modify) | `escapeHtml`. |
| `backend/src/main/java/com/investingapp/backend/service/MarketBreakdownEmailComposer.java` (modify) | Renders content, bars, variants, plain text. |
| `backend/src/main/java/com/investingapp/backend/controller/MarketBreakdownController.java` (modify) | Preview treats an old-format row as not generated. |

---

### Task 1: Content model, strict parser, field validator

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/dto/MarketBreakdownContent.java`
- Create: `backend/src/main/java/com/investingapp/backend/util/MarketBreakdownContentParser.java`
- Modify: `backend/src/main/java/com/investingapp/backend/util/MarketNarrativeValidator.java`
- Test: `backend/src/test/java/com/investingapp/backend/util/MarketBreakdownContentParserTest.java` (new)
- Test: `backend/src/test/java/com/investingapp/backend/util/MarketNarrativeValidatorTest.java` (rewrite)

**Interfaces:**
- Produces:
  ```java
  public record MarketBreakdownContent(List<String> headline, String intro, List<Story> stories,
          Map<String, String> funds, List<String> closing) {
      public record Story(String title, List<String> bullets, String joke) {}
  }
  // MarketBreakdownContentParser
  public static MarketBreakdownContent parse(String raw);   // throws IllegalStateException
  public static String toJson(MarketBreakdownContent content);
  // MarketNarrativeValidator
  public static ValidationResult validate(MarketBreakdownContent content);
  ```
  `ValidationResult` keeps its current shape (`valid`, `violations`). The old `validate(String)` is deleted in Task 3 when its last caller goes; keep it compiling until then.

A valid sample used across tests (put it in a shared test helper `MarketBreakdownTestContent.validJson()` / `.valid()` under `backend/src/test/java/com/investingapp/backend/util/`):

```json
{"headline":["A muddy month for tech.","FRED kept his snout up."],
 "intro":"FRED here. I rolled around in the headlines so you don't have to.",
 "stories":[
  {"title":"Outlook took a long nap","bullets":["Microsoft 365 email went down for many users.","Microsoft found no sign of an attack."],"joke":"I only use my snout for mail anyway."},
  {"title":"A chip chief in the headlines","bullets":["A report raised allegations involving a contact of a chip company's chief.","The claims are unproven and were denied."],"joke":null}],
 "funds":{"VTI":"It holds a lot of big tech, so tech's rough month showed up here most.",
          "VXUS":"It lives overseas, far from most of this month's US headlines.",
          "VBR":"It owns smaller companies that were out of the splash zone."},
 "closing":["Some months are clover, some are mud.","A pig is happy in both."]}
```

- [ ] **Step 1: Write failing parser tests**

  In `MarketBreakdownContentParserTest`:
  - `parsesValidObject`: `parse(validJson())` gives 2 headline lines, 2 stories, `stories().get(1).joke() == null`, `funds().get("VTI")` starts with "It holds".
  - `stripsOneSurroundingCodeFence`: input wrapped as `` ```json\n…\n``` `` parses to the same content.
  - `rejectsLeadingProse`: `"Here is the recap:\n" + validJson()` throws `IllegalStateException`.
  - `rejectsUnknownField`: adding `"extra":1` at top level throws.
  - `rejectsWrongType`: `"headline":"one string"` throws.
  - `rejectsMissingStories`: removing `stories` throws with a message containing `stories`.
  - `rejectsBlankAndNull`: `null`, `""`, `"   "` each throw.
  - `emptyJokeBecomesNull`: `"joke":"  "` parses with `joke() == null`.
  - `roundTrips`: `parse(toJson(valid()))` equals `valid()`.

- [ ] **Step 2: Run, expect failure**

  Run: `./gradlew test --tests "*MarketBreakdownContentParserTest"`
  Expected: compilation failure (classes do not exist).

- [ ] **Step 3: Implement the record and the parser**

  Jackson `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=true`, `FAIL_ON_NULL_FOR_PRIMITIVES` irrelevant. Trim; strip exactly one surrounding fence (optional `json` tag); then the remaining text must start with `{` and end with `}`. After mapping, reject (IllegalStateException naming the field) when `headline`, `intro`, `stories`, `funds` or `closing` is null, or any story has null `title` or `bullets`. Normalize a blank `joke` to null. Wrap every Jackson exception in `IllegalStateException("Content is not valid JSON of the expected shape: " + …)`.

- [ ] **Step 4: Run, expect pass**

- [ ] **Step 5: Write failing validator tests**

  Rewrite `MarketNarrativeValidatorTest` against `validate(MarketBreakdownContent)`. Build variants by copying `valid()` with one field changed. One test per rule, each asserting `!valid` and that a violation mentions the field path (for example `stories[0].bullets[1]`):
  - `acceptsValidContent`.
  - Counts: headline of 1 and of 3 lines; closing of 1; stories of 0 and of 7; `acceptsOneStory`; `acceptsSixStories`; bullets of 1 and of 4; funds missing `VBR`; funds with an extra key `SPY`.
  - Lengths (limits below): headline line of 7 and of 29 chars; intro of 91; title of 46; bullet of 121; joke of 91; fund line of 131; closing line of 41; any blank string.
  - Forbidden characters, each in a different field: `<`, `>`, `{{`, `}}`, `http`, `www.`, `javascript:`, `—`, `–`.
  - Advice phrases: `"you should"` in a bullet; `"you\nshould"` (whitespace split) in the intro; `"Guaranteed"` (case) in a joke.
  - Fund lines: one containing `%`, one containing `$`.
  - `allowsApostropheAndAmpersand`: `"AT&T didn't budge."` as a bullet is valid.

  Limits, as constants in the validator: `HEADLINE_MIN=8, HEADLINE_MAX=28, INTRO_MAX=90, TITLE_MAX=45, BULLET_MAX=120, JOKE_MAX=90, FUND_MAX=130, CLOSING_MIN=8, CLOSING_MAX=40, STORIES_MIN=1, STORIES_MAX=6, BULLETS_MIN=2, BULLETS_MAX=3`. Fund keys are exactly `VTI`, `VXUS`, `VBR`.

- [ ] **Step 6: Run, expect failure**

  Run: `./gradlew test --tests "*MarketNarrativeValidatorTest"`

- [ ] **Step 7: Implement `validate(MarketBreakdownContent)`**

  Keep `FORBIDDEN_PHRASES` and the lowercase plus whitespace-collapsed matching. Walk every string with a field path. Do not short-circuit; collect all violations (the retry prompt in Task 3 shows them to the model).

- [ ] **Step 8: Run both test classes, expect pass**

- [ ] **Step 9: Commit**

  ```bash
  git add backend/src/main/java/com/investingapp/backend/dto/MarketBreakdownContent.java \
    backend/src/main/java/com/investingapp/backend/util/MarketBreakdownContentParser.java \
    backend/src/main/java/com/investingapp/backend/util/MarketNarrativeValidator.java \
    backend/src/test/java/com/investingapp/backend/util/MarketBreakdownContentParserTest.java \
    backend/src/test/java/com/investingapp/backend/util/MarketBreakdownTestContent.java \
    backend/src/test/java/com/investingapp/backend/util/MarketNarrativeValidatorTest.java
  git commit -m "feat(mmb): labeled content model, strict parser, field validator"
  ```

---

### Task 2: Only the text after the last search result

**Files:**
- Modify: `backend/src/main/java/com/investingapp/backend/service/LLMService.java` (`generateGroundedMarketSummary`, around lines 167-204)
- Test: `backend/src/test/java/com/investingapp/backend/service/LLMServiceAnswerTextTest.java` (new)

**Interfaces:**
- Produces: `static String answerText(List<ContentBlock> blocks)` in `LLMService` (package-private). `GroundedSummary.text` is now `answerText(message.content())`. Signature of `generateGroundedMarketSummary` is unchanged.

Why: with web search on, the model narrates before searching ("I'll look up…"). The spec says the parser must not hunt for JSON inside a longer reply, so the narration is dropped here, structurally.

- [ ] **Step 1: Write failing tests** for `answerText`:
  - `textAfterLastSearchResultOnly`: blocks `[text "I'll search", serverToolUse, webSearchToolResult, text "{\"a\":", text "1}"]` gives `{"a":1}`.
  - `usesLastOfSeveralSearches`: text between two search results is dropped.
  - `noSearchResultJoinsAllText`: `[text "x", text "y"]` gives `xy`.
  - `noTextAfterSearchIsEmpty`: gives `""` (the existing blank check then throws).

  Build blocks with the SDK's builders; if a block type cannot be built in a test, extract the rule into a helper that takes a list of small `(isSearchResult, text)` pairs and test that, with `answerText` as a thin adapter.

- [ ] **Step 2: Run, expect failure.** `./gradlew test --tests "*LLMServiceAnswerTextTest"`

- [ ] **Step 3: Implement** and use it in `generateGroundedMarketSummary`. Source collection is unchanged.

- [ ] **Step 4: Run, expect pass.** Also run `./gradlew test --tests "*LLMService*"`.

- [ ] **Step 5: Commit** (`feat(mmb): grounded summary keeps only the text after the last search`).

---

### Task 3: Storage, prompts, generation flow with one retry

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__market_breakdown_content_json.sql`
- Modify: `backend/src/main/java/com/investingapp/backend/model/MarketBreakdown.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/MarketBreakdownService.java`
- Modify: `backend/src/main/java/com/investingapp/backend/util/MarketNarrativeValidator.java` (delete `validate(String)` and its now-unused members)
- Test: `backend/src/test/java/com/investingapp/backend/service/MarketBreakdownPromptTest.java`, `MarketBreakdownServiceGenerateTest.java` (update)

**Interfaces:**
- Consumes: Task 1 parser and validator; Task 2 `GroundedSummary.text`.
- Produces:
  ```java
  // MarketBreakdown
  private String contentJson;               // @Column(name = "content_json", columnDefinition = "TEXT")
  public boolean hasContent();               // GENERATED and contentJson not blank
  // MarketBreakdownService
  static final int MAX_ATTEMPTS = 2;
  static String buildSystemPrompt();
  static String buildUserPrompt(YearMonth month, List<SymbolMonthlyReturn> returns);
  static String buildRetryPrompt(String userPrompt, List<String> violations);
  ```
  `getOrGenerate(YearMonth)` keeps its signature and its FAILED-row behavior.

- [ ] **Step 1: Migration and entity**

  ```sql
  ALTER TABLE market_breakdown ADD COLUMN content_json TEXT NULL;
  ```
  Add the field with Lombok-consistent accessors and `hasContent()`. `narrativeHtml` stays mapped and is no longer written by new code. Confirm the column name the entity's naming strategy expects matches (`content_json`).

- [ ] **Step 2: Write failing prompt tests** in `MarketBreakdownPromptTest` (replace assertions about the HTML structure):
  - System prompt contains each of: `"pig"`, `"NEVER recommend"`, `"NEVER predict"`, `"em dashes"`, `"alleg"`, `"null"` (the joke rule), `"JSON"`; and does not contain `"<h3>"` or the characters `—` / `–` outside the sentence that names them (keep the existing way this is asserted).
  - User prompt for a month contains the month label, each of `VTI`, `VXUS`, `VBR`, each fund's `returnPct`, the phrases `"between 1 and 6"`, `"Do not pad"`, and every JSON field name (`headline`, `intro`, `stories`, `title`, `bullets`, `joke`, `funds`, `closing`) and every character limit from Task 1.
  - `buildRetryPrompt(user, List.of("headline[0]: too long (31 > 28)"))` starts with the original user prompt and contains the violation text and `"Return the full corrected JSON object only"`.

- [ ] **Step 3: Run, expect failure.** `./gradlew test --tests "*MarketBreakdownPromptTest"`

- [ ] **Step 4: Rewrite the prompts**

  System prompt carries the voice and hard rules. Copy every rule from the spec's "Prompt rules" section (kept rules and new rules) as one bullet each; do not paraphrase the meaning away. The output rule becomes: "Output exactly one JSON object and nothing else. No markdown, no code fence, no text before or after it. Every value is plain text with no HTML."

  User prompt keeps the existing numbers block (including SPY as context) and the search instruction, then gives the JSON shape with the limits and this story rule verbatim: "Include every story that really mattered to the market this month: between 1 and 6. Do not pad to reach a number and do not drop a significant story. Biggest first." Include one short shape example whose text is obviously placeholder (`"title": "..."`) so the model does not copy sample jokes.

- [ ] **Step 5: Run prompt tests, expect pass**

- [ ] **Step 6: Write failing generation tests** in `MarketBreakdownServiceGenerateTest` (the existing `llmReturns(String)` helper now returns JSON; add `llmReturnsInOrder(String... texts)`):
  - `happyPathStoresContentJson`: LLM returns `validJson()` with one source. Row is GENERATED, `contentJson` parses back to `valid()`, `narrativeHtml` is null, `sourcesJson` and `etfReturnsJson` set, LLM called once.
  - `invalidThenValidRetriesOnce`: first reply has a 40-char headline line, second is valid. Row GENERATED, LLM called exactly twice, and the second call's user prompt contains `headline[0]`.
  - `unparseableThenValidRetries`: first reply `"Sorry, here you go"`, second valid. GENERATED.
  - `twoBadAttemptsFail`: both invalid. Throws `IllegalStateException`; saved row is FAILED, `contentJson` null, `errorMessage` mentions the violation; LLM called exactly twice.
  - `ungroundedAttemptIsRetried`: first reply valid JSON but zero sources, second valid with sources. GENERATED, sources from the second.
  - `llmExceptionIsNotRetried`: LLM throws. FAILED, called once.
  - `oldFormatGeneratedRowIsRegenerated`: repository returns a GENERATED row with `narrativeHtml` set and `contentJson` null. LLM is called; the same row (same id) is saved with `contentJson` set.
  - `newFormatGeneratedRowShortCircuits`: GENERATED with `contentJson`. LLM never called.
  - Keep `failedRowIsRegeneratedInPlace` and `incompleteMonthIsRejected`, updated to JSON.

- [ ] **Step 7: Run, expect failure**

- [ ] **Step 8: Implement the flow in `getOrGenerate`**

  Short-circuit only when `existing.hasContent()`. Fetch returns once (not retried). Then up to `MAX_ATTEMPTS`: call the LLM (first with `buildUserPrompt`, then `buildRetryPrompt` with the previous violations); a zero-source reply, a parse failure, and validator violations each become that attempt's violation list; an exception thrown by the LLM call itself propagates immediately. On success store `MarketBreakdownContentParser.toJson(content)` in `contentJson`, set sources and model from the successful attempt, status GENERATED. On exhaustion throw with the last violations joined, into the existing catch block; that block also nulls `contentJson`. Delete `validate(String)` from the validator.

- [ ] **Step 9: Run** `./gradlew test --tests "*MarketBreakdown*" --tests "*MarketNarrative*"`. Composer tests may still pass (untouched). Everything green.

- [ ] **Step 10: Commit** the migration, entity, service, validator and the two test classes (`feat(mmb): structured content generation with one retry and content_json storage`).

---

### Task 4: The pig image, public asset path, properties

**Files:**
- Create: `backend/src/main/resources/static/email-assets/fred-chart.png`
- Modify: `backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java` (permit list near line 116)
- Modify: `backend/src/main/resources/application.properties` (next to `app.email.*`, lines 47-49)
- Test: `backend/src/test/java/com/investingapp/backend/config/EmailAssetsPublicAccessTest.java` (new)

**Interfaces:**
- Produces: `GET /email-assets/fred-chart.png` returns the PNG with no auth. Properties:
  ```properties
  app.email.asset-base-url=${EMAIL_ASSET_BASE_URL:}
  app.email.privacy-url=${EMAIL_PRIVACY_URL:https://fredvested.com/privacy}
  ```

- [ ] **Step 1: Produce the PNG** from `frontend/src/assets/images/pig-range-3.svg`: 460px wide (2x the 230px display width used in the mockup), transparent background, under 150 KB. Neither `rsvg-convert` nor ImageMagick is installed; headless Chrome screenshot of the SVG with a transparent background works, as does `npx` with an SVG rasterizer. Do not use `qlmanage` (white background).

  Verify: `sips -g pixelWidth -g hasAlpha backend/src/main/resources/static/email-assets/fred-chart.png` shows `pixelWidth: 460` and `hasAlpha: yes`. Open the file with the Read tool and confirm it is the chart-pointing pig, not cropped.

- [ ] **Step 2: Write the failing access test**: a MockMvc test (same Spring test slice the project's other security-sensitive controller tests use) asserting `GET /email-assets/fred-chart.png` with no Authorization header returns 200 and content type `image/png`, and that `GET /email-assets/../application.properties` does not return 200.

- [ ] **Step 3: Run, expect 401/403.** `./gradlew test --tests "*EmailAssetsPublicAccessTest"`

- [ ] **Step 4: Add** `.requestMatchers("/email-assets/**").permitAll()` and the two properties. If the JWT filter rejects unauthenticated requests before the matcher applies, exempt the same path there the way `/.well-known/apple-app-site-association` is exempted.

- [ ] **Step 5: Run, expect pass. Commit** the PNG, config, properties and test (`feat(mmb): hosted FRED image for email, privacy link property`).

---

### Task 5: Templates and composer

**Files:**
- Rewrite: `backend/src/main/resources/templates/email/market-breakdown.html`, `market-breakdown-numbers.html`, `market-breakdown-empty.html`
- Create: `market-breakdown-story.html`, `market-breakdown-fund-row.html`, `market-breakdown-pig.html` (split is the implementer's to adjust; `market-breakdown-change-rows.html` is deleted if unused)
- Modify: `backend/src/main/java/com/investingapp/backend/util/EmailTemplateRenderer.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/MarketBreakdownEmailComposer.java`
- Modify: `backend/src/main/java/com/investingapp/backend/controller/MarketBreakdownController.java` (line 82)
- Test: `MarketBreakdownEmailComposerTest.java`, `EmailTemplateRendererTest.java`, `MarketBreakdownControllerTest.java` (update)

**Interfaces:**
- Consumes: `MarketBreakdownContentParser.parse`, `MarketBreakdown.getContentJson()`, `getEtfReturnsJson()` (a JSON array of `{symbol, priorClose, endClose, returnPct}`), `hasContent()`.
- Produces:
  ```java
  // EmailTemplateRenderer
  public static String escapeHtml(String s);   // & < > " '  ->  &amp; &lt; &gt; &quot; &#39;
  // MarketBreakdownEmailComposer
  public MarketBreakdownEmailComposer(PortfolioDashboardService, InvestmentExecutionRepository,
          @Value("${app.email.asset-base-url:}") String assetBaseUrl,
          @Value("${app.email.privacy-url:https://fredvested.com/privacy}") String privacyUrl);
  public ComposedEmail compose(MarketBreakdown breakdown, UserMonthlyNumbers numbers); // unchanged signature
  static int barWidthPct(BigDecimal returnPct, BigDecimal largestAbsReturn);
  ```

**Fixed copy (exact):**
- Eyebrow: `Monthly Market Breakdown · {periodLabel}`
- Portfolio card title: `Your portfolio in {month name}` (month name only, for example `November`)
- Tile labels: `You put in`, `What the market did`
- Under the tiles (only when the market tile shows): `Market return excludes your deposits. Your hooves did the rest.`
- Not-invested card: `Your numbers show up here once you're invested.`
- Section: `What happened out there` / subtitle `The stories everyone was squealing about.`
- Section: `How your funds did` (member with numbers) or `How FRED's funds did` (no numbers) / key line `Each bar starts at the middle line. Left means down, right means up.`
- Fund descriptions: VTI `the whole US market`, VXUS `the rest of the world`, VBR `small US companies`
- Section: `Why they moved`
- Sign-off: `See you next month. Oink.`
- Footer: `The FRED Team`; `help@fredvested.com` (mailto); the existing three-sentence disclaimer from the current template, unchanged; `News items come from public reporting.`; `FREDvested LLC, PO Box 2406, Rockville, MD 20847`; `Unsubscribe` (href `{{unsubscribeUrl}}`, still `#`); `Privacy policy` (href the property).

**Visual values:** copy colors, sizes, radii, paddings and the center-line bar markup from the mockup file. Table-based layout, inline styles only, 560px card.

**Rules:**
- Bars: one decimal, half-up. Scale = `max(5.0, largest absolute one-decimal return of the three)`. `barWidthPct` = `round(abs / scale * 100)`, minimum 2 when the rounded return is not zero, 0 when it is. Negative: red `#DC2626`, bar in the left half, aligned to the middle. Positive: green `#16A34A`, right half. Zero after rounding: gray `#6B7280`, text `0.0%`, no bar. Order is always VTI, VXUS, VBR.
- Headline and closing: each sentence is its own block element. Never a `<br>` inside one element.
- Portfolio card variants: (a) `numbers == null`: gray not-invested card. (b) `startEquity == 0`: blue card with ending balance and `You put in` only, no market tile, no hooves line. (c) otherwise: both tiles; market value is `(end − start − contributions) / start`, signed, one decimal, as today.
- Stories are numbered from 1. A story with a null joke draws no joke row.
- Pig: when `assetBaseUrl` is blank, the pig row is omitted entirely. Otherwise `<img>` src is `assetBaseUrl` (trailing slash trimmed) + `/email-assets/fred-chart.png`, `width="230"`, alt `FRED the pig pointing at a chart`.
- Every AI string goes through `escapeHtml`. Code-made strings (money, percents, fixed copy) do not.
- `compose` throws `IllegalStateException` when `contentJson` is missing or unparseable, or when `etfReturnsJson` lacks any of VTI, VXUS, VBR.
- Plain text body: subject; headline lines; intro; `What happened out there`; each story as `1. Title` then each bullet on its own line prefixed `* `, then the joke if any; `How your funds did` with `VTI (the whole US market): -2.1%` lines each followed by its why line; the member's numbers sentence as today when present; closing lines; `See you next month. Oink.`; the existing disclaimer and help line. No HTML entities in it.
- Add `<meta name="color-scheme" content="light">` and a `@media (max-width:480px)` rule that lowers the headline and closing font size; tune in Task 6.

- [ ] **Step 1: Write failing tests**

  `EmailTemplateRendererTest`: `escapeHtml("AT&T <b> \"don't\"")` equals `AT&amp;T &lt;b&gt; &quot;don&#39;t&quot;`; `escapeHtml(null)` is `""`. Update the existing test that fills `narrativeHtml` to the new shell's slots.

  `MarketBreakdownEmailComposerTest` (build a `MarketBreakdown` with `contentJson` from `MarketBreakdownTestContent` and `etfReturnsJson` with VTI -2.10, VXUS -0.60, VBR 1.40; keep all existing `resolveNumbers` tests untouched):
  - `rendersHeadlineAsTwoBlocks`: html contains both headline sentences, no `<br>` between them, and each appears inside its own element.
  - `rendersEveryStoryNumbered`: with 2 stories, html contains both titles and all bullets; story 2 has no 🐷 row; story 1 has one.
  - `rendersOneStory` and `rendersSixStories`: titles count matches; no unresolved `{{`.
  - `fundBarsDirectionAndScale`: `barWidthPct(-2.1, 2.1) == 42`, `barWidthPct(1.4, 2.1) == 28`, `barWidthPct(-0.6, 2.1) == 12`; html shows `-2.1%`, `-0.6%`, `+1.4%` with red, red, green.
  - `barScalesToLargestMoveAbove5`: `barWidthPct(8.0, 8.0) == 100`, `barWidthPct(-4.0, 8.0) == 50`.
  - `tinyMoveGetsMinimumBar`: `barWidthPct(0.1, 2.1) == 2`.
  - `roundsToZeroShowsFlat`: VTI `-0.04` renders `0.0%` in gray, and the html contains neither `-0.0%` nor `+0.0%`; `barWidthPct(0.0, x) == 0`.
  - `fundedMemberShowsBothTiles`: start 820.91, end 1050.18, contributions 240: html contains `$1,050.18`, `$240.00`, `-1.3%`, `Your portfolio in`, the hooves line, and `How your funds did`.
  - `midMonthMemberHidesMarketTile`: start 0: contains the balance and `You put in`; does not contain `What the market did` or the hooves line.
  - `notInvestedMemberSeesGrayCard`: numbers null: contains `Your numbers show up here once you're invested.` and `How FRED's funds did`; does not contain `Your portfolio in`.
  - `escapesAiText`: a bullet `AT&T didn't budge.` appears as `AT&amp;T didn&#39;t budge.` in html and as `AT&T didn't budge.` in text.
  - `blankAssetUrlOmitsPig`: no `<img`. `assetUrlRendersPig`: base `https://x.test/` gives `src="https://x.test/email-assets/fred-chart.png"`.
  - `footerHasAddressAndLinks`: contains the PO Box line, `href="https://fredvested.com/privacy"`, `Unsubscribe`, `News items come from public reporting.`
  - `noDashesAnywhere`: html and text contain none of `—`, `–`, `&mdash;`, `&ndash;`.
  - `missingContentThrows`: `contentJson` null throws `IllegalStateException`.
  - `missingFundReturnThrows`: `etfReturnsJson` without VBR throws; `etfReturnsJson` of `"not json"` throws.
  - `subjectUnchanged`: `FRED's Monthly Market Breakdown - October 2026`.
  - `plainTextCarriesEverything`: text contains a headline line, `1. ` + first title, `VTI (the whole US market): -2.1%`, a closing line, and `not investment advice`.

  `MarketBreakdownControllerTest`: `previewTreatsOldFormatRowAsNotGenerated`: a GENERATED row with null `contentJson` gets the same response as a missing row, and `composer.compose` is never called.

- [ ] **Step 2: Run, expect failure.** `./gradlew test --tests "*MarketBreakdownEmailComposerTest" --tests "*EmailTemplateRendererTest" --tests "*MarketBreakdownControllerTest"`

- [ ] **Step 3: Implement** `escapeHtml`, the templates, the composer, and change the controller check at line 82 to `row.isEmpty() || !row.get().hasContent()`.

- [ ] **Step 4: Run the three classes, then the whole backend suite.** `./gradlew test`. Expected: all green. `MarketBreakdownSchedulerTest` mocks the composer and should need no change.

- [ ] **Step 5: Commit** templates (including any deletion, by explicit path with `git rm`), renderer, composer, controller and tests (`feat(mmb): new email layout drawn from labeled content`).

---

### Task 6: See it for real, tune line breaks

**Files:**
- Modify (only if tuning is needed): the templates, `MarketNarrativeValidator` limits, the prompt limits and their tests.

- [ ] **Step 1: Render a fixture without the backend.** Add a test-scope `main` or a disabled-by-default test (`MarketBreakdownRenderSample`) that composes three emails from `MarketBreakdownTestContent` with worst-case lengths (headline lines of exactly 28 chars made of wide letters, closing lines of 40, six stories, three bullets each at 120) for the funded, mid-month and not-invested variants, and writes them to `backend/build/mmb-samples/`.

- [ ] **Step 2: Screenshot each at 600px and 375px wide** (headless Chrome) and read the images. Check against the mockup: each headline sentence and each closing sentence on one line; bars start at the middle line; cards and footer intact; nothing overflows the card at 375px.

- [ ] **Step 3: If a sentence wraps,** lower the phone font size in the media query first. If it still wraps at 375px, lower `HEADLINE_MAX` / `CLOSING_MAX`, update the prompt text and the tests that pin them, and re-run `./gradlew test`.

- [ ] **Step 4: Live check, if the local backend is running on :8080.** The dev-auth and generate endpoints need the dev tools key and a JWT. Do not read the key from `application-local.properties` yourself; ask Andy to run the auth command and save the JWT to a file, as before. Then `POST /api/market-breakdown/generate?month=2026-10` and `GET /api/market-breakdown/preview?month=2026-10`. Confirm: the row has `content_json`, the story count is between 1 and 6, no joke sits on a story about an accusation or a death, no dashes, and the preview renders like the fixture. If the backend is not running or the JWT is not available, say so plainly in the report; do not claim this step passed.

- [ ] **Step 5: Final sweep.**
  `grep -rn "mdash\|ndash\|—\|–" backend/src/main/resources/templates/email/` returns nothing.
  `grep -rn "narrativeHtml\|getNarrativeHtml" backend/src/main` shows only the entity field.
  `./gradlew test` is green.

- [ ] **Step 6: Commit** any tuning and the sample renderer (`chore(mmb): sample renderer, line-break tuning`).
