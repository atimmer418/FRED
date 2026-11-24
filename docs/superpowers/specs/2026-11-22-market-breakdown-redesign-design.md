# Monthly Market Breakdown redesign: design

Date: 2026-11-22
Status: approved by Andy 2026-11-22
Builds on: `2026-07-15-monthly-market-breakdown-design.md` (the feature as shipped)

## Intent

The Monthly Market Breakdown email works, but it reads like a wall of text. Andy called it
"very, very, very boring and basic." He wants it to look and sound like the welcome email
from the landing page: short pieces, bullets, numbered lists, visuals, a cute FRED, and a
pig's voice with pig jokes.

The email still goes out by itself on the 1st. Andy does not pick topics and does not
approve drafts.

A throwaway mockup of the target look was built and approved by Andy on 2026-11-22. It is
the visual reference for this work. It is not product code. A copy is committed next to
this doc as `2026-11-22-market-breakdown-redesign-mockup.html`. Its words and numbers are
samples. Where the mockup and this doc disagree, this doc wins. Known differences: the
mockup shows three stories (the real count is one to six), and its headline "Your trough
is fine" breaks the rule below about not talking about the reader's balance.

## Goals

1. The email follows the mockup's layout, top to bottom (see "The email").
2. The month's writing is in FRED's pig voice, with jokes, in short pieces. No long
   paragraphs anywhere.
3. Layout is fixed by a template. The AI supplies words only, in labeled pieces.
4. Every compliance rule that exists today still holds, checked by code and not by trust.
5. Each sentence in the headline and the closing sits on its own line. A line never breaks
   in the middle of a sentence at normal phone and desktop widths.

## Non-goals

- Resend or any real email provider. Sends stay mocked.
- A working unsubscribe endpoint, failed-month alerting, or the other go-live gates.
- Picking the pig image by the member's balance tier. One fixed pig for now.
- Any human review or topic input step.
- Changes to who receives the email, when it sends, the subject line, or idempotency.
- Changes to how the member's numbers are calculated.

## Anti-goals

- A joke about a death, a disaster's victims, or a named person's private life.
- An allegation stated as fact. Example from the brainstorm: reporting says a woman linked
  to Nvidia's CEO is alleged by US intelligence to have worked for Chinese intelligence.
  The email may say that it was alleged and what the market did. It may not say she was a
  spy, and it may not joke about it.
- Advice, predictions, or reassurance about the reader's own money. "Your trough is fine"
  is shared by every member, including one having a bad month, so the shared writing must
  not make claims about the reader's balance.
- A number the AI made up about a fund or about the member's portfolio.
- The AI controlling markup. A bad model output must never be able to break the layout or
  inject a link.

## Constraints

- No em dashes or en dashes in anything a member reads. This is a house rule. The current
  templates still contain `&mdash;` in two places and those go away with this work.
- Email clients do not show SVG. The pig must be a hosted PNG.
- The schema is managed by Flyway with `ddl-auto=validate`. Any new column needs a
  migration with the next free version number.
- Email bodies are never stored in the database. Only the month's shared content is.
- This repo never touches the landing-page repo or any lpapi host. The welcome email is a
  visual reference only.

## Approaches

Three ways to get from "free-flowing AI text" to the mockup were considered.

1. **Labeled pieces into a fixed template (picked).** The AI returns a small JSON object:
   headline, intro line, one to six stories with bullets and an optional joke, one line
   per fund, closing. The template draws everything. Code checks each piece. Trade-off:
   the layout is rigid, so every story has the same card shape. That rigidity is also
   what makes it safe and consistent.
2. **Let the AI write richer HTML.** Widen the allowed tags and let the model produce
   cards and lists itself. Trade-off: flexible, but the look drifts month to month, email
   HTML is fragile, and the safety check gets much harder. Rejected.
3. **Two passes: facts first, then a voice pass.** One call gathers facts, a second
   rewrites them as FRED. Trade-off: better separation of truth and jokes, but twice the
   cost and twice the failure points. Deferred. It can be layered onto approach 1 later
   if the jokes start bending facts.

Andy handed the "how" to Claude. Approach 1 is Claude's pick.

## The email

Top to bottom, matching the mockup:

1. **Wordmark.** Bold italic blue "FRED" with a blue rule under it.
2. **Pig.** The chart-pointing FRED (`frontend/src/assets/images/pig-range-3.svg`), as a
   PNG, every month.
3. **Eyebrow.** "Monthly Market Breakdown · November 2026".
4. **Headline.** Two short sentences, one per line. From the AI.
5. **Intro line.** One gray sentence in FRED's voice. From the AI.
6. **Your portfolio card** (blue). Ending balance, big. Two tiles: "You put in" and "What
   the market did". Small line under: "Market return excludes your deposits. Your hooves
   did the rest." All numbers from code, same math as today.
   - Member funded mid-month (no starting balance): show the balance and "You put in"
     only. No market tile. Same rule as today's change rows.
   - Member not invested yet: a soft gray card instead: "Your numbers show up here once
     you're invested." No numbers. Replaces today's empty-state text.
7. **What happened out there.** Numbered story cards, as many as the month really had:
   at least one, at most six. Andy's rule (2026-11-22): "however much was actually out
   there", not a fixed three. Each card has a title, two or three short bullets, and an
   optional pig joke line with a 🐷. From the AI. Fixed subtitle under the section
   title, with no count in it: "The stories everyone was squealing about."
8. **How your funds did.** One row per fund (VTI, VXUS, VBR): ticker, a fixed plain
   description, the month's return, and a bar. From code. See "Fund bars". Members who
   are not invested yet still see this section and the next one, because the month's
   story is the same for everyone. For them the title reads "How FRED's funds did".
9. **Why they moved.** A numbered list in a gray card, one short line per fund. From the AI.
10. **Closing.** Two short sentences, one per line, then "See you next month. Oink."
    The two sentences are from the AI. The sign-off is fixed.
11. **Footer** (gray). "The FRED Team", help@fredvested.com, the existing not-advice
    disclaimer, the line "News items come from public reporting.", "FREDvested LLC, PO Box 2406, Rockville, MD 20847", Unsubscribe, Privacy
    policy.

Fixed fund descriptions: VTI "the whole US market", VXUS "the rest of the world", VBR
"small US companies".

### Fund bars

The first mockup used a bar that filled from the left, which read like a progress bar and
confused Andy. The approved version:

- Each bar has a dark line in the middle. That line is zero.
- A fund that fell gets a red bar growing left from the middle. A fund that rose gets a
  green bar growing right. A flat fund gets no bar and a gray "0.0%".
- Bar length is the size of the move. A full half-track equals 5%, or the biggest move of
  the three funds that month if that is larger. So bars are comparable with each other
  and never overflow.
- A one-line key sits under the section title: "Each bar starts at the middle line. Left
  means down, right means up."
- Returns come from the stored `etf_returns_json`, shown to one decimal place with a sign.

### Line breaks

Headline and closing are each stored as two separate sentences and rendered as two
separate block lines. Length caps (below) are set so each sentence fits on one line at
600px and at 375px wide. The builder confirms this by rendering at both widths and may
lower the phone font size with a media query to make it hold. If a client ignores the
media query the sentence may wrap, which is acceptable; a break between the two sentences
must always be there.

## The month's content (what the AI returns)

One JSON object, nothing else:

| Field | Shape | Limits |
|---|---|---|
| `headline` | array of exactly 2 sentences | each 8 to 28 characters |
| `intro` | one sentence | up to 90 characters |
| `stories` | array of 1 to 6 | |
| `stories[].title` | short phrase | up to 45 characters |
| `stories[].bullets` | array of 2 or 3 | each up to 120 characters |
| `stories[].joke` | string or null | up to 90 characters |
| `funds` | object with exactly `VTI`, `VXUS`, `VBR` | each up to 130 characters |
| `closing` | array of exactly 2 sentences | each 8 to 40 characters |

The character limits are starting values. The builder may tune them while checking the
render, and records the final values in the validator and its tests.

All fields are plain text. No HTML, no markdown. The template HTML-escapes every piece
when it draws it.

### Prompt rules

Kept from today: no advice, no predictions, no guarantees, only the supplied fund numbers,
news facts only from web search, no em or en dashes, plain words.

New:

- Voice: FRED is a pig. Short, warm, funny. Puns about mud, hooves, troughs, snouts,
  truffles, oinking and similar are welcome. Aim for a laugh, never at someone's expense.
- Stories: every story from the month that really mattered to the market, and no filler.
  A quiet month may have one or two. A busy month may have five or six. Six is the cap.
  Do not pad to reach a number and do not drop a significant story to stay short.
  Market-moving news and big-tech headlines come first, biggest first. A big move in a
  single well-known company (for example Apple falling sharply) counts when it helped
  move the market. Each story is a title and two or three bullets of one short sentence
  each: what happened, how big, what markets did.
- Not every story needs a joke. In a month with many stories, roughly half with a joke
  is plenty.
- Jokes: `joke` is null when the story involves a death, injuries, a disaster's victims,
  an accusation against a person, or anyone's private life. Otherwise one short line.
- Allegations are reported as allegations, with who is alleging it. Never as fact.
- Only state what a search result supports. If a rumored event cannot be confirmed, leave
  it out.
- `headline`, `intro` and `closing` are about the month and about FRED. They never say or
  imply anything about the reader's balance, and never say what will happen next.
- `funds` lines explain why each fund moved in plain words. They contain no percentages
  or dollar figures, because the template shows the real return right above them.

### Checks in code

`MarketNarrativeValidator` is reworked to check the parsed content, field by field. The
month fails, exactly as it does today, when any of these is true:

- The output is not valid JSON of the shape above, or a count or length limit is broken.
  With web search on, the model usually writes a line of narration before it searches.
  So the text to parse is only the text that comes after the last search result. From
  that text the parser may strip a surrounding code fence. It must not repair anything
  else, and it must not go hunting for a `{...}` inside a longer reply.
- Any field contains `<` or `>`, `{{` or `}}`, `http`, `www.`, `javascript:`, or an em or
  en dash.
- Any field contains a forbidden advice phrase. The existing list and the existing
  whitespace-normalized matching are kept.
- A `funds` line contains `%` or `$`.
- The model ran no web searches (unchanged rule).

**One retry inside a run.** The new limits are easier to trip than the old ones, and
nobody is watching for a skipped month yet. So when the output fails these checks,
`getOrGenerate` asks once more in the same run, passing the list of violations back to
the model. Two attempts at most per run. If the second also fails, the month is marked
FAILED as today and the scheduler's passes on the 2nd and 3rd try again. This is Claude's
call. It can double the AI cost of a bad run and never raises it on a good one.

Jokes in good taste and "allegations as allegations" cannot be checked by code. They are
prompt rules, backed by the `joke: null` rule above. This is a known, accepted limit of
the hands-off choice.

## Storage

- New nullable column on `market_breakdown`: `content_json` (TEXT), holding the validated
  object. New Flyway migration, next free version (V11 as of this writing).
- `narrative_html` stays in the table and is no longer written. It is not dropped, so the
  migration is safe to roll back around.
- A row with status GENERATED but no `content_json` is an old-format row. `getOrGenerate`
  treats it like a missing row and regenerates it in place. No real email has ever been
  sent, so nobody has seen the old text. This also lets Andy regenerate October in the new
  style to preview it.
- The `email_log` idempotency rule is untouched. Regenerating a month's content never
  re-sends to someone already logged for that month.

## The pig image

- A PNG of `pig-range-3.svg` on a transparent background, about 2x the displayed size, is
  committed under the backend's static resources and served without login at a stable
  path such as `/email-assets/fred-chart.png`. This needs one permit-all rule for that
  path only.
- The email builds the image URL from a new property, `app.email.asset-base-url`. The
  image has alt text "FRED the pig pointing at a chart" and fixed width and height.
- If the property is blank, the email renders without the image row. A missing image must
  never fail a send.

## Other fixed pieces

- `app.email.privacy-url` property for the footer link, default
  `https://fredvested.com/privacy` (given by Andy 2026-11-22).
- Unsubscribe link stays a placeholder for now, as today. The real one is a go-live gate
  and is out of scope here.
- The plain-text body is rebuilt from the same pieces: headline, stories as numbered
  items with dashes-free bullets, fund lines with their returns, the member's numbers,
  closing, disclaimer.
- The "See this month's sources" link from the first mockup is dropped. Sources are still
  stored for audit in `sources_json`. A public sources page is a separate piece of work.
- Subject line, scheduler, recipients, preview endpoint path, and manual generate
  endpoint are unchanged. The preview endpoint returns the new render.

## What is left to the builder

- Exact template file split and slot names.
- How the JSON is requested from Claude (plain instruction and parse, or the API's
  structured output feature if it works together with web search).
- The Java shape of the parsed content.
- Final character limits and phone font sizes, found by rendering.
- How the PNG is produced from the SVG.
- Outlook fallbacks. Rounded corners may square off there. Layout must stay readable.
- Test layout. Existing test classes for the prompt, validator, composer, and generate
  flow are updated to the new shape. New tests cover at least: each validator rule, bar
  direction and scaling including a flat fund and a move above 5%, the three portfolio
  card variants, one story and six stories, HTML escaping of AI text, old-format row regeneration, and a blank
  asset URL.

## Done means

- All backend tests pass.
- With the local backend running, `POST /generate` for 2026-10 produces a new-format row,
  and the preview for a funded member and an unfunded member both render like the mockup
  at 600px and 375px wide.
- No `&mdash;`, em dash or en dash anywhere in the templates or in the generated content.
