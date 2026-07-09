# 01-non-ai-filter

The non-AI baseline for this tutorial: two ways to filter a Vaadin `Grid` of `Customer` records
without any LLM involved, so they can be compared against the AI-driven approaches in
`02-ai-agent-filter` and `04-local-ai-filter`.

## Views

- **`/` (alias `/in-memory`)** — `InMemoryCustomerListView`: loads all customers into memory and
  filters them with a single `TextField`, using a plain Java `Stream` over the in-memory list.
  Simplest possible approach; not lazy.
- **`/lazy`** — `LazyCustomerListView`: a lazy-loading grid with a filter field under each column
  header. Each field updates a JPA `Specification`, so filtering, sorting, and paging all happen
  as SQL queries against the database instead of in memory.

## Running

```bash
./mvnw -pl 01-non-ai-filter spring-boot:run   # http://localhost:8081
```

## Sources

- `src/main/java/dev/demo/vaadin/aigridfilter/ui/InMemoryCustomerListView.java` — view 1
- `src/main/java/dev/demo/vaadin/aigridfilter/ui/LazyCustomerListView.java` — view 2
- `src/main/java/dev/demo/vaadin/aigridfilter/data/` — the shared `Customer`/`Address` JPA model
- `src/main/resources/data.sql` — seed data (100 customers)
- `src/test/java/dev/demo/vaadin/aigridfilter/ui/` — BrowserlessTests for both views (Vaadin's
  [browserless testing](https://vaadin.com/docs/latest/flow/testing/browserless), no browser or
  servlet container needed)
