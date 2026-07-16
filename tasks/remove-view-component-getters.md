# Replace test-only component getters with package-private fields (modules 01–03)

## Task

The list views currently expose package-private getter methods (`getGrid()`, `getFilterField()`)
whose *only* purpose is to let the browserless UI tests reach the view's internal components. Remove
these test-only getters. Instead, make the underlying component **fields package-private** so the
tests — which live in the same package as the views — access the component instances directly
(`view.grid`, `view.filterField`) without a dedicated accessor.

This is a mechanical refactor of the test-access surface only. No production runtime behavior, no UI,
and no AI/filter logic changes.

## Context

- **Affected modules:** all three Vaadin apps — `01-non-ai-filter`, `02-ai-agent-filter`,
  `03-ai-structured-filter`. The getters exist identically in all three, so per the root `CLAUDE.md`
  "apply consistently across all affected modules" convention the cleanup is done in all three in one
  task.
- **Purpose:** these are conference-talk demos; production code should stay clean and free of
  test-only scaffolding (root `CLAUDE.md`: "easy to understand, presentable"). Removing accessors
  that exist purely for tests serves that goal.
- **All test classes live in the same package as their view**
  (`dev.demo.vaadin.aigridfilter.ui`), so package-private field access compiles without any
  visibility widening beyond package-private.

### Exact inventory (source of truth — verify each still exists before editing)

Production views — remove the listed getter methods and drop the `private` modifier on the listed
fields (keep them `final`):

| Module | View | Getter methods to remove | Fields → package-private |
|---|---|---|---|
| 01 | `InMemoryCustomerListView` | `getGrid()`, `getFilterField()` | `grid`, `filterField` |
| 01 | `LazyCustomerListView` | `getGrid()` | `grid` |
| 02 | `CustomerListView` | `getGrid()`, `getFilterField()` | `grid`, `filterField` |
| 03 | `CustomerListView` | `getGrid()`, `getFilterField()` | `grid`, `filterField` |

Test classes — replace `view.getGrid()` → `view.grid` and `view.getFilterField()` →
`view.filterField` (including the private `headerFilter(...)` helper in 01's lazy test, which reads
the grid via `view.getGrid()`):

- 01: `InMemoryCustomerListViewBrowserlessTest`, `LazyCustomerListViewBrowserlessTest`
- 02: `CustomerListViewBrowserlessTest`, `CustomerListViewBrowserlessIT`
- 03: `CustomerListViewBrowserlessTest`, `CustomerListViewBrowserlessIT`

### Interaction with the `extract-customer-grid-components` spec

The separate spec `tasks/extract-customer-grid-components.md` (module 01 only) currently states that
`getGrid()`/`getFilterField()` must be *preserved*. These two tasks conflict on that point. Whichever
runs **second** must reconcile: this task's package-private-field access is the intended end state, so
if the extraction task runs after this one, it should keep using direct field access (e.g.
`view.grid`) and must **not** reintroduce the getters. Flag this in the final report if the other
task has not yet been executed.

## Implementation frame

Decided design (do not re-open):

- **Access mechanism:** package-private component **fields**, accessed directly from the same-package
  tests. Do **not** use the browserless `$`/`find` component-query DSL, and do **not** add
  `setId()`/test-ids to production components — the field-access approach was chosen deliberately.
- **Scope:** all three modules (01, 02, 03), applied identically.

Constraints:

- Fields keep their type and `final` modifier; only the `private` visibility modifier is dropped.
  A one-line comment noting the package-private visibility exists for same-package test access is
  welcome (clarity), but optional.
- Do not change any component wiring, filter behavior, column config, data providers, or AI code.
- Do not touch modules beyond the three views and six test classes listed above. No dependency,
  profile, or `pom.xml` changes. Do not modify `data.sql`.
- The Ollama-backed `*IT` classes must be updated so they **compile**, but this task does not require
  running them (see Definition of Done).

## Procedure

1. **Plan first.** Present a short plan (the concrete edits per file, mapping to the inventory
   table). **STOP and wait for explicit approval before changing any file** (root `CLAUDE.md`
   plan-approval gate).
2. After approval, implement. Suggested increment boundary: one commit per module (view + its tests),
   or a single commit if the run prefers — each committed increment must build green. Conventional
   Commits, no push.
3. Verify against the Definition of Done; iterate autonomously on failures.
4. No README/`CLAUDE.md` updates are expected (no user-facing or architectural change); only update
   docs if a concrete statement about the getters exists and becomes wrong.

## Definition of Done

- `./mvnw verify -pl 01-non-ai-filter,02-ai-agent-filter,03-ai-structured-filter` passes cleanly.
  This compiles all test sources — including the `*IT` classes (surefire excludes them and, with no
  profile active, failsafe runs no Ollama IT, so no Ollama is required) — and runs the browserless
  unit tests (`*BrowserlessTest`) for all three modules green.
- No getter methods remain: `grep -rn "getGrid\|getFilterField" 01-non-ai-filter 02-ai-agent-filter
  03-ai-structured-filter` returns **zero** matches (neither declarations nor call sites, in main or
  test sources).
- Every field in the inventory table is declared package-private (no `private` modifier) and still
  `final`; verifiable by inspecting the four view files.
- The `*IT` classes (`CustomerListViewBrowserlessIT` in 02 and 03) compile (proven by the `verify`
  test-compile phase above). **Running** the `-Pit-local-ollama` ITs is explicitly out of scope: no
  AI/filter behavior changed, so `CLAUDE.md` DoD point 3 does not apply here.
- No screenshot is required: this refactor changes no production runtime or UI behavior
  (`CLAUDE.md` DoD point 2 does not apply).

## Final report

Provide, at completion:

- What changed, per commit (Conventional Commits messages), and the list of files touched mapped to
  the inventory table.
- Confirmation that the combined `./mvnw verify -pl 01-non-ai-filter,02-ai-agent-filter,03-ai-structured-filter`
  is green, and that the `grep` for `getGrid`/`getFilterField` returns no matches.
- Explicit confirmation that the Ollama-backed ITs were updated for compilation only and not executed
  (and the reason: no AI/filter behavior change).
- **Interaction flag:** state whether `tasks/extract-customer-grid-components.md` has already been
  executed; if not, note that that task must be reconciled to use direct field access and not
  reintroduce the getters. This reconciliation is a decision left to the maintainer.