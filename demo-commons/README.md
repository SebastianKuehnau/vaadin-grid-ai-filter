# demo-commons

Shared **runtime** infrastructure for all four apps. No numeric prefix, because this is not a step of the
talk — it is the scaffolding the four steps stand on.

This module is the repository's **one deliberate exception** to its "duplication per module is on
purpose" rule. Everything else stays duplicated, so each numbered module can be read on its own.

Its own test sources hold one thing that is not runtime scaffolding: `CanonicalQuerySetConsistencyTest`,
which guards the four AI modules' copies of the canonical query set against `docs/canonical-query-set.md`.
It lives here because this is the module every build touches and the invariant is repo-wide.

## What is in here

```
demo-commons/
  data/       Customer · Address · CreditRating · CustomerRepository
  ui/         CustomerGrid (incl. CreditScoreIndicator) · AbstractCustomerSearchView
  ai/         CustomerSearchAgent · TokenUsageAdvisor · TokenUsageRecorder
  resources/  data.sql · META-INF/resources/credit-score-indicator.css
```

Each of those was byte-identical in every module that had it, and none of them says anything about how a
filter comes into being:

- **`data/`** — the domain. All four apps filter the same 100 customers.
- **`ui/CustomerGrid`** — the `Grid<Customer>` on screen: columns, revenue formatting, the coloured
  credit-rating indicator, the responsive breakpoints, and the backend sort configuration. A view may
  override the sorting; `01-non-ai-filter`'s in-memory view does, with `Comparator`s.
- **`ui/AbstractCustomerSearchView`** — the natural-language filter field above that grid plus the async
  plumbing behind it (off the UI thread, applied through `ui.access(...)`, error as a notification). Used
  by `02`/`03`/`04`; `01` has no AI layer and uses `CustomerGrid` alone.
- **`ai/CustomerSearchAgent`** — the one-method seam between view and AI layer. It names no Spring AI type,
  which is what lets `01` depend on this module without resolving Spring AI.
- **`ai/TokenUsageAdvisor` + `TokenUsageRecorder`** — the token and latency measurement, as a
  `ChatClient` advisor. Keeping it here is what leaves each `CustomerSearchService` with nothing but its
  prompt and its tool.

## What must never move in here

**The AI services, the `Criteria` / `Condition` / `Specifications` types, the `SYSTEM_PROMPT`s, the tool
signatures, and any `@Route` view.**

Those *are* the comparison this repository exists to make. Sharing them would not remove duplication, it
would remove the point: four modules that differ in nothing are not a ladder. The rule to apply when in
doubt:

> If a reader of the talk would need to see it on a slide to understand the difference between two
> approaches, it stays in its module.

The views are a borderline case worth spelling out. `AbstractCustomerSearchView` is shared because the
*plumbing* is identical and invisible — but each module keeps its own `@Route`-annotated subclass, because
which `CustomerSearchAgent` gets injected there is exactly the difference being demonstrated.

## Two constraints this module has to respect

**`01-non-ai-filter` must resolve neither Spring AI nor Micrometer.** It is the non-AI baseline, and its
dependency tree is checked for it. So both are declared `<optional>true</optional>` here, which stops them
propagating to consumers; `02`/`03`/`04` declare their own starters and actuator, as they always have.

**Hence: no `@Component` on `TokenUsageAdvisor` or `TokenUsageRecorder`.** A component scan reads
annotations from ASM metadata *without loading the class*, so Spring would find a `@Component` here while
running `01` and then fail to instantiate the bean with `NoClassDefFoundError`. Each of `02`/`03`/`04`
declares the recorder in its own 21-line `TokenUsageConfiguration`. An `@AutoConfiguration` with
`@ConditionalOnClass` would work too and was rejected on purpose: auto-configuration magic is the wrong
thing to explain from a stage.

## Build

A plain jar: no `spring-boot-maven-plugin` (nothing to repackage) and no `vaadin-maven-plugin` (this
module contributes no `@Route` and no frontend resources, so `build-frontend` has nothing extra to do).

The package names are the same as the apps' own — `dev.demo.vaadin.aigridfilter.data` / `.ui` / `.ai` —
which is not cosmetic: it is why the modules need no imports for these classes, why Hibernate's entity
scan and `JpaRepositoriesAutoConfiguration` find them across the jar boundary without `@EntityScan`, and
why `data.sql` is picked up from Boot's default `optional:classpath*:data.sql`.

That last point cuts both ways: `classpath*:` returns **every** match, so a second `data.sql` anywhere on
the classpath would be executed as well. There must be exactly one, and it is this one.

```bash
./mvnw verify -pl demo-commons
./mvnw install -DskipTests     # needed before spring-boot:run of any app, and after changing this module
```
