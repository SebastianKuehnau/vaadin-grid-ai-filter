# Bring the AI integration tests of modules 02 and 03 to true parity (comparability)

## Task

Make the AI integration tests of `02-ai-agent-filter` (tool calling) and `03-ai-structured-filter`
(structured output) genuinely comparable, so that a `-Pit-local-ollama` run of the two modules can be
compared apples-to-apples on result quality and timing between the two AI approaches.

Comparability today is only claimed in Javadoc and hand-maintained via duplicated query strings — and
it has drifted. Bring the two modules' shared integration-test suites back to **true parity**:

- The **shared** AI-layer cases — those a query can express in *both* modules — must be an identical
  set across `02`'s and `03`'s `CustomerSearchAgentIT`: same test-method names, same
  natural-language query strings, same source order, and one consistent tolerance philosophy for
  corresponding expected outcomes.
- Cases only `03` can express (its `CustomerFilter` supports capabilities `02`'s flat
  `CustomerSearchCriteria` cannot: negation, operator precision such as STARTS_WITH/ENDS_WITH/EQUALS,
  arbitrary date bounds) stay out of the shared suite and remain in `03`'s `CustomerSearchAgentExtraIT`.
- The two UI-level integration tests (`CustomerListViewBrowserlessIT` in both modules) must likewise
  hold an identical shared case set (same method names, queries, order) with drift removed.

Parity is defined at the level of **(test-method name, query string, expected semantic outcome)** — NOT
at the level of assertion code. The two modules return different result types
(`CustomerSearchCriteria` vs `CustomerFilter`/`Condition`), so the per-type assertion mechanics
(`anySatisfy` over list fields vs the `hasCondition`/`flatten` leaf matching) necessarily differ and
must stay as they are; only the case set, wording, ordering, and tolerance philosophy are unified.

## Context

- **Affected modules:** `02-ai-agent-filter` and `03-ai-structured-filter` (both are standalone
  Spring Boot apps over the same `Customer` domain, differing only in the AI filtering mechanism).
  Module `04-ollama-benchmark` is **out of scope** for this task (it has its own comparable-case
  machinery; not touched here).
- **Purpose:** these modules back conference talks that contrast tool calling vs structured output;
  a side-by-side, provably-equal test suite is what makes the "which approach wins on small local
  models" comparison credible.
- **Files in scope (source of truth — verify each exists before editing):**
  - `02-ai-agent-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchAgentIT.java`
    (19 cases today; result type `CustomerSearchCriteria`; service `CustomerSearchToolCallingService`).
  - `03-ai-structured-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchAgentIT.java`
    (20 cases today; result type `CustomerFilter`; service `CustomerSearchStructuredOutputService`;
    holds the shared `hasCondition`/`flatten` helpers).
  - `03-ai-structured-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchAgentExtraIT.java`
    (`03`-only capability cases; reuses `CustomerSearchAgentIT.hasCondition`).
  - `02-ai-agent-filter/src/test/java/dev/demo/vaadin/aigridfilter/ui/CustomerListViewBrowserlessIT.java`
    and `03-.../ui/CustomerListViewBrowserlessIT.java` (8 cases each; full UI→AI→grid pipeline).
- **Known drift to reconcile (enumerated so nothing is missed; the plan must resolve each):**
  1. AI-layer suites differ in membership: `03` has `phoneNumberContains` with no `02` counterpart;
     `02` has 19 methods, `03` has 20.
  2. AI-layer suites differ in source order of otherwise-shared cases.
  3. Inconsistent tolerance philosophy for corresponding cases, e.g. `annualRevenueOverThreshold`
     (query "over 200000"): `02` asserts `atLeast ≥ 150_000`, `03` asserts the value contains
     "200000"; `citiesAndRevenue_keepsEveryCondition`: `02` `≥ 75_000` vs `03` "100000".
  4. UI ITs: `02` carries a commented-out `@BeforeAll requireReachableBackend()` block and an unused
     `assumeTrue` import; `03`'s class Javadoc claims "same **5** queries" but both have 8.
  5. Stale references in the in-scope files: Javadoc `{@link}`/`{@code}` to `LocalOllamaTests` and
     `OllamaTestSupport`, which do **not** exist as classes anywhere in the tree; and UI-IT Javadoc
     stating the test profile default is `${AI_TEST_PROFILE:ollama}` while the actual
     `src/test/resources/application.properties` default is `${AI_TEST_PROFILE:cloud}`.

## Implementation frame

Decided design (do not re-open):

- **Approach:** bring the *existing* IT classes to parity by hand. Do **not** introduce a shared
  test module, test-jar, shared resource file, or any cross-module code sharing — the two suites stay
  as separate, self-contained classes per module.
- **Scope of tests:** the AI-layer ITs (`CustomerSearchAgentIT` in both modules, plus `03`'s
  `CustomerSearchAgentExtraIT`) **and** the UI-level `CustomerListViewBrowserlessIT` in both modules.
- **Shared set = reliably-passing intersection.** A case belongs in the shared suite only if it is
  expressible in both modules *and* passes reliably on each module's configured local Ollama model.
  Resolve each drift item by either adding the missing counterpart (if the other module can express
  and reliably pass it) or keeping it module-specific (`03`-only → `CustomerSearchAgentExtraIT`;
  never a shared-suite case without a counterpart). Any tolerance change is applied **identically**
  to the corresponding case in both modules. Statistical prompt reliability (per-case pass-rate over
  many runs) is deliberately NOT the job of these ITs — that is handled by the separate
  `tasks/prompt-reliability-eval.md` task (module 04). Here the ITs are the lean, fast
  correctness/smoke layer: a single green run is the behavioral bar (see Definition of Done).
- **Bounded documentation cleanup (in scope, but only inside the five in-scope IT files):** remove or
  correct stale references and inaccurate claims — dead `{@link}`/`{@code}` to the non-existent
  `LocalOllamaTests`/`OllamaTestSupport`, the wrong "5 queries" count, the profile-default statement
  that contradicts the real `${AI_TEST_PROFILE:cloud}`, the commented-out `@BeforeAll` block, and
  unused imports. Make each file's Javadoc state the comparability contract accurately.

Boundaries / out of scope (do NOT change; list any you think should change under Final report):

- No changes to production/AI source, prompts, `CustomerSearchCriteria`/`CustomerFilter`, services,
  or `data.sql`.
- No change to the actual `AI_TEST_PROFILE` default (`:cloud`) in either
  `src/test/resources/application.properties`.
- No changes to `pom.xml` (including its own stale `LocalOllamaTests` surefire-exclude and profile
  comments) — flag these for the maintainer instead.
- No new dependencies, no framework/version changes.

## Procedure

1. **Plan first.** Present a plan that, for the AI-layer suites, lists the final shared case set as an
   ordered table of (method name, query string, expected outcome + tolerance) and shows how each of
   the five drift items is resolved; and, for the UI suites, the final shared case set plus the doc/
   dead-code cleanup. **STOP and wait for explicit approval before changing any file** (root
   `CLAUDE.md` plan-approval gate).
2. **Mid-task gate (explicit):** after aligning the AI-layer `CustomerSearchAgentIT` pair and proving
   the case-set diff is empty (see DoD), pause and report the parity diff before moving on to the UI
   ITs — reworking the shared set after the UI ITs are aligned would be expensive.
3. Implement in increments, committing after each verified step (Conventional Commits, no push):
   suggested split — (a) AI-layer parity, (b) UI-IT parity + doc cleanup.
4. Verify against the Definition of Done; iterate autonomously on failures. Do not update
   `README.md`/`CLAUDE.md` unless a concrete statement there about these ITs becomes wrong.

## Definition of Done

**Hard precondition (check first; abort and report if unmet — do not turn into a conditional DoD):**
a native Ollama instance is reachable at `OLLAMA_BASE_URL`, and every model referenced by the two
modules' `ollama` Spring profile (in `src/main/resources/application*.properties`) is present in the
local Ollama, verified via the Ollama API (`GET /api/tags`). Missing model → pull it or abort.

- **Compilation + unit tests green:**
  `./mvnw verify -pl 02-ai-agent-filter,03-ai-structured-filter` passes (compiles the ITs; the
  Ollama ITs are not run in this phase — failsafe has no active profile).
- **Provable case parity (deterministic artifact):** extracting the ordered list of
  (test-method name, natural-language query string) from `02`'s and `03`'s `CustomerSearchAgentIT`
  yields **identical** lists — `diff` of the two extracted lists is empty. The same holds for the two
  `CustomerListViewBrowserlessIT`. The execution run includes the exact extraction+diff command and
  its empty output in the final report. Cases in `03`'s `CustomerSearchAgentExtraIT` are excluded
  from this comparison by design.
- **Consistent tolerance:** for every shared AI-layer case, the expected outcome (fields, values,
  thresholds) is semantically equivalent across both modules, using one documented tolerance
  philosophy; no corresponding pair asserts contradictory thresholds (drift item 3 resolved).
- **Behavioral smoke (non-deterministic, single run):** run against the reachable native Ollama with
  `./mvnw verify -pl <module> -Pit-local-ollama -DAI_TEST_PROFILE=ollama` — `CustomerSearchAgentIT`
  and `CustomerListViewBrowserlessIT` each pass in **one green run** in each of `02` and `03`. This
  is a correctness/smoke check that the aligned suites execute end to end, not a reliability
  measurement. A case that flakes on this single run is a poor comparable case: remove it from the
  shared set or widen its tolerance identically in both modules (per the Implementation frame).
  Quantifying per-case reliability of prompt changes is out of scope here and belongs to
  `tasks/prompt-reliability-eval.md`.
- **No stale references remain in the in-scope files:** `grep -n "LocalOllamaTests\|OllamaTestSupport"`
  over the five in-scope IT files returns zero matches; no in-scope file's Javadoc states a query
  count or a test-profile default that contradicts the code; no commented-out `@BeforeAll` block or
  unused imports remain in the UI ITs.

**Artifact policy:** only the modified test sources are committed. All IT run logs, surefire/failsafe
reports, and any generated comparison output are build artifacts — gitignored, never committed. Verify
the staged file list (`git status`) before every commit.

## Final report

Provide, at completion:

- What changed, per commit (Conventional Commits messages), with the final shared AI-layer case table
  and the UI-IT case list.
- The exact case-parity extraction+diff commands and their empty output (proof of parity), for both
  the AI-layer and the UI suites.
- The `-Pit-local-ollama -DAI_TEST_PROFILE=ollama` results: confirmation of one green run each for
  `CustomerSearchAgentIT` and `CustomerListViewBrowserlessIT` per module, plus the model(s) used and
  per-run timings if captured (for the comparability story).
- Any case dropped from the shared set (with the reason: not expressible in one module, or not
  reliably passing) and any tolerance that was widened, noting it was applied to both modules.
- **Decisions left to the maintainer (flag, do not action):** the stale `pom.xml` items
  (`LocalOllamaTests` surefire-exclude and the profile comments) and the `${AI_TEST_PROFILE:cloud}`
  vs. `-Pit-local-ollama`-implies-Ollama mismatch in the profile documentation — whether to correct
  these in a follow-up.
