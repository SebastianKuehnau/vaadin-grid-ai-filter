# Task
Build a benchmark that compares different local Ollama models via the **existing
integration tests** — as a replacement for the separate test logic in
`benchmark_models.py`. The ITs are the single source of truth; the benchmark runs them
per model and evaluates the results.

# Context
- Module: `04-local-ai-filter` (contains `CustomerSearchServiceLocalOllamaIT` and
  the provider-agnostic `CustomerSearchIT`).
- Ollama runs on the host: `http://host.docker.internal:11434`. Check at the beginning via
  `GET /api/tags` which models are available, and use these as the candidate list.
  If fewer than two models are available: abort and notify me, rather than guessing models.
- Purpose: Demo/talk material — the evaluation should be presentable as a table
  ("which local model filters how reliably and how fast?").

# Implementation (framework, details belong in your plan)
1. Make the model parametrizable in the ITs: a property `benchmark.model`
   (System property/env), which overrides `spring.ai.ollama.chat.options.model`.
   Without the property set, the previous default behavior remains exactly as is —
   the ITs must continue to run green standalone.
2. Build a runner (Bash or Python, without heavy new dependencies) under
   `04-local-ai-filter/src/test/scripts/benchmark_it.<ext>`, which:
   - iterates over the model list and per model executes
     `./mvnw verify -pl 04-local-ai-filter -Dit.test=CustomerSearchServiceLocalOllamaIT -Dbenchmark.model=<model>`
     (one run per model, no parallelism — models would otherwise compete
     for the GPU and runtimes would be meaningless),
   - backs up and evaluates the Failsafe reports (`target/failsafe-reports/*.xml`) after each run:
     passed/failed per test method + runtime,
   - generates a report `benchmark-results/<date>-report.md`:
     table Model × Test case (✅/❌, seconds) plus summary row
     (hit rate, total runtime per model),
   - additionally stores raw data as JSON (`benchmark-results/<date>-raw.json`).
3. Redirect Maven/test console output to log files (`benchmark-results/logs/`)
   instead of flooding the terminal. Report progress to me only in compact form
   (one status per model run).
4. Mark `benchmark_models.py` as deprecated (comment header with reference to
   the new runner). Don't delete — I'll decide after review.
5. Document the benchmark briefly in the module's README (prerequisites, invocation,
   where results end up).

# Approach
1. First create a plan (Plan Mode) and show it to me. Wait for my OK.
2. Implement; commit after each verified step (Conventional Commits,
   no push). Logical steps: (a) Parametrization, (b) Runner + evaluation,
   (c) Docs + deprecation.
3. Verify autonomously and iterate on errors independently before
   reporting completion.

# Definition of Done (in addition to CLAUDE.md)
- `./mvnw verify -pl 04-local-ai-filter` runs green WITHOUT `benchmark.model` set,
  unchanged behavior (no behavioral drift of existing ITs).
- A complete benchmark run over at least two real available models has
  completed; the Markdown report exists and the numbers match the
  Failsafe XMLs (spot check documented in report).
- A failing test case does NOT abort the benchmark, but appears
  as ❌ in the report (Failsafe exit code handled appropriately).
- The runner is idempotent: repeated invocation doesn't overwrite anything, but creates
  a new date-stamped report.

# Final Report
Summarize: commits with one-liner, link/path to generated report including the
results table, anomalies in the models (e.g., test cases that only fail with
certain models), open decisions (e.g., permanently remove benchmark_models.py?).
