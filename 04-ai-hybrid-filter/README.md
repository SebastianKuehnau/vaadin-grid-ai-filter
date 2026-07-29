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

`docs/extending-tool-calling-with-operators.md` analyzed exactly this change before it existed; this
module is that analysis, built.

## What is copied and what is new

Copied **1:1** from `03-ai-structured-filter` (same records, same Jackson annotations, same enum
values, same translation logic) — there is no shared Maven module in this repository, so each app keeps
its own copy of everything, as `01`/`02`/`03` already do:

- `ai/filter/Condition.java`, `ai/filter/Operator.java`, `ai/filter/CustomerFilter.java`
- `ai/filter/CustomerFilterSpecifications.java` (conditions → JPA `Specification`)
- `ai/CustomerSearchAgent.java`, `ai/TokenUsageRecorder.java`, `data/`, `ui/`, `data.sql`

New, and the only interesting file in the module:

- `ai/CustomerSearchHybridToolCallingService.java` — a `CustomerSearchAgent` that exposes

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
  (and blurring/pressing enter) sends it to the AI layer; a blank query resets to all rows. Identical
  to 03's view — the delivery mechanism is invisible from up here, which is part of the point.
- **`CustomerGrid`** — the `Grid<Customer>` itself (column config, backend sort configuration,
  responsive show/hide).

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
./mvnw -pl 04-ai-hybrid-filter test                                                # unit tests + CustomerListViewBrowserlessTest, no LLM
./mvnw -pl 04-ai-hybrid-filter verify -Pit-local-ollama                            # ITs vs native Ollama (ollama is the default test profile)
./mvnw -pl 04-ai-hybrid-filter verify -Pit-local-ollama -DAI_TEST_PROFILE=openai   # same suite, against the real OpenAI API
```

- **`CustomerFilterSpecificationsTest` / `CustomerFilterSpecificationsExtraTest`** (`@DataJpaTest`, no
  LLM) — the copied translation logic against the seeded H2 data, a 1:1 copy of 03's tests. If 03 and 04
  ever disagree on a query, this proves the cause is the delivery mechanism, not the translation.
- **`CustomerListViewBrowserlessTest`** — [Vaadin Browserless
  testing](https://vaadin.com/docs/latest/flow/testing/browserless) with a fake, deterministic
  `CustomerSearchAgent` bean, so it never calls a real model.
- **`CustomerListViewBrowserlessIT`** — the same setup against a real native Ollama instance,
  exercising `TextField` → tool call → `Grid` end to end, with the same 7 queries as 03's equivalent IT
  for direct comparability.

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchHybridToolCallingService.java` — the one
  file that makes this module different from 03
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/filter/` — the filter type, copied 1:1 from 03
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/` — the view and the grid
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers), byte-identical to `01`/`02`/`03`'s
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
