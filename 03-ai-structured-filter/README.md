# 03-ai-structured-filter

Natural-language filtering of a Vaadin `Grid` of `Customer` records via **AI structured output**:
the LLM returns a single `CustomerFilter` JSON object (instead of calling a tool), which Java
translates into a JPA `Specification`.

Step 4 of this tutorial's escalation ladder, and the step where the filter *type* changes: the two
tool-calling variants before it (`02-ai-agent-filter`) carry one value — or one value, operator and
negate flag — per field, which cannot express multi-value OR or a range on one field. A flat list of
conditions can. `04-ai-hybrid-filter` then delivers this very same filter type through a tool call, which
is how the repository separates "what a filter can express" from "how the model hands it over". Compare
also with the non-AI baselines in `01-non-ai-filter`; the root `README.md` has the whole ladder.

## View

- **`/`** — `CustomerListView`: a single natural-language `TextField` above the grid. Typing a
  query (and blurring/pressing enter) sends it to the AI layer; a blank query resets to all rows.
  The view has zero Spring AI imports — it only knows `CustomerSearchAgent` and applies the
  `Specification` it returns.
- **`AbstractCustomerSearchView`** (in **`demo-commons`**) — the filter field, the async search
  (`CompletableFuture` + `ui.access(...)`) and the error notification, shared with `02` and `04`. This
  module's view is a heading on top of it, which is the point: nothing up here reveals that the model
  returned an object rather than calling a tool.
- **`CustomerGrid`** — the `Grid<Customer>` itself (column config, backend sort configuration, and
  responsive show/hide). It lives in **`demo-commons`**: all four apps show the same grid, and it says
  nothing about how a filter came into being. Unlike `01-non-ai-filter`, this module needs no per-column
  filter fields — filtering is the one AI `TextField` above — and it keeps the shared grid's backend sort
  configuration unchanged.

## AI layer (`ai` / `ai/filter`)

```
ai/
├── CustomerSearchService.java            (@Service — ChatClient, system prompt, structured-output call)
└── filter/
    ├── CustomerFilter.java                (public record — a flat list of conditions, ALL combined with AND)
    ├── Condition.java                     (public record — one field/operator/values/negate condition, with the nested Operator enum)
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
at the cost of that expressiveness.

The comparison downwards is with `02-ai-agent-filter`'s per-field criteria: 02(a) has one scalar value per
field with the semantics baked into each field's predicate builder, and 02(b) adds an operator and a
negate flag per field — but neither can hold two values or two bounds for *one* field, so multi-value OR
and ranges are impossible there, not merely unreliable. `04-ai-hybrid-filter` uses the same
`CustomerFilter`/`Condition`/`Operator` types as this module, copied 1:1, and reaches them through a tool
call instead.

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
recursive tree — see `../ollama-benchmark`'s recorded latency/accuracy comparison.

## Running

```bash
./mvnw -pl 03-ai-structured-filter spring-boot:run   # http://localhost:8083
```

### Switching LLM backends

The AI layer only ever talks to a generic Spring AI `ChatModel` bean — switching between backends
is purely a matter of which Spring profile is active (`application-<profile>.properties`), never a
code change. `openai` speaks the OpenAI-compatible chat completions API
(`spring-ai-starter-model-openai`); `ollama` uses Spring AI's *native* Ollama binding
(`spring-ai-starter-model-ollama`) instead — see the trade-off note below for why.

```bash
./mvnw -pl 03-ai-structured-filter spring-boot:run                                     # openai (default, no profile needed)
./mvnw -pl 03-ai-structured-filter spring-boot:run -Dspring-boot.run.profiles=ollama   # local Ollama
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
  Other models benchmarked against this module in `../ollama-benchmark`: `qwen3.5:4b-mlx`,
  `qwen3:8b`, `gemma4:26b-mlx` — swap `spring.ai.ollama.chat.model` in
  `application-ollama.properties` to try one.

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
block per call; a non-reasoning model like `llama3.1:8b` ignores the flag anyway) and
`spring.ai.ollama.chat.num-ctx=4096` (now genuinely applied, unlike the old best-effort
`extra-body.options.num_ctx` passthrough this replaced).

## Tests

```bash
./mvnw -pl 03-ai-structured-filter test                        # unit tests only, no LLM, no UI
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama                            # StructuredCanonicalQueryIT + PromptRobustnessIT + CustomerListViewBrowserlessIT vs native Ollama (ollama is the default test profile)
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama -DAI_TEST_PROFILE=openai   # same suite, against the real OpenAI API
```

`-Pit-local-ollama` only controls which test classes run (it enables the Ollama-only ITs); which
Spring profile they use is a separate choice that defaults to `ollama` in the test config
(`src/test/resources/application.properties`), so the ITs target a native Ollama instance
(`OLLAMA_BASE_URL`) out of the box. Pass `-DAI_TEST_PROFILE=openai` (or the `AI_TEST_PROFILE`
environment variable, respecting `OPENAI_API_KEY` the same as the app itself) to run the identical
test classes against the real OpenAI API instead. The app's *own* default profile is `openai`; only
the test config overrides it to `ollama`.

> **Note:** the configured default model, `qwen3:8b`, handles queries that stack three-plus
> conditions together with the bare-year date-range rule (see below) correctly. A weaker model such
> as `llama3.1:8b` is occasionally unreliable on them — it sometimes drops one bound of a date
> range. This is a model-capability gap, not a bug in the prompt/schema; keep the configured default
> (or swap the model in `application-ollama.properties`) if you hit it during a demo.

- **`demo-commons`' `CanonicalQuerySetConsistencyTest`** (plain JUnit, no Spring, no LLM) — fails the
  build if `CanonicalQuery` or the benchmark script stops matching `docs/canonical-query-set.md`
  verbatim, in wording or order.
- **`StructuredCanonicalQueryIT`** — the eight queries of `docs/canonical-query-set.md`, each scored on the
  **resulting customer set**: the returned `Specification` is executed against the seeded database and the
  matching ids are compared with those of a reference predicate. All eight are expected to pass here;
  `02-ai-agent-filter`'s two variants and `04-ai-hybrid-filter` run the identical queries, which is what
  makes the capability matrix a measurement rather than a claim.
- **`PromptRobustnessIT`** — the opposite direction, which the canonical set does not probe: small talk,
  an unrelated question, "show me all customers" and an explicit reset must each leave the grid
  *unfiltered* rather than produce a hallucinated condition, and one German query must filter the same as
  its English equivalent. Expectations are computed from the seeded data, not hard-coded. None of the five
  needs a filter type, so 02's two variants and 04 run them too and must all pass.
- **`CustomerListViewBrowserlessIT`** — same Browserless setup, but against a real native Ollama
  instance instead of a fake agent bean (it fails rather than skipping if unreachable, like the
  canonical-query IT), exercising the full `TextField` → structured-output AI layer → `Grid`
  pipeline end to end. Since the real model's result size isn't known upfront, the wait condition
  is "the filter field is re-enabled" (it's disabled for the duration of a search) rather than a
  fixed grid size. It runs the same eight queries with the same expectations as
  `StructuredCanonicalQueryIT`, so the only variable between the two is the view layer — and every other
  AI module runs the same eight through its UI as well, which makes the `-Pit-local-ollama` runs directly
  comparable on speed (per-test elapsed time in `target/failsafe-reports/`) and result quality.

All three IT kinds extend a base class from `demo-commons`' test-jar and share the query sets with the
other AI modules, so what a module's ITs contain is one line: which agent or view to ask, and which
queries its filter type can express.


## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerListView.java` — the view: a heading on top of
  the shared `AbstractCustomerSearchView`
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/` — the AI layer (see above)
- `../demo-commons/` — the `Customer`/`Address` JPA model, `data.sql`, `CustomerGrid` and
  `AbstractCustomerSearchView`, shared by all four apps
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
- `../ollama-benchmark/` — standalone benchmark script comparing local Ollama models on this
  module's natural-language-to-filter task
