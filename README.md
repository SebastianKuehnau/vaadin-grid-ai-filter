# Use AI to filter a Vaadin Grid with natural language

A tutorial repository that shows how to filter a Vaadin `Grid` of `Customer` records, building up from
a plain text filter to natural-language filtering driven by an LLM.

It is a **Maven multi-module reactor**: a root parent POM aggregates four self-contained Spring Boot +
Vaadin applications. They share the same `Customer`/`Address` data model and the
`dev.demo.vaadin.aigridfilter` package, and are meant to be read and run in order. Each module runs on
its own port, so several can run at the same time.

## Stack

- **Java 25**, **Spring Boot 4.1.0**
- **Vaadin 25.2.0** (Flow — server-side Java UI, Aura theme)
- **Spring AI 2.0.0** (modules 3 and 4 only)
- **Spring Data JPA** + **H2** in-memory database, seeded from `data.sql` on startup

## Modules

| Module | Port | What it shows |
| --- | --- | --- |
| `01-simple-filter` | 8081 | A simple grid with an **in-memory data provider**, filtered with plain Java (a `Stream` over all rows). |
| `02-lazy-filter` | 8082 | A **lazy-loading grid** with a per-column filter form. The filter state is turned into a JPA `Specification`, so filtering and paging happen as SQL queries in the database. |
| `03-ai-filter` | 8083 | A first take on **natural-language filtering using AI tool calling**. The LLM calls a `@Tool` method and passes the filter values; the query is built from those values. |
| `04-local-ai-filter` | 8084 | Filtering with a **local LLM**, where the AI generates the filter as **structured output**. A side challenge here is finding a suitable local model (via a benchmark) and testing the model's capabilities. |

- **`01-simple-filter`** — One `TextField` over the whole grid. `CustomerListView` loads all customers
  into memory and filters one paramter with a Java `Stream`. The simplest possible approach; not lazy.
- **`02-lazy-filter`** — Per-column filter fields in the grid header row. A lazy data view builds a JPA
  `Specification` from the multi field filter form, so the work is pushed to the database instead of memory. No AI.
- **`03-ai-filter`** — A single natural-language `TextField`. The LLM parses the request and calls a
  `@Tool`-annotated `searchCustomers(...)` method (one parameter per field); the tool builds the
  `Specification` and updates the grid. First step towards filtering data with natural language.
- **`04-local-ai-filter`** — The same natural-language idea, but the model returns a single, flat
  `CustomerFilter` object as **structured output** (instead of calling a tool), which Java translates
  into a `Specification`. This is more reliable for smaller, local models. The module is layered
  (`ui` / `ai` / `data`) so the AI layer is testable in isolation, and it ships a benchmark script and
  integration tests to pick and validate a local model. See `COMPARISON.md` for the tool-calling (03)
  vs. structured-output (04) write-up.

In every module the LLM only produces filter *intent* (`field` / `operator` / `value`); it never sees
the customer data and never writes the final query — Java turns the intent into a `Specification` and
the database executes it.

## Running

Use the root Maven wrapper (`./mvnw`) from the repository root. Modules have no inter-dependencies, so
`-pl` alone is enough to run one.

```bash
./mvnw -pl 01-simple-filter   spring-boot:run   # http://localhost:8081
./mvnw -pl 02-lazy-filter     spring-boot:run   # http://localhost:8082
./mvnw -pl 03-ai-filter       spring-boot:run   # http://localhost:8083
./mvnw -pl 04-local-ai-filter spring-boot:run   # http://localhost:8084
```

Each application opens a browser automatically and serves its UI at the root URL of its port. To build
the whole reactor at once:

```bash
./mvnw clean package
```

## Configuration

- **`01-` / `02-`** need no configuration — they do not call a model.
- **`03-ai-filter`** uses the **OpenAI** chat model and needs an API key. Set the `OPENAI_API_KEY`
  environment variable before running.
- **`04-local-ai-filter`** is configured for a **local Ollama** by default. Start Ollama and pull the
  model first:
  ```bash
  ollama pull llama3.1:8b
  ```
  It has both the OpenAI and Ollama starters on the classpath; switch providers via `spring.ai.model.chat`
  in its `application.properties` (uncomment the OpenAI block, comment the Ollama one).

## Tests (module 4)

```bash
./mvnw -pl 04-local-ai-filter test -Dtest=CustomerFilterSpecificationsTest      # fast unit test (no Docker)
./mvnw -pl 04-local-ai-filter test -Dtest=CustomerSearchServiceLocalOllamaIT    # AI test vs native Ollama (skips if unreachable)
./mvnw -pl 04-local-ai-filter test -Dtest=CustomerSearchServiceTestContainerIT  # AI test vs Ollama in Docker (needs a pre-built image)
```

### Model benchmark

To compare local models for accuracy and speed, run the benchmark script against a native Ollama
(`--openai <model>` adds a cloud model for reference):

```bash
python3 04-local-ai-filter/src/test/scripts/benchmark_models.py
```

A sample run over 22 test cases (Apple Silicon, native Ollama; `gpt-5.4-mini` for reference):

| Model | Accuracy | Median latency | TTFT | Tokens/s | Wall-clock |
| --- | --- | --- | --- | --- | --- |
| `llama3.1:8b` | 22/22 | 875 ms | 284 ms | 32.9 | 81.2 s |
| `qwen3:8b` | 22/22 | 1168 ms | 284 ms | 27.7 | 95.5 s |
| `gemma4:12b-mlx` | 22/22 | 1954 ms | 523 ms | 19.3 | 202.5 s |
| `qwen3.5:4b` | 22/22 | 2618 ms | 1388 ms | 38.9 | 222.6 s |
| `qwen3.5:4b-mlx` | 21/22 | 1007 ms | 87 ms | 37.6 | 100.9 s |
| `gemma4:e4b-mlx` | 18/22 | 1146 ms | 125 ms | 35.7 | 106.1 s |
| `openai/gpt-5.4-mini` | 22/22 | 902 ms | 554 ms | 33.6 | 81.7 s |

`llama3.1:8b` is the module's default: it reaches full accuracy at the lowest latency and wall-clock of
the local models tested, on par with `gpt-5.4-mini`. Results are non-deterministic and hardware-dependent,
so treat them as a trend rather than fixed numbers.
