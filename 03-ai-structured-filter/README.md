# 03-ai-structured-filter

Natural-language filtering of a Vaadin `Grid` of `Customer` records via **AI structured output**:
the LLM returns a single `CustomerFilter` JSON object (instead of calling a tool), which Java
translates into a JPA `Specification`. Second step in this tutorial towards filtering data with
natural language — compare with the tool-calling approach in `02-ai-agent-filter` and the non-AI
baselines in `01-non-ai-filter`.

## View

- **`/`** — `CustomerListView`: a single natural-language `TextField` above the grid. Typing a
  query (and blurring/pressing enter) sends it to the AI layer; a blank query resets to all rows.
  The view has zero Spring AI imports — it only knows `CustomerSearchAgent` and applies the
  `Specification` it returns.
- **`CustomerGrid`** — the `Grid<Customer>` itself (column config, backend sort configuration, and
  responsive show/hide), extracted out of the view. Unlike `01-non-ai-filter`'s
  `CustomerGrid`/`FilterableCustomerGrid` split, this module has a single fixed sort strategy and no
  per-column filter fields (filtering is the one AI `TextField` above), so sort config lives inside
  `CustomerGrid` rather than being applied by the view afterward.

## AI layer (`ai` / `ai/filter`)

```
ai/
├── CustomerSearchAgent.java                    (public interface — the view's only dependency, the testability seam)
├── CustomerSearchStructuredOutputService.java  (@Service — ChatClient, system prompt, structured-output call)
└── filter/
    ├── CustomerFilter.java                (public record — a flat list of conditions, ALL combined with AND)
    ├── Condition.java                     (public record — one field/operator/values/negate condition)
    ├── Operator.java                      (enum — CONTAINS, EQUALS, GREATER_OR_EQUAL, ...)
    └── CustomerFilterSpecifications.java  (public final utility — flat conditions -> Specification<Customer>)
```

`CustomerSearchAgent.resolveFilter(...)` never throws: on any failure (bad model response,
unreachable model, ...) it falls back to an unrestricted specification, so the UI never breaks.

### Flat filter schema

`CustomerFilter` is a flat list of `Condition`s, always combined with AND (see `Condition.java`).
Each `Condition` can itself express OR (several `values` for the same field, e.g. `city` matches
"Berlin" or "Köln") and negation (`negate=true` excludes matches instead of requiring them, e.g.
"not from Berlin"). A value *range* on one field (e.g. a year) becomes two sibling conditions on
that field, AND-combined like everything else. `CustomerFilterSpecifications` translates the list
with a flat walk: per condition, OR the predicates for each value, negate if requested, then AND
all conditions together. An empty (or `null`) conditions list matches every customer.

This is deliberately less expressive than a recursive AND/OR/NOT tree: **cross-field OR** (e.g.
`city = Berlin OR annualRevenue >= 1000000`) and **arbitrary nesting** are not representable — a
conscious trade-off for a shape that's far easier for a small/local model to produce correctly,
at the cost of that expressiveness. `02-ai-agent-filter`'s flat `CustomerSearchCriteria` remains
the point of comparison, though its model is simpler still (no explicit operator/negate — semantics
are baked into each field's predicate builder).

Example — "customers from Berlin or Köln, not from Munich, with at least 100000 revenue":

```json
{
  "conditions": [
    { "field": "city", "operator": "CONTAINS", "values": ["Berlin", "Köln"], "negate": false },
    { "field": "city", "operator": "CONTAINS", "values": ["Munich"], "negate": true },
    { "field": "annualRevenue", "operator": "GREATER_OR_EQUAL", "values": ["100000"], "negate": false }
  ]
}
```

Small/local models are noticeably more reliable at producing this shape than the previous
recursive tree — see `../04-ollama-benchmark`'s recorded latency/accuracy comparison.

## Running

```bash
./mvnw -pl 03-ai-structured-filter spring-boot:run   # http://localhost:8083
```

### Switching LLM backends

The AI layer only ever talks to a generic Spring AI `ChatModel` bean — switching between backends
is purely a matter of which Spring profile is active (`application-<profile>.properties`), never a
code change. `mlx`/`cloud` both speak the OpenAI-compatible chat completions API
(`spring-ai-starter-model-openai`); `ollama` uses Spring AI's *native* Ollama binding
(`spring-ai-starter-model-ollama`) instead — see the trade-off note below for why.

```bash
./mvnw -pl 03-ai-structured-filter spring-boot:run                                    # ollama (default, no profile needed)
./mvnw -pl 03-ai-structured-filter spring-boot:run -Dspring-boot.run.profiles=mlx     # mlx_lm.server, Apple Silicon only
./mvnw -pl 03-ai-structured-filter spring-boot:run -Dspring-boot.run.profiles=cloud   # real OpenAI API
```

- **`ollama`** (default) — a local Ollama instance via Spring AI's *native* Ollama binding (not the
  OpenAI-compatible endpoint — see below). Start Ollama and pull the model first:
  ```bash
  ollama pull llama3.1:8b
  ```
  Other models benchmarked against this module in `../04-ollama-benchmark`: `qwen3.5:4b-mlx`,
  `qwen3:8b`, `gemma4:26b-mlx` — swap `spring.ai.ollama.chat.model` in
  `application-ollama.properties` to try one.
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

**Why `ollama` uses a different starter than `mlx`/`cloud`:** Ollama's OpenAI-compatible endpoint
(`/v1/chat/completions`) silently ignores `"think":false` and `"options":{"num_ctx":...}` — verified
empirically against Ollama 0.32.0 (`qwen3:8b` kept reasoning regardless of `think:false`; a
requested `num_ctx` never changed the loaded model's actual context size per `/api/ps`). Both are
fully honored on Ollama's *native* `/api/chat` endpoint, which is what `spring-ai-starter-model-ollama`
talks to. Since Spring Boot autoconfigures one `ChatModel` bean per starter present on the
classpath, both starters are declared in `pom.xml` and `spring.ai.model.chat` picks which one wins
per profile (`openai` by default in `application.properties`, overridden to `ollama` in
`application-ollama.properties`) — `mlx`/`cloud` still go through the OpenAI-compatible surface,
since `mlx_lm.server`/the real OpenAI API don't speak Ollama's native protocol.

`application-ollama.properties` sets `spring.ai.ollama.chat.think=false` (llama3.1:8b never
"thinks" anyway, but this matters the moment you swap in a reasoning-capable model like `qwen3:8b` —
without it, such a model burns hundreds of tokens on a `<think>` block per call) and
`spring.ai.ollama.chat.num-ctx=4096` (now genuinely applied, unlike the old best-effort
`extra-body.options.num_ctx` passthrough this replaced).

## Tests

```bash
./mvnw -pl 03-ai-structured-filter test                        # unit tests + CustomerListViewBrowserlessTest, no LLM
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama     # CustomerSearchAgentIT(+Extra) + CustomerListViewBrowserlessIT vs native Ollama (skip if unreachable)
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama -DAI_TEST_PROFILE=cloud   # same suite, against the cloud (or mlx) profile instead
```

`-Pit-local-ollama` targets the `ollama` profile by default; `-DAI_TEST_PROFILE=mlx|cloud` (or the
`AI_TEST_PROFILE` environment variable, if you prefer) points the exact same test classes at the
app's other Spring profiles instead (respecting `MLX_BASE_URL`/`OPENAI_API_KEY`, same as the app
itself) — see `src/test/resources/application.properties`.

> **Note:** the module's configured default model, `llama3.1:8b`, is occasionally unreliable on
> queries that stack three-plus conditions together with the new bare-year date-range rule (see
> below) — it sometimes drops one bound of a date range. `qwen3:8b` handles the same queries
> correctly. This is a model-capability gap, not a bug in the prompt/schema; swap the model in
> `application-ollama.properties` if you hit it during a demo.

- **`CustomerFilterSpecificationsTest`** (`@DataJpaTest`, no LLM) — deterministic test of the flat
  translation against the seeded H2 data. The single-field, multi-value-OR, and AND-across-fields
  cases use the same field values as `02-ai-agent-filter`'s `CustomerSpecificationsTest`, so
  DB-level results are directly comparable.
- **`CustomerFilterSpecificationsExtraTest`** (`@DataJpaTest`, no LLM) — the one case split out of
  the class above that `02-ai-agent-filter`'s flat model cannot express at all: negation
  (`Condition.negate()`).
- **`CustomerSearchAgentIT`** — natural-language queries against a native Ollama instance.
  Assertions are tolerant of LLM non-determinism: they check that an expected condition is
  present *somewhere* in the flat conditions list, ignoring extras. Every case here uses the
  exact same wording/values as one of `02-ai-agent-filter`'s `CustomerSearchAgentIT` cases, so
  the two modules' results and timings are directly comparable by running this class alone
  (`-Dit.test=CustomerSearchAgentIT`).
- **`CustomerSearchAgentExtraIT`** — the cases that need a capability `02-ai-agent-filter`'s flat
  model cannot express at all: negation, STARTS_WITH/ENDS_WITH/EQUALS operator precision, and
  arbitrary date bounds (tagged `negation`/`operator-precision`/`relative-date` respectively).
  Cross-field OR and arbitrary nesting are no longer part of `CustomerFilter` either, so the cases
  that used to need them (tagged `cross-field-or`/`nested-tree`) were removed rather than moved
  here — a deliberate trade-off for faster/more reliable structured output, not an oversight.
- **`CustomerListViewBrowserlessTest`** — [Vaadin Browserless
  testing](https://vaadin.com/docs/latest/flow/testing/browserless) with a fake, deterministic
  `CustomerSearchAgent` bean, so it never calls a real model. Since the view applies results
  asynchronously (`CompletableFuture` + `ui.access(...)`), assertions after a non-blank query use
  `MockVaadin.runUIQueue()` (to flush the queued `ui.access()` command) inside an Awaitility
  `pollInSameThread()` loop (so the flush runs on the thread holding the UI `ThreadLocal`) —
  needed because a plain synchronous assertion races the background search thread. Includes the
  same multi-value OR-within-field case as `02-ai-agent-filter`'s equivalent test, expressed via
  `CustomerFilter`'s flat conditions list instead of a criteria record.
- **`CustomerListViewBrowserlessIT`** — same Browserless setup, but against a real native Ollama
  instance instead of a fake agent bean (skips gracefully if unreachable, like
  `CustomerSearchAgentIT`), exercising the full `TextField` → structured-output AI layer → `Grid`
  pipeline end to end. Since the real model's result size isn't known upfront, the wait condition
  is "the filter field is re-enabled" (it's disabled for the duration of a search) rather than a
  fixed grid size. `02-ai-agent-filter` has an identical test with the same 8 queries, so the two
  modules' `-Pit-local-ollama` runs are directly comparable on speed (per-test elapsed time in
  `target/failsafe-reports/`) and result quality between tool calling and structured output.

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerListView.java` — the view
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerGrid.java` — the grid (columns, sort, responsive layout)
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/` — the AI layer (see above)
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
- `../04-ollama-benchmark/` — standalone benchmark script comparing local Ollama models on this
  module's natural-language-to-filter task
