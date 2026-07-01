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

**Test system:** MacBook Pro, Apple **M2 Pro** (12 cores: 8 performance + 4 efficiency), 32 GB unified
memory, macOS 26.5.1 (build 25F80), Ollama 0.30.11. Apple-Silicon-optimized `mlx` variants were
preferred where available. Run on 2026-06-30.

A run over 22 test cases (5 runs per model; `gpt-5.4-mini` for cloud reference):

```bash
python3 04-local-ai-filter/src/test/scripts/benchmark_models.py --runs 5 \
  qwen3:8b qwen3.5:4b-mlx qwen3.5:9b-mlx qwen3.5:27b-mlx \
  gemma4:e4b-mlx gemma4:12b-mlx gemma4:26b-mlx \
  llama3.1:8b ministral-3:8b phi4-mini:3.8b deepseek-r1:8b \
  --openai gpt-5.4-mini
```

| Model | Accuracy | Median latency | TTFT | Tokens/s | Wall-clock |
| --- | --- | --- | --- | --- | --- |
| `qwen3:8b` | 22/22 | 1008 ms | 271 ms | 31.6 | 129.3 s |
| `qwen3.5:4b-mlx` | 21/22 | 825 ms | **69 ms** | 44.9 | 133.8 s |
| `qwen3.5:9b-mlx` | 22/22 | 2011 ms | 110 ms | 27.1 | 276.3 s |
| `qwen3.5:27b-mlx` | 21/22 | 2995 ms | 921 ms | 9.7 | 522.6 s |
| `gemma4:e4b-mlx` | 18/22 | 943 ms | 108 ms | 42.6 | 126.9 s |
| `gemma4:12b-mlx` | 22/22 | 1708 ms | 468 ms | 20.1 | 295.8 s |
| `gemma4:26b-mlx` | 21/22 | 843 ms | 276 ms | 39.5 | 139.2 s |
| `llama3.1:8b` | 22/22 | 810 ms | 277 ms | 34.5 | 119.2 s |
| `ministral-3:8b` | 20/22 | 1231 ms | 337 ms | 30.9 | 159.4 s |
| `phi4-mini:3.8b` | 18/22 | 850 ms | 238 ms | 55.9 | 126.4 s |
| `deepseek-r1:8b` | 5/22 | 16784 ms | 12090 ms | 30.8 | 2130.7 s |
| `openai/gpt-5.4-mini` | 22/22 (1 err) | 819 ms | 512 ms | 39.0 | 171.7 s |

Takeaways:

- **`llama3.1:8b` is the module's default** — fastest of the models that reach full accuracy (22/22),
  on par with `gpt-5.4-mini`. `qwen3:8b` and `gemma4:12b-mlx` also reach 22/22 but are slower.
- **`qwen3.5:4b-mlx` is the best interactive choice** — near-perfect (21/22) with by far the lowest
  time-to-first-token (69 ms), high throughput and a 4 GB footprint, so it feels instant while typing.
  Its single failure is arguable (it maps "Germany" to `countryCode = DEU`).
- **Bigger is not better here.** `qwen3.5:27b-mlx` scored *lower* (21/22) than the 4B model while being
  the slowest local model by far — no need for a large local model on this task.
- **The cloud model is not needed.** `gpt-5.4-mini` matched, but did not beat, the best local models;
  the feature runs fully local.
- **Local and cloud numbers aren't directly comparable.** The benchmark fires all prompts back-to-back,
  so every local model runs *warm* — its cold-start model load is diluted away. In real single-request
  use a local model is often cold (Ollama unloads it after a timeout) and competes with the app for the
  machine's resources, whereas the cloud model is always warm on dedicated hardware. That is why cloud
  feels faster in practice even though its measured latency here is on par with the fast local models.
- **Reasoning models are unsuitable.** `deepseek-r1:8b` collapsed to 5/22 with a 12 s TTFT and ~35 min
  wall-clock — its thinking traces dominate and the structured output mostly never lands.
- **`phi4-mini:3.8b`** is the throughput leader (55.9 tok/s) but anchors dates to 2023 instead of the
  current date, failing relative-date prompts ("yesterday", "this year").

Results are non-deterministic and hardware-dependent, so treat them as a trend rather than fixed numbers.
