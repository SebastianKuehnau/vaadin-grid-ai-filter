# Extract Customer Grid into reusable component classes (module 01)

## Task

Refactor the two views in `01-non-ai-filter` so the `Grid<Customer>` is no longer built
inline in each view. Extract the grid into two component classes that build on each other
through inheritance:

- **`CustomerGrid`** (base) — contains **only** the column configuration (columns, headers,
  keys, revenue formatting, credit-rating component column) and the responsive-columns behavior
  (breakpoint-based show/hide on attach and window resize). It carries **no** sort configuration
  and **no** filter fields.
- **`FilterableCustomerGrid extends CustomerGrid`** — adds a filter header row with one filter
  field per column (text / date / integer / credit-rating multi-select), holds the current filter
  state, and exposes that state to its host view together with a change-notification mechanism.

The two views consume these classes:

- **`InMemoryCustomerListView`** uses `CustomerGrid`. Its single global `TextField` and the
  in-memory `Stream` filtering stay in the view. The view applies its **in-memory sort strategy**
  (Java `Comparator`s, incl. the address comparator) to the custom columns.
- **`LazyCustomerListView`** uses `FilterableCustomerGrid`. It applies its **backend sort
  strategy** (`setSortProperty`) to the custom columns, subscribes to the grid's filter-change
  notifications, and — unchanged in responsibility — builds the JPA `Specification` and wires the
  lazy data provider itself.

Functional behavior of both views (columns, sorting, responsive visibility, filtering results,
routes `/`, `/in-memory`, `/lazy`) must remain identical to today. This is a pure structural
refactor.

## Context

- **Affected module:** `01-non-ai-filter` only. This is the non-AI baseline module (two standalone
  filtering demos over the same `Customer` domain), used in conference talks; clarity and
  presentability of the code are the top priority (see root `CLAUDE.md`).
- **Deliberately not propagated:** modules `02`/`03` are separate Spring Boot apps with their own
  grids and filtering mechanisms. The `CLAUDE.md` "apply consistently across modules" rule is about
  behavioral changes to the shared domain; this extraction is an internal 01-only refactor and is
  **not** to be replicated into 02/03 in this task. Whether to extract similar components there
  later is an open decision left to the maintainer (see Final report).
- **Existing code to build on / mirror:**
  - `01-non-ai-filter/src/main/java/dev/demo/vaadin/aigridfilter/ui/InMemoryCustomerListView.java`
    — column setup, `compareAddress`, responsive breakpoints (768 / 1200), global text filter.
  - `01-non-ai-filter/src/main/java/dev/demo/vaadin/aigridfilter/ui/LazyCustomerListView.java`
    — column setup with `setSortProperty`, header-row filter fields (`createFilterField`,
    `createDateFilterField`, `createIntegerFilterField`, `createRatingFilterField`), filter state
    (`filterCustomer`, `addressFilter`, `creditRatingFilterSet`), `buildCustomerSpecification()`,
    lazy data provider wiring.
  - `01-non-ai-filter/src/main/java/dev/demo/vaadin/aigridfilter/ui/CreditScoreIndicator.java`
    — component used in the credit-rating column (unchanged).
  - `01-non-ai-filter/src/main/java/dev/demo/vaadin/aigridfilter/data/` — `Customer`, `Address`,
    `CreditRating`, `CustomerRepository` (unchanged).
- **Existing tests (source of truth for verification):**
  - `01-non-ai-filter/src/test/java/dev/demo/vaadin/aigridfilter/ui/InMemoryCustomerListViewBrowserlessTest.java`
    — uses `view.getGrid()` and `view.getFilterField()`.
  - `01-non-ai-filter/src/test/java/dev/demo/vaadin/aigridfilter/ui/LazyCustomerListViewBrowserlessTest.java`
    — uses `view.getGrid()` and reads the appended header-filter row via the grid.

## Implementation frame

Boundaries and constraints (the execution run makes its own plan within these):

- **Decided design (do not re-open):**
  1. **Sort strategy lives in the views, not the grid.** `CustomerGrid` configures columns and
     responsive behavior only. Each view applies sorting to the custom columns after construction
     (InMemory: `Comparator`s incl. `compareAddress`; Lazy: `setSortProperty`). Standard bean
     columns created via `setColumns(...)` may keep their auto-generated sortability if that
     preserves today's behavior in both views.
  2. **Filter state flows via a filter model + change listener.** `FilterableCustomerGrid` owns the
     filter state and exposes it through getters (or a small filter-model object) plus an
     `addFilterChangeListener`-style hook. `LazyCustomerListView` registers a listener, reads the
     state, (re)builds the `Specification`, and refreshes the lazy data view. The `Specification`
     construction and the lazy data-provider wiring stay in the view.
  3. **Class names:** base `CustomerGrid`, subclass `FilterableCustomerGrid extends CustomerGrid`.
- Both grid classes extend `Grid<Customer>` (the responsive `onAttach` logic moves into
  `CustomerGrid`).
- Keep the view / component / repository layering clean (root `CLAUDE.md` conventions). No business
  logic duplicated between the grid classes and the views.
- **Preserve the test-facing API.** `view.getGrid()` must keep returning something the existing
  tests can drive (`Grid<Customer>` is acceptable as the declared return type);
  `InMemoryCustomerListView.getFilterField()` stays. If a test helper's assumption about the
  header-row ordering changes because the row is now appended inside the grid class, update the
  test (and its explanatory comment) minimally so it still targets the correct row — do not weaken
  what it asserts.
- CSS/theme unchanged; no inline CSS in Java (existing theme variants like `TextFieldVariant.SMALL`
  stay as they are).
- No dependency/version/framework changes. Do not modify `data.sql`.
- No changes outside `01-non-ai-filter` except, if strictly necessary, none — this task is
  module-local.

## Procedure

1. **Plan first.** Produce a concrete plan (target class list, what moves where, how the filter
   model / change hook is shaped, how `getGrid()` return types are handled, which test assumptions
   need touching) and present it. **STOP and wait for explicit approval before changing any file**
   (root `CLAUDE.md` plan-approval gate).
2. After approval, implement in logical increments. Suggested increments (the run may refine):
   extract `CustomerGrid` + switch `InMemoryCustomerListView` to it → verify; add
   `FilterableCustomerGrid` + switch `LazyCustomerListView` to it → verify. **Commit after each
   verified increment** (Conventional Commits, no push).
3. Verify independently against the Definition of Done below; iterate on failures autonomously
   (do not hand raw error messages back for analysis if you can reproduce and fix them).
4. Update `01-non-ai-filter/README.md` to describe the new grid component classes and their
   relationship. Do **not** edit the root `CLAUDE.md` (it is maintained via `/update-claude-md`).

## Definition of Done

- `./mvnw verify -pl 01-non-ai-filter` passes cleanly (compile + both browserless test classes),
  with no new warnings introduced by the refactor.
- `CustomerGrid` and `FilterableCustomerGrid` exist under
  `01-non-ai-filter/src/main/java/dev/demo/vaadin/aigridfilter/ui/`, with `FilterableCustomerGrid`
  extending `CustomerGrid`; `CustomerGrid` contains column config + responsive logic and **no**
  filter fields and **no** sort configuration; `FilterableCustomerGrid` adds the header-row filter
  fields and the filter-state + change-listener API.
- Neither view builds its `Grid` columns inline any longer; each view instantiates the appropriate
  grid class. `InMemoryCustomerListView` retains its global `TextField` + stream filter;
  `LazyCustomerListView` retains `buildCustomerSpecification()` and the lazy data-provider wiring.
- `InMemoryCustomerListViewBrowserlessTest` and `LazyCustomerListViewBrowserlessTest` pass without
  weakening any assertion (test changes limited to reflecting the moved header row / new types).
- **UI proof:** the app runs (`./mvnw -pl 01-non-ai-filter spring-boot:run`, http://localhost:8081)
  and a Playwright screenshot of **each** view (`/in-memory` and `/lazy`) is saved to
  `~/screenshots/`, showing the columns, responsive layout, and — for `/lazy` — the header filter
  row, matching today's appearance.
- `01-non-ai-filter/README.md` documents `CustomerGrid` and `FilterableCustomerGrid` (and the
  sort-strategy-in-view / filter-state-in-grid split); no outdated statements remain.
- Note: DoD points 3 (Ollama IT) and 4 (benchmark test case) from root `CLAUDE.md` do **not** apply
  — module 01 has no AI/filter-LLM layer and no new filter capability is added.

## Final report

Provide, at completion:

- What changed, per commit (Conventional Commits messages).
- Confirmation that `./mvnw verify -pl 01-non-ai-filter` is green and both browserless test classes
  pass, plus which assertions/tests (if any) were adjusted and why.
- The two screenshot paths under `~/screenshots/` and a one-line before/after visual confirmation.
- The public API shape chosen for `FilterableCustomerGrid` (filter-model/getters + listener) and
  the `getGrid()` return types, in case the maintainer wants them tightened.
- **Open decision left to the maintainer (not to be actioned in this task):** whether to extract
  equivalent grid components in modules `02`/`03`.