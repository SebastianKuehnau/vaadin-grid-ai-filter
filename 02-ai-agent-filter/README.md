# 02-ai-agent-filter

Natural-language filtering of a Vaadin `Grid` of `Customer` records via **AI tool calling**: the
LLM parses the request and calls a `searchCustomers` tool, passing one argument per field; Java
turns those arguments into a JPA `Specification`. First step in this tutorial towards filtering
data with natural language — compare with the non-AI baselines in `01-non-ai-filter` and the
structured-output approach in `03-ai-structured-filter`.

## View

- **`/`** — `CustomerListView`: a single natural-language `TextField` above the grid. Typing a
  query (and blurring/pressing enter) sends it to the AI layer; a blank query resets to all rows.
  The view has zero Spring AI imports — it only knows `CustomerSearchAgent` and applies the
  `Specification` it returns.

## AI layer (`ai` / `ai/filter`)

```
ai/
├── CustomerSearchAgent.java              (public interface — the view's only dependency, the testability seam)
├── CustomerSearchToolCallingService.java (@Service @Scope("prototype") — ChatClient, system prompt, both @Tool methods)
└── filter/
    ├── CustomerSearchCriteria.java      (public record — the flat extracted filter values, one List per field)
    ├── CustomerSpecifications.java      (public final utility — OR-within-field, AND-across-fields -> Specification<Customer>)
    └── RevenueRange.java                (public record — one annualRevenue bound, atLeast/atMost)
```

`CustomerSearchToolCallingService` is `@Scope("prototype")`, not the default singleton — because
`CustomerListView` (the only place a `CustomerSearchAgent` is injected) isn't a singleton either
(Vaadin creates a fresh view instance per navigation), each view gets its own service instance. That
makes it safe for the two `@Tool` methods (`searchCustomers`, `currentLocalDateTime`) and the
`criteria` field they extract into to live directly on the bean: different browser tabs/sessions
never share an instance, and within one instance the view only ever has one search in flight at a
time (it disables the filter field for the duration of a search). `requestCriteria(...)` resets
`criteria` to `null` at the start of every call, since — unlike a fresh per-call object — the field
now outlives a single call. `CustomerSpecifications` combines fields with AND, same as
`03-ai-structured-filter`'s tree at the top level, but stays flat (no OR/NOT across fields, no
nesting) — the deliberate, demo-relevant contrast with that module's `FilterNode` tree.

`CustomerSearchAgent.resolveFilter(...)` never throws: on any failure (bad model response,
unreachable model, ...) it falls back to an unrestricted specification, so the UI never breaks.

### Multi-value filtering

Every `CustomerSearchCriteria` field is a `List` rather than a single value, so a query like
"customers from Berlin or Hamburg" extracts two cities, matched with OR; different fields still
combine with AND, e.g. "from Berlin or Hamburg with GOOD or MEDIUM credit rating" is `(city='Berlin'
OR city='Hamburg') AND (creditRating='GOOD' OR creditRating='MEDIUM')`. A single-value query still
works exactly as before — it's just a one-element list.

`customerSince`/`lastOrderDate` values are each interpreted as the full year they fall in (Jan 1 -
Dec 31), so "since 2020 or 2021" OR-combines two year ranges rather than two single days.

`annualRevenue` is modeled differently from the other fields: it's a continuous `BigDecimal`, not a
discrete value like `city` or `creditRating`, so "multi-value" means a list of `RevenueRange(atLeast,
atMost)` bounds rather than a list of exact numbers. Either bound may be omitted for an open-ended
range ("over 500000" -> `{atLeast: 500000}`), and multiple ranges are OR-combined ("over 500000 or
under 50000" -> two ranges). The fields are named `atLeast`/`atMost` rather than `min`/`max`
deliberately: with a small local model driving the tool call, `llama3.1:8b` consistently swapped
`min`/`max` for "over X" queries during testing — a self-describing field name fixed it, since the
model no longer needs the tool description prose to carry the "over" vs. "under" direction.

### Known limitation: relative dates need two chained tool calls

For a relative date ("yesterday", "last week") the model must call `currentLocalDateTime()`, then
compute an offset from its result and pass that computed date into `searchCustomers`. This
two-hop chain is harder than a single tool call: `llama3.1:8b` (this module's configured default)
reliably fails it — it either passes a literal placeholder string instead of a computed date, or
skips the tool call and hallucinates a stale one — while `qwen3:8b` handles it correctly. This is
a genuine model-capability gap in the tool-calling approach, not a bug in the tool wiring, and is
why `CustomerSearchAgentIT` (below) does not include a relative-date case. `03-ai-structured-filter`
avoids the issue entirely by putting "today" directly into its prompt text instead of requiring a
live tool call — a good illustration of the trade-off between the two approaches.

## Running

```bash
./mvnw -pl 02-ai-agent-filter spring-boot:run   # http://localhost:8082
```

### Switching LLM backends

The AI layer only ever talks to a single `ChatModel` bean produced by
`spring-ai-starter-model-openai` — all three backends speak the OpenAI-compatible chat completions
API, so switching between them is purely a matter of which Spring profile is active
(`application-<profile>.properties`), never a code change:

```bash
./mvnw -pl 02-ai-agent-filter spring-boot:run                                    # ollama (default, no profile needed)
./mvnw -pl 02-ai-agent-filter spring-boot:run -Dspring-boot.run.profiles=mlx     # mlx_lm.server, Apple Silicon only
./mvnw -pl 02-ai-agent-filter spring-boot:run -Dspring-boot.run.profiles=cloud   # real OpenAI API
```

- **`ollama`** (default) — a local Ollama instance via its OpenAI-compatible endpoint. Start
  Ollama and pull the model first:
  ```bash
  ollama pull llama3.1:8b
  ```
- **`mlx`** — a local [`mlx_lm.server`](https://github.com/ml-explore/mlx-lm) instance (Apple
  Silicon only, doesn't run in a Linux container). Start it manually on the host first, e.g.:
  ```bash
  mlx_lm.server --model mlx-community/Meta-Llama-3.1-8B-Instruct-4bit --port 8090
  ```
  Adjust `spring.ai.openai.chat.model` in `application-mlx.properties` to whatever model you
  actually loaded. If the app itself runs inside a container that needs to reach a host-side
  `mlx_lm.server`, point `MLX_BASE_URL` at `http://host.docker.internal:8090` instead of
  `localhost`, following the same pattern as `OLLAMA_BASE_URL`.
- **`cloud`** — the real OpenAI API. Requires the `OPENAI_API_KEY` environment variable (never
  hardcoded/committed); without it the app still starts (a dummy fallback key avoids a boot-time
  crash) but real requests fail with 401, caught by the same fallback-to-unrestricted-specification
  path as any other model failure.

Trade-off: going through the OpenAI-compatible surface for all three backends means Ollama-native
tuning (`chat.think`, `chat.num-ctx`, `init.pull-model-strategy`) is no longer configurable via
Spring AI — see `application-ollama.properties` for the one best-effort exception attempted
(`chat.extra-body.options.num_ctx`).

## Tests

```bash
./mvnw -pl 02-ai-agent-filter test                        # unit tests + CustomerListViewBrowserlessTest, no LLM
./mvnw -pl 02-ai-agent-filter verify -Pit-local-ollama     # CustomerSearchAgentIT + CustomerListViewBrowserlessIT vs native Ollama (skip if unreachable)
```

- **`CustomerSpecificationsTest`** (`@DataJpaTest`, no LLM) — one test per predicate/field against
  the seeded H2 data (single- and multi-value/OR cases, including `annualRevenue`'s open- and
  closed-ended ranges), plus AND-across-fields and null-matches-all.
- **`CustomerSearchToolCallingServiceToolsTest`** (plain JUnit, no Spring context) — the extraction
  plumbing (single- and multi-value arguments) and the date tool, in isolation.
- **`CustomerSearchAgentIT extends LocalOllamaTests`** — 18 natural-language queries against a
  native Ollama instance (`LocalOllamaTests`/`OllamaTestSupport` duplicated from
  `03-ai-structured-filter`, this repo's established per-module pattern for Ollama IT
  infrastructure). Assertions are tolerant of LLM non-determinism (case-insensitive, substring).
  Every case here uses the exact same wording/values as one of
  `03-ai-structured-filter`'s `CustomerSearchAgentIT` cases, so the two modules' results and
  timings are directly comparable; that module has additional cases (tagged `negation`,
  `operator-precision`, `relative-date`, `cross-field-or`, `nested-tree`) with no counterpart
  here, because this module's flat `CustomerSearchCriteria` can't express NOT,
  STARTS_WITH/ENDS_WITH, arbitrary date bounds, or OR/nesting across different fields.
- **`CustomerListViewBrowserlessTest`** — [Vaadin Browserless
  testing](https://vaadin.com/docs/latest/flow/testing/browserless) with a fake, deterministic
  `CustomerSearchAgent` bean, so it never calls a real model. Since the view applies results
  asynchronously (`CompletableFuture` + `ui.access(...)`), assertions after a non-blank query use
  `MockVaadin.runUIQueue()` (to flush the queued `ui.access()` command) inside an Awaitility
  `pollInSameThread()` loop (so the flush runs on the thread holding the UI `ThreadLocal`) —
  needed because a plain synchronous assertion races the background search thread.
- **`CustomerListViewBrowserlessIT`** — same Browserless setup, but against a real native Ollama
  instance instead of a fake agent bean (skips gracefully if unreachable, like
  `CustomerSearchAgentIT`), exercising the full `TextField` → tool-calling AI layer → `Grid`
  pipeline end to end. Since the real model's result size isn't known upfront, the wait condition
  is "the filter field is re-enabled" (it's disabled for the duration of a search) rather than a
  fixed grid size. `03-ai-structured-filter` has an identical test with the same 8 queries, so the
  two modules'
  `-Pit-local-ollama` runs are directly comparable on speed (per-test elapsed time in
  `target/failsafe-reports/`) and result quality between tool calling and structured output.

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerListView.java` — the view
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/` — the AI layer (see above)
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
