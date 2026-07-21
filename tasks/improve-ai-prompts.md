# Improve the AI prompts of modules 02 and 03 — findings & outcome

> **Status: closed.** The original goal was to improve the AI prompts of `02-ai-agent-filter` and
> `03-ai-structured-filter` to raise **AI-interaction performance and correctness**. Investigation
> showed correctness is already saturated on the configured model and that a *prompt-driven* speed
> gain is not safely achievable. What shipped is a set of safe hygiene/doc fixes plus this recorded
> finding. This document is the outcome record, not a forward-looking plan.

## Original goal

Make natural-language → filter translation more reliable and/or faster by editing only the prompts:
`02`'s `SYSTEM_PROMPT` + `@Tool`/`@ToolParam` descriptions, and `03`'s `systemPrompt(LocalDate)` +
`@JsonClassDescription`/`@JsonPropertyDescription` annotations. Measured with the module-04 eval
(`04-ollama-benchmark/BenchmarkLocalModels.java`, `--approach=both --runs=5`) on the configured local
model `qwen3:8b`, plus the `-Pit-local-ollama` integration tests as the real-app correctness gate.

## Findings

1. **Correctness is already saturated.** Baseline module-04 eval (`--approach=both --runs=5`,
   `qwen3:8b`, full 36-case set): **100 % mean pass-rate for both approaches**, 100 % per-field
   accuracy. There is no weak case/field to improve against on the configured model. (Weaker models
   do have headroom — e.g. `granite3.2:8b` scored 0 % on the tool-calling quick subset — but the demo
   ships `qwen3:8b`.)

2. **Prompt trimming cannot buy speed.** Direct Ollama timing of the real `03` system prompt: prompt
   **prefill ≈ 5 % of latency** (≈140 ms of ≈2.9 s), output **generation ≈ 90 %**. Shortening the
   prompt touches only the ~5 % prefill slice.

3. **Output compaction (the only real speed lever) is framework-owned and unsafe from the prompt.**
   Generation cost scales with output tokens. A "compact/minified JSON, no code fences" directive in
   `03`'s system prompt cut output tokens/latency by ~33–38 % in an isolated micro-probe, **but in the
   real Spring AI app it conflicts with the `.entity()` `BeanOutputConverter`'s own format
   instruction**, producing malformed JSON (a stray closing `}`) → `CustomerSearchAgentIT` regressed
   **3/16** (`creditworthyCustomers`, `country`, `customerSinceYear`) with
   `StreamReadException: Unexpected close marker '}'`. The directive was reverted. For `02`
   tool-calling the model output is protocol-structured (Ollama tool-call args), so there is no
   prompt-level output lever at all.

   **Conclusion:** per-query latency here is generation-bound, and output format is controlled by the
   framework (Spring AI structured output / Ollama tool protocol), not by the system prompt — so there
   is no safe prompt-only speed improvement. Real speed levers (native schema-constrained decoding,
   `num_predict` cap, a smaller/faster model, streaming) are code/config changes, out of this
   prompt-only scope. **Maintainer decision (recorded): leave speed as-is** — `qwen3:8b` is already
   fast and 100 % correct.

## What shipped (branch `feature/improve-ai-prompts`, not pushed)

- **`chore`** — allow `api.openai.com` through the project firewall (`.devcontainer/allowed-domains.conf`),
  so the `openai` profile can be reached at all.
- **`refactor(03)`** — remove the no-op `.options(ChatOptions.builder())` call (an unbuilt builder that
  set nothing) and its misleading "low temperature" comment in `CustomerSearchStructuredOutputService`;
  drop the now-unused `ChatOptions` import. Temperature (0) is configured per active profile in
  `application-<provider>.properties`, and the comment now says so. Behaviour-neutral.
- **`docs(02,03)`** — correct the model-name drift: both modules configure
  `spring.ai.ollama.chat.model=qwen3:8b`, so the READMEs now name `qwen3:8b` as the configured default
  (pull command, `think=false` / capability notes) and keep `llama3.1:8b` only as a justified
  weaker-model comparison.

The compact-output prompt experiment left **no trace** in the tree (reverted before commit).

## Verification

- `./mvnw verify -pl 02-ai-agent-filter,03-ai-structured-filter` → BUILD SUCCESS (unit tests 30/30 in
  `03`, `02` green).
- `03` `CustomerSearchAgentIT` vs native Ollama (`qwen3:8b`,
  `-Pit-local-ollama -DAI_TEST_PROFILE=ollama`): **18/18 green** with the shipped changes.
- `04` baseline eval report is a generated artifact (`benchmark-report-*`, gitignored) — cited above,
  not committed.

## Flagged for the maintainer (out of scope here)

- **OpenAI cross-check via the ITs is not operable as configured.** `03`'s test
  `src/test/resources/application.properties` pins `spring.ai.model.chat=ollama` unconditionally, so
  `-DAI_TEST_PROFILE=openai` still binds the Ollama `ChatModel` (and hits `localhost:11434`). This is
  the same profile-default inconsistency already tracked by `tasks/align-ai-integration-tests.md`;
  fixing it (so the openai profile actually selects the OpenAI model in tests) belongs there, not in a
  prompt task. The shipped `03` change is a behaviour-neutral no-op removal, already verified green on
  the real Ollama path, so this gap does not affect it.
- If speed is pursued later, do it via the non-prompt levers listed under Findings §3 — a separate,
  code-scoped task.
