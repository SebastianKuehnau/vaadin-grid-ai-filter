# 01-non-ai-filter

The non-AI baseline for this tutorial: two ways to filter a Vaadin `Grid` of `Customer` records
without any LLM involved, so they can be compared against the AI-driven approaches in
`02-ai-agent-filter` and `03-ai-structured-filter`.

## Views

- **`/` (alias `/in-memory`)** — `InMemoryCustomerListView`: loads all customers into memory and
  filters them with a single `TextField`, using a plain Java `Stream` over the in-memory list.
  Simplest possible approach; not lazy.
- **`/lazy`** — `LazyCustomerListView`: a lazy-loading grid with a filter field under each column
  header. Each field updates a JPA `Specification`, so filtering, sorting, and paging all happen
  as SQL queries against the database instead of in memory.

## Grid components

Both views build on a shared pair of `Grid<Customer>` subclasses instead of assembling columns
inline:

- **`CustomerGrid`** (base) — column configuration only: keys, headers, revenue formatting, the
  `CreditScoreIndicator` component column, and the responsive breakpoint-based show/hide behavior
  (768px / 1200px, applied on attach and window resize). It carries no sort configuration and no
  filter fields, so it has no opinion on *how* a view sorts or filters.
- **`FilterableCustomerGrid extends CustomerGrid`** — adds the header-row filter field per column
  (text / date / integer / credit-rating multi-select), owns the resulting filter state
  (`getFilterCustomer()`, `getAddressFilter()`, `getCreditRatingFilterSet()`), and notifies
  `addFilterChangeListener(Runnable)` listeners whenever any field changes.

Sort strategy stays in the views, not the grid, because the two views sort the same custom columns
(`annualRevenue`, `address`, `creditRating`) differently:

- `InMemoryCustomerListView` uses `CustomerGrid` directly and applies in-memory `Comparator`s
  (including the address comparator) to those columns after construction.
- `LazyCustomerListView` uses `FilterableCustomerGrid`, applies backend `setSortProperty(...)` to
  the same columns, and registers a filter-change listener that rebuilds its JPA `Specification`
  (`buildCustomerSpecification()`) and refreshes the lazy data view — the `Specification`
  construction and lazy-data-provider wiring remain the view's responsibility, not the grid's.

## Running

```bash
./mvnw -pl 01-non-ai-filter spring-boot:run   # http://localhost:8081
```

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
