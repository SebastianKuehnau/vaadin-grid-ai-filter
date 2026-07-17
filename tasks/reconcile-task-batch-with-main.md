# Reconcile the `feature/task-batch` branch with `main` by rebasing it, uniting both parallel developments

## Task

`main` and `feature/task-batch` diverged from a common ancestor and evolved in parallel, each
implementing a *different* set of the specs under `tasks/`. They now overlap in six files. Bring the
two back into harmony by **rebasing `feature/task-batch` onto `main`**, resolving every conflict so
that the intent of *both* developments is preserved, except for one deliberate contradiction where a
single winner has already been chosen (test profile strategy — see below).

The two developments:

- **`main`** implements `tasks/harden-filter-test-assertions.md`: opt-in strict field allow-list +
  exact-numeric matching in the benchmark, mirrored strict assertions + robustness cases in `03`'s
  ITs, plus a test-profile tweak (default `ollama`) and a `.gitignore` addition.
- **`feature/task-batch`** implements a batch of the other specs: `tasks/prompt-reliability-eval.md`
  (benchmark turned into a pass-rate-over-K / field-precise eval covering both AI approaches),
  `tasks/align-ai-integration-tests.md` (aligned IT case sets, 4 unreliable shared cases dropped,
  browserless-IT cleanup), `tasks/extract-customer-grid-components.md` (extract `CustomerGrid` in 02
  and 03), `tasks/remove-view-component-getters.md`, and a **profile refactor** (remove the Spring
  `mlx` profile, rename the `cloud` profile to `openai`, default test profile `openai`).

After the rebase, `feature/task-batch` must contain all of `main` as its ancestor plus its own nine
commits replayed on top, reconciled, and the whole tree must build and test green.

## Context

- **Branches / worktrees (verify current values before starting — hashes below are as of writing):**
  - `main` — tip `39f8fbc`, the rebase **base**. Worktree: repo root.
  - `feature/task-batch` — tip `f972f22`, the branch to **rebase**. Worktree:
    `../vaadin-grid-ai-filter-feature-task-batch`.
  - Common ancestor (merge-base): `5693244`. `main` is 10 commits ahead of it, `feature/task-batch`
    9 commits. Neither branch is pushed (`main` is ahead of `origin/main`); this rebase rewrites only
    **un-pushed** history.
  - A third branch `feature/harden-filter-test-assertions` exists but is already merged into `main`;
    it is **not** part of this task.
- **The six overlapping files (the entire conflict zone):**
  1. `04-ollama-benchmark/BenchmarkLocalModels.java`
  2. `04-ollama-benchmark/README.md`
  3. `03-ai-structured-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchAgentIT.java`
  4. `03-ai-structured-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchAgentExtraIT.java`
  5. `03-ai-structured-filter/src/test/resources/application.properties`
  6. `02-ai-agent-filter/src/test/resources/application.properties`
- **Governing specs (all present in `tasks/` on `main`):** `harden-filter-test-assertions.md`,
  `prompt-reliability-eval.md`, `align-ai-integration-tests.md`, `extract-customer-grid-components.md`,
  `remove-view-component-getters.md`. These are the source of truth for what each side *intended*; use
  them to resolve conflicts, not guesswork.
- **Nature of the conflicts:**
  - **Benchmark (`BenchmarkLocalModels.java`, `README.md`) — complementary, must be unioned.** `main`'s
    strict field allow-list + exact-numeric value matching and task-batch's prompt-reliability eval
    (pass-rate over K, field-precise scoring for both approaches) both target the *same* scoring code.
    Note the functional overlap: task-batch's field-precise scoring (exact field placement, empty
    fields, operator/negate) may already subsume `main`'s allow-list guard; `main`'s exact-numeric
    matching adds value on top. The result must be **one coherent scoring path** satisfying both
    specs' DoDs — not two rival mechanisms glued together.
  - **`03` ITs — complementary, must be unioned on the aligned case set.** task-batch aligned the case
    sets and deliberately dropped 4 shared cases that don't reliably pass on `03`'s default model
    (commit `801140f`); `main` strengthened assertions and added robustness cases
    (`smalltalk_noCriteria`, `unrelatedRequest_noCriteria`, `revenueExact_notOverGenerated`). The
    aligned set (from `align-ai-integration-tests.md`, including the 4 drops) is the base; `main`'s
    strict assertions and the three *new* robustness cases are layered onto the surviving cases.
  - **Test `application.properties` (02 + 03) — genuine contradiction, task-batch wins (decided).**
    `main` set the default test profile to `ollama` and keeps `mlx`/`cloud`; task-batch removed the
    Spring `mlx` profile, renamed `cloud`→`openai`, and set the default test profile to `openai`. The
    reconciled result uses **task-batch's** strategy everywhere. `main`'s "default ollama" change is
    superseded; keep any *non-conflicting* additions from `main` (e.g. the `spring.ai.model.chat`
    shadow line, present on both).

## Implementation frame

Decided (do not re-open):

- **Mechanism:** `git rebase main` on `feature/task-batch` (replay its 9 commits onto `main`). Resolve
  conflicts commit-by-commit. Keep task-batch's commit granularity; do not squash unless a replayed
  commit becomes empty (drop empty ones).
- **Resolution principle:** the reconciled tree is the **union of both feature sets' functionality**,
  with exactly one exception: the test-profile strategy, where **task-batch wins** (openai default,
  Spring `mlx` profile removed, `cloud`→`openai`). Any `main` change that assumes the old profile world
  (default `ollama`, `mlx`/`cloud` present) is adjusted to task-batch's world during conflict
  resolution.
- **Precision — two different "mlx" things:** task-batch removes the **Spring** `application-mlx.properties`
  profile from modules 02/03 (and its pom/wiring). The benchmark's `--backend=mlx` (an MLX-*server*
  runtime backend in `BenchmarkLocalModels.java`) is a **separate, unrelated feature and must stay**.
  Do not remove `--backend=mlx` support from the benchmark.
- **No feature loss:** after resolution, both benchmark capabilities (strict/exact matching *and* the
  pass-rate/field-precise eval) exist and work; the ITs carry the aligned case set *and* the strict
  assertions *and* the three robustness cases; the grid-extraction and getter-removal refactors from
  task-batch remain intact.
- **This task changes no product behavior beyond what the five governing specs already defined** — it
  only reconciles them. Do not introduce new features, new flags, or new test cases beyond uniting
  what the two branches already contain.

Open questions for the plan gate (mechanism only — resolve and present before touching files):

1. **Benchmark scoring unification:** whether `main`'s field allow-list guard is fully subsumed by
   task-batch's field-precise scoring (then keep the latter + add exact-numeric matching), or both are
   needed. The plan must show the concrete unified scoring design and which existing code it keeps.
2. **Case-set final list:** the exact reconciled case list for the `03` ITs and the benchmark — which
   of `main`'s strengthened cases survive given task-batch's 4 drops, and confirmation that the three
   robustness cases are additive. Present the final list before implementing.

Boundaries / out of scope:

- No push, no merge of `feature/task-batch` into `main`, no branch deletion — those are the
  maintainer's calls (see Final report).
- No changes to `data.sql`, to production prompts/AI logic, or to any spec file under `tasks/`.
- No dependency/framework upgrades; the benchmark stays dependency-free (no `pom.xml`, no JUnit).

## Procedure

1. **Verify the starting state** (abort and report if it does not match): current branch, `main` tip,
   `feature/task-batch` tip, merge-base, and that `feature/task-batch` is not yet an ancestor of
   `main`. Confirm the six overlapping files above are still the full conflict zone
   (`comm -12` of the two `git diff --name-only <merge-base> <branch>` lists).
2. **Plan first.** Present the rebase plan: conflict-resolution approach per overlapping file, answers
   to the two open questions above, and the final reconciled IT/benchmark case list. **STOP and wait
   for explicit approval before running the rebase or changing any file** (root `CLAUDE.md` plan gate).
3. Execute the rebase in the `feature/task-batch` worktree. Resolve conflicts per the principle above;
   after each replayed commit that touched an overlapping file, keep the working tree buildable. It is
   acceptable to reword a replayed commit's message to reflect the reconciliation, but preserve
   authorship intent.
4. After the rebase completes, verify against the Definition of Done; iterate autonomously on
   failures (a failed IT/build is fixed here, not surfaced for manual analysis).
5. Reconcile the two READMEs and any doc lines that mention the removed `mlx` Spring profile or the old
   default profile, so docs match the reconciled config.

## Definition of Done

**Hard preconditions (check first; abort and report if unmet — not conditional DoD items):**
(a) The starting state matches step 1 (branch tips as expected, `feature/task-batch` not yet merged
into `main`). (b) A native Ollama instance is reachable at `OLLAMA_BASE_URL` with the modules' default
IT model present (verify via `GET /api/tags`), so the AI ITs can run.

- **Rebase is clean and complete:** `feature/task-batch` has no rebase in progress, and
  `git merge-base --is-ancestor main feature/task-batch` exits 0 (all of `main` is now an ancestor).
- **Both feature sets present (union):**
  - `04-ollama-benchmark/BenchmarkLocalModels.java` contains a single coherent scoring path that
    performs both exact-numeric value matching (a required `annualRevenue` `1000` does not match a
    returned `1000000`) **and** the prompt-reliability field-precise scoring / pass-rate-over-K from
    `prompt-reliability-eval.md`. `java BenchmarkLocalModels.java --help` runs with no build step and
    lists the reliability-eval flags; the benchmark remains dependency-free (no `pom.xml`, still absent
    from the root `<modules>`), and `--backend=mlx` is still supported.
  - The `03` ITs (`CustomerSearchAgentIT` + `CustomerSearchAgentExtraIT`) contain the aligned case set
    (with task-batch's 4 drops honored), `main`'s strict assertions, and the three robustness cases
    (`smalltalk_noCriteria`, `unrelatedRequest_noCriteria`, `revenueExact_notOverGenerated`).
  - The grid-extraction (`CustomerGrid` in 02 and 03) and view-getter-removal refactors from
    task-batch are still in place.
- **Profile strategy is task-batch's, consistently:** no `application-mlx.properties` remains under
  `02-ai-agent-filter`/`03-ai-structured-filter`; there is no `mlx` Spring profile referenced in those
  modules' `pom.xml` or `application*.properties`; the default test profile is `openai`
  (`spring.profiles.active=${AI_TEST_PROFILE:openai}` in both test `application.properties`). A repo
  grep for a Spring `mlx` profile (`grep -rn "application-mlx\|profiles.active.*mlx\|:mlx}" 02-ai-agent-filter 03-ai-structured-filter`)
  returns nothing. (The benchmark's `--backend=mlx` string is expected and is *not* a violation.)
- **Everything builds:** `./mvnw verify` (all Maven modules) is green.
- **AI ITs pass, repeatably:** `./mvnw test -pl 02-ai-agent-filter,03-ai-structured-filter -Pit-local-ollama`
  is green on **3 consecutive runs** with the default IT model.
- **Docs consistent:** `04-ollama-benchmark/README.md` and any module README/config comments no longer
  describe the removed Spring `mlx` profile or a default `ollama` test profile; the benchmark README
  reflects both the strict/exact matching and the reliability-eval capabilities.

**Artifact policy:** only source/test/config/doc files are committed. All `benchmark-report-*` files
and any run logs are generated artifacts — gitignored, never committed. The two currently-untracked
`04-ollama-benchmark/benchmark-report-2026-07-16-*` files must not be added. Verify the staged file
list (`git status`) before every commit.

## Final report

Provide, at completion:

- Confirmation of the reconciled state: `feature/task-batch` tip, that `main` is now its ancestor, and
  the replayed commit list (noting any commit dropped because the rebase made it empty, or reworded).
- Per overlapping file, one line on how the conflict was resolved.
- The answers that were taken for the two plan-gate open questions (benchmark scoring unification;
  final case list).
- Evidence: `git merge-base --is-ancestor` result, `./mvnw verify` result, the 3 consecutive
  `-Pit-local-ollama` green runs (with the model used), the `--help` run, and the `mlx`-profile grep
  showing it is gone.
- **Decisions left to the maintainer (do not automate):** whether to push, whether/how to merge
  `feature/task-batch` into `main`, and whether to delete the branch/worktree afterwards.
