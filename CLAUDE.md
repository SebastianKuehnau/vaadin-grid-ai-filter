# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A tutorial/demo repo showing how to filter a Vaadin `Grid` of `Customer` records, building up from a
plain text filter to LLM-driven natural-language filtering. It is a **Maven multi-module reactor**:
a root parent POM (`ai-grid-filter-parent`, `packaging=pom`) aggregates four modules, each a
self-contained Spring Boot + Vaadin app sharing the same `Customer`/`Address` data model and
`dev.demo.vaadin.aigridfilter` package. Each module has its own Spring Boot entry-point class named
after the module (`SimpleFilterApplication`, `LazyFilterApplication`, `AiFilterApplication`,
`LocalAiFilterApplication`) so it is clear which app is running. They are meant to be read and run
in order:

- **`01-simple-filter/`** (port 8081) — One `TextField` over the whole grid. `CustomerListView` loads
  `customerRepository.findAll()` into memory and filters with a Java `Stream` across all columns.
  Simplest possible approach; not lazy.
- **`02-lazy-filter/`** (port 8082) — Per-column filter fields in the grid header row. Switches to a lazy
  `GridLazyDataView` backed by a JPA `Specification` (`buildCustomerSpecification`) so filtering and
  paging happen in the database, not in memory. No AI.
- **`03-ai-filter/`** (port 8083) — Single natural-language `TextField`. Injects Spring AI's
  `ChatModel`/`ChatClient` and exposes a `@Tool`-annotated `searchCustomers(...)` method with one
  `@ToolParam` per field. The LLM parses the user's request and calls the tool, which builds a
  `Specification` and updates the grid. A `currentLocalDateTime()` `@Tool` lets the model resolve
  relative dates ("last month").
- **`04-local-ai-filter/`** (port 8084) — Same NL-filter idea, but contrasts `03-` on two axes:
  it uses **structured output instead of tool calling**, and it is **split into layers** so each part is
  testable in isolation. Instead of a `@Tool` callback, the model is asked to return a single
  `CustomerFilter` object (`...call().entity(CustomerFilter.class)`). `CustomerFilter` is intentionally
  **flat** — just a list of `FilterCriterion`, with no AND/OR switch. How they combine is fixed and
  resolved in `CustomerFilterSpecifications`: criteria on the **same field** using `CONTAINS`/`EQUALS` are
  OR-ed ("Berlin or Hamburg"); comparisons (`>=`/`<=`) and `NOT_*` are AND-ed (e.g. a value range);
  **different fields** are AND-ed. This is far more reliable for smaller / local LLMs than nested boolean
  logic (trade-off: it cannot express cross-field OR like "in Berlin OR revenue > 1M"). Layers:
  - `ui/CustomerListView` — Vaadin only: builds the grid + filter field, calls the search service, binds
    the result to the grid. No Spring AI, no criteria code. Runs the (blocking) search off the UI thread
    via `CompletableFuture.supplyAsync(...)` and applies the result inside `ui.access(...)`.
  - `ai/CustomerSearchService` — the AI layer: package-private `requestFilter(String)` runs the
    `ChatClient` with structured output and returns a `CustomerFilter` (an empty filter = show-all on any
    unusable response); `resolveFilter(String)` = `CustomerFilterSpecifications.from(requestFilter(...))`
    and returns a `Specification<Customer>`. It never touches the UI, so it is Vaadin-free and testable.
    The system prompt (package-private `systemPrompt(LocalDate)`) carries the field/operator rules, the
    current date (so no date tool is needed) and few-shot examples.
  - `ai/filter/` — the filter model (`CustomerFilter`, `FilterCriterion`, `Operator`; field descriptions via
    Jackson `@JsonPropertyDescription`/`@JsonClassDescription`) and the pure `CustomerFilter` →
    `Specification` translation with the grouping rules above (`CustomerFilterSpecifications.from(filter)`).
    It is the AI layer's internal model (only `CustomerSearchService` uses it), hence nested under `ai/`.
  - `data/` — `CustomerRepository` (backend access).

All four views are mapped to `@Route("")`, so each app's filter UI is served at its own root URL.

Each module runs on its own port and sets a distinct `spring.application.name` and H1 title, so all
four can run at once and it is always clear (console banner, browser URL, on-screen heading) which
stage is live — handy for demos.

## Stack (bleeding-edge versions — verify APIs against docs, don't assume)

- **Java 25** (`java.version` in each `pom.xml`)
- **Spring Boot 4.1.0**
- **Vaadin 25.2.0** (Flow — server-side Java UI; `vaadin-spring-boot-starter`). The Aura theme is
  applied via `@StyleSheet(Aura.STYLESHEET)` and `@Push` is enabled (the AI modules call the model off
  the UI thread and update the grid via `ui.access(...)`; `03-` streams tokens, `04-` calls the model
  synchronously in a background thread).
- **Spring AI 2.0.0**, used only in `03-` and `04-`. `03-` uses the **OpenAI** chat model
  (`spring-ai-starter-model-openai`, `gpt-5.4-mini`). `04-` has **both** the OpenAI and **Ollama**
  starters (`spring-ai-starter-model-ollama`) on the classpath and selects one via `spring.ai.model.chat`;
  it is currently configured for a **local Ollama** model (`llama3.1:8b`), with the OpenAI block available
  by uncommenting (see Configuration).
- **Spring Data JPA** + **H2** in-memory database (auto-created from the entities, seeded by `data.sql`).

Version management (Java 25, Spring AI, Vaadin) and the BOM imports live in the **root POM**; the module
POMs only declare their `artifactId` and dependencies. Modules `01-`–`03-` were scaffolded from one
template and declare the same dependency set (incl. the Spring AI starter and Testcontainers test deps)
even where unused. `04-` additionally pulls in `spring-ai-starter-model-ollama` and actually uses the
Testcontainers test deps (see Tests & tooling).

## Commands

Run everything through the **root Maven wrapper** (`./mvnw`) from the repo root — no system Maven, no
per-module wrappers:

```bash
./mvnw clean package                       # build the whole reactor (all 4 modules; runs build-frontend)
./mvnw -pl 03-ai-filter spring-boot:run    # run one module (modules have no inter-deps, so -pl alone works)
./mvnw -pl 01-simple-filter,02-lazy-filter package   # build a subset
./mvnw -pl 04-local-ai-filter test -Dtest=CustomerFilterSpecificationsTest      # fast unit test (no Docker)
./mvnw -pl 04-local-ai-filter test -Dtest=CustomerSearchServiceLocalOllamaIT    # LLM test vs native Ollama (skips if unreachable)
./mvnw -pl 04-local-ai-filter test -Dtest=CustomerSearchServiceTestContainerIT  # LLM test vs Ollama in Docker (needs pre-built image)
```

Each module serves on its own port (`01`→8081 … `04`→8084) and launches a browser
(`vaadin.launch-browser=true`), so several can run simultaneously without conflict. Note: a plain
`./mvnw test` also triggers `04-`'s AI integration tests, but they skip gracefully — the native-Ollama
variant when no Ollama is reachable, the Testcontainer variant when Docker is unavailable (see Tests &
tooling). Running `04-`
(`spring-boot:run`) talks to a local Ollama by default — switch to OpenAI by editing its
`application.properties` (uncomment the OpenAI block, comment the Ollama one).

## Configuration that must be supplied

`03-` needs an OpenAI API key or its chat calls fail. `application.properties` reads
`spring.ai.openai.api-key=${OPENAI_API_KEY:SOMETHING}`, so set env var `OPENAI_API_KEY` (the literal
fallback `SOMETHING` will not work). `01-` and `02-` do not call the model, but the OpenAI starter is
still on the classpath; if context startup ever complains about a missing key, supply one anyway.

`04-` is configured for a **local Ollama** by default (`spring.ai.model.chat=ollama`, `llama3.1:8b`,
`think=false`, `num-ctx=4096`, `init.pull-model-strategy=when_missing`), so it needs a running Ollama
with the model available (`ollama pull llama3.1:8b`) and **no OpenAI key**. Its `application.properties`
is a multi-document file (split by `#---`); to use OpenAI instead, uncomment the OpenAI block and comment
the Ollama one.

The H2 datasource, `ddl-auto=create-drop`, deferred datasource init, and `data.sql` (~110 customer rows)
are already wired in each module's `application.properties` — no external database is needed.

## Architecture notes

- **Entry-point class** (one per module, e.g. `SimpleFilterApplication`, `LocalAiFilterApplication`) —
  `@SpringBootApplication` + `@Push` + `@StyleSheet(Aura.STYLESHEET)`, implements `AppShellConfigurator`.
- **Data model** (identical across modules, in `data/`):
  - `Customer` — `@Entity` with `companyName`, `contactName`, `active`, `annualRevenue` (`BigDecimal`),
    `customerSince`/`lastOrderDate` (`LocalDate`), `email`, `phone` (E.164), and an `@Embedded` `Address`.
  - `Address` — `@Embeddable` with `street`, `houseNumber`, `postalCode`, `city`, `state`, `country`,
    `countryCode`.
  - `CustomerRepository` — `JpaRepository<Customer, Long>` **and** `JpaSpecificationExecutor<Customer>`
    (the `findAll(Specification, Pageable)` overload is what the lazy/AI filtering relies on).
- **Lazy grid** (`02-`/`03-`/`04-`) — `grid.setItems(query -> repo.findAll(spec, VaadinSpringDataHelpers
  .toSpringPageRequest(query)).stream(), countQuery -> ...)`. Building a JPA `Specification` from filter
  state is the core pattern; the AI modules just generate that filter state from natural language.
- **AI filtering** — both modules use `ChatClient.builder(chatModel).build()`, but demonstrate two
  *different* Spring AI techniques for turning language into a filter. The model never sees customer
  data and never writes SQL; it only produces filter *intent* (`field/operator/value`), which Java
  turns into a `Specification` and the database executes.
  - `03-` — **tool calling**. `CustomerListView` holds the `ChatClient` and a `@Tool searchCustomers(...)`
    method (one `@ToolParam` per field) plus a `currentLocalDateTime()` `@Tool`, calls `.stream()`, and
    the tool updates the grid inside `ui.access(...)` (the streamed assistant text is ignored). The tool
    builds the `Specification` itself via a private `buildCustomerSpecification`. The `@Tool`/`@ToolParam`
    **descriptions carry the real logic** (date formats, E.164 normalization, AND/OR semantics) — treat
    them as prompt engineering.
  - `04-` — **structured output**, layered (see above). `CustomerSearchService.resolveFilter(input)` calls
    `.call().entity(CustomerFilter.class)` (blocking) so the model returns one flat `CustomerFilter`
    object, and **returns** a `Specification` instead of mutating any UI. Here the real logic lives in the
    **system prompt** (`systemPrompt(LocalDate)`) and the Jackson `@JsonPropertyDescription`s on the
    records — keep those accurate. `CustomerListView` runs the service off the UI thread
    (`CompletableFuture.supplyAsync`) and applies the result inside `ui.access(...)` through a single
    `applyFilter(Specification)` method (the unfiltered case passes `Specification.unrestricted()`).
- **Tests & tooling** (in `04-`; `01-`–`03-` still have no tests):
  - `ai/filter/CustomerFilterSpecificationsTest` — `@DataJpaTest` against the seeded H2 (no Docker, fast):
    verifies the `CustomerFilter` → `Specification` translation, including the field-grouping rules
    (e.g. "(Berlin OR Hamburg) AND revenue ≥ 100000", and a single-field range as AND).
  - `ai/CustomerSearchIT` — **abstract** base holding the shared AI-layer tests (natural language →
    `CustomerFilter`) against a real Ollama running `MODEL` (`llama3.1:8b`). Asserts tolerantly (the LLM
    is non-deterministic): it only checks the expected criteria are present (field + value substring),
    ignoring operator and extras. Two subclasses supply the connection and run the same tests:
    - `ai/CustomerSearchServiceLocalOllamaIT` — against a **native** Ollama (no Docker) at
      `OLLAMA_BASE_URL` (default `http://localhost:11434`); the model must already be pulled there.
      **Skips gracefully** (JUnit assumption) when no Ollama is reachable, so a plain `./mvnw test` stays
      green without one.
    - `ai/CustomerSearchServiceTestContainerIT` — against an Ollama **Testcontainer** (**needs Docker**;
      `@Testcontainers(disabledWithoutDocker = true)`). Uses `GenericContainer` (the
      `org.testcontainers:ollama` module is only published for Testcontainers 1.x and is incompatible
      with the resolved 2.x core, so `@ServiceConnection` can't be used) with a **pre-built image** that
      has the model baked in (`src/test/docker/Dockerfile` + `build-images.sh`, tag
      `ai-grid-filter/ollama:<model>`). Both subclasses wire the base-url via `@DynamicPropertySource`
      and exclude Vaadin's autoconfig (`webEnvironment=NONE` has no `WebApplicationContext`).
  - `src/test/scripts/benchmark_models.py` — Docker-free comparison of local Ollama models (accuracy +
    median latency + tokens/s + total wall-clock per model) against the native Ollama on `:11434`;
    OpenAI models can be added with `--openai <model>` (needs `OPENAI_API_KEY`).
  - See `COMPARISON.md` for the `03-` (tool calling) vs `04-` (structured output) write-up.

## Vaadin tooling

For Vaadin component APIs, styling, and docs, prefer the `vaadin` MCP tools (e.g.
`get_component_java_api`, `search_vaadin_docs`) over guessing — Vaadin 25 is a recent release.