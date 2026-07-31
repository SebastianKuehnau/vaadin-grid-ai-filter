# 01-non-ai-filter

Step 1 of this tutorial's escalation ladder and its non-AI baseline: two ways to filter a Vaadin `Grid`
of `Customer` records without any LLM involved, so they can be compared against the three AI-driven
steps that follow — `02-ai-agent-filter` (tool calling, in two variants), `03-ai-structured-filter`
(structured output) and `04-ai-hybrid-filter` (tool calling with 03's filter type). The root
`README.md` has the whole ladder in one table.

The per-column filter form of `/lazy` is also the yardstick for what the AI steps have to reach: it can
express everything a user can type into it, but only because a human filled in every field by hand.

## Views

- **`/` (alias `/in-memory`)** — `InMemoryCustomerListView`: loads all customers into memory and
  filters them with a single `TextField`, using a plain Java `Stream` over the in-memory list.
  Simplest possible approach; not lazy.
- **`/lazy`** — `LazyCustomerListView`: a lazy-loading grid with a filter field under each column
  header. Each field updates a JPA `Specification`, so filtering, sorting, and paging all happen
  as SQL queries against the database instead of in memory.

## Grid components

Both views build on `Grid<Customer>` subclasses instead of assembling columns inline:

- **`CustomerGrid`** (base) — column configuration: keys, headers, revenue formatting, the
  `CreditScoreIndicator` component column, and the responsive breakpoint-based show/hide behavior
  (768px / 1200px, applied on attach and window resize). It lives in **`demo-commons`**, because all
  four apps of this repository show the very same grid; see `demo-commons/README.md`.
- **`FilterableCustomerGrid extends CustomerGrid`** — this module's own addition, and the thing that
  makes step 1 step 1: a header-row filter field per column (text / date / integer / credit-rating
  multi-select), owning the resulting filter state (`getFilterCustomer()`, `getAddressFilter()`,
  `getCreditRatingFilterSet()`) and notifying `addFilterChangeListener(Runnable)` listeners whenever
  any field changes. No other module has anything like it.

**Sort strategy stays with the views**, because the two views sort the same custom columns
(`annualRevenue`, `address`, `creditRating`) differently, and the shared base can only carry one
default (the backend sorting the AI modules use):

- `InMemoryCustomerListView` uses `CustomerGrid` directly and *replaces* that default with in-memory
  `Comparator`s (including the address comparator) on those three columns. That is what actually takes
  effect for a list-backed grid: `Column.setComparator(...)` wins over a sort property, which is only
  consulted when the data provider issues a query.
- `LazyCustomerListView` uses `FilterableCustomerGrid`, keeps the backend sort properties, adds the one
  for `annualRevenue` that the shared base deliberately leaves open, and registers a filter-change
  listener that rebuilds its JPA `Specification` (`buildCustomerSpecification()`) and refreshes the lazy
  data view — the `Specification` construction and lazy-data-provider wiring remain the view's
  responsibility, not the grid's.

## Running

```bash
./mvnw install -DskipTests                    # once: this app depends on demo-commons
./mvnw -pl 01-non-ai-filter spring-boot:run   # http://localhost:8081
```

This module contains **no Spring AI code**, declares no model starter, and therefore autoconfigures no
`ChatModel` — it is the non-AI baseline, and the comparison only means something if the baseline really is
one. What it does inherit through `demo-commons` are the Spring AI *classes* on its classpath, because that
module declares `spring-ai-client-chat` regularly so its `TokenUsageAdvisor` can be a single `@Component`
instead of three copies of a configuration class. The one visible consequence is an idle
`TokenUsageAdvisor` bean in this app, which nothing ever calls. See `demo-commons/README.md` for the trade.

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/InMemoryCustomerListView.java` — view 1
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/LazyCustomerListView.java` — view 2
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/CustomerGrid.java` — shared column config + responsive layout
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/FilterableCustomerGrid.java` — header-row filter fields + filter state
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/ui/` — BrowserlessTests for both views (Vaadin's
  [browserless testing](https://vaadin.com/docs/latest/flow/testing/browserless), no browser or
  servlet container needed)
