# Use AI to filter a Vaadin Grid with natural language

A tutorial repository that shows how to filter a Vaadin `Grid` of `Customer` records, building up from
a plain text filter to natural-language filtering driven by an LLM.

It is a **Maven multi-module reactor**: a root parent POM aggregates three self-contained Spring Boot +
Vaadin applications. They share the same `Customer`/`Address` data model and the
`dev.demo.vaadin.aigridfilter` package, and are meant to be read and run in order. Each module runs on
its own port, so several can run at the same time. A fourth, non-Maven directory,
`04-ollama-benchmark`, holds a standalone script for benchmarking local Ollama models.

## Stack

- **Java 25**, **Spring Boot 4.1.0**
- **Vaadin 25.2.0** (Flow — server-side Java UI, Aura theme)
- **Spring AI 2.0.0** (modules 2 and 3 only)
- **Spring Data JPA** + **H2** in-memory database, seeded from `data.sql` on startup
- **Vaadin Browserless Testing** (`browserless-test-spring`, modules 1, 2 and 3) — drives real
  Vaadin views and Grid interactions without a browser or servlet container

## Modules

| Module | Port | What it shows |
| --- | --- | --- |
| `01-non-ai-filter` | 8081 | Two non-AI baseline views: an **in-memory data provider** filtered with plain Java (a `Stream` over all rows), and a **lazy-loading grid** with a per-column filter form whose state is turned into a JPA `Specification`, so filtering and paging happen as SQL queries in the database. |
| `02-ai-agent-filter` | 8082 | A first take on **natural-language filtering using AI tool calling**. The LLM calls a `@Tool` method and passes the filter values; the query is built from those values. |
| `03-ai-structured-filter` | 8083 | Filtering with a **local LLM**, where the AI generates the filter as **structured output**. A side challenge here is finding a suitable local model — see `04-ollama-benchmark`. |

- **`01-non-ai-filter`** — The non-AI baseline, as two views. `InMemoryCustomerListView` (route `/`,
  alias `/in-memory`) loads all customers into memory and filters with a single `TextField` via a Java
  `Stream`; the simplest possible approach, not lazy. `LazyCustomerListView` (route `/lazy`) has
  per-column filter fields in the grid header row, and a lazy data view builds a JPA `Specification`
  from them, so the work is pushed to the database instead of memory. No AI in either view.
- **`02-ai-agent-filter`** — A single natural-language `TextField`. The LLM parses the request and calls a
  `@Tool`-annotated `searchCustomers(...)` method (one parameter per field); the tool builds the
  `Specification` and updates the grid. First step towards filtering data with natural language. See
  `02-ai-agent-filter/README.md` for details.
- **`03-ai-structured-filter`** — The same natural-language idea, but the model returns a single
  `CustomerFilter` object as **structured output** (instead of calling a tool), which Java translates
  into a `Specification`. This is more reliable for smaller, local models. The `CustomerFilter` is a
  flat list of conditions — each with a field, operator, values, and a `negate` flag — deliberately
  not a recursive AND/OR/NOT tree; that trade-off keeps the shape easy for a small model to produce
  while still expressing negation and per-field operators. The module is layered (`ui` / `ai` / `data`)
  so the AI layer is testable in isolation. See `03-ai-structured-filter/README.md` for the flat
  filter schema and the Ollama integration test architecture.
- **`04-ollama-benchmark`** — Not a Maven module: a standalone, dependency-free script that compares
  local Ollama models on `03-ai-structured-filter`'s natural-language-to-filter task. See
  `04-ollama-benchmark/README.md`.

In every AI module the LLM only produces filter *intent*; it never sees the customer data and never
writes the final query — Java turns the intent into a `Specification` and the database executes it.

## Running

Use the root Maven wrapper (`./mvnw`) from the repository root. Modules have no inter-dependencies, so
`-pl` alone is enough to run one.

```bash
./mvnw -pl 01-non-ai-filter        spring-boot:run   # http://localhost:8081 (/ or /in-memory, and /lazy)
./mvnw -pl 02-ai-agent-filter      spring-boot:run   # http://localhost:8082
./mvnw -pl 03-ai-structured-filter spring-boot:run   # http://localhost:8083
```

Each application opens a browser automatically and serves its UI at the root URL of its port. To build
the whole reactor at once:

```bash
./mvnw clean package
```

## Configuration

- **`01-non-ai-filter`** needs no configuration — it does not call a model.
- **`02-ai-agent-filter`** and **`03-ai-structured-filter`** both talk to a single Spring AI
  `ChatModel` bean and pick a backend purely via Spring profile (`openai` / `ollama`, each an
  `application-<profile>.properties` file) — never a code change. Default (no profile) is the real
  **OpenAI API** (needs `OPENAI_API_KEY`); `ollama` targets a local Ollama instance via Spring AI's
  native Ollama binding:
  ```bash
  ollama pull qwen3:8b
  ```
  See either module's README for the full switching commands and trade-offs.

## Tests

### 01-non-ai-filter

Both views are covered by BrowserlessTests — no browser or servlet container needed, see
[Stack](#stack) above:

```bash
./mvnw -pl 01-non-ai-filter test   # InMemoryCustomerListViewBrowserlessTest + LazyCustomerListViewBrowserlessTest
```

### 02-ai-agent-filter

```bash
./mvnw -pl 02-ai-agent-filter test                        # unit tests + CustomerListViewBrowserlessTest (no LLM)
./mvnw -pl 02-ai-agent-filter verify -Pit-local-ollama     # CustomerSearchAgentIT vs native Ollama (fails if Ollama unreachable — no probe)
```

See `02-ai-agent-filter/README.md` for details, including a known model-capability limitation
around relative-date queries.

### 03-ai-structured-filter

```bash
./mvnw -pl 03-ai-structured-filter test                        # unit tests + CustomerListViewBrowserlessTest (no LLM)
./mvnw -pl 03-ai-structured-filter verify -Pit-local-ollama     # CustomerSearchAgentIT + CustomerListViewBrowserlessIT vs native Ollama (fails if Ollama unreachable — no probe)
```

See `03-ai-structured-filter/README.md` for details, including the flat filter schema and the
Ollama integration test architecture.

### 04-ollama-benchmark

Not part of the Maven reactor — see `04-ollama-benchmark/README.md` for how to run
`BenchmarkLocalModels.java` and the recorded comparison of local models.
