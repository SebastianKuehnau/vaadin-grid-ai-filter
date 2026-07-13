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
├── AiConfiguration.java                        (@Configuration — Ollama/OpenAI ChatModel provider selection)
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

Configured for a local Ollama by default (see `AiConfiguration`); start Ollama and pull the model
first:

```bash
ollama pull llama3.1:8b
```

Switch to OpenAI by setting `app.ai.provider=openai` in `application.properties` (needs the
`OPENAI_API_KEY` environment variable), or uncomment one of the alternative local models in the
Ollama block.

## Tests

```bash
./mvnw -pl 03-ai-structured-filter test                        # unit tests + CustomerListViewBrowserlessTest, no LLM
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama     # CustomerSearchAgentIT(+Nested) + CustomerListViewBrowserlessIT vs native Ollama (skip if unreachable)
```

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
