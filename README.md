# Use AI to filter a Vaadin Grid with natural language

A tutorial repository that shows how to filter a Vaadin `Grid` of `Customer` records, building up from
a plain text filter to natural-language filtering driven by an LLM.

It is a **Maven multi-module reactor**: a root parent POM aggregates four self-contained Spring Boot +
Vaadin applications. They share the same `Customer`/`Address` data model and the
`dev.demo.vaadin.aigridfilter` package, and are meant to be read and run in order. Each module runs on
its own port, so several can run at the same time. The reactor also builds `canonical-query-testkit`,
a small test-only module the AI modules share. A further, non-Maven directory, `ollama-benchmark`,
holds a standalone script for benchmarking local Ollama models.

## The escalation ladder

The point of the repository is not one AI implementation but a **ladder of five steps**, each one more
expressive than the last, ending in a comparison that answers a question the middle steps raise:

| Step | Where | Filter type | Delivery | What it adds |
| --- | --- | --- | --- | --- |
| 1 | `01-non-ai-filter` | per-column filter fields | — | the non-AI baseline |
| 2 | `02-ai-agent-filter` · **02(a)** | one scalar value per field | tool call, 13 parameters | natural language at all |
| 3 | `02-ai-agent-filter` · **02(b)** | one value **+ operator + negate** per field | tool call, **39** parameters | negation, operator precision, day-level dates |
| 4 | `03-ai-structured-filter` | `CustomerFilter` = `List<Condition>` | structured output | multi-value OR, ranges |
| 5 | `04-ai-hybrid-filter` | **the same** `List<Condition>` | tool call, **1** parameter | nothing — and that is the finding |

Steps 2 and 3 show what per-field tool parameters can and cannot do: 02(b) triples the parameter count
and still cannot express "Berlin **or** Hamburg" or "revenue **between** X and Y", because both need two
values or two bounds for one field. Step 4 changes the filter *type* and gets both. Step 5 keeps step 4's
type but goes back to step 3's *delivery mechanism* — and loses nothing. So:

> **Expressiveness lives in the filter type, not in the delivery mechanism.**

[`docs/extending-tool-calling-with-operators.md`](docs/extending-tool-calling-with-operators.md) walks
that argument through in detail; it used to be a design note about a change nobody had made, and
`04-ai-hybrid-filter` is that change, built.

## Stack

- **Java 25**, **Spring Boot 4.1.0**
- **Vaadin 25.2.0** (Flow — server-side Java UI, Aura theme)
- **Spring AI 2.0.0** (modules 2, 3 and 4)
- **Spring Data JPA** + **H2** in-memory database, seeded from `data.sql` on startup
- **Vaadin Browserless Testing** (`browserless-test-spring`, all four modules) — drives real Vaadin
  views and Grid interactions without a browser or servlet container

## Modules

| Module | Port | What it shows |
| --- | --- | --- |
| `01-non-ai-filter` | 8081 | Two non-AI baseline views: an **in-memory data provider** filtered with plain Java (a `Stream` over all rows), and a **lazy-loading grid** with a per-column filter form whose state is turned into a JPA `Specification`, so filtering and paging happen as SQL queries in the database. |
| `02-ai-agent-filter` | 8082 | **Natural-language filtering via AI tool calling**, in two variants behind two routes of one running app: 02(a) with one scalar value per field, 02(b) with a value, an operator and a negate flag per field. |
| `03-ai-structured-filter` | 8083 | Filtering with a **local LLM**, where the AI returns the filter as **structured output** — one `CustomerFilter` holding a flat list of conditions. A side challenge here is finding a suitable local model — see `ollama-benchmark`. |
| `04-ai-hybrid-filter` | 8084 | **Tool calling with 03's filter type**: the model calls `@Tool searchCustomers(List<Condition>)`, i.e. the same payload 03 returns. The step that separates capability from delivery. |

- **`01-non-ai-filter`** — The non-AI baseline, as two views. `InMemoryCustomerListView` (route `/`,
  alias `/in-memory`) loads all customers into memory and filters with a single `TextField` via a Java
  `Stream`; the simplest possible approach, not lazy. `LazyCustomerListView` (route `/lazy`) has
  per-column filter fields in the grid header row, and a lazy data view builds a JPA `Specification`
  from them, so the work is pushed to the database instead of memory. No AI in either view.
- **`02-ai-agent-filter`** — A single natural-language `TextField`, and two AI layers behind it. The LLM
  parses the request and calls a `@Tool`-annotated `searchCustomers(...)` method; the tool's arguments
  build the `Specification`. Variant 02(a) (route `/`) passes one scalar value per field; variant 02(b)
  (route `/operator`) passes a value, an `Operator` and a `negate` flag per field — 39 parameters, and
  still no way to say "Berlin or Hamburg". Both variants live in the same running app, so a talk can
  switch between them with one click. See `02-ai-agent-filter/README.md`.
- **`03-ai-structured-filter`** — The same natural-language idea, but the model returns a single
  `CustomerFilter` object as **structured output** (instead of calling a tool), which Java translates
  into a `Specification`. This is more reliable for smaller, local models. The `CustomerFilter` is a
  flat list of conditions — each with a field, operator, values, and a `negate` flag — deliberately
  not a recursive AND/OR/NOT tree; that trade-off keeps the shape easy for a small model to produce
  while still expressing negation, per-field operators, multi-value OR and ranges. See
  `03-ai-structured-filter/README.md` for the flat filter schema and the Ollama integration test
  architecture.
- **`04-ai-hybrid-filter`** — 03's filter type, copied 1:1, delivered as a tool call:
  `@Tool searchCustomers(List<Condition> conditions)`. Spring AI derives that tool's parameter schema
  from the very same Jackson annotations that drive 03's response format, so the model sees the same
  vocabulary either way. Since 04 can express everything 03 can, while 02(a)/02(b) cannot, the ladder
  ends with a conclusion rather than a preference. See `04-ai-hybrid-filter/README.md`.
- **`canonical-query-testkit`** — The one shared module: the canonical query set, the customer sets
  each query must produce, and the assert/log step all four canonical-query ITs run. Everything else
  in this repository stays duplicated per module on purpose; this is test infrastructure, where five
  copies of eight query strings were five chances to drift. See `canonical-query-testkit/README.md`.
- **`ollama-benchmark`** — Not a Maven module: a standalone, dependency-free script that compares
  local Ollama models on the natural-language-to-filter task, for all four AI approaches, using the
  same queries the modules' integration tests use. See `ollama-benchmark/README.md`.

In every AI module the LLM only produces filter *intent*; it never sees the customer data and never
writes the final query — Java turns the intent into a `Specification` and the database executes it.

## Documentation

- [`docs/canonical-query-set.md`](docs/canonical-query-set.md) — the eight natural-language queries all
  four AI modules and the benchmark are measured with, and the customer set each must produce. The single
  source of truth; a per-module test fails the build if any copy drifts from it.
- [`docs/capability-matrix.md`](docs/capability-matrix.md) — which query types each approach can express
  and how reliably, evidence-linked to test methods.
- [`docs/tool-calling-vs-structured-output.md`](docs/tool-calling-vs-structured-output.md) — the
  pros/cons comparison with measured token cost, latency and reliability.
- [`docs/extending-tool-calling-with-operators.md`](docs/extending-tool-calling-with-operators.md) — how
  far per-field tool parameters get you, and why module 04 exists.

## Running

Use the root Maven wrapper (`./mvnw`) from the repository root. Modules have no inter-dependencies, so
`-pl` alone is enough to run one.

```bash
./mvnw -pl 01-non-ai-filter        spring-boot:run   # http://localhost:8081 (/ or /in-memory, and /lazy)
./mvnw -pl 02-ai-agent-filter      spring-boot:run   # http://localhost:8082 (/ or /flat, and /operator)
./mvnw -pl 03-ai-structured-filter spring-boot:run   # http://localhost:8083
./mvnw -pl 04-ai-hybrid-filter     spring-boot:run   # http://localhost:8084
```

Each application opens a browser automatically and serves its UI at the root URL of its port. To build
the whole reactor at once:

```bash
./mvnw clean package
```

## Configuration

- **`01-non-ai-filter`** needs no configuration — it does not call a model.
- **`02-ai-agent-filter`**, **`03-ai-structured-filter`** and **`04-ai-hybrid-filter`** each talk to a
  single Spring AI `ChatModel` bean and pick a backend purely via Spring profile (`openai` / `ollama`,
  each an `application-<profile>.properties` file) — never a code change. Default (no profile) is the
  real **OpenAI API** (needs `OPENAI_API_KEY`); `ollama` targets a local Ollama instance via Spring AI's
  native Ollama binding:
  ```bash
  ollama pull qwen3:8b
  ```
  See any AI module's README for the full switching commands and trade-offs.

## Tests

Every module builds and tests without an LLM; the Ollama-backed integration tests are behind the
`it-local-ollama` profile.

### 01-non-ai-filter

Both views are covered by BrowserlessTests — no browser or servlet container needed, see
[Stack](#stack) above:

```bash
./mvnw -pl 01-non-ai-filter test   # InMemoryCustomerListViewBrowserlessTest + LazyCustomerListViewBrowserlessTest
```

### AI modules

```bash
./mvnw -pl 02-ai-agent-filter      test                       # unit + browserless view tests, no LLM
./mvnw -pl 02-ai-agent-filter      verify -Pit-local-ollama    # both variants vs native Ollama (fails if unreachable — no probe)
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama
./mvnw -pl 04-ai-hybrid-filter     verify -Pit-local-ollama
```

Each AI module has a **canonical-query IT** that runs the eight queries of
[`docs/canonical-query-set.md`](docs/canonical-query-set.md) and scores each one on the resulting
customer set — including the queries a variant cannot express, which are asserted as documented,
non-erroring failures. See each module's README for its remaining test classes, and
`02-ai-agent-filter/README.md` for a known model-capability limitation around relative-date queries.

### ollama-benchmark

Not part of the Maven reactor — see `ollama-benchmark/README.md` for how to run
`BenchmarkLocalModels.java` across all four AI approaches and the recorded comparison of local models.
