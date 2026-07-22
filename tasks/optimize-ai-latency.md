# Speed up AI filtering in modules 02 and 03 via non-prompt (config + structured-output) levers

## Task

Reduce the per-query latency of the AI filtering in `02-ai-agent-filter` and `03-ai-structured-filter`
**without touching the prompts** and **without losing correctness**. A companion investigation
(`tasks/improve-ai-prompts.md`) established that the prompts cannot buy speed — prompt prefill is only
~5 % of latency and injecting output-compaction text into the system prompt breaks Spring AI's
structured-output parsing. This task pursues the remaining, genuinely effective levers, which are
**configuration and a small, localized change to how the structured-output call is made** — never the
prompt text or the description annotations.

Primary lever (decided, see Implementation frame): switch module `03`'s structured-output call from
free-form JSON completion (Spring AI `.entity()` default) to **native grammar-constrained /
schema-constrained decoding** on the Ollama backend (the model is handed the JSON Schema via Ollama's
`format` field), which forces compact, valid JSON — cutting output tokens (the ~90 % cost driver) and
eliminating the malformed-JSON failures seen when compaction was attempted from the prompt. Secondary
config levers (`num_predict` cap, `keep_alive`) are evaluated and applied where they measurably help.

Success is **measured**: latency/output-token reduction on the module-04 eval with the per-case
pass-rate held at 100 % on the configured `qwen3:8b`, and the integration tests green on both
providers.

## Context

- **Affected modules:** `02-ai-agent-filter` (tool calling) and `03-ai-structured-filter` (structured
  output); same `Customer` domain, standalone Spring Boot apps. `04-ollama-benchmark` is the
  measurement tool (not a Maven module). Provider is chosen by Spring profile (`openai` /`ollama`).
- **Purpose:** these modules back conference talks contrasting the two AI approaches on small local
  models; a faster response at equal correctness is directly demo-relevant.
- **Measured baseline (this box, `qwen3:8b`, module-04 eval `--approach=both --runs=5`, full 36-case
  set — re-measure at task start, do not trust these stale numbers):** structured ≈ 1115 ms median,
  TTFT ≈ 280 ms, ≈ 31 tok/s; tool-calling ≈ 1043 ms median. Direct timing of the `03` prompt showed
  **prefill ≈ 5 % / generation ≈ 90 %** of latency, and the raw structured output was pretty-printed,
  fenced JSON (≈ 80 output tokens for a 2-condition filter) — i.e. output tokens, not prompt size, are
  the cost.
- **Why the prompt route is closed (do not retry it):** a "compact/minified JSON" directive in `03`'s
  system prompt conflicts with Spring AI's `.entity()` `BeanOutputConverter` format instruction and
  produced malformed JSON (`StreamReadException: Unexpected close marker '}'`) → 3/16 real-app IT
  regressions. See `tasks/improve-ai-prompts.md`. The fix is to control the output format at the
  **decoding layer** (grammar constraint), not via prose.
- **Files in scope (verify each exists before editing):**
  - `03-ai-structured-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java`
    — the `.entity(CustomerFilter.class)` call to convert to schema-constrained output.
  - `03-.../ai/filter/CustomerFilter.java`, `Condition.java`, `Operator.java` — the shape whose JSON
    Schema is handed to Ollama's `format` (read-only reference; **do not** change their structure).
  - `03-ai-structured-filter/src/main/resources/application-ollama.properties` and
    `02-ai-agent-filter/src/main/resources/application-ollama.properties` — where `num_predict`,
    `keep_alive`, and (if used as a knob) the model are configured.
  - `02-ai-agent-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchToolCallingService.java`
    — the tool-calling call (its output is protocol-structured; expect little headroom — measure, do
    not force a change).
- **Measurement + verification infrastructure (already implemented):**
  - `04-ollama-benchmark/BenchmarkLocalModels.java` — `--approach=both --runs=5`, reports median
    latency, TTFT, tok/s and per-case pass-rate. It **already has a `--mode=schema`** path that sends a
    grammar-constrained JSON Schema via Ollama's native `format` field — use it as the reference
    implementation / A-B comparison for the in-app change, and as the speed+correctness measurement.
  - `CustomerSearchAgentIT` (02: 16 cases, 03: 18 cases incl. no-criteria) + `03`'s
    `CustomerSearchAgentExtraIT`, run via `-Pit-local-ollama`. Correctness gate.
  - Provider profiles: `qwen3:8b` (ollama, temperature 0, num-ctx 4096, think=false) and
    `gpt-5.4-mini` (openai). The ITs now route to the profile's model via
    `spring.ai.model.chat=${AI_TEST_PROFILE:ollama}` in each module's test `application.properties`
    (fixed in the improve-ai-prompts task), so `-DAI_TEST_PROFILE=openai` genuinely targets OpenAI.

## Implementation frame

Decided design (do not re-open):

- **Prompts are frozen.** No change to any system prompt, `@Tool`/`@ToolParam` description,
  `@JsonClassDescription`/`@JsonPropertyDescription`, or few-shot example. This task is
  config + decoding-mechanism only.
- **Primary lever — schema-constrained structured output for `03` on Ollama.** Make the
  structured-output call hand the `CustomerFilter` JSON Schema to Ollama's native `format` field
  (grammar-constrained decoding) instead of relying on free-form completion + `BeanOutputConverter`
  parsing. Prefer the Spring AI-supported configuration path (e.g. Ollama chat options `format`) over
  bespoke HTTP code; keep the change localized to the service (and properties). The `CustomerFilter`
  record/`Condition`/`Operator` **structure is not changed** — only how its schema is enforced at
  decode time. This must remain a no-op for the `openai` profile (OpenAI has its own structured-output
  path; do not break it) — degrade gracefully per provider.
- **Secondary config levers (apply only if they measurably help and don't regress correctness):**
  a bounded `spring.ai.ollama.chat.options.num-predict` cap (prevent runaway generation), and
  `keep_alive` tuning (avoid model reload latency between calls). Applied identically in both modules
  where relevant (root `CLAUDE.md` cross-module consistency rule).
- **Out of scope / decided against:**
  - **Streaming** — the filter must be complete before it can be applied to the grid (you cannot apply
    a half-parsed `CustomerFilter`/tool call), so streaming would only change perceived, not actual,
    latency and adds view-layer complexity. Not pursued.
  - **Swapping the default model** to a smaller one — that changes the demo's headline model and is a
    talk-level decision, not a code task. Leave `qwen3:8b` as the configured default; the model stays a
    one-line knob. (If the executor wants to *report* a measured latency/quality trade-off for a
    smaller model as information, that is fine, but do not change the committed default.)
  - No prompt edits; no `CustomerFilter`/`Condition`/`Operator` structure change; no `data.sql` change;
    no Spring AI / framework version change (pinned on purpose); no changes to the eval/IT case sets.
- **Both approaches measured.** Run the eval for `--approach=both`. Expect the real win in `03`
  (structured); `02` tool-calling output is protocol-bound — if no config lever measurably helps it,
  say so and leave it unchanged.

Open questions for the plan gate (mechanism only — scope above is settled):

- The exact Spring AI API/config to enable Ollama grammar-constrained `format` output for the
  `.entity()` call in this pinned Spring AI version (config property vs. `OllamaOptions.format` in
  code), and how it composes with `.entity(CustomerFilter.class)` parsing. The plan must show the
  concrete mechanism (mirroring module-04's `--mode=schema`) before editing; if it cannot be done in
  the pinned version without new dependencies, **stop and report** rather than work around it.
- Whether `num_predict`/`keep_alive` earn their place (present the baseline-vs-candidate numbers at the
  plan gate; drop any lever that doesn't measurably help).

## Procedure

1. **Precondition check first (hard gate — abort and report if unmet, not a conditional DoD):**
   (a) native Ollama reachable at `OLLAMA_BASE_URL` with `qwen3:8b` present (`GET /api/tags`);
   (b) `OPENAI_API_KEY` set to a real key and `api.openai.com` reachable (for the OpenAI correctness
   gate).
2. **Baseline + plan (plan-approval gate).** Capture a fresh baseline eval (`--approach=both --runs=5`,
   `qwen3:8b`; date-stamped report, gitignored) and, for `03`, an A-B of module-04
   `--mode=freeform` vs `--mode=schema` to quantify the expected schema-constrained win. Present a plan
   with: the concrete Spring AI mechanism for in-app schema-constrained output, the secondary levers
   with their measured deltas, and the target numbers. **STOP and wait for explicit approval before
   changing any file** (root `CLAUDE.md` plan gate).
3. **Mid-task gate (explicit):** after the `03` structured-output change, re-run the eval
   (`--approach=both --runs=5`) and the `03` ITs on **both** providers; report the latency delta and
   confirm pass-rate is still 100% before touching the secondary config levers or `02`. A wrong
   decoding change is expensive to unwind later.
4. Implement in increments, committing after each verified step (Conventional Commits, no push);
   suggested split — (a) `03` schema-constrained structured output, (b) `num_predict`/`keep_alive`
   config (both modules) if they help, (c) README updates where behaviour/config claims change.
5. Update `02`/`03` `README.md` where a config/behaviour statement becomes inaccurate; do not otherwise
   rewrite them.

## Definition of Done

**Hard preconditions (checked in step 1; abort and report if unmet — not conditional DoD items):**
Ollama reachable with `qwen3:8b`; `OPENAI_API_KEY` set to a real key with `api.openai.com` reachable.

- **Build green:** `./mvnw verify -pl 02-ai-agent-filter,03-ai-structured-filter` → BUILD SUCCESS.
- **Measured latency improvement, correctness held (quantitative bar; Ollama `qwen3:8b`):** a post-change
  module-04 eval (`--approach=both --runs=5`, same command as the baseline) shows the `03` structured
  approach with **lower median latency AND fewer output tokens/faster tok/s than baseline** (state the
  %; target ≥ 15 % on at least one of median-latency or output-tokens), while its **per-case pass-rate
  stays 100 %** and no case regresses. `02` tool-calling latency does not regress. Baseline and after
  numbers are quoted in the final report; both reports are gitignored artifacts (cited, not committed).
- **No parse regressions:** the malformed-JSON failure mode from the prompt experiment does not recur —
  `03`'s `requestFilter` logs no `Could not turn query into a filter` for any eval/IT case in the
  after-runs.
- **ITs green on both providers (single green run each, per repo philosophy):**
  - Ollama: `./mvnw -pl 02-ai-agent-filter -Pit-local-ollama -DAI_TEST_PROFILE=ollama` and the same for
    `03` — `CustomerSearchAgentIT` (+ `03` `CustomerSearchAgentExtraIT`) + `CustomerListViewBrowserlessIT`
    pass in one green run per module.
  - OpenAI: the same two commands with `-DAI_TEST_PROFILE=openai` pass in one green run per module
    (confirming the `03` decoding change degrades gracefully off-Ollama).
- **Config levers justified:** any `num_predict`/`keep_alive` change is backed by a before/after number
  in the final report; levers that don't measurably help are not committed. Model default stays
  `qwen3:8b` in both modules.
- **Prompts untouched (guard):** `git diff` shows no change to any system prompt string,
  `@Tool`/`@ToolParam`, or `@Json*Description` annotation, and no change to `CustomerFilter`/`Condition`/
  `Operator` structure or `data.sql`.

**Artifact policy:** only source/config/README changes are committed. All `benchmark-report-*` files
and IT/eval logs are generated artifacts — gitignored, never committed. Verify the staged file list
(`git status`) before every commit.

## Related lever: prompt readability / de-duplication (NOT a latency or correctness win)

Separate from latency, the tool-calling prompt carries redundant per-parameter prose that can be
de-duplicated for clarity (project priority: *presentable, clarity beats cleverness*). This is **not**
sold as faster or more correct — prompt prefill is ~5 % of latency and the configured model is already
at 100 % — only as readability, and it must be proven **behaviour-neutral**.

- **Already done in `02`** (committed on `feature/improve-ai-prompts`): the eight text `@ToolParam`
  descriptions in `CustomerSearchToolCallingService` each repeated ", part of each to match, or null";
  since that shared rule is already stated once in the `@Tool` description, each was trimmed to just
  name its field. **Reliability-critical** descriptions (JSON-array wrapping — added deliberately in
  commit `66e18d6`; phone E.164; day-first dates; credit-rating enum; revenue ranges) were left
  untouched. Verified neutral: tool-calling pass-rate 100 % on `qwen3:8b` **and** `llama3.1:8b`
  (eval, runs=5) and `CustomerSearchAgentIT` 16/16 on both Ollama and OpenAI.
- **`03` — attempted and reverted (no safe redundancy).** The one plausible candidate — the
  `negate` re-explanation in the text-fields rule, seemingly duplicated by the "Building the conditions
  list" bullet and the dedicated example — turned out to be **load-bearing**: removing it regressed
  `contactNameAndCity`(+`_German`) to 0/5 on `qwen3:8b` and the `notInCityWithRevenueAndYear`
  negation cases to 0/5 on `granite3.2:8b` (eval, `--approach=structured --runs=5`). That example
  doubles as the canonical `field=city` / `CONTAINS` demonstration *and* as negation reinforcement for
  weaker models. Reverted byte-exact, not committed. Lesson: `03`'s repetition is deliberate
  reinforcement, not redundancy — do not retry shortening it.
- **Guard for any such change:** it is a readability refactor, so verify it is behaviour-neutral the
  same way — eval pass-rate stays 100 % on `qwen3:8b` (both approaches) **and** on at least one weaker
  tool-capable model (so removed verbosity isn't silently propping up weak-model robustness), plus ITs
  green on both providers. Do **not** touch prompt content that encodes a reliability fix.

## Final report

Provide, at completion:

- What changed, per commit (Conventional Commits), separating the `03` decoding change from the config
  levers.
- Baseline vs. after eval numbers (`--approach=both --runs=5`, `qwen3:8b`): median latency, output
  tokens / tok/s, TTFT, and per-case pass-rate for both approaches — proving the latency win and the
  held 100 % correctness. Include the module-04 `--mode=freeform` vs `--mode=schema` A-B that motivated
  the change.
- The concrete Spring AI mechanism used for Ollama schema-constrained output, and confirmation it is a
  no-op / graceful on the `openai` profile.
- Two-provider IT results: one green run each for `02` and `03` under both `AI_TEST_PROFILE=ollama` and
  `AI_TEST_PROFILE=openai`, with the models used.
- Which secondary levers were kept vs. dropped, each with its measured justification.
- **Decisions left to the maintainer (flag, do not action):** whether to change the default local
  model for a further speed/quality trade-off (report the measured trade-off if explored), and the
  wider `AI_TEST_PROFILE` default-profile question tracked by `tasks/align-ai-integration-tests.md`.
