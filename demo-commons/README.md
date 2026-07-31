# demo-commons

Shared **runtime** infrastructure for all four apps. No numeric prefix, because this is not a step of the
talk — it is the scaffolding the four steps stand on.

This module is the repository's **one deliberate exception** to its "duplication per module is on
purpose" rule. Everything else stays duplicated, so each numbered module can be read on its own.

## The test-jar

Its test sources are a second, separate shared thing, published as `demo-commons-<version>-tests.jar`
(`maven-jar-plugin`'s `test-jar` goal). 02/03/04 consume it as

```xml
<artifactId>demo-commons</artifactId>
<type>test-jar</type><scope>test</scope>
```

A test-jar rather than ordinary main code, because otherwise JUnit, AssertJ and
`browserless-test-spring` would become compile dependencies of this module and land in all four apps'
runtime classpath.

It holds the shared test foundation, in package `canonicalquery`:

| Class | Role |
|---|---|
| `CanonicalQuery` | the eight queries of `docs/canonical-query-set.md`, each with the reference predicate a correct answer satisfies |
| `RobustnessQuery` | the five cases that ask for *no* filter, plus one query in German |
| `Outcome` | `SUCCESS` · `FAIL_BY_DESIGN` |
| `AbstractCanonicalQueryIT` | the canonical set through a module's `CustomerSearchAgent` |
| `AbstractCustomerListViewBrowserlessIT` | the same set through the UI: filter field in, grid rows out |
| `AbstractPromptRobustnessIT` | the robustness set; no `Outcome`, every variant must pass all five |
| `CanonicalQuerySetConsistencyTest` | guards `CanonicalQuery` and the benchmark script against the document |

The same rule applies here as to the main sources: what a variant can *express* is the comparison the talk
is about, so it stays in the module — as a `*Outcomes` class with an exhaustive `switch`, read by both of
that variant's ITs.

## What is in here

```
demo-commons/
  data/       Customer · Address · CreditRating · CustomerRepository
  ui/         CustomerGrid (incl. CreditScoreIndicator) · AbstractCustomerSearchView
  ai/         CustomerSearchAgent · TokenUsageAdvisor
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
- **`ai/CustomerSearchAgent`** — the one-method seam between view and AI layer. It names no Spring AI type
  at all: everything about the model lives behind it, in each module's own `CustomerSearchService`.
- **`ai/TokenUsageAdvisor`** — the whole token and latency measurement, as a `ChatClient` advisor.
  Keeping it here is what leaves each `CustomerSearchService` with nothing but its prompt and its tool.
  It sits *innermost* in the advisor chain on purpose: Spring AI's tool loop re-enters the chain for the
  follow-up call, so only an innermost advisor sees every round trip — and only then are its totals the
  real cost of a query. See its Javadoc.

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

## One trade this module makes, on purpose

`spring-ai-client-chat` is a **regular** dependency here, not `<optional>`, which is what lets
`TokenUsageAdvisor` be a plain `@Component`: it sits in `dev.demo.vaadin.aigridfilter.ai`, every app scans
that package, and one annotation replaces the three identical `@Configuration` classes `02`/`03`/`04` used
to carry.

The cost lands on `01-non-ai-filter`, which resolves **17 further artefacts** through that line — the four
Spring AI jars plus `jtokkit`, the victools JSON-Schema modules, ANTLR/ST4, `micrometer-core` and
`spring-messaging`. It is still the non-AI baseline in every sense that matters: no Spring AI code, no
`spring-ai-starter-model-*`, so no autoconfiguration and no `ChatModel`. The only reachable thing is a
`TokenUsageAdvisor` bean that is created and never called — verified on a running app.

Making the dependency `<optional>` again is possible, and brings the three configuration classes straight
back: with `01` scanning the same package, an annotated class there is found from ASM metadata and then
fails to instantiate with `NoClassDefFoundError`. That trade — 17 artefacts against 54 lines — was weighed
and settled in favour of the annotation. An `@AutoConfiguration` with `@ConditionalOnClass` would avoid
both, and was rejected on purpose: auto-configuration magic is the wrong thing to explain from a stage.

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
./mvnw install -DskipTests                      # once, before the first spring-boot:run of any app
./mvnw install -pl demo-commons -DskipTests     # after changing this module — restarts a running app
```

The apps reach this module as a jar from `~/.m2`, and `spring-boot-devtools` watches directories rather
than jars — so on its own, reinstalling while an app runs would change nothing visible. Each app therefore
sets `spring.devtools.restart.additional-paths=../demo-commons/target/classes`: that gives devtools the
trigger it lacks, and the restart then loads the freshly installed jar. Verified on all four apps.
`compile` is not enough — it updates the watched directory but not the jar.
