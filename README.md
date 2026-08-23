# Use AI to filter a Vaadin Grid with natural language

Filter a Vaadin `Grid` of `Customer` records, building up from a plain text filter to
natural-language filtering driven by an LLM. Four Spring Boot + Vaadin apps, meant to be read and run
in order; each on its own port, so several can run at the same time.

## The escalation ladder

| Step | Where | Filter type | Delivery | What it adds |
| --- | --- | --- | --- | --- |
| 1 | `01-non-ai-filter` | per-column filter fields | — | the non-AI baseline |
| 2 | `02-ai-agent-filter` · **02(a)** | one scalar value per field | tool call, 13 parameters | natural language at all |
| 3 | `02-ai-agent-filter` · **02(b)** | one value **+ operator + negate** per field | tool call, **39** parameters | negation, operator precision, day-level dates |
| 4 | `03-ai-structured-filter` | `CustomerFilter` = `List<Condition>` | structured output | multi-value OR, ranges |
| 5 | `04-ai-hybrid-filter` | **the same** `List<Condition>` | tool call, **1** parameter | nothing — and that is the finding |

02(b) triples the parameter count and still cannot express "Berlin **or** Hamburg" or "revenue
**between** X and Y", because both need two values for one field. Step 4 changes the filter *type* and
gets both. Step 5 keeps that type but goes back to step 3's *delivery mechanism* — and loses nothing:

> **Expressiveness lives in the filter type, not in the delivery mechanism.**

## What each approach can express

| # | Capability | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|---|
| C1 | single value | ✅ | ✅ | ✅ | ✅ |
| C2 | multiple values for one field (OR) | ❌ | ❌ | ✅ | ✅ |
| C3 | negation | ❌ | ✅ | ✅ | ✅ |
| C4 | non-CONTAINS operator (starts-with) | ❌ | ✅ | ✅ | ✅ |
| C5 | combined AND across fields | ✅ | ✅ | ✅ | ✅ |
| C6 | numeric range | ❌ | ❌ | ✅ | ✅ |
| C7 | relative date | ❌ | ✅ | ✅ | ✅ |
| C8 | date range | ❌ | ❌ | ✅ | ✅ |
| | **Capabilities reached** | **2 / 8** | **5 / 8** | **8 / 8** | **8 / 8** |

❌ means *architecturally impossible*, not *unreliable*: no prompt and no model can make a filter type
carry a value it has no slot for. The queries behind these eight rows are in
[`docs/canonical-query-set.md`](docs/canonical-query-set.md).

## Stack

- **Java 25**, **Spring Boot 4.1.0**
- **Vaadin 25.2.4** (Flow — server-side Java UI, Aura theme)
- **Spring AI 2.0.0** — used by modules 2, 3 and 4; on every classpath via `00-commons`
- **Spring Data JPA** + **H2** in-memory database, seeded from `data.sql` on startup
- **Vaadin Browserless Testing** — drives real Vaadin views and Grid interactions without a browser

## Modules

| Module | Port | What it shows |
| --- | --- | --- |
| `01-non-ai-filter` | 8081 | Two non-AI baselines: an in-memory data provider filtered with a Java `Stream`, and a lazy-loading grid whose per-column filter form becomes a JPA `Specification`. |
| `02-ai-agent-filter` | 8082 | Natural-language filtering via **tool calling**, two variants behind two routes of one app: 02(a) one scalar value per field (`/`), 02(b) value + operator + negate per field (`/operator`). |
| `03-ai-structured-filter` | 8083 | The model returns the filter as **structured output** — one `CustomerFilter` holding a flat list of conditions. |
| `04-ai-hybrid-filter` | 8084 | **Tool calling with 03's filter type**: `@Tool searchCustomers(List<Condition>)`. The step that separates capability from delivery. |
| `00-commons` | — | What all four apps share at runtime: the domain layer, `data.sql`, the `CustomerGrid` and search view, the `CustomerSearchAgent` seam and the token measurement. Never an AI service, filter type or prompt — those are what the repository compares. |

In every AI module the LLM only produces filter *intent*; it never sees the customer data and never
writes the final query — Java turns the intent into a `Specification` and the database executes it.

## Running

Every app depends on `00-commons`, so a single-module build needs `-am`. `spring-boot:run` cannot use
`-am` and resolves from `~/.m2`, so run `./mvnw install -DskipTests` once first.

```bash
./mvnw -pl 01-non-ai-filter        spring-boot:run   # http://localhost:8081 (/ or /in-memory, and /lazy)
./mvnw -pl 02-ai-agent-filter      spring-boot:run   # http://localhost:8082 (/ or /flat, and /operator)
./mvnw -pl 03-ai-structured-filter spring-boot:run   # http://localhost:8083
./mvnw -pl 04-ai-hybrid-filter     spring-boot:run   # http://localhost:8084
```

## Configuration

`01-non-ai-filter` needs no configuration. The three AI modules each talk to a single Spring AI
`ChatModel` bean and pick a backend purely via Spring profile — never a code change:

- **`ollama`** (default) — a local Ollama instance at `OLLAMA_BASE_URL`: `ollama pull qwen3:8b`
- **`openai`** — the OpenAI cloud API, needs `OPENAI_API_KEY`

That is what the *app* needs. The tests bring their own Ollama; see below.

## Tests

The ITs need no Ollama installation — only Docker. They start one as a Testcontainer from
`00-commons/src/test/resources/ollama/Dockerfile`, which bakes `qwen3:8b` into the image, and Spring
AI's `@ServiceConnection` points `spring.ai.ollama.base-url` at it. The first run downloads roughly
5 GB; later runs reuse the image layer.

```bash
./mvnw verify                                 # everything, including the Ollama-backed ITs
./mvnw verify -DskipITs                       # everything that needs no model
OLLAMA_TESTCONTAINER=false ./mvnw verify      # against your own Ollama at OLLAMA_BASE_URL
AI_TEST_PROFILE=openai ./mvnw verify          # against the OpenAI API
```

The container is reusable and deliberately outlives the build, so a second run pays no model reload —
one container serves every Spring context, which is also what keeps the ITs inside a laptop's RAM.
Remove it with `docker rm -f $(docker ps -q --filter ancestor=ai-grid-filter/ollama:qwen3-8b)`.
Why a Testcontainer and not a provisioned server: `docs/adr/0002-ollama-as-a-testcontainer.md`.

Each AI module has two IT classes per variant, and both spell out what they do: one `@Test` per
natural-language query, the prompt as a string literal and the expected customer set right next to it.
The `*CustomerSearchIT` asks the AI service directly (prompt → `Specification` → database); the
`*BrowserlessIT` types the same queries into the filter field and reads the grid. Queries a variant's
filter type cannot express are `@Disabled` with the reason.
