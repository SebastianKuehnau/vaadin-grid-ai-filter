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

## AI layer (`ai` / `ai/filter`)

```
ai/
├── CustomerSearchAgent.java                    (public interface — the view's only dependency, the testability seam)
├── CustomerSearchStructuredOutputService.java  (@Service — ChatClient, system prompt, structured-output call)
└── filter/
    ├── CustomerFilter.java                (public record — wraps the root FilterNode)
    ├── FilterNode.java                    (sealed interface — AND/OR/NOT/CONDITION tree)
    ├── Operator.java                      (enum — CONTAINS, EQUALS, GREATER_OR_EQUAL, ...)
    └── CustomerFilterSpecifications.java  (public final utility — recursive tree -> Specification<Customer>)
```

`CustomerSearchAgent.resolveFilter(...)` never throws: on any failure (bad model response,
unreachable model, ...) it falls back to an unrestricted specification, so the UI never breaks.

### Filter tree structure

`CustomerFilter` wraps a single `root` `FilterNode` — a tree of `AND` / `OR` / `NOT` / `CONDITION`
nodes (see `FilterNode.java`). This lets the LLM express any boolean combination, including
cross-field OR (`city = Berlin OR annualRevenue >= 1000000`), which a flat list of conditions
cannot represent — the deliberate, demo-relevant contrast with `02-ai-agent-filter`'s flat
`CustomerSearchCriteria`. `CustomerFilterSpecifications` translates the tree into a JPA
`Specification` with a recursive walk (AND → `cb.and`, OR → `cb.or`, NOT → `cb.not`, CONDITION →
the per-field predicate builders). A `null` root, or an `AND`/`OR` with no children, matches every
customer.

Example — `(city=Berlin OR city=Hamburg) AND (annualRevenue>=500000 OR creditRating=GOOD)`:

```json
{
  "root": {
    "type": "AND",
    "children": [
      { "type": "OR", "children": [
          { "type": "CONDITION", "field": "city", "operator": "CONTAINS", "value": "Berlin" },
          { "type": "CONDITION", "field": "city", "operator": "CONTAINS", "value": "Hamburg" } ] },
      { "type": "OR", "children": [
          { "type": "CONDITION", "field": "annualRevenue", "operator": "GREATER_OR_EQUAL", "value": "500000" },
          { "type": "CONDITION", "field": "creditRating", "operator": "EQUALS", "value": "GOOD" } ] }
    ]
  }
}
```

Nesting is a bigger ask of the model than a flat list — it must correctly place AND/OR/NOT rather
than emit one flat list. Smaller local models are more likely to flatten a nested query
incorrectly (e.g. drop a condition, or misplace it in the wrong branch), especially for
cross-field OR and NOT-negated groups. `CustomerSearchAgentNestedIT`'s nesting/cross-field cases
are tagged by the complexity they require (`medium-model-query`, `large-model-query`) so the
difference shows up per model in the `04-ollama-benchmark` results.

### Ollama integration test architecture

The Ollama-backed integration tests use a subclassing structure instead of one unrelated test
class per use case:

```
ai/
├── LocalOllamaTests.java       (infrastructure: native Ollama, @SpringBootTest)
└── CustomerSearchAgentIT.java  (test cases, provider-agnostic) extends LocalOllamaTests
```

- `LocalOllamaTests` is the infrastructure class: it declares the `@SpringBootTest` that wires
  Spring AI to a native Ollama instance and skips gracefully (via `assumeTrue`) if Ollama is
  unreachable at `OLLAMA_BASE_URL`.
- `CustomerSearchAgentIT` holds the actual test cases and assertions. It declares no
  `@SpringBootTest` of its own — it inherits the Spring context and reachability check from
  `LocalOllamaTests`.
- Run with `-Pit-local-ollama`, or a single suite with `-Dit.test=CustomerSearchAgentIT`.

The LLM only produces filter *intent* (a `FilterNode` tree); it never sees the customer data and
never writes the final query — Java turns the intent into a `Specification` and the database
executes it.

## Running

```bash
./mvnw -pl 03-ai-structured-filter spring-boot:run   # http://localhost:8083
```

### Switching LLM backends

The AI layer only ever talks to a single `ChatModel` bean produced by
`spring-ai-starter-model-openai` — all three backends speak the OpenAI-compatible chat completions
API, so switching between them is purely a matter of which Spring profile is active
(`application-<profile>.properties`), never a code change:

```bash
./mvnw -pl 03-ai-structured-filter spring-boot:run                                    # ollama (default, no profile needed)
./mvnw -pl 03-ai-structured-filter spring-boot:run -Dspring-boot.run.profiles=mlx     # mlx_lm.server, Apple Silicon only
./mvnw -pl 03-ai-structured-filter spring-boot:run -Dspring-boot.run.profiles=cloud   # real OpenAI API
```

- **`ollama`** (default) — a local Ollama instance via its OpenAI-compatible endpoint. Start
  Ollama and pull the model first:
  ```bash
  ollama pull llama3.1:8b
  ```
  Other models benchmarked against this module in `../04-ollama-benchmark`: `qwen3.5:4b-mlx`,
  `qwen3:8b`, `gemma4:26b-mlx` — swap `spring.ai.openai.chat.model` in
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

Trade-off: going through the OpenAI-compatible surface for all three backends means Ollama-native
tuning (`chat.think`, `chat.num-ctx`, `init.pull-model-strategy`) is no longer configurable via
Spring AI — see `application-ollama.properties` for the one best-effort exception attempted
(`chat.extra-body.options.num_ctx`).

## Tests

```bash
./mvnw -pl 03-ai-structured-filter test                        # unit tests + CustomerListViewBrowserlessTest, no LLM
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama     # CustomerSearchAgentIT(+Nested) + CustomerListViewBrowserlessIT vs native Ollama (skip if unreachable)
AI_TEST_PROFILE=cloud ./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama   # same suite, against the cloud (or mlx) profile instead
```

`-Pit-local-ollama` targets the `ollama` profile by default; `AI_TEST_PROFILE=mlx|cloud` points the
exact same test classes at the app's other Spring profiles instead (respecting
`MLX_BASE_URL`/`OPENAI_API_KEY`, same as the app itself) — see `OllamaTestSupport`.

- **`CustomerFilterSpecificationsTest`** (`@DataJpaTest`, no LLM) — deterministic test of the tree
  translation against the seeded H2 data. The single-field and multi-value-OR cases use the same
  field values as `02-ai-agent-filter`'s `CustomerSpecificationsTest`, so DB-level results are
  directly comparable.
- **`CustomerFilterSpecificationsNestedTest`** (`@DataJpaTest`, no LLM) — the tree-only cases
  split out of the class above (AND/OR/NOT nesting, cross-field OR, negation) that have no
  counterpart in `02-ai-agent-filter`'s flat model, so there's nothing there to compare them
  against.
- **`CustomerSearchAgentIT extends LocalOllamaTests`** — 19 natural-language queries against a
  native Ollama instance (`LocalOllamaTests`/`OllamaTestSupport` duplicated from
  `02-ai-agent-filter`, this repo's established per-module pattern for Ollama IT infrastructure).
  Assertions are tolerant of LLM non-determinism: they check that an expected condition is
  present *somewhere* in the filter tree, ignoring exact tree shape. Every case here uses the
  exact same wording/values as one of `02-ai-agent-filter`'s `CustomerSearchAgentIT` cases, so
  the two modules' results and timings are directly comparable by running this class alone
  (`-Dit.test=CustomerSearchAgentIT`).
- **`CustomerSearchAgentNestedIT extends LocalOllamaTests`** — the 19 cases split out of the class
  above that need a capability `02-ai-agent-filter`'s flat model cannot express at all: negation
  (`NOT`/`NOT_*`), STARTS_WITH/ENDS_WITH/EQUALS operator precision, arbitrary date bounds,
  cross-field OR, and deeper nesting. The nesting/cross-field cases are additionally tagged
  `medium-model-query`/`large-model-query` by the complexity required, harder for smaller local
  models to produce reliably. Note: `04-ollama-benchmark/BenchmarkLocalModels.java`'s published
  results table still reflects the original 32-query set that predates this split into two
  classes (38 cases total now) — see that module's README before relying on a "same queries"
  claim there.
- **`CustomerListViewBrowserlessTest`** — [Vaadin Browserless
  testing](https://vaadin.com/docs/latest/flow/testing/browserless) with a fake, deterministic
  `CustomerSearchAgent` bean, so it never calls a real model. Since the view applies results
  asynchronously (`CompletableFuture` + `ui.access(...)`), assertions after a non-blank query use
  `MockVaadin.runUIQueue()` (to flush the queued `ui.access()` command) inside an Awaitility
  `pollInSameThread()` loop (so the flush runs on the thread holding the UI `ThreadLocal`) —
  needed because a plain synchronous assertion races the background search thread. Includes the
  same multi-value OR-within-field case as `02-ai-agent-filter`'s equivalent test, expressed via
  `CustomerFilter`'s tree instead of a flat criteria list.
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
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/` — the AI layer (see above)
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
- `../04-ollama-benchmark/` — standalone benchmark script comparing local Ollama models on this
  module's natural-language-to-filter task
