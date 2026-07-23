# Log consumed tokens per AI request in the views (02, 03) and print a token summary at the end of the ITs

## Task

Make the LLM token cost of natural-language filtering **visible in the log** for both AI modules:

1. **Per request (production path, view-initiated).** Every AI request triggered from the customer
   view in `02-ai-agent-filter` and `03-ai-structured-filter` logs its consumed tokens —
   **prompt / completion / total** — as a dedicated log line.
2. **Per test suite (integration tests).** At the **conclusion of all test cases**, the following
   IT classes print a token summary — **total tokens, number of requests, and average tokens per
   request**:
   - `02-ai-agent-filter` and `03-ai-structured-filter`: `CustomerSearchAgentIT` (AI-layer IT).
   - `02-ai-agent-filter` and `03-ai-structured-filter`: `CustomerListViewBrowserlessIT` (UI→AI IT).

**Decided semantics (do not re-open):**
- Module 02 (tool calling) makes several LLM round trips per request; the logged/accumulated figure
  is the **sum of tokens over all round trips** of a request, not just the final response — so the
  number is comparable to module 03's single round trip and honestly reflects tool-calling cost.
- Per-request log content: **prompt, completion, and total** tokens.
- End-of-suite summary content: **total tokens, request count, and average tokens per request.**

## Context

- **Affected modules:** `02-ai-agent-filter` (tool calling) and `03-ai-structured-filter`
  (structured output) — standalone Spring Boot apps over the same `Customer` domain, differing only
  in the AI filtering mechanism. Spring AI **2.0.0** (BOM in root `/workspace/pom.xml:21,42-43`),
  Spring Boot 4.1.0. Module `04-ollama-benchmark` is **out of scope** (see boundaries).
- **Purpose:** these modules back conference talks contrasting tool calling vs structured output.
  Surfacing per-request token cost — and a per-suite total — makes the "which approach is cheaper on
  small local models" story concrete and demonstrable live from the log.
- **Where the tokens live.** In Spring AI 2.0 token usage is on the `ChatResponse`:
  `chatResponse.getMetadata().getUsage()` → `getPromptTokens()` / `getCompletionTokens()` /
  `getTotalTokens()`. Both services currently **discard** the `ChatResponse`, so today usage is not
  reachable. The views only ever receive a `Specification<Customer>` and, per project convention, may
  contain no AI logic — therefore token logging is added in the **AI service layer** (the layer the
  view calls), **not in the view.** The views themselves are expected to need **no** code change.
- **Files in scope (source of truth — verify each exists before editing):**
  - Module 02 service — `02-ai-agent-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchToolCallingService.java`
    (`requestCriteria(String)`, lines ~77-94; today `.call().content()` with the value discarded,
    criteria populated via the `@Tool searchCustomers` callback).
  - Module 03 service — `03-ai-structured-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java`
    (`requestFilter(String)`, lines ~52-69; today `.call().entity(CustomerFilter.class)`).
  - AI-layer ITs — `.../ai/CustomerSearchAgentIT.java` in **both** modules (plain JUnit 5, no base
    class, one `@Test` per case; 02 calls `agent.requestCriteria(query)`, 03 calls
    `service.requestFilter(query)`; **no `@AfterAll`/teardown today**). Note: `03` also has a sibling
    `CustomerSearchAgentExtraIT.java` — see boundaries.
  - UI ITs — `.../ui/CustomerListViewBrowserlessIT.java` in **both** modules (`extends
    com.vaadin.browserless.SpringBrowserlessTest`; drives the real view via a `search(String)` helper
    so `CustomerListView.onFilter` calls the real agent off-thread; **no `@AfterAll`/teardown today**).
  - Views (reference only, expected unchanged) — `.../ui/CustomerListView.java` in both modules:
    `onFilter` → `CompletableFuture.supplyAsync(() -> searchAgent.resolveFilter(...))`.
- **No pre-existing hook to reuse.** There is no shared test base class (`LocalOllamaTests` does not
  exist — it is a stale doc reference only), no `@BeforeAll`/`@AfterAll`/`TestWatcher`/extension, and
  no cross-test token aggregation anywhere in either module's test tree. Both services already use an
  SLF4J `private static final Logger` with `{}` placeholders and a `SimpleLoggerAdvisor` on the call —
  the existing `logger.info("requestFilter(...) -> {}")` / `logger.info("requestCriteria(...) -> {}")`
  lines are the natural neighbours for the new per-request token line.

## Implementation frame

Boundaries and constraints — the execution run makes its own detailed plan within these.

**Recommended design (may be refined at the plan gate, but stay within the boundaries below):**
- Reach the usage metadata by capturing the `ChatResponse` instead of discarding it:
  - Module 03: switch `.call().entity(CustomerFilter.class)` to `.call().responseEntity(CustomerFilter.class)`
    (returns both the parsed `CustomerFilter` **and** the `ChatResponse`), then read usage from the
    `ChatResponse`. The returned `CustomerFilter` and the `requestFilter` signature stay identical.
  - Module 02: switch `.call().content()` to `.call().chatResponse()`; the `@Tool` callback still
    populates `criteria` as a side effect and `requestCriteria` still returns the same
    `CustomerSearchCriteria`.
- Introduce a **minimal, Spring-managed token accountant** (e.g. a small `@Component` the service
  records each request's usage into). It (a) emits the per-request log line
  (prompt/completion/total), and (b) accumulates running totals + request count so the ITs can read
  and print the summary. This one mechanism serves both the production per-request logging and the
  test summary, and works uniformly for the AI-layer ITs (direct service call) and the browserless
  ITs (view→service off-thread) because both drive the same Spring context. **Do not** change the
  public `resolveFilter` / `requestFilter` / `requestCriteria` signatures (the ITs and views depend
  on them). The accountant must be **resettable per test class** (reset in a `@BeforeAll`, print in
  a static `@AfterAll`) so a shared/cached Spring context does not leak one class's tokens into
  another's summary.
- **Multi-module consistency (required by `CLAUDE.md`):** the two modules are separate apps with **no
  shared module** — mirror the implementation in each module consistently (same as services/views
  are already duplicated). Do **not** introduce a shared test module, test-jar, or cross-module code
  sharing.
- Keep it demo-grade clear and presentable (top project priority): a readable dedicated log line and
  a compact summary line, English text, SLF4J with `{}` placeholders, CSS/logging config in the
  proper place (no inline hacks). No new runtime dependencies.

**Explicit plan-gate technical item (single open question — resolve and confirm at the plan gate,
before writing code):** how to obtain the **summed** module-02 token total across tool-calling round
trips in Spring AI 2.0. Determine whether the final `.call().chatResponse()` usage already aggregates
across the internal tool-execution round trips, or whether per-round accumulation is needed (e.g. a
custom advisor that sees each `ChatResponse`, or another supported hook). The **semantic** (sum over
all round trips) is already decided; only the mechanism is open. State the chosen mechanism and the
evidence for it at the plan gate.

**Boundaries / out of scope (do NOT change; list anything you think should change under Final report):**
- **Module `04-ollama-benchmark` is untouched.** It already reports tok/s from backend-native fields
  via its own raw-HTTP path; this task adds nothing there. This is not a new filter capability, so
  `CLAUDE.md` DoD point 4 (add a benchmark case) does **not** apply.
- No changes to prompts, `CustomerFilter` / `CustomerSearchCriteria`, `data.sql`, the AI provider
  config, or the network/transport layer.
- No changes to test **assertions**, case sets, queries, ordering, or tolerances — this task only
  **adds** token logging and an end-of-suite summary. `03`'s `CustomerSearchAgentExtraIT` is not in
  scope for the summary requirement (the task named only the four classes above); if the chosen
  mechanism makes it trivially inherit the summary, that is acceptable but not required — state what
  was done.
- No framework/dependency/version upgrades (Spring AI version is pinned on purpose).

## Procedure

1. **Plan first.** Present a plan covering: the exact service change per module, the token-accountant
   mechanism, the module-02 round-trip-aggregation mechanism (the plan-gate item above, with
   evidence), the per-request log-line format, the summary-line format, and how each of the four IT
   classes resets + prints the summary. **STOP and wait for explicit approval before changing any
   file** (root `CLAUDE.md` plan-approval gate). A later check-in from the user is authoritative, not
   stale.
2. **Mid-task gate (explicit):** after the module-02 aggregation mechanism is implemented and the
   summed total is proven correct with a short manual/IT check, pause and report the observed round
   trips and summed vs final-only numbers before wiring the summary into all four IT classes —
   reworking the accountant after the ITs are wired would be expensive.
3. Implement in increments, committing after each verified step (Conventional Commits, no push).
   Suggested split, applied consistently to both modules: (a) service captures `ChatResponse` +
   per-request token log via the accountant; (b) end-of-suite summary in the two ITs. Apply
   multi-module changes to **both** 02 and 03 in the same step.
4. Verify against the Definition of Done; iterate autonomously on failures. Update `README.md` /
   `CLAUDE.md` only if a concrete statement there becomes wrong because of this change.

## Definition of Done

**Hard precondition (check first; abort and report if unmet — never a conditional DoD):** a native
Ollama instance is reachable at `OLLAMA_BASE_URL`, and every model referenced by the two modules'
`ollama` Spring profile (`src/main/resources/application-ollama.properties`) is present locally,
verified via the Ollama API (`GET /api/tags`). Missing model → pull it or abort and report.

- **Compilation + unit tests green:** `./mvnw verify -pl 02-ai-agent-filter,03-ai-structured-filter`
  passes (both modules compile, including the ITs; existing unit tests still green; return
  types/signatures unchanged).
- **Per-request token log (production, view-initiated) — proven by artifact.** For **each** module:
  start the app (`./mvnw spring-boot:run -pl <module>` with a profile whose usage is populated),
  type a filter query in the view, and capture the console log showing the dedicated per-request
  token line with **prompt, completion, and total** values (total > 0). The final report includes
  the log excerpt (one per module). Since the observable change is a log line (not a visual change),
  the log excerpt — not a UI screenshot — is the proof.
- **End-of-suite summary in all four ITs — proven by artifact.** Running each IT with the local
  Ollama profile —
  `./mvnw verify -pl 02-ai-agent-filter -Pit-local-ollama -DAI_TEST_PROFILE=ollama -Dit.test=CustomerSearchAgentIT`
  (and likewise `CustomerListViewBrowserlessIT`, and both classes in `03-ai-structured-filter`) —
  ends with **one** summary line per class showing **total tokens, request count, and average per
  request** (total > 0, request count = number of executed cases). The report includes the four
  summary lines. This summary is deterministic given the run completes (it is `@AfterAll` output,
  independent of case pass/fail).
- **Behavioral non-regression (LLM-backed, single green run):** each of the four IT classes passes in
  **one** green `-Pit-local-ollama -DAI_TEST_PROFILE=ollama` run per module (the existing single-run
  behavioral bar; this task does not change assertions, so no new flakiness is expected). A case that
  flakes here is pre-existing and unrelated to this change — note it, do not "fix" it by touching
  assertions.
- **Multi-module consistency:** the service change and the summary mechanism are present and
  equivalent in **both** 02 and 03; no shared test module / test-jar / cross-module code sharing was
  introduced.
- **Views unchanged (or, if touched, justified):** confirm `CustomerListView.java` in both modules is
  unmodified (token logging lives in the service layer); if any view change proved unavoidable,
  justify it in the final report.

**Artifact policy:** only modified source and test files are committed. All IT run logs,
surefire/failsafe reports, app console output, and any generated summaries are build artifacts —
gitignored, never committed. Verify the staged file list (`git status`) before every commit.

## Final report

Provide, at completion:
- What changed, per commit (Conventional Commits messages), for each module.
- The **module-02 round-trip aggregation mechanism** chosen, with the evidence that the logged number
  is the summed total (observed round-trip count; summed vs final-only figures).
- The per-request log excerpt for **each** module (view-initiated), showing prompt/completion/total.
- The **four** end-of-suite summary lines (one per IT class) with total / count / average, plus
  confirmation of one green `-Pit-local-ollama` run per class, and the model(s) used.
- Confirmation that the views were not modified (or justification if they were), and that module 04
  and the test case sets/assertions were left untouched.
- **Decisions left to the maintainer (flag, do not action):** whether to also print the summary for
  `03`'s `CustomerSearchAgentExtraIT`, and whether any stale doc reference encountered (e.g.
  `LocalOllamaTests` in `CLAUDE.md`) should be corrected in a follow-up.
