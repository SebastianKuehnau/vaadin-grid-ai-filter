# Task
Split module `04-local-ai-filter` into two modules:
- `03-ai-structured-filter`: the structured-output filtering feature (currently in
  `04-local-ai-filter`), restructured to follow `02-ai-agent-filter`'s conventions
  (naming, testing, README) — including new Browserless UI tests equivalent in scope
  to module 02's.
- `04-ollama-benchmark`: only the standalone `BenchmarkLocalModels.java` script,
  as a plain non-Maven directory (the script is dependency-free and runs outside the
  Maven build lifecycle; it does not belong in the root `pom.xml` `<modules>` list).

# Context
- Affected modules: `02-ai-agent-filter` (template/reference only, not modified),
  `04-local-ai-filter` (source, removed after the split), new `03-ai-structured-filter`,
  new `04-ollama-benchmark`; also root `pom.xml`, root `README.md`, `CLAUDE.md`.
- Purpose: clean tutorial progression for conference talks — `01` (non-AI) →
  `02` (tool calling) → `03` (structured output) as parallel, comparably-tested
  filtering approaches. The local-model benchmarking tooling is unrelated to any
  single filtering approach, so it moves into its own, non-Maven directory instead
  of piggybacking on a feature module.
- Relevant existing parts (serve as source/template — copy from these, do not
  reinvent):
  - `04-local-ai-filter/src/main/java/dev/demo/vaadin/aigridfilter/**` — feature
    code to copy into `03-ai-structured-filter` (`LocalAiFilterApplication`,
    `ai/AiConfiguration`, `ai/CustomerSearchService`,
    `ai/filter/{CustomerFilter,FilterNode,Operator,CustomerFilterSpecifications}`,
    `data/*`, `ui/*`).
  - `04-local-ai-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/{CustomerSearchIT,
    LocalOllamaTests,OllamaTestSupport,filter/CustomerFilterSpecificationsTest}.java`
    — tests to copy/rename into `03-ai-structured-filter`.
  - `04-local-ai-filter/src/test/scripts/BenchmarkLocalModels.java` — script to copy
    into the new `04-ollama-benchmark/` directory.
  - `02-ai-agent-filter/**` — structural template for module 03: package layout,
    the `CustomerSearchAgent` interface seam pattern (implemented by
    `CustomerSearchToolCallingService` in module 02), the
    `LocalOllamaTests`/`OllamaTestSupport`/`*IT` pattern, the
    `CustomerListViewBrowserlessTest`/`CustomerListViewBrowserlessIT` pattern,
    `pom.xml` test dependencies (`browserless-test-spring`, `awaitility`) and the
    `it-local-ollama` profile, and the module's own `README.md` structure.
  - Root `pom.xml` (`<modules>` list), root `README.md`, `CLAUDE.md`.

# Approach
1. First create a plan (Plan Mode) and show it to me. Wait for my OK.
2. Implement the plan. Work in logical steps, commit after each verified step
   (Conventional Commits, no push).
3. Verify autonomously according to Definition of Done in CLAUDE.md.
   Iterate on errors independently — don't present error messages for analysis
   that you can reproduce and fix yourself.

## Step-by-step outline (for the implementation plan)
1. Create `03-ai-structured-filter` by copying `04-local-ai-filter`'s main sources,
   then apply module-02-style naming:
   - Rename `LocalAiFilterApplication` → `AiStructuredFilterApplication`.
   - Introduce interface `ai/CustomerSearchAgent.java` (same contract as module 02:
     `Specification<Customer> resolveFilter(String naturalLanguageQuery)`); rename
     `CustomerSearchService` → `CustomerSearchStructuredOutputService`, implementing
     the interface (mirrors module 02's `CustomerSearchToolCallingService`).
     `CustomerListView` depends on the interface, not the concrete class.
   - Keep `ai/filter/{CustomerFilter,FilterNode,Operator,CustomerFilterSpecifications}`,
     `data/*`, `ui/CreditScoreIndicator`, `application.properties`, `data.sql`,
     frontend/CSS unchanged (copy as-is).
2. Port tests into `03-ai-structured-filter`:
   - Copy `OllamaTestSupport`, `LocalOllamaTests` unchanged (this repo's established
     per-module duplication pattern for Ollama IT infra).
   - Copy `CustomerFilterSpecificationsTest` unchanged.
   - Rename `CustomerSearchIT` → `CustomerSearchAgentIT` (mirrors module 02's naming),
     update it to autowire `CustomerSearchStructuredOutputService`; keep all 28
     existing test cases/tags as-is.
   - New: `ui/CustomerListViewBrowserlessTest.java` — mirrors module 02's, with an
     `@Primary` fake `CustomerSearchAgent` bean returning a fixed `Specification`
     built from a `FilterNode`/`CustomerFilter`; tests: typing narrows grid, blank
     query resets to all rows.
   - New: `ui/CustomerListViewBrowserlessIT.java` — mirrors module 02's, using the
     *same 5* natural-language queries as module 02's IT (Berlin customers,
     creditworthy customers, at-risk customers, customers since 2020, company name
     contains "Data") against real Ollama, so the two modules' `failsafe-reports`
     stay directly comparable.
3. `pom.xml` for `03-ai-structured-filter`: base on module 02's pom (artifactId
   `03-ai-structured-filter`; keep `browserless-test-spring`/`awaitility` test
   dependencies; `it-local-ollama` profile includes `CustomerSearchAgentIT` +
   `CustomerListViewBrowserlessIT`).
4. `README.md` for `03-ai-structured-filter`: new file, module-02-style, migrating
   the "filter tree structure" and "Ollama integration test architecture"
   explanations currently in the root README's 04-local-ai-filter sections, adapted
   to the new class names.
5. Create `04-ollama-benchmark/` as a plain, non-Maven directory:
   - Copy `BenchmarkLocalModels.java` there (flattened directly into the folder,
     not nested under `src/test/scripts`, since there is no Maven module structure
     to justify it).
   - Update its source-locating path/regex logic to find
     `CustomerSearchStructuredOutputService.java` under the new
     `03-ai-structured-filter/src/main/java/...` path (update all relative-path
     candidates it supports).
   - Do not carry over `src/test/docker/{Dockerfile,build-images.sh}` from module 04
     (currently unused, intentionally dropped).
   - New `README.md` in `04-ollama-benchmark/` migrating the root README's "Model
     benchmark" subsection (run instructions, model auto-discovery, report output,
     recorded results table).
6. Remove `04-local-ai-filter` entirely (superseded by 03 + the benchmark folder).
7. Root `pom.xml`: `<modules>` → `01-non-ai-filter`, `02-ai-agent-filter`,
   `03-ai-structured-filter` (drop `04-local-ai-filter`; `04-ollama-benchmark` is
   intentionally not listed — it is not a Maven module).
8. Root `README.md`: update the module table (03 replaces 04-local-ai-filter's row;
   add a short note that `04-ollama-benchmark` is a standalone script, not a
   module); remove the migrated 04-local-ai-filter-specific sections (now living in
   03's own README, per the "don't duplicate in root README" convention) and the
   "Model benchmark" subsection (now living in `04-ollama-benchmark/README.md`); fix
   all remaining path/module-name references.
9. `CLAUDE.md`: update the module table row (03 replaces 04's description; note
   that `04-ollama-benchmark` is a script directory, not a Spring Boot module, and
   therefore out of scope for `./mvnw verify -pl <module>`); update the
   "01 → 02 → 04 increase in complexity" note to "01 → 02 → 03"; update the
   `BenchmarkLocalModels.java` path reference in the Definition of Done section.
10. Grep the repo for any stray remaining references to `04-local-ai-filter` /
    `LocalAiFilterApplication` / `CustomerSearchService` (old name) and fix/remove
    them.

# Definition of Done (in addition to CLAUDE.md)
- `./mvnw verify` (whole repo) passes with the new module list (01, 02, 03); no
  references to `04-local-ai-filter` remain anywhere in the repo.
- `03-ai-structured-filter`'s unit tests (`CustomerFilterSpecificationsTest`,
  `CustomerListViewBrowserlessTest`) pass under plain `mvn test`.
- `03-ai-structured-filter`'s `CustomerSearchAgentIT` and
  `CustomerListViewBrowserlessIT` pass under `-Pit-local-ollama` if a local Ollama
  instance is reachable in this environment; otherwise document that they were
  skipped via the existing `assumeTrue`/reachability guard, not left broken.
- `04-ollama-benchmark/BenchmarkLocalModels.java` still locates and reads the
  production system prompt from its new path (03's service class) correctly —
  verify by running it against local Ollama if available, or at minimum by tracing
  the path logic.
- Root `README.md`, `CLAUDE.md`, and the new 03/04-ollama-benchmark READMEs are
  internally consistent (module table, run commands, paths) and free of outdated
  `04-local-ai-filter` mentions.
- Revise the README.md and CLAUDE.md files and update them to reflect the current
  status of the project. Delete any outdated information.

# Final Report
Summarize at the end: what was changed (per commit), which tests are green, open
points/decisions that I need to make.
