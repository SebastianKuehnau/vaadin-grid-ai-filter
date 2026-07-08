# 02-ai-filter-agent

Natural-language filtering of a Vaadin `Grid` of `Customer` records via **AI tool calling**: the
LLM parses the request and calls a `searchCustomers` tool, passing one argument per field; Java
turns those arguments into a JPA `Specification`. First step in this tutorial towards filtering
data with natural language — compare with the non-AI baselines in `01-non-ai-filter` and the
structured-output approach in `04-local-ai-filter`.

## View

- **`/`** — `CustomerListView`: a single natural-language `TextField` above the grid. Typing a
  query (and blurring/pressing enter) sends it to the AI layer; a blank query resets to all rows.
  The view has zero Spring AI imports — it only knows `CustomerSearchAgent` and applies the
  `Specification` it returns.

## AI layer (`ai` / `ai/filter`)

```
ai/
├── AiConfiguration.java                 (@Configuration — Ollama/OpenAI ChatModel provider selection)
├── CustomerSearchAgent.java             (public interface — the view's only dependency, the testability seam)
├── ToolCallingCustomerSearchAgent.java  (@Service @Scope("prototype") — ChatClient, system prompt, both @Tool methods)
└── filter/
    ├── CustomerSearchCriteria.java      (public record — the flat extracted filter values)
    └── CustomerSpecifications.java      (public final utility — flat AND -> Specification<Customer>)
```

`ToolCallingCustomerSearchAgent` is `@Scope("prototype")`, not the default singleton — because
`CustomerListView` (the only place a `CustomerSearchAgent` is injected) isn't a singleton either
(Vaadin creates a fresh view instance per navigation), each view gets its own agent instance. That
makes it safe for the two `@Tool` methods (`searchCustomers`, `currentLocalDateTime`) and the
`criteria` field they extract into to live directly on the bean: different browser tabs/sessions
never share an instance, and within one instance the view only ever has one search in flight at a
time (it disables the filter field for the duration of a search). `requestCriteria(...)` resets
`criteria` to `null` at the start of every call, since — unlike a fresh per-call object — the field
now outlives a single call. `CustomerSpecifications` is a flat AND-conjunction only (no OR/NOT
tree) — the deliberate, demo-relevant contrast with `04-local-ai-filter`'s `FilterNode` tree.

`CustomerSearchAgent.resolveFilter(...)` never throws: on any failure (bad model response,
unreachable model, ...) it falls back to an unrestricted specification, so the UI never breaks.

### Known limitation: relative dates need two chained tool calls

For a relative date ("yesterday", "last week") the model must call `currentLocalDateTime()`, then
compute an offset from its result and pass that computed date into `searchCustomers`. This
two-hop chain is harder than a single tool call: `llama3.1:8b` (this module's configured default)
reliably fails it — it either passes a literal placeholder string instead of a computed date, or
skips the tool call and hallucinates a stale one — while `qwen3:8b` handles it correctly. This is
a genuine model-capability gap in the tool-calling approach, not a bug in the tool wiring, and is
why `CustomerSearchAgentIT` (below) does not include a relative-date case. `04-local-ai-filter`
avoids the issue entirely by putting "today" directly into its prompt text instead of requiring a
live tool call — a good illustration of the trade-off between the two approaches.

## Running

```bash
./mvnw -pl 02-ai-filter-agent spring-boot:run   # http://localhost:8082
```

Configured for a local Ollama by default (see `AiConfiguration`); start Ollama and pull the model
first:

```bash
ollama pull llama3.1:8b
```

Switch to OpenAI by setting `app.ai.provider=openai` in `application.properties` (needs the
`OPENAI_API_KEY` environment variable).

## Tests

```bash
./mvnw -pl 02-ai-filter-agent test                        # unit tests + CustomerListViewBrowserlessTest, no LLM
./mvnw -pl 02-ai-filter-agent verify -Pit-local-ollama     # CustomerSearchAgentIT vs native Ollama (skips if unreachable)
```

- **`CustomerSpecificationsTest`** (`@DataJpaTest`, no LLM) — one test per predicate/field against
  the seeded H2 data, plus AND-combination and null-matches-all.
- **`ToolCallingCustomerSearchAgentToolsTest`** (plain JUnit, no Spring context) — the extraction
  plumbing and the date tool, in isolation.
- **`CustomerSearchAgentIT extends LocalOllamaTests`** — natural-language queries against a native
  Ollama instance (`LocalOllamaTests`/`OllamaTestSupport` duplicated from `04-local-ai-filter`,
  this repo's established per-module pattern for Ollama IT infrastructure). Assertions are
  tolerant of LLM non-determinism (case-insensitive, substring). Tagged `small-model-query`.
- **`CustomerListViewBrowserlessTest`** — [Vaadin Browserless
  testing](https://vaadin.com/docs/latest/flow/testing/browserless) with a fake, deterministic
  `CustomerSearchAgent` bean, so it never calls a real model. Since the view applies results
  asynchronously (`CompletableFuture` + `ui.access(...)`), assertions after a non-blank query use
  `MockVaadin.runUIQueue()` (to flush the queued `ui.access()` command) inside an Awaitility
  `pollInSameThread()` loop (so the flush runs on the thread holding the UI `ThreadLocal`) —
  needed because a plain synchronous assertion races the background search thread.

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerListView.java` — the view
- `src/main/java/dev/demo/vaadin/aigridfilter/ai/` — the AI layer (see above)
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/` — tests (see [Tests](#tests) above)
