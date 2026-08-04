# 04-ai-hybrid-filter

Natural-language filtering of a Vaadin `Grid` of `Customer` records via **AI tool calling with the
structured-output filter type**: the LLM *calls* a tool, and that tool's single parameter is the very
same `List<Condition>` that `03-ai-structured-filter` receives as a returned JSON object.

This is the last step of the tutorial's escalation ladder, and the one that resolves it:

| Step | Module | Filter type | Delivery |
|---|---|---|---|
| 1 | `01-non-ai-filter` | per-column filter fields | — (no AI) |
| 2 | `02-ai-agent-filter` 02(a) | one scalar value per field | tool call |
| 3 | `02-ai-agent-filter` 02(b) | value + operator + negate per field | tool call |
| 4 | `03-ai-structured-filter` | `CustomerFilter` = `List<Condition>` | structured output |
| **5** | **`04-ai-hybrid-filter`** | **`CustomerFilter` = `List<Condition>`** (identical to 03) | **tool call** |

Modules 03 and 04 share the filter type; modules 02 and 04 share the delivery mechanism. Since 04 can
express everything 03 can — multi-value OR, negation, operator precision, real ranges — while 02(a) and
02(b) cannot, the conclusion is hard to argue with:

> **Expressiveness lives in the filter *type*, not in the delivery *mechanism*.**

This module started life as a design note. The since-removed `docs/extending-tool-calling-with-operators.md`
(retrievable from the git history) asked what
`02-ai-agent-filter` would need in order to express negation and operator precision the way
`03-ai-structured-filter` does, answered "swap the flat per-field criteria for a condition list and keep
the tool call as the delivery mechanism", and closed with "this is analysis only — no such change is
made". This module *is* that change: `Condition`, `Operator`, `CustomerFilter` and
`CustomerFilterSpecifications` copied 1:1 out of module 03, behind one
`@Tool searchCustomers(List<Condition> conditions)`. Module 02 went a deliberately smaller way instead —
variant 02(b), which keeps per-field parameters and merely adds an operator and a negate flag to each. So
the repository now holds *both* answers to the note's question, and they disagree about how far you get,
which is the finding above. The note itself has been retired now that the module answers it; see the git
history for the original analysis.

## What is copied and what is new

Copied **1:1** from `03-ai-structured-filter` (same records, same Jackson annotations, same enum
values, same translation logic) — deliberately copied rather than shared, because the filter type *is*
what this module demonstrates about 03, and sharing it would hide the claim being made:

- `ai/filter/Condition.java` (with its nested `Operator`), `ai/filter/CustomerFilter.java`
- `ai/filter/CustomerFilterSpecifications.java` (conditions → JPA `Specification`)

What this module does *not* copy is the scaffolding around it: the domain model, `data.sql`, the grid, the
search view, the `CustomerSearchAgent` seam and the token measurement all come from `00-commons`, which
all four apps depend on. None of those says anything about tool calling versus structured output.

New, and the only interesting file in the module:

- `ai/CustomerSearchService.java` — a `CustomerSearchAgent` that exposes

  ```java
  @Tool void searchCustomers(List<Condition> conditions)
  ```

  and, like 03, bakes "today" into `systemPrompt(LocalDate)` instead of using a live-clock tool. Its
  prompt is 03's prompt with one difference: it says *call the searchCustomers tool* where 03 says
  *return a CustomerFilter*.

Deliberately **not** done: one tool parameter per field. That would be a middle ground between 02(b)
and this module and would blur the very point — expressiveness comes from `Condition`'s shape (a
`values` list for OR, sibling conditions for ranges), not from the number of parameters.

## How OR and ranges work here

Exactly as in 03, because it is the same type:

- **OR within a field** — one condition with several values:
  `{ "field": "city", "operator": "EQUALS", "values": ["Berlin", "Köln"], "negate": false }`
- **A real range** — two sibling conditions on the same field, AND-combined like everything else:
  `annualRevenue GREATER_OR_EQUAL [100000]` **+** `annualRevenue LESS_OR_EQUAL [500000]`
- **Negation** — `negate: true` on the condition, applied as `cb.not(...)`.

Date bounds are genuine day-level comparisons (`CustomerFilterSpecifications.datePredicate`), so
"last ordered in 2024" is the closed range `>= 2024-01-01` AND `<= 2024-12-31` — not 02(a)'s
whole-calendar-year match.

Spring AI derives the tool's parameter schema from `Condition`'s `@JsonClassDescription` /
`@JsonPropertyDescription` annotations, i.e. from the same annotations that drive 03's response-format
schema. Verified before this module was built: the generated tool schema contains the nested condition
object, the enumerated `Operator` values, the `values` array and every description, and a local
`qwen3:8b` fills it correctly for a combined OR + range query.

## View

- **`/`** — `CustomerListView`: a single natural-language `TextField` above the grid. Typing a query
  (and blurring/pressing enter) sends it to the AI layer; a blank query resets to all rows. Apart from its
  heading it is identical to 03's view, and both are now just a heading on top of the shared
  `AbstractCustomerSearchView` — the delivery mechanism is invisible from up here, which is part of the
  point.
- **`CustomerGrid`** — the `Grid<Customer>` itself (column config, backend sort configuration, and
  responsive show/hide). It lives in **`00-commons`**: all four apps show the same grid, and it says
  nothing about how a filter came into being. Unlike `01-non-ai-filter`, this module needs no per-column
  filter fields — filtering is the one AI `TextField` above — and it keeps the shared grid's backend sort
  configuration unchanged.

`CustomerSearchAgent.resolveFilter(...)` never throws: on any failure (bad model response, unreachable
model, ...) it falls back to an unrestricted specification, so the UI never breaks.

The service is `@Scope("prototype")`, like `02-ai-agent-filter`'s: the tool call writes its result into
a field on the bean, and Vaadin creates a fresh view instance per navigation, so each view gets its own
service instance and different tabs/sessions never share one.

## Running

```bash
./mvnw -pl 04-ai-hybrid-filter spring-boot:run   # http://localhost:8084
```

### Switching LLM backends

Same mechanism as modules 02 and 03 — the AI layer only ever talks to a generic Spring AI `ChatModel`
bean, and the backend is chosen by Spring profile (`application-<profile>.properties`), never in code:

```bash
./mvnw -pl 04-ai-hybrid-filter spring-boot:run                                     # openai (default, no profile needed)
./mvnw -pl 04-ai-hybrid-filter spring-boot:run -Dspring-boot.run.profiles=ollama   # local Ollama
```

- **`openai`** (default) — the real OpenAI API, requires `OPENAI_API_KEY`.
- **`ollama`** — a local Ollama instance via Spring AI's *native* Ollama binding (not the
  OpenAI-compatible endpoint, so `think`/`num_ctx` are actually honored):
  ```bash
  ollama pull qwen3:8b
  ```

See `02-ai-agent-filter/README.md` for the full rationale behind the two starters and the
`think=false` / `num-ctx` settings, which this module inherits verbatim.

## Tests

```bash
./mvnw -pl 04-ai-hybrid-filter test                                                # unit tests only, no LLM, no UI
./mvnw -pl 04-ai-hybrid-filter verify -Pit-local-ollama                            # ITs vs native Ollama (ollama is the default test profile)
./mvnw -pl 04-ai-hybrid-filter verify -Pit-local-ollama -DAI_TEST_PROFILE=openai   # same suite, against the real OpenAI API
```

- **`CustomerSearchServiceToolsTest`** (plain JUnit, no Spring, no LLM) — the one tool-calling failure
  mode this repository has observed and mitigated: a `void` tool is answered with a bare "Done", which a
  model can read as "nothing happened" and call again with no arguments. The guard must keep what the
  first call extracted. Nothing else about the tool is tested — asserting that arguments land in a record
  was plumbing.
- **`HybridCustomerSearchIT`** — both query sets through the service against a real Ollama, one test method each.
  `canonicalQuery` runs the eight queries of `docs/canonical-query-set.md`, each scored on the **resulting
  customer set** (the `Specification` is executed against the seeded database and the matching ids compared
  with a reference predicate). All eight are expected to pass here, exactly as in 03: same filter type, same
  prompt rules, same queries. A divergence between the two modules could therefore only come from the
  delivery mechanism.
  `robustnessQuery` runs the five cases the canonical set does not probe: small talk, an unrelated
  question, "show me all customers" and an explicit reset must each leave the grid *unfiltered*, plus one
  German query. No filter type is involved, so there is no `ExpectedResult` for them and all five must pass,
  exactly as in 02 and 03.
- **`CustomerListViewBrowserlessIT`** — the same eight queries and expectations through the UI against a
  real native Ollama instance, exercising `TextField` → tool call → `Grid` end to end. Identical input to
  `HybridCustomerSearchIT`, so the view layer is the only variable; identical to 03's equivalent IT too,
  so structured output and tool calling stay directly comparable.

> **Models encode this tool argument differently — expect that, don't fear it.** 04 asks the model for a
> nested object array as a tool argument (`searchCustomers(List<Condition>)`), which has to survive one
> more encoding step than 03's structured-output response. In the 5-run benchmark all four tested models
> produce the right conditions; `llama3.1:8b` simply sends them as a JSON-*encoded string* rather than a
> JSON array. Spring AI binds that to `List<Condition>` without complaint — this module's
> `HybridCustomerSearchIT` passes **11 of 13** cases on that model, and since the benchmark harness learned
> to accept the same shape (2026-08-04) it scores **100% of the canonical set** there too, where it used to
> report 0%. The zero was the harness's, never the model's or this module's.
> The lesson worth carrying over into your own code: whatever consumes a tool argument should be as
> tolerant of shape deviations as a structured-output parser is — and should say when it had to be, rather
> than normalizing the deviation away in silence. See
> [`docs/capability-matrix.md` § the `llama3.1:8b` 04 column](../docs/capability-matrix.md#reliability-across-models).

All three IT kinds extend a base class from `00-commons`' test-jar and share the query sets with the
other AI modules, so what a module's ITs contain is one line: which agent or view to ask, and which
queries its filter type can express.


## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchService.java` — the one
  file that makes this module different from 03
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/filter/` — the filter type, copied 1:1 from 03
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerListView.java` — the view: a heading on top of
  the shared `AbstractCustomerSearchView`, and otherwise identical to 03's
- `../00-commons/` — the `Customer`/`Address` JPA model, `data.sql`, `CustomerGrid` and
  `AbstractCustomerSearchView`, shared by all four apps
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
