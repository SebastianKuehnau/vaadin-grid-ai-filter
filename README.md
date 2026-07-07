# Use AI to filter a Vaadin Grid with natural language

A tutorial repository that shows how to filter a Vaadin `Grid` of `Customer` records, building up from
a plain text filter to natural-language filtering driven by an LLM.

It is a **Maven multi-module reactor**: a root parent POM aggregates three self-contained Spring Boot +
Vaadin applications. They share the same `Customer`/`Address` data model and the
`dev.demo.vaadin.aigridfilter` package, and are meant to be read and run in order. Each module runs on
its own port, so several can run at the same time.

## Stack

- **Java 25**, **Spring Boot 4.1.0**
- **Vaadin 25.2.0** (Flow — server-side Java UI, Aura theme)
- **Spring AI 2.0.0** (modules 2 and 4 only)
- **Spring Data JPA** + **H2** in-memory database, seeded from `data.sql` on startup
- **Vaadin Browserless Testing** (`browserless-test-spring`, module 1 only) — drives real
  Vaadin views and Grid interactions without a browser or servlet container

## Modules

| Module | Port | What it shows |
| --- | --- | --- |
| `01-non-ai-filter` | 8081 | Two non-AI baseline views: an **in-memory data provider** filtered with plain Java (a `Stream` over all rows), and a **lazy-loading grid** with a per-column filter form whose state is turned into a JPA `Specification`, so filtering and paging happen as SQL queries in the database. |
| `02-ai-filter-agent` | 8082 | A first take on **natural-language filtering using AI tool calling**. The LLM calls a `@Tool` method and passes the filter values; the query is built from those values. |
| `04-local-ai-filter` | 8084 | Filtering with a **local LLM**, where the AI generates the filter as **structured output**. A side challenge here is finding a suitable local model (via a benchmark) and testing the model's capabilities. |

- **`01-non-ai-filter`** — The non-AI baseline, as two views. `InMemoryCustomerListView` (route `/`,
  alias `/in-memory`) loads all customers into memory and filters with a single `TextField` via a Java
  `Stream`; the simplest possible approach, not lazy. `LazyCustomerListView` (route `/lazy`) has
  per-column filter fields in the grid header row, and a lazy data view builds a JPA `Specification`
  from them, so the work is pushed to the database instead of memory. No AI in either view.
- **`02-ai-filter-agent`** — A single natural-language `TextField`. The LLM parses the request and calls a
  `@Tool`-annotated `searchCustomers(...)` method (one parameter per field); the tool builds the
  `Specification` and updates the grid. First step towards filtering data with natural language.
- **`04-local-ai-filter`** — The same natural-language idea, but the model returns a single
  `CustomerFilter` object as **structured output** (instead of calling a tool), which Java translates
  into a `Specification`. This is more reliable for smaller, local models. The module is layered
  (`ui` / `ai` / `data`) so the AI layer is testable in isolation, and it ships a benchmark script and
  integration tests to pick and validate a local model.

### 04-local-ai-filter: filter tree structure

`CustomerFilter` wraps a single `root` `FilterNode` — a tree of `AND` / `OR` / `NOT` / `CONDITION`
nodes (see `FilterNode.java`). This lets the LLM express any boolean combination, including
cross-field OR (`city = Berlin OR annualRevenue >= 1000000`), which a flat list of conditions
cannot represent. `CustomerFilterSpecifications` translates the tree into a JPA `Specification`
with a recursive walk (AND → `cb.and`, OR → `cb.or`, NOT → `cb.not`, CONDITION → the per-field
predicate builders). A `null` root, or an `AND`/`OR` with no children, matches every customer.

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
than emit one flat list. Smaller local models are more likely to flatten a nested query incorrectly
(e.g. drop a condition, or misplace it in the wrong branch), especially for cross-field OR and
NOT-negated groups. `CustomerSearchIT` tags its test cases by the nesting complexity they require
(`small-model-query`, `medium-model-query`, `large-model-query`) so the difference shows up per model
in the benchmark below.

### 04-local-ai-filter: Ollama integration test architecture

The Ollama-backed integration tests use a subclassing structure instead of one unrelated test
class per use case:

```
ai/
├── LocalOllamaTests.java     (infrastructure: native Ollama, @SpringBootTest)
└── CustomerSearchIT.java     (test cases, provider-agnostic) extends LocalOllamaTests
```

- `LocalOllamaTests` is the infrastructure class: it declares the `@SpringBootTest` that wires
  Spring AI to a native Ollama instance and skips gracefully (via `assumeTrue`) if Ollama is
  unreachable at `OLLAMA_BASE_URL`.
- `CustomerSearchIT` holds the actual test cases and assertions. It declares no `@SpringBootTest`
  of its own — it inherits the Spring context and reachability check from `LocalOllamaTests`.
- Adding a second use case (e.g. `ProductValidation`) needs one new class, `ProductValidationIT
  extends LocalOllamaTests`, plus adding it to the failsafe `<includes>` of the
  `it-local-ollama` profile — not changes to the infrastructure class itself.
- Run with `-Pit-local-ollama`, or a single suite with `-Dit.test=CustomerSearchIT`.

In every module the LLM only produces filter *intent* (`field` / `operator` / `value`); it never sees
the customer data and never writes the final query — Java turns the intent into a `Specification` and
the database executes it.

## Running

Use the root Maven wrapper (`./mvnw`) from the repository root. Modules have no inter-dependencies, so
`-pl` alone is enough to run one.

```bash
./mvnw -pl 01-non-ai-filter   spring-boot:run   # http://localhost:8081 (/ or /in-memory, and /lazy)
./mvnw -pl 02-ai-filter-agent spring-boot:run   # http://localhost:8082
./mvnw -pl 04-local-ai-filter spring-boot:run   # http://localhost:8084
```

Each application opens a browser automatically and serves its UI at the root URL of its port. To build
the whole reactor at once:

```bash
./mvnw clean package
```

## Configuration

- **`01-non-ai-filter`** needs no configuration — it does not call a model.
- **`02-ai-filter-agent`** is configured for a **local Ollama** by default (same as `04-local-ai-filter`).
  Start Ollama and pull the model first:
  ```bash
  ollama pull llama3.1:8b
  ```
  It has both the OpenAI and Ollama starters on the classpath; switch to OpenAI by setting
  `app.ai.provider=openai` in its `application.properties` (needs the `OPENAI_API_KEY` environment
  variable).
- **`04-local-ai-filter`** is configured for a **local Ollama** by default. Start Ollama and pull the
  model first:
  ```bash
  ollama pull llama3.1:8b
  ```
  It has both the OpenAI and Ollama starters on the classpath; switch providers via `spring.ai.model.chat`
  in its `application.properties` (uncomment the OpenAI block, comment the Ollama one).

## Tests

### 01-non-ai-filter

Both views are covered by BrowserlessTests — no browser or servlet container needed, see
[Stack](#stack) above:

```bash
./mvnw -pl 01-non-ai-filter test   # InMemoryCustomerListViewBrowserlessTest + LazyCustomerListViewBrowserlessTest
```

### 04-local-ai-filter

```bash
./mvnw -pl 04-local-ai-filter test   -Dtest=CustomerFilterSpecificationsTest   # fast unit test (no Ollama)
./mvnw -pl 04-local-ai-filter verify -Pit-local-ollama                        # AI test vs native Ollama (skips if unreachable)
```

#### Model benchmark

`BenchmarkLocalModels.java` compares local Ollama models for accuracy and speed on the same 28
natural-language queries as `CustomerSearchIT`. It is a dependency-free Java single-file program (JDK
stdlib only) run directly with Java's source launcher — no Maven, no JUnit, no Spring context:

```bash
cd 04-local-ai-filter/src/test/scripts
java BenchmarkLocalModels.java                          # auto-discovers tool-capable models from Ollama
java BenchmarkLocalModels.java llama3.1:8b qwen3:8b      # or benchmark specific models
```

It talks to Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434`), so start Ollama and pull
the models to compare first. Results are printed to the console and written as
`benchmark-report-<timestamp>.md`/`.txt` in the current directory.

**Test system:** MacBook Pro, Apple **M2 Pro** (12 cores: 8 performance + 4 efficiency), 32 GB unified
memory, macOS 26.5.1 (build 25F80), Ollama 0.30.11. Apple-Silicon-optimized `mlx` variants were
preferred where available. Run on 2026-07-06 with `BenchmarkLocalModels.java`.

| Model | Accuracy | Median latency | TTFT | Tokens/s | Model size |
| --- | --- | --- | --- | --- | --- |
| `llama3.2:1b` | 12/32 | 1195 ms | 179 ms | 113.9 | 1.2 GB |
| `qwen3.5:9b-mlx` | 26/32 | 3898 ms | 9270 ms | 26.8 | 8.3 GB |
| `qwen3.5:4b-mlx` | 29/32 | 1366 ms | 5164 ms | 45.0 | 3.7 GB |
| `gemma4:e4b` | 29/32 | 1726 ms | 442 ms | 41.2 | 8.9 GB |
| `gemma4:e4b-mlx` | 28/32 | 1664 ms | 3582 ms | 42.5 | 9.0 GB |
| `gemma4:12b-mlx` | 30/32 | 2814 ms | 16431 ms | 20.2 | 6.3 GB |
| `qwen3:8b` | 29/32 | 1796 ms | 291 ms | 30.3 | 4.9 GB |
| `gemma4:26b-mlx` | 31/32 | 1571 ms | 6149 ms | 40.5 | 15.5 GB |
| `llama3.1:8b` (module default) | 27/32 | 1561 ms | **290 ms** | 33.0 | 4.6 GB |

Takeaways:

- **`gemma4:26b-mlx` is the most accurate** (31/32), but at 15.5 GB it's the heaviest model tested.
- **`llama3.1:8b`, the module's configured default, is not the most accurate** (27/32) — `qwen3:8b`,
  `gemma4:e4b`, `qwen3.5:4b-mlx` (all 29/32) and `gemma4:12b-mlx` (30/32) score higher at a similar or
  smaller size; it remains the default mainly for its fast, consistent time-to-first-token (290 ms).
- **`llama3.2:1b` is unsuitable** (12/32) — too small to reliably nest AND/OR/NOT filter trees.
- **High TTFT hurts the "MLX" quantizations** despite otherwise-decent accuracy — `gemma4:12b-mlx`
  (16.4 s) and `qwen3.5:9b-mlx` (9.3 s) feel slow to first response even though their token throughput
  is fine once generation starts.
- Alternative models are available by uncommenting the corresponding line in
  `04-local-ai-filter/src/main/resources/application.properties`.

Results are non-deterministic and hardware-dependent, so treat them as a trend rather than fixed numbers.
