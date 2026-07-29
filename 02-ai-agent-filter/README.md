# 02-ai-agent-filter

Natural-language filtering of a Vaadin `Grid` of `Customer` records via **AI tool calling**: the LLM
parses the request and calls a `searchCustomers` tool, passing the filter values as tool arguments;
Java turns those arguments into a JPA `Specification`.

This module holds **two variants of the same mechanism**, steps 2 and 3 of the tutorial's escalation
ladder — both live in one running application behind their own route, so a talk can switch between
them without a restart:

| Variant | Route | Tool signature | Adds | Still out of reach |
|---|---|---|---|---|
| **02(a)** scalar | `/` (alias `/scalar`) | 13 parameters — one scalar value per field | nothing (the simplest possible tool call) | negation, operator precision, multi-value OR, ranges, date bounds |
| **02(b)** value + operator + negate | `/operator` | **39** parameters — value, `Operator`, `negate` per field | negation, operator precision, day-level date bounds | multi-value OR, any range (revenue or date) |

The interesting part is 02(b)'s bill: **three times** the tool parameters of 02(a) buys exactly two
capability categories, and still not general-purpose expressiveness. Multi-value OR and ranges need
more than one condition per field, which no per-field parameter list can carry. `03-ai-structured-filter`
and `04-ai-hybrid-filter` drop the per-field shape for a flat *condition list* instead — 03 delivers it
as structured output, 04 as a tool call. Compare with the non-AI baselines in `01-non-ai-filter`.

## Views

- **`/`** (alias `/scalar`) — `ScalarCustomerListView`, variant 02(a).
- **`/operator`** — `OperatorCustomerListView`, variant 02(b).
- **`AbstractCustomerListView`** — everything the two share: one natural-language `TextField` above the
  grid, the async search (`CompletableFuture` + `ui.access(...)`), the error notification, and a
  variant switcher linking to the other view. The subclasses only differ in their heading and in which
  `CustomerSearchAgent` bean Spring injects. Neither has a single Spring AI import — they only know
  `CustomerSearchAgent` and apply the `Specification` it returns.
- **`CustomerGrid`** — the `Grid<Customer>` itself (column config, backend sort configuration, and
  responsive show/hide), extracted out of the view. Unlike `01-non-ai-filter`'s
  `CustomerGrid`/`FilterableCustomerGrid` split, this module has a single fixed sort strategy and no
  per-column filter fields (filtering is the one AI `TextField` above), so sort config lives inside
  `CustomerGrid` rather than being applied by the view afterward.

Both agents implement the same `CustomerSearchAgent` interface, so they are interchangeable from the
view's point of view. Because there are two implementations, each view injects its own by **bean name**
(`@Qualifier("scalarSearchAgent")` / `@Qualifier("operatorSearchAgent")`) instead of by type.

## AI layer (`ai`)

```
ai/
├── CustomerSearchAgent.java            (public interface — the views' only dependency, the testability seam)
├── TokenUsageRecorder.java             (@Component — per-request token usage and duration)
├── scalar/                             ← variant 02(a)
│   ├── ScalarToolCallingService.java   (@Service("scalarSearchAgent") @Scope("prototype") — ChatClient, system prompt, the @Tool method)
│   ├── ScalarCriteria.java             (public record — one scalar value per field)
│   └── ScalarSpecifications.java       (public final utility — AND-across-fields -> Specification<Customer>)
└── operator/                           ← variant 02(b)
    ├── OperatorToolCallingService.java (@Service("operatorSearchAgent") @Scope("prototype") — 39 flat @ToolParams + the date tool)
    ├── Operator.java                   (public enum — CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH)
    ├── FieldCriterion.java             (public record — one field's value + operator + negate)
    ├── OperatorCriteria.java           (public record — one FieldCriterion per field)
    └── OperatorSpecifications.java     (public final utility — operator-driven predicates, negate via cb.not)
```

Both services are `@Scope("prototype")`, not the default singleton — because the views aren't singletons
either (Vaadin creates a fresh view instance per navigation), each view gets its own service instance.
That makes it safe for the `@Tool` methods and the `criteria` field they extract into to live directly on
the bean: different browser tabs/sessions never share an instance, and within one instance the view only
ever has one search in flight at a time (it disables the filter field for the duration of a search).
`requestCriteria(...)` resets `criteria` to `null` at the start of every call, since — unlike a fresh
per-call object — the field now outlives a single call.

`CustomerSearchAgent.resolveFilter(...)` never throws: on any failure (bad model response, unreachable
model, ...) it falls back to an unrestricted specification, so the UI never breaks.

### Variant 02(a) — one scalar value per field

The simplest tool call that can still filter: `searchCustomers(companyName, contactName, …, annualRevenue)`,
one scalar parameter per field, no `List` anywhere, no second tool. Because `ScalarCriteria` carries no
operator, every field's meaning is hard-wired in `ScalarSpecifications`:

- text fields — case-insensitive substring match,
- `customerSince` / `lastOrderDate` — the **whole calendar year** the given date falls in,
- `annualRevenue` — a **minimum** (`>=`), the most common phrasing ("revenue over X"),
- `creditRating` — the credit-score band of that rating.

So "customers from Berlin or Hamburg" loses one city, "except from Berlin" cannot be said at all, and
"revenue between 100000 and 500000" degrades to "at least 100000". Those are the queries variant 02(b)
partly fixes — and the ones it still can't.

### Variant 02(b) — value + operator + negate per field

Every field grows from one parameter to three: the value, `<field>Operator`, and `<field>Negate`. 13
fields × 3 = **39 flat tool parameters** on one `searchCustomers` tool, grouped internally into one
`FieldCriterion(value, operator, negate)` per field. What that buys:

- **negation** — "customers except from Berlin" → `city="Berlin"`, `cityNegate=true`, applied as
  `cb.not(...)`. There is no `NOT_CONTAINS` operator; negation is a flag, exactly as in
  `03-ai-structured-filter`'s `Condition`.
- **operator precision** — `CONTAINS` / `EQUALS` / `STARTS_WITH` / `ENDS_WITH` on text instead of always
  substring-matching, `EQUALS` / `GREATER_OR_EQUAL` / `LESS_OR_EQUAL` on numbers and dates.
- **real day-level date bounds** — "last ordered since 2024-07-01" is a genuine `>=` comparison, not
  02(a)'s whole-year match.

What it deliberately still cannot express — its **ceiling**, and the reason the ladder continues:

- **multi-value OR within a field.** One value parameter per field means "Berlin or Hamburg" has no
  second slot to go into.
- **any range.** A range needs a lower *and* an upper bound on the same field, i.e. two conditions;
  02(b) has one operator per field. This is true for `annualRevenue` ("between 100000 and 500000") and
  for dates ("last ordered in 2024") alike. There is deliberately no range-shaped value type — that
  would smuggle a second bound back in through the value.

The system prompt therefore never teaches range phrasing: the model has no parameter to put it in, and
pretending otherwise only produces invented values (e.g. `"100000-500000"` in a numeric field).

### Relative dates need two chained tool calls (02(b) only)

Variant 02(b) keeps a second tool, `currentLocalDateTime()`. For a relative date ("yesterday",
"in the last 12 months") the model must call it first, then compute an offset and pass that computed
date into `searchCustomers`. This two-hop chain is harder than a single tool call: a weaker model like
`llama3.1:8b` reliably fails it — it either passes a literal placeholder string instead of a computed
date, or skips the tool call and hallucinates a stale one — while the configured default `qwen3:8b`
handles it correctly. That is a genuine model-capability gap of the tool-calling approach, not a bug in
the tool wiring. `03-ai-structured-filter` and `04-ai-hybrid-filter` avoid the issue entirely by putting
"today" directly into the prompt text instead of requiring a live tool call — a good illustration of the
trade-off between the two ways of getting a value the model cannot know at prompt time.

Variant 02(a) has **no** date tool at all: with whole-year date semantics there is nothing useful a
computed day-level date could do.

## Running

```bash
./mvnw -pl 02-ai-agent-filter spring-boot:run   # http://localhost:8082 (/ or /scalar, and /operator)
```

### Switching LLM backends

The AI layer only ever talks to a generic Spring AI `ChatModel` bean — switching between backends
is purely a matter of which Spring profile is active (`application-<profile>.properties`), never a
code change. `openai` speaks the OpenAI-compatible chat completions API
(`spring-ai-starter-model-openai`); `ollama` uses Spring AI's *native* Ollama binding
(`spring-ai-starter-model-ollama`) instead — see the trade-off note below for why.

```bash
./mvnw -pl 02-ai-agent-filter spring-boot:run                                     # openai (default, no profile needed)
./mvnw -pl 02-ai-agent-filter spring-boot:run -Dspring-boot.run.profiles=ollama   # local Ollama
```

- **`openai`** (default) — the real OpenAI API. Requires the `OPENAI_API_KEY` environment variable
  (never hardcoded/committed); without it the app still starts (a dummy fallback key avoids a
  boot-time crash) but real requests fail with 401, caught by the same
  fallback-to-unrestricted-specification path as any other model failure.
- **`ollama`** — a local Ollama instance via Spring AI's *native* Ollama binding (not the
  OpenAI-compatible endpoint — see below). Start Ollama and pull the model first:
  ```bash
  ollama pull qwen3:8b
  ```

**Why `ollama` uses a different starter than `openai`:** Ollama's OpenAI-compatible endpoint
(`/v1/chat/completions`) silently ignores `"think":false` and `"options":{"num_ctx":...}` — verified
empirically against Ollama 0.32.0 (`qwen3:8b` kept reasoning regardless of `think:false`; a
requested `num_ctx` never changed the loaded model's actual context size per `/api/ps`). Both are
fully honored on Ollama's *native* `/api/chat` endpoint, which is what `spring-ai-starter-model-ollama`
talks to. Since Spring Boot autoconfigures one `ChatModel` bean per starter present on the
classpath, both starters are declared in `pom.xml` and `spring.ai.model.chat` picks which one wins
per profile (`openai` by default in `application.properties`, overridden to `ollama` in
`application-ollama.properties`) — `openai` still goes through the OpenAI-compatible surface,
since the real OpenAI API doesn't speak Ollama's native protocol.

`application-ollama.properties` sets `spring.ai.ollama.chat.think=false` (the configured default
`qwen3:8b` is reasoning-capable, so without this it would burn hundreds of tokens on a `<think>`
block per tool call; a non-reasoning model like `llama3.1:8b` ignores the flag anyway) and
`spring.ai.ollama.chat.num-ctx=4096` (now genuinely applied, unlike the old best-effort
`extra-body.options.num_ctx` passthrough this replaced).

## Tests

```bash
./mvnw -pl 02-ai-agent-filter test                                                # unit tests + both browserless view tests, no LLM
./mvnw -pl 02-ai-agent-filter verify -Pit-local-ollama                            # both variants' ITs vs native Ollama (ollama is the default test profile)
./mvnw -pl 02-ai-agent-filter verify -Pit-local-ollama -DAI_TEST_PROFILE=openai   # same suite, against the real OpenAI API
```

`-Pit-local-ollama` only controls which test classes run (it enables the Ollama-only ITs); which
Spring profile they use is a separate choice that defaults to `ollama` in the test config
(`src/test/resources/application.properties`), so the ITs target a native Ollama instance
(`OLLAMA_BASE_URL`) out of the box. Pass `-DAI_TEST_PROFILE=openai` (or the `AI_TEST_PROFILE`
environment variable, respecting `OPENAI_API_KEY` the same as the app itself) to run the identical
test classes against the real OpenAI API instead. The app's *own* default profile is `openai`; only
the test config overrides it to `ollama`.

Without an LLM (`test`), per variant:

- **`ScalarSpecificationsTest` / `OperatorSpecificationsTest`** (`@DataJpaTest`) — the filter
  translation against the seeded H2 data: one test per field group, AND-across-fields, and
  null-matches-all. Each also **asserts the variant's ceiling** (02(a): a date is always a whole year,
  revenue is always a minimum; 02(b): one operator per field means no range), so the limits are pinned
  down by tests rather than only described in prose.
- **`ScalarToolCallingServiceToolsTest` / `OperatorToolCallingServiceToolsTest`** (plain JUnit, no
  Spring context) — the extraction plumbing in isolation: arguments must land verbatim in the criteria
  record, a missing operator must default to `CONTAINS`, a field without a value must stay unset, and
  02(b)'s date tool must return the current time.
- **`ScalarCustomerListViewBrowserlessTest` / `OperatorCustomerListViewBrowserlessTest`** — [Vaadin
  Browserless testing](https://vaadin.com/docs/latest/flow/testing/browserless) with a fake,
  deterministic `CustomerSearchAgent` bean, so they never call a real model. Since the view applies
  results asynchronously (`CompletableFuture` + `ui.access(...)`), assertions after a non-blank query use
  `MockVaadin.runUIQueue()` (to flush the queued `ui.access()` command) inside an Awaitility
  `pollInSameThread()` loop (so the flush runs on the thread holding the UI `ThreadLocal`) — needed
  because a plain synchronous assertion races the background search thread.

Against a real model (`verify -Pit-local-ollama`):

- **`ScalarCustomerListViewBrowserlessIT` / `OperatorCustomerListViewBrowserlessIT`** — the same
  Browserless setup, but against a real native Ollama instance instead of a fake agent bean (they fail
  rather than skipping if unreachable), exercising the full `TextField` → tool-calling AI layer → `Grid`
  pipeline end to end. Since the real model's result size isn't known upfront, the wait condition is
  "the filter field is re-enabled" (it's disabled for the duration of a search) rather than a fixed grid
  size. Each IT only asks what its variant can express; 02(b)'s adds a negation and a STARTS_WITH case.

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/` — the two variant views, their shared base class,
  and the grid (columns, sort, responsive layout)
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/` — the AI layer, one package per variant (see above)
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
