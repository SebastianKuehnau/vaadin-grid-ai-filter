# Turn the module-04 benchmark into a prompt-reliability eval (pass-rate over K runs, both AI approaches)

## Task

Extend `04-ollama-benchmark/BenchmarkLocalModels.java` from a single-shot accuracy benchmark into a
**prompt-reliability eval**, so that after editing a system prompt (in either AI module) you can
answer, quantitatively: *"does this prompt produce the correct filter with high probability, and did
any case regress?"* — and get that answer on a fast inner loop.

Three new capabilities:

1. **Per-case pass-rate over K runs.** Run every case `N` times (configurable, e.g. `--runs=N`) and
   report, per model, a per-case pass-rate (`passes/N`) plus an aggregate mean pass-rate — instead of
   today's single-shot `x/total passed`. This is what makes prompt reliability measurable and
   regressions visible (a case dropping from 100% to 70% across runs).
2. **Both AI approaches.** Evaluate both `02-ai-agent-filter`'s tool-calling path and
   `03-ai-structured-filter`'s structured-output path (today only structured output is covered), each
   driven by the **real system prompt extracted from that module's production source** so the eval
   cannot drift from what the app does. Selectable via a flag (e.g. `--approach=tool-calling|structured|both`).
   This lets you compare the reliability and speed of the two approaches on the same model, and tune
   either module's prompt against a measured baseline.
3. **Field-precise scoring for annotation tuning.** Each case carries an exact expected outcome, and
   scoring is field-precise: the correct field/parameter is set with the expected value, the fields
   that must stay empty are empty, and — where the model expresses it — the operator and `negate` are
   correct. Report a **per-field / per-parameter accuracy** breakdown (across the case suite × runs),
   so you can see exactly which annotation is weak (e.g. company-name queries leaking into `email`)
   and, after editing a `@ToolParam` / `@JsonPropertyDescription` description, whether that specific
   mapping improved and nothing else regressed. Value matching stays tolerant (case-insensitive
   substring); field placement, empty-fields, operator, and `negate` are scored exactly — because
   those are precisely what the annotation descriptions control.

Fast feedback is a first-class requirement: a documented **`--quick` subset** (a few representative
cases at a low run count) must give a seconds-scale check for the tight edit loop, while the full run
(all cases × `N`) is the considered verdict. (The per-commit fast layers — deterministic unit tests
and the lean LLM smoke ITs — live in `tasks/align-ai-integration-tests.md`; this eval is the
deliberately-run reliability layer.)

## Context

- **Affected component:** `04-ollama-benchmark/BenchmarkLocalModels.java` and
  `04-ollama-benchmark/README.md`. Module 04 is **not** a Maven module — it is a dependency-free,
  single-file Java program (JDK stdlib only), run with `java BenchmarkLocalModels.java`, no Maven, no
  JUnit, no Spring. This constraint is load-bearing and must be preserved.
- **Purpose:** the demo compares tool calling (02) vs structured output (03) on small local models;
  reliably tuning either prompt needs a pass-rate measurement, not a one-shot pass/fail.
- **Current behavior to build on (source of truth — verify before editing):**
  - Runs each case **once**; accuracy is `passed/totalCases` of that single pass.
  - Covers **only** `03`: extracts the system prompt from
    `../03-ai-structured-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java`
    and runs the same NL queries as `03`'s `CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT`.
  - Talks to Ollama at `OLLAMA_BASE_URL` (native `/api/chat`), auto-discovers tool-capable models;
    `--backend=mlx` uses an OpenAI-compatible MLX server. Existing flags: positional model names,
    `--backend`, `--base-url`, `--mode=schema`, `--think`, `--debug-raw`, `--help`.
  - Writes `benchmark-report-<timestamp>.md`/`.txt` in the working directory; prints to console.
  - For `02`'s tool-calling path, the production source to extract from is
    `../02-ai-agent-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchToolCallingService.java`.
- **What each approach exposes (the object to score) and the annotations being tuned:**
  - `02` tool calling: the model calls `searchCustomers(...)`, whose arguments map **1:1** into a
    `CustomerSearchCriteria` (per-field `List` values; `RevenueRange`; `CreditRating` enum). The
    descriptions under test are the method's `@Tool` description and each parameter's `@ToolParam`
    description (`CustomerSearchToolCallingService`). Field-precise scoring here = which `List`
    parameters were populated (with the expected value substring) and which stayed empty/null.
  - `03` structured output: the model returns a `CustomerFilter` = flat list of `Condition`s
    (`field`, `operator` (`Operator`), `values`, `negate`). The descriptions under test are the
    `@JsonClassDescription` / `@JsonPropertyDescription` on `CustomerFilter` / `Condition`. Field-
    precise scoring here = the expected `Condition`s (correct `field`, `operator`, `negate`, and a
    value containing the expected substring) are present, and no condition on a field that must stay
    unfiltered is emitted.
- **Dependency on `tasks/align-ai-integration-tests.md`:** that task defines the canonical, aligned
  shared case set across `02` and `03`. This eval's case list must mirror the **aligned** suites
  (shared cases + `03`'s `CustomerSearchAgentExtraIT`). **Precondition below makes this a hard gate:**
  run this task only after the alignment task has landed, so the cases match.

## Implementation frame

Decided design (do not re-open):

- **Scope:** both approaches (`02` tool calling + `03` structured output), the pass-rate-over-K
  capability, and field-precise scoring with per-field accuracy reporting — all in the existing
  single file.
- **Field-precise scoring rules (decided):** each case has one exact expected outcome. A case counts
  as passed for a run only if every expected field/parameter is correct AND every field/parameter
  that must stay unset is empty. Precision is at **field + operator + negate** granularity: for `03`,
  score the `field`, the `Operator`, and the `negate` flag of each expected condition and reject stray
  conditions on other fields; for `02`, score the exact set of populated `searchCustomers` parameters
  (and `RevenueRange` bounds / `CreditRating` enum) vs. those that must be null/empty. **Value**
  comparison stays tolerant (case-insensitive substring, as today); field/operator/negate/empty are
  exact. The same expected outcome drives both the pass-rate and the per-field accuracy tally.
- **Per-field accuracy reporting:** for each approach × model, report an accuracy (across suite × `N`
  runs) per field/parameter — the tuning readout that localizes a weak annotation.
- **ITs stay tolerant; no contradiction.** The `align-ai-integration-tests` ITs remain
  presence-only/tolerant (stable smoke); this eval holds the strict, negative-inclusive expectations.
  The eval's per-case expected outcome must be consistent with (a strict refinement of) the tolerant
  IT assertion for the same query — never contradict it.
- **Stay dependency-free:** no new libraries, no Maven-ization, no JUnit/Spring. If reproducing `02`'s
  tool-calling handshake faithfully would require adding a dependency, **stop and report** (see plan
  gate) — do not add one.
- **No drift:** each approach's prompt (and, for tool calling, its tool/argument schema) is extracted
  from that module's production source at runtime, exactly as `03`'s prompt is extracted today; the
  eval never hard-codes a copy of a prompt.
- **Preserve the LLM-run properties (root `CLAUDE.md` conventions):** models discovered via the Ollama
  API; runs are **sequential** (GPU contention — do not parallelize); console output also captured to
  a log file; results written to **date-stamped** files (idempotent re-runs).
- **Backward compatibility:** existing invocations keep working (`--mode=schema`, `--backend=mlx`,
  positional model names, `--help`). If a default changes (e.g. `--runs` default), document it in
  `--help` and the README.

Open question for the plan gate (mechanism only — the *scope* decision that both approaches are in is
already made): the exact way to reproduce `02`'s tool-calling flow over the dependency-free HTTP
client (Ollama `/api/chat` `tools` field + tool-call parsing, and how the tool arguments map to the
comparable expected-outcome check). The plan must show this concretely before implementation; if it
proves infeasible without new dependencies, that is a blocker to surface, not to work around.

Boundaries / out of scope:

- No changes to production/AI source in `02`/`03`, to prompts, or to `data.sql`.
- No committing of generated reports/logs (see artifact policy).
- Cross-run regression *comparison* tooling (diffing two reports automatically) is a nice-to-have, not
  required; per-case pass-rate in each date-stamped report is the required artifact.

## Procedure

1. **Plan first.** Present a plan covering: the pass-rate data model and report layout (per model ×
   approach: per-case pass-rate table + aggregate + existing timing metrics); the flags to add
   (`--runs`, `--approach`, `--quick`, `--min-pass-rate`) with defaults; the `--quick` subset; and —
   answering the open question above — the concrete mechanism for the `02` tool-calling path and how
   its prompt/tool schema is extracted from source. **STOP and wait for explicit approval before
   changing any file** (root `CLAUDE.md` plan-approval gate).
2. Implement in increments, committing after each verified step (Conventional Commits, no push):
   suggested split — (a) pass-rate-over-K + report + flags for the existing `03` path, (b) add the
   `02` tool-calling approach, (c) `--quick` and `--min-pass-rate`, (d) README.
3. Verify against the Definition of Done; iterate autonomously on failures.
4. Update `04-ollama-benchmark/README.md`: document `--runs`, `--approach`, `--quick`,
   `--min-pass-rate`, the pass-rate semantics, and that cases mirror the aligned ITs.

## Definition of Done

**Hard preconditions (check first; abort and report if unmet — not conditional DoD items):**
(a) The `tasks/align-ai-integration-tests.md` alignment has landed on the branch point, so a single
canonical shared case set exists to mirror. (b) A native Ollama instance is reachable at
`OLLAMA_BASE_URL` with at least one tool-capable model present, verified via `GET /api/tags`.

- **Still dependency-free and runnable:** `java BenchmarkLocalModels.java --help` runs with no build
  step and no external dependency, and lists the new flags. No `pom.xml` is introduced; module 04
  remains absent from the root `<modules>`.
- **Per-case pass-rate:** `java BenchmarkLocalModels.java --runs=3 --quick <one-model>` produces a
  report (console + date-stamped file) showing, per case, a pass-rate of the form `passes/3`, plus an
  aggregate mean pass-rate for the model — not a single pass/fail.
- **Both approaches:** a run with `--approach=both` (or two runs, one per approach) evaluates the
  tool-calling and structured-output paths, each labeled distinctly in the report, each using the
  prompt extracted from its own production source. A log line (or `--debug-raw` output) names the
  source file each prompt was extracted from, proving no hard-coded copy.
- **Case set mirrors the aligned ITs:** the eval's shared case list matches the aligned
  `CustomerSearchAgentIT` set (+ `03`'s `ExtraIT`); the execution run states how this correspondence
  was verified (e.g. the query strings match those extracted from the ITs).
- **Field-precise scoring works:** for at least one case where a wrong-field mapping is plausible
  (e.g. a company-name query that could leak into `email`/`contactName`), the report demonstrably
  distinguishes "right field set" from "wrong field also set" — i.e. a run where the model populates
  an unexpected field is scored as a failure for that case, not a pass. Value matching remains
  tolerant (substring), field/operator/negate/empty are exact.
- **Per-field accuracy report:** the report includes, per approach × model, an accuracy figure per
  field/parameter across the suite × runs (the annotation-tuning readout), not only an aggregate.
- **Scriptable gate:** `--min-pass-rate=<X>` makes the program exit non-zero when the measured
  aggregate (or any case, as documented) falls below `X`, and exit zero otherwise — demonstrated with
  one deliberately-high and one deliberately-low threshold on the same `--quick --runs=3` data.
- **Fast subset:** `--quick` runs a documented small subset; the run completes markedly faster than
  the full set (state both case counts and wall-clock in the final report).
- **Sequential + idempotent:** runs remain sequential; each invocation writes a fresh
  `benchmark-report-<timestamp>.md`/`.txt`; console output is also captured to a log file.
- **No regression in existing behavior:** `--mode=schema`, `--backend=mlx`, and positional
  model-name selection still work (verify `--help` plus one `--quick` run of the existing `03` path).

**Artifact policy:** only the changes to `BenchmarkLocalModels.java` and `04-ollama-benchmark/README.md`
are committed. All `benchmark-report-*` files and run logs are generated artifacts — gitignored, never
committed. Verify the staged file list (`git status`) before every commit.

## Final report

Provide, at completion:

- What changed, per commit (Conventional Commits messages).
- The new flags and their defaults, and the `--quick` subset (which cases, why representative).
- A sample report excerpt from a `--quick --runs=3` run on one small model, showing per-case
  pass-rate, aggregate, the per-field/per-parameter accuracy breakdown, and both approaches labeled;
  plus the case count + wall-clock for `--quick` vs the full set.
- A short worked example of the field-precise scoring: one case where a wrong-field mapping is
  penalized (right field set vs. an unexpected field also set), showing how the readout would guide a
  `@ToolParam` / `@JsonPropertyDescription` edit.
- Proof of the anti-drift extraction (the source files each prompt was read from) and how the case
  set was confirmed to match the aligned ITs.
- The chosen mechanism for the `02` tool-calling path, and — if any part could not be done
  dependency-free — exactly what was blocked (decision left to the maintainer, not worked around).
