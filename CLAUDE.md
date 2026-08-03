# CLAUDE.md — ai-grid-filter

Demo project for conference talks: filtering data with natural language (Spring AI + Vaadin).
Top priority for all code: **easy to understand, presentable, extensible** — clarity beats cleverness.

## Modules

| Module | Port | Approach |
|---|---|---|
| `01-non-ai-filter` | 8081 | Classic filtering without AI (baseline): an in-memory `Stream` filter view and a lazy `Specification`-based filter view |
| `02-ai-agent-filter` | 8082 | AI filtering via tool calling, in two variants behind two routes of one app: 02(a) one scalar value per field (`/`), 02(b) value + operator + negate per field (`/operator`) |
| `03-ai-structured-filter` | 8083 | AI filtering via structured output (`CustomerFilter` → JPA Specifications), against local Ollama models |
| `04-ai-hybrid-filter` | 8084 | AI filtering via tool calling with 03's `List<Condition>` filter type, copied 1:1 — same capability, different delivery |
| `demo-commons` | — | Shared **runtime** infrastructure: the domain layer (`Customer`, `Address`, `CreditRating`, `CustomerRepository`, `data.sql`), the shared Vaadin components (`CustomerGrid`, `AbstractCustomerSearchView`) and the AI layer's seam plus token measurement (`CustomerSearchAgent`, `TokenUsageAdvisor`). No numeric prefix — not a step of the talk. See `demo-commons/README.md` for the rule on what must never move there |

Each of the four numbered modules above is a standalone Spring Boot app (`<ModuleName>Application`).
The single `data.sql` lives in `demo-commons` and is picked up from the jar (Boot's default
`optional:classpath*:data.sql`) — there must never be a second copy, or the data is seeded twice.
For a module's architecture details, see `<module>/README.md` — do **not** duplicate them here.

`ollama-benchmark` is **not** a Maven module (no `pom.xml`, not in the root `<modules>` list):
it's a standalone, dependency-free script benchmarking local Ollama models against all four AI
approaches. See `ollama-benchmark/README.md`.

The eight natural-language queries all AI modules are measured with live in
`docs/canonical-query-set.md` — the single source of truth; see the Definition of Done below.

## Build & Run

```bash
./mvnw verify -pl <module> -am             # build + all tests of one module
./mvnw install -DskipTests                 # once, before the first spring-boot:run (see below)
./mvnw spring-boot:run -pl <module>        # start the app (on that module's port, see the table above)
./mvnw test -pl <module> -am -Dtest=<Class># run a single test class
```

`-am` (also-make) is needed for **all four** apps: every one of them depends on `demo-commons`, which
Maven has to build first. Without it a `-pl` build fails to resolve that dependency.

`spring-boot:run` cannot use `-am` (it would try to run `demo-commons` too) and resolves dependencies
from `~/.m2`, so `demo-commons` has to be installed once — `./mvnw install -DskipTests` — and again after
every change to it. With a running app, `./mvnw install -pl demo-commons -DskipTests` is enough: each app
watches `../demo-commons/target/classes` (`spring.devtools.restart.additional-paths`), so devtools
restarts it and picks up the new jar. Use `install`, not `compile` — `compile` fires the trigger but
leaves the jar the app loads untouched.

AI provider is selected via Spring profiles: `openai` (default) or `ollama` (expects Ollama at
`OLLAMA_BASE_URL`; inside the dev container this is `http://host.docker.internal:11434`).

## Verification — Definition of Done

A task is only finished when:

1. `./mvnw verify -pl <affected modules> -am` passes.
2. For UI changes: the app has been started and the change verified via a Playwright screenshot
   (save screenshots to `~/screenshots/`).
3. For changes to filter/AI logic: the affected module's IT classes pass, run via `-Pit-local-ollama`
   (against a native Ollama instance). Every AI module runs two kinds, both extending a shared base
   in `demo-commons`' test-jar: the `*AiFilterIT` (through the service — the eight canonical queries
   and the five robustness cases, one test method each) and the browserless IT (the same eight
   through the UI). 02 has one of each per variant, so four.
4. For new filter capabilities: the query goes into `docs/canonical-query-set.md` first, then into
   `demo-commons`' `CanonicalQuery` enum and into `ollama-benchmark/BenchmarkLocalModels.java` —
   verbatim in both copies, kept in sync by hand. Each variant then has to say what the new query
   means for it: its `expectedResultFor` method is an exhaustive `switch`, in both of its ITs, so all
   of them stop compiling until that decision is made — `MATCH` or `NO_MATCH_BY_DESIGN`.

Points 1–3 apply before **every** commit, not only at the end of the task.
Iterate on your own until all points are met before reporting the task as done.

## Plan approval gate

When a task requests a plan first: present the plan, then STOP and wait for an
explicit go-ahead before changing any file. A later check-in from the user is
never "stale" — treat it as authoritative. Do not begin implementation, commits,
or file changes on your own initiative.

## Conventions

- Keep layers separated: view (Vaadin) / AI service / repository — no AI calls inside views.
- UI texts and code comments in English.
- Changes affecting multiple modules must be applied consistently in **all** affected modules
  (01 → 02(a) → 02(b) → 03 → 04 increase in expressiveness; same domain, different filtering
  mechanism — everything that *is* the comparison stays duplicated per module on purpose, so each
  step can be read on its own).
- **One exception to that duplication:** `demo-commons`, a compile dependency of all four apps. It
  owns the domain layer, the shared Vaadin components, the `CustomerSearchAgent` seam and the token
  measurement. **Never** the AI services, the `Criteria`/`Condition`/`Specifications` types, the
  `SYSTEM_PROMPT`s, the tool signatures or any `@Route` view. The rule when in doubt: if a reader of
  the talk would need to see it on a slide to understand the difference between two approaches, it
  stays in its module. See `demo-commons/README.md`.
- The test layer is the second exception, and it is shared through `demo-commons`' **test-jar**
  (`<type>test-jar</type><scope>test</scope>`), never through `src/main` — otherwise JUnit and
  browserless would land in all four apps' runtime classpath. It owns the query sets and the two
  abstract ITs. What stays per module is the one thing that differs: an `expectedResultFor` method
  saying which queries that variant's filter type can express.
- CSS belongs in theme files, not inline in Java components.
- Commit after every completed, verified step (Conventional Commits, no push).
- Never commit benchmark reports, logs, or other generated artifacts unless the
    task explicitly says so — they are covered by .gitignore; verify the staged
    file list (`git status`) before every commit.
- For Spring test configuration, prefer test-scoped `application.properties`
  files over custom `ActiveProfilesResolver` or `@DynamicPropertySource`
  mechanisms — choose the simplest configuration approach that works.
- Only launch subagents with a concrete deliverable and a step limit, and show
  their results directly — never wait silently on background agents. If a file
  referenced by the task is missing, stop and report instead of improvising.

## Guidelines & Skills

- For Vaadin-specific patterns, use the skills from the Vaadin Claude plugin (e.g. `responsive-layouts`)
  before inventing your own solutions.
- Additional project-specific guidelines: `guidelines/` (if present — check there first for tasks
  involving Grid, filtering, or AI configuration).

## What NOT to do

- No framework/dependency upgrades without an explicit request (the Spring AI version is pinned on purpose).
- Do not restructure `data.sql`, only extend it — the demo data is tailored to the talks.
- Do not rewrite this file on your own initiative; it is maintained via `/update-claude-md`.