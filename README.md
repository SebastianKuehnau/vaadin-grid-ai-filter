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

Every row below is one IT test case: a natural-language query written as a string literal in each AI
module's IT class, with the expected customer set computed from the seeded data right next to it.
✅ means the test runs, ❌ means it carries `@Disabled` with the reason the variant's filter type
cannot express that query. The queries themselves are in
[`docs/canonical-query-set.md`](docs/canonical-query-set.md).

| # | Capability | IT test method | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|---|---|
| C1 | single value | `findsCustomersInOneCity` | ✅ | ✅ | ✅ | ✅ |
| C2 | multiple values for one field (OR) | `findsCustomersInEitherOfTwoCities` | ❌ | ❌ | ✅ | ✅ |
| C3 | negation | `findsCustomersOutsideOneCity` | ❌ | ✅ | ✅ | ✅ |
| C4 | non-CONTAINS operator (starts-with) | `findsCustomersWhoseContactNameStartsWithALetter` | ❌ | ✅ | ✅ | ✅ |
| C5 | combined AND across fields | `findsCreditworthyCustomersInOneCity` | ✅ | ✅ | ✅ | ✅ |
| C6 | numeric range | `findsCustomersWithinARevenueRange` | ❌ | ❌ | ✅ | ✅ |
| C7 | relative date | `findsCustomersWithAnOrderInTheLastTwelveMonths` | ❌ | ✅ | ✅ | ✅ |
| C8 | date range | `findsCustomersWhoLastOrderedWithinADateRange` | ❌ | ❌ | ✅ | ✅ |
| C9 | single value on a second address field | `findsCustomersInOneCountry` | ✅ | ✅ | ✅ | ✅ |
| C10 | numeric upper bound | `findsCustomersUpToARevenueLimit` | ❌ | ✅ | ✅ | ✅ |
| C11 | exact day, German date format | `findsCustomersWhoLastOrderedOnAGermanFormattedDate` | ✅ | ✅ | ✅ | ✅ |
| C12 | rating stated as a negation | `findsCustomersWhoAreNotCreditworthy` | ✅ | ✅ | ✅ | ✅ |
| | **Capabilities reached** | | **5 / 12** | **9 / 12** | **12 / 12** | **12 / 12** |

C1–C8 run twice per variant — once through the AI service (`*CustomerSearchIT`) and once through the
UI (`*BrowserlessIT`). C9–C12 run through the service only.

❌ means *architecturally impossible*, not *unreliable*: no prompt and no model can make a filter type
carry a value it has no slot for.

### The robustness set

The same IT classes also run input that exercises no new capability — phrasing, spelling, language,
and one hostile query. None of it depends on the filter type, so all four variants are expected to
pass all of it; these run in the service-level `*CustomerSearchIT` only.

| # | Input | IT test method | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|---|---|
| R1 | small talk | `ignoresSmallTalk` | ✅ | ✅ | ✅ | ✅ |
| R2 | an unrelated question | `ignoresAnUnrelatedQuestion` | ✅ | ✅ | ✅ | ✅ |
| R3 | "show me all customers" | `showsEveryCustomerWhenAskedForAll` | ✅ | ✅ | ✅ | ✅ |
| R4 | asking for the filter to be reset | `showsEveryCustomerWhenTheFilterIsReset` | ✅ | ✅ | ✅ | ✅ |
| R5 | C1 asked in German | `understandsAGermanQuery` | ✅ | ✅ | ✅ | ✅ |
| R6 | C1 in all caps | `understandsAnAllUppercaseQuery` | ✅ | ✅ | ✅ | ✅ |
| R7 | C1 with polite filler words | `understandsAPoliteQueryWithFillerWords` | ✅ | ✅ | ✅ | ✅ |
| R8 | a prompt injection that tells the model to clear the filter | `keepsTheFilterWhenTheQueryContainsAnInjection` | ⏸ | ⏸ | ⏸ | ⏸ |
| R9 | the empty string | `showsEveryCustomerForAnEmptyQuery` | ✅ | ✅ | ✅ | ✅ |
| R10 | a single blank | `showsEveryCustomerForABlankQuery` | ✅ | ✅ | ✅ | ✅ |

⏸ is `@Disabled("not supported yet")`: **R8 fails in all four variants** — the model follows the
injected instruction and clears the filter. That is a reliability finding and an open task, not a
limit of any filter type.

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
