# Harden the filter-test assertions: penalize over-generation and match numbers exactly, plus add robustness cases

## Task

The natural-language-to-filter checks in `04-ollama-benchmark` and `03-ai-structured-filter`'s
integration tests are **too lenient in two specific ways**, which lets wrong model output score as
correct. Close those two gaps with *targeted* strictness (keeping the intentional tolerance for LLM
non-determinism everywhere else), and add a few robustness cases:

1. **Over-generation is not penalized.** Scoring only checks that every *expected* condition is
   present; it never checks that *no extra* condition was emitted. A model that hallucinates an
   additional filter (e.g. adds an `annualRevenue` condition to "customers in Berlin") still passes.
   Add an opt-in, per-case "no conditions on fields other than these" guard and apply it to a curated
   set of cases where extra fields are unambiguously wrong.
2. **Numeric values are matched by substring.** A required value `"1000"` is accepted when the model
   returns `"1000000"` (substring `contains`). Add exact numeric matching and apply it to pure integer
   fields (`annualRevenue`) only — **date/year fields stay tolerant**, since models legitimately emit
   them as `"2020-01-01"` and stripping to a number there would reject valid answers.
3. **Add robustness / anti-hallucination cases** (mirrored in both the benchmark and the `03` ITs):
   - a small-talk query that must yield an **empty** filter,
   - an unrelated/off-topic query that must yield an **empty** filter,
   - an "exactly N" revenue query that exercises `EQUALS` on a numeric field, exact-value matching,
     and the no-extra-fields guard together.

Deliberately **not** included: typo/misspelling cases — the "correct" behavior is ambiguous (a model
may auto-correct) and would make the ITs flaky.

## Context

- **Affected components:**
  - `04-ollama-benchmark/BenchmarkLocalModels.java` — a dependency-free, single-file Java program
    (JDK stdlib only), run with `java BenchmarkLocalModels.java`; **not** a Maven module, not in the
    root `<modules>`. This constraint is load-bearing and must be preserved (no `pom.xml`, no JUnit,
    no new dependencies).
  - `04-ollama-benchmark/README.md`.
  - `03-ai-structured-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchAgentIT.java`
    and `.../CustomerSearchAgentExtraIT.java`.
- **Purpose:** these suites are how prompt/model changes are judged in the demo. The two leniencies
  above mean a regression (spurious field, wrong-magnitude number) can pass silently, which
  undermines the benchmark's and the ITs' value as a signal.
- **Current behavior to build on (source of truth — verify before editing):**
  - Benchmark scoring lives in `BenchmarkLocalModels.caseCorrect(...)` / `matches(...)`: for a
    non-empty expectation it requires `expected.allMatch(e -> matches(criteria, e))`; `matches`
    compares field (case-insensitive), an optional set of acceptable operators, and value via
    case-insensitive `contains`. Empty-expectation cases require `criteria.isEmpty()`. Test cases are
    built in `testCases(LocalDate today)` via `TestCase.of(...)` / `ExpectedCriterion.of(...)`.
  - The `03` ITs mirror this tolerance deliberately (see the class Javadoc in `CustomerSearchAgentIT`:
    "Assertions are tolerant … only check that the expected condition is present *somewhere* … ignoring
    operator and extras"). Matching is done by `hasCondition(...)` over `flatten(...)`; empty-filter
    cases assert `flatten(filter)).isEmpty()`. A negated condition's operator is flattened to a
    synthetic `NOT_` prefix (`NOT_EQUALS`/`NOT_CONTAINS`) in both places.
  - Production filter shape: `CustomerFilter` = flat list of `Condition`(`field`, `operator`,
    `values`, `negate`); `Operator` enum = `CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL,
    STARTS_WITH, ENDS_WITH` (negation is the `negate` flag, not an operator).
  - `02-ai-agent-filter`'s `CustomerSearchAgentIT` shares wording with several `03` cases for
    comparability, but its flat `CustomerSearchCriteria` model **cannot express** negation / exact
    operator precision — so the new strict behavior is **out of scope for `02`** (see boundaries).
- **Relationship to `tasks/prompt-reliability-eval.md`:** that (larger) task also touches benchmark
  scoring (pass-rate over K runs, field-precise scoring for both approaches). This task is the narrow
  precursor: harden the *existing* single-shot tolerant matching and the `03` ITs. The execution run
  must **re-read the current state of `BenchmarkLocalModels.java` first** and layer these changes onto
  whatever scoring code is actually present, without duplicating or fighting that task's mechanisms.

## Implementation frame

Decided design (do not re-open):

- **Scope:** `04-ollama-benchmark` **and** the `03` ITs, kept mutually consistent (same query strings,
  same intent). `02` is **not** modified (its model can't express the strict conditions).
- **Strictness is targeted, not global.** The default tolerant matching (case-insensitive value
  substring, operator flexibility, extras ignored) stays the default for existing cases. The two new
  strict behaviors are **opt-in per case/criterion**:
  - *No-extra-fields guard:* an optional per-case allow-list of field names. When set, a case fails if
    the model emits any condition on a field outside the list. When unset, behavior is unchanged.
  - *Exact numeric value:* an optional per-criterion flag. When set, the value must parse to the same
    number as expected (formatting/currency/thousands-separators tolerated); when unset, the current
    case-insensitive substring match is used.
- **Numeric-exact applies to integer fields only** (`annualRevenue`). Do **not** apply it to
  `customerSince` / `lastOrderDate` or any date/year value.
- **Curated cases to make strict** (apply the no-extra-fields guard, and numeric-exact on revenue
  values): the single-/two-field, unambiguous cases — e.g. `singleCity`, `multipleCities`,
  `contactName*`, `contactNameAndCity`, `phoneNumberContains`, `country`, `email*`,
  `companyNameStartsWith`, `creditworthyInCity`, `citiesAndRevenue*`, `citiesWithRevenueRange`,
  `notInCityWithRevenueRange*`, `notInCityWithRevenueAndYear*`. Leave genuinely ambiguous or
  free-form cases tolerant. The execution run decides the final per-case list and states it.
- **New cases (identical wording in benchmark and `03` ITs):**
  - `smalltalk_noCriteria` → expects an empty filter.
  - `unrelatedRequest_noCriteria` → expects an empty filter.
  - `revenueExact_notOverGenerated` → "exactly 100000" → single `annualRevenue` condition, exact
    value, no other fields. Exact query strings are the execution run's call; keep them short and
    unambiguous.
- **The ITs' tolerance contract is refined, not contradicted.** Every strict IT assertion must be a
  strict refinement of what the tolerant assertion for the same query would accept. Update the
  `CustomerSearchAgentIT` class Javadoc to describe the new, deliberately-stricter checks (tolerance
  remains the default for the other cases).
- **Stay dependency-free** in module 04: no new libraries, no Maven-ization, no JUnit/Spring.
- **Backward compatibility:** existing benchmark invocations and flags keep working
  (`--mode=schema`, `--backend=mlx`, positional model names, `--help`); existing `TestCase.of(...)` /
  `ExpectedCriterion.of(...)` call sites remain valid (add new factories/overloads rather than
  breaking the old ones).

Boundaries / out of scope:

- No changes to production/AI source, prompts, `data.sql`, or `02-ai-agent-filter`.
- No committing of generated benchmark reports/logs (artifact policy below).
- No pass-rate-over-K or per-field-accuracy reporting — that is `tasks/prompt-reliability-eval.md`.

## Procedure

1. **Plan first.** Re-read the current `BenchmarkLocalModels.java` scoring code and the `03` ITs, then
   present a plan covering: the exact API for the two opt-in strict behaviors (new factory/overload
   shapes for `TestCase` / `ExpectedCriterion` on the benchmark side; the new helper(s) on the IT
   side, e.g. a "no conditions on fields other than …" assertion and an exact-numeric value match);
   the final curated list of cases turned strict; and the wording of the three new cases.
   **STOP and wait for explicit approval before changing any file** (root `CLAUDE.md` plan gate).
2. Implement in increments, committing after each verified step (Conventional Commits, no push).
   Suggested split: (a) benchmark scoring + new factories, (b) apply strict/numeric to curated
   benchmark cases + add the three new benchmark cases, (c) mirror into the `03` ITs (new helpers,
   strict assertions on the matching cases, three new `@Test`s, Javadoc), (d) README.
3. Verify against the Definition of Done; iterate autonomously on failures — do not surface
   reproducible errors for manual analysis.
4. Update `04-ollama-benchmark/README.md`: the new case count, and a short description of the two
   strict behaviors and the robustness cases.

## Definition of Done

**Hard preconditions (check first; abort and report if unmet — not conditional DoD items):**
A native Ollama instance is reachable at `OLLAMA_BASE_URL` with the module's default model available
(verify via `GET /api/tags`), so the ITs and a benchmark run can actually execute. If unreachable,
stop and report; do not mark the task done on build-only evidence.

- **Benchmark still dependency-free and runnable:** `java BenchmarkLocalModels.java --help` runs with
  no build step and no external dependency; no `pom.xml` is added; module 04 stays out of the root
  `<modules>`. `javac BenchmarkLocalModels.java` compiles with no errors.
- **Over-generation is caught (benchmark):** demonstrate that a strict case fails when a condition on
  a disallowed field is present and passes when it is absent — e.g. via a documented unit-style check
  or a targeted run whose report shows the case failing on an over-generated field. The default
  (non-strict) cases still ignore extras.
- **Numeric exactness is enforced (benchmark):** demonstrate that a required `annualRevenue` of
  `1000` no longer matches a returned `1000000`, while formatting variants of the same number (e.g.
  `100000`, `100,000`, `100000.00`) still match. Date/year cases remain substring-tolerant.
- **New cases present and wired in both places:** `smalltalk_noCriteria`,
  `unrelatedRequest_noCriteria`, and `revenueExact_notOverGenerated` exist in
  `BenchmarkLocalModels.testCases(...)` **and** as `@Test` methods in the `03` suite, with matching
  query strings.
- **`03` module builds and unit tests pass:** `./mvnw verify -pl 03-ai-structured-filter` is green.
- **`03` ITs pass against Ollama, repeatably:** `./mvnw test -pl 03-ai-structured-filter -Pit-local-ollama`
  (the `CustomerSearchAgentIT` + `CustomerSearchAgentExtraIT` infrastructure) is green on **3
  consecutive runs** with the module's default model — including the three new cases and the
  strengthened assertions. If a specific strengthened case proves genuinely flaky on the default
  model across those runs, that is a decision to surface (keep it tolerant, or note a model that
  handles it), not to silently weaken.
- **Full benchmark run sanity:** one `java BenchmarkLocalModels.java <default-model>` run completes,
  the console + date-stamped `.md`/`.txt` report reflect the new case count, and the strict cases are
  scored as intended (spot-check the report).
- **Consistency:** the strict IT assertions are refinements of (never contradictions of) the tolerant
  ones for the same query; the `CustomerSearchAgentIT` Javadoc documents the new stricter behavior.

**Artifact policy:** only the changes to `BenchmarkLocalModels.java`, `04-ollama-benchmark/README.md`,
and the two `03` IT files are committed. All `benchmark-report-*` files and any run logs are generated
artifacts — gitignored, never committed. Verify the staged file list (`git status`) before every
commit.

## Final report

Provide, at completion:

- What changed, per commit (Conventional Commits messages).
- The final API of the two opt-in strict behaviors (benchmark factories/overloads + IT helpers) and
  the exact list of cases turned strict, with the field allow-list per case.
- The three new cases' final query strings and expected outcomes.
- Evidence for the DoD demonstrations: the over-generation check (fail-with-extra-field vs.
  pass-without), the numeric-exactness check (`1000` ≠ `1000000`, formatting variants still equal),
  and the 3 consecutive green `-Pit-local-ollama` runs (with the model used).
- Any strengthened case that had to stay tolerant for flakiness reasons, as an explicit decision left
  to the maintainer.
