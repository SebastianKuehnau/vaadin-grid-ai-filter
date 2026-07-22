# Task Spec: Contrast tool calling vs. structured output (02 vs. 03)

## Task

Make the difference between the two AI-call styles this project demos — **tool calling / agentic**
(`02-ai-agent-filter`) and **structured output** (`03-ai-structured-filter`) — visibly and
verifiably clearer, using the existing aligned integration tests as evidence. Produce three
outputs:

1. **A presentation-ready comparison document** (`docs/tool-calling-vs-structured-output.md`) that
   states the pros and cons of each approach, every claim backed by a named, existing test case or
   by a benchmark number — no unsupported assertions.
2. **A capability matrix** in its own document (`docs/capability-matrix.md`) — a table mapping
   query types to each approach's outcome (reliably handled / not expressible / model-dependent),
   derived strictly from the actual test suites. The comparison document links to it.
3. **A paired side-by-side comparison view in the benchmark report** — extend
   `04-ollama-benchmark/BenchmarkLocalModels.java`'s report rendering so tool-calling and
   structured-output results for the same model appear contrasted directly (not only as separate
   rows/sections), so divergence is legible at a glance.

Additionally, **clean up all genuine documentation/code discrepancies** discovered around these
tests (inventory below) — but only after verifying each against the current code; some flagged
items are already correct and must be left untouched.

## Context

- **Demo/talk relevance:** This is a conference-talk demo project; top priority is clarity and
  presentability. The comparison document and matrix are talk material — write for a conference
  audience: concise, concrete, example-driven.
- **The approaches (already implemented — do not change the AI logic):**
  - `02-ai-agent-filter` — `CustomerSearchToolCallingService` builds a `ChatClient`, exposes
    `@Tool searchCustomers(...)` (one `List` param per field) and `@Tool currentLocalDateTime()`;
    the LLM calls the tool, whose body writes a flat `CustomerSearchCriteria` →
    `CustomerSpecifications.from(...)`.
  - `03-ai-structured-filter` — `CustomerSearchStructuredOutputService` uses
    `.call().entity(CustomerFilter.class)`; the LLM returns one JSON object (a `CustomerFilter`
    with a list of `Condition(field, Operator, values, negate)`) →
    `CustomerFilterSpecifications.from(...)`. `systemPrompt(LocalDate today)` bakes "today" into the
    prompt text (no live date tool call).
- **The tests are the source of truth for every claim** (read them all; do not paraphrase from
  memory):
  - `02-ai-agent-filter/src/test/java/.../ai/CustomerSearchAgentIT.java` and
    `03-ai-structured-filter/src/test/java/.../ai/CustomerSearchAgentIT.java` — AI-layer ITs; the
    two classes are deliberately aligned (identical method names, query wording, source order) so
    results/timings are directly comparable.
  - `03-ai-structured-filter/src/test/java/.../ai/CustomerSearchAgentExtraIT.java` — the
    structured-only cases (negation, `STARTS_WITH`/`ENDS_WITH`/`EQUALS` precision, arbitrary date
    bounds, `phoneNumberContains`) that `02`'s flat `CustomerSearchCriteria` cannot express.
  - `02-ai-agent-filter/src/test/java/.../ui/CustomerListViewBrowserlessIT.java` and
    `03-...` counterpart — the 7 aligned end-to-end UI cases (query → real LLM → grid rows).
  - `04-ollama-benchmark/BenchmarkLocalModels.java` — already runs **both** approaches
    (`--approach=both`, `Approach.TOOL_CALLING`/`STRUCTURED`), scores them approach-agnostically,
    and writes `benchmark-report-<timestamp>.md/.txt`. The report currently lists each approach as
    separate summary rows and separate per-case/per-field sections; there is **no paired view**.
- **Distinguishing evidence already documented in the test Javadocs** (mine these for the matrix;
  reconcile against the current code — do not trust this summary blindly):
  - *Tool-calling-only / where 02 wins:* relative dates via the `currentLocalDateTime()` chained
    tool call (model-dependent — a weaker model fails the two-hop chain); and three cases dropped
    from the shared set because `03`'s structured layer failed them on its default model while `02`
    passed: `germanPhoneNumberNormalizedToE164`, `multiValueCustomerSinceYears`,
    `citiesAndCreditRating_German`.
  - *Structured-only / where 03 wins:* negation, operator precision, arbitrary date bounds (not
    expressible in `02` at all); and `phoneNumberContains`, which `02`'s tool-calling layer failed
    (hallucinated an unrelated number) but `03` handles.
  - The configured default Ollama model per module is authoritative from each module's
    `application.properties` (ollama profile) — read it; do not hardcode a model name from this
    spec, as model/temperature comments have been corrected recently.
- **Discrepancy inventory (candidates — VERIFY each against current code before editing):**
  1. `LocalOllamaTests` / `OllamaTestSupport` — referenced but the class does not exist. Live
     references in: `02-ai-agent-filter/README.md`, both `pom.xml`s (surefire
     `<exclude>**/LocalOllamaTests.java</exclude>`, ~line 87–94), `CLAUDE.md`,
     `tasks/align-ai-integration-tests.md`. The ITs are plain `@SpringBootTest`, extend nothing.
  2. "skips gracefully if unreachable" claims in the three module READMEs — the IT code has no
     reachability probe and fails (not skips) when the backend is down (the IT Javadocs say so).
  3. Stale `03` filter model in docs — root `README.md` (and possibly others) describing a
     recursive `FilterNode` / AND-OR-NOT tree that the current flat `CustomerFilter` no longer
     implements. `FilterNode`/tree wording also appears in source comments
     (`CustomerSearchCriteria.java`, `CustomerFilter.java`) — some may be legitimate comparative
     remarks; classify before editing.
  4. openai/ollama test-profile default — **appears already correct** (READMEs distinguish app
     default `openai` from test override `ollama`). Verify and, if correct, leave untouched and
     record it as "checked, no change".

## Implementation frame (boundaries, not steps)

- **Do not change any AI logic, prompts, filter types, or existing test assertions.** This task is
  documentation + benchmark-report rendering + doc/comment cleanup only.
- **Two separate documents:** the prose comparison in `docs/tool-calling-vs-structured-output.md`
  and the capability matrix in `docs/capability-matrix.md`. The comparison document links to the
  matrix (and vice versa) so they read as a pair. Both English, Markdown, talk-audience tone.
- **Every claim in the document must cite its source** — a specific test method
  (`Class#method`), an `ExtraIT` case, or a benchmark figure. No claim without a pointer.
- **The "reliable vs. flaky" dimension of the matrix is backed by benchmark pass-rates**
  (`--runs` ≥ 5), not by repeated JUnit runs — leverage the existing quantitative harness rather
  than inventing a new reliability measure.
- **Benchmark change is confined to report rendering** (`renderMarkdown`/`renderText` and any
  helper they need) plus, if required, a small results-pairing helper. Do not change the case set,
  the scoring, the CLI, or the approach abstraction. The `benchmark-report-*` files stay gitignored
  and are **never committed** (`.gitignore` already covers `**/benchmark-report-*` and
  `**/benchmark-results/`).
- **Cleanup scope — living artifacts only:** root `README.md`, the module `README.md`s, the two
  `pom.xml` surefire excludes, and stale source comments. **Explicitly excluded:**
  - `CLAUDE.md` — self-maintained via `/update-claude-md`; do **not** edit it. Report its
    `LocalOllamaTests` reference in the final report for the user to handle.
  - `tasks/*.md` — historical, point-in-time spec records; do **not** "correct" them. Flag only.
- **Consistency across modules:** doc/comment fixes that apply to both 02 and 03 must be applied to
  both (per the project's cross-module consistency rule).
- **Subagents:** optional; if used, give a concrete deliverable and a step limit and surface
  results directly — no silent background waits.

## Procedure

1. **Precondition check (hard gate — abort and report if unmet).** Confirm a native Ollama is
   reachable at `OLLAMA_BASE_URL` with at least one tools-capable model pulled, ideally each
   module's configured default model. If unreachable or no suitable model, stop and report — do not
   proceed with unbacked claims.
2. **Present a plan and STOP for explicit go-ahead** before changing any file (project plan-gate).
   The plan must include the drafted capability-matrix structure and the concrete list of
   discrepancies confirmed genuine vs. already-correct.
3. After approval, implement with a **commit after every verified step** (Conventional Commits, no
   push). Suggested step boundaries (each independently verifiable):
   - a. Discrepancy verification + cleanup of living docs/comments/poms.
   - b. Benchmark paired-comparison rendering.
   - c. Comparison document + capability matrix.
4. **Mid-task gate (before writing document claims):** run the evidence-gathering (ITs and/or a
   benchmark `--approach=both --runs≥5` pass) and reconcile the matrix against the observed results;
   if observed reality contradicts the documented Javadoc expectations, stop and report the
   conflict rather than documenting either version.

## Definition of Done

All criteria must hold; each is checkable by a command, a test, or an inspectable artifact.

1. **Build green:** `./mvnw verify -pl 02-ai-agent-filter,03-ai-structured-filter` passes (default
   profile; confirms doc/comment/pom edits didn't break compilation or unit tests).
2. **Benchmark compiles & runs:** `cd 04-ollama-benchmark && java BenchmarkLocalModels.java --help`
   succeeds, and one live run
   `java BenchmarkLocalModels.java --approach=both --quick --runs=5` (Ollama reachable per
   precondition) produces a `benchmark-report-<timestamp>.md` that **contains the new paired
   tool-calling-vs-structured-output section** (grep the section heading). The report file is
   **not** staged for commit (`git status` shows it untracked/ignored).
3. **Evidence run recorded:** both modules' `CustomerSearchAgentIT` and
   `CustomerListViewBrowserlessIT` have been run via `-Pit-local-ollama` at least once, and the
   pass/fail outcome of each documented claim matches what the comparison document asserts. Where
   the document calls a capability "reliable" or "flaky", the label is backed by the `--runs=5`
   benchmark pass-rate, not a single JUnit run.
4. **Comparison document exists and is fully sourced:** `docs/tool-calling-vs-structured-output.md`
   exists, states pros/cons for both approaches, links to `docs/capability-matrix.md`, and every
   claim cites a specific test method, `ExtraIT` case, or benchmark figure. A reviewer can trace
   each bullet to its source.
5. **Capability matrix present and accurate:** `docs/capability-matrix.md` exists and contains a
   matrix table covering, at minimum, the aligned shared cases, the structured-only capabilities
   (negation, operator precision, arbitrary date bounds), the tool-calling relative-date case, and
   the bidirectional "one approach passed, the other didn't" cases; every matrix cell corresponds
   to an actual test case or benchmark result.
6. **Discrepancies resolved:** every item in the discrepancy inventory is either corrected (living
   docs/comments/poms) or explicitly recorded as "verified, already correct, no change". No
   reference to the non-existent `LocalOllamaTests` remains in living docs or the poms.
   `CLAUDE.md` and `tasks/*.md` are unchanged.
7. **Artifact hygiene:** `git status` before each commit shows no benchmark reports, logs, or other
   generated files staged.

## Final report

The completion message must state:

- The three deliverables' locations and a one-line summary of each.
- The final capability matrix (or a pointer + its headline findings), including the
  bidirectional-win cases that best dramatize the contrast for the talk.
- The evidence basis: which IT runs and which benchmark run(s) (models, `--runs`, pass-rates)
  back the claims.
- The discrepancy resolution log: for each inventory item — corrected (with file) or
  verified-already-correct.
- **Reserved for the user (do not automate):** the `CLAUDE.md` `LocalOllamaTests` reference —
  surface it as a `/update-claude-md` follow-up. Note that `tasks/*.md` historical references were
  intentionally left unchanged.
- Any capability where observed test/benchmark reality diverged from the previously documented
  expectation, and how it was resolved in the document.
