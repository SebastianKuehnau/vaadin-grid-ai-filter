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
| `00-commons` | — | Shared **runtime** infrastructure: the domain layer (`Customer`, `Address`, `CreditRating`, `CustomerRepository`, `data.sql`), the shared Vaadin components (`CustomerGrid`, `AbstractCustomerSearchView`) and the AI layer's seam plus token measurement (`CustomerSearchAgent`, `TokenUsageAdvisor`). No numeric prefix — not a step of the talk |

Each of the four numbered modules above is a standalone Spring Boot app (`<ModuleName>Application`).
The single `data.sql` lives in `00-commons` and is picked up from the jar (Boot's default
`optional:classpath*:data.sql`) — there must never be a second copy, or the data is seeded twice.
Each module's architecture is meant to be read from its own source; there are no per-module READMEs.

The eight natural-language queries all AI modules are measured with live in
`docs/canonical-query-set.md` — the single source of truth; see the Definition of Done below.

## Build & Run

```bash
./mvnw verify -pl <module> -am             # build + all tests of one module
./mvnw install -DskipTests                 # once, before the first spring-boot:run (see below)
./mvnw spring-boot:run -pl <module>        # start the app (on that module's port, see the table above)
./mvnw test -pl <module> -am -Dtest=<Class># run a single test class
```

`-am` (also-make) is needed for **all four** apps: every one of them depends on `00-commons`, which
Maven has to build first. Without it a `-pl` build fails to resolve that dependency.

`spring-boot:run` cannot use `-am` (it would try to run `00-commons` too) and resolves dependencies
from `~/.m2`, so `00-commons` has to be installed once — `./mvnw install -DskipTests` — and again after
every change to it. With a running app, `./mvnw install -pl 00-commons -DskipTests` is enough: each app
watches `../00-commons/target/classes` (`spring.devtools.restart.additional-paths`), so devtools
restarts it and picks up the new jar. Use `install`, not `compile` — `compile` fires the trigger but
leaves the jar the app loads untouched.

The Spring profile says **which** backend answers: `ollama` (default) or `openai` (needs
`OPENAI_API_KEY`). Two environment variables say **where** that Ollama runs — see below.

`./mvnw verify` runs the Ollama-backed ITs — they are the default, not behind a profile, and they
bring their own Ollama: `OllamaContainerConfig` (in `00-commons`' test-jar) starts
`00-commons/src/test/resources/ollama/Dockerfile`, which bakes in `qwen3:8b`, and Spring AI's
`@ServiceConnection` wires `spring.ai.ollama.base-url` to it. **Docker is therefore required**;
`-DskipITs` builds without one. Two escape hatches:

- `OLLAMA_TESTCONTAINER=false` skips the container, so the ITs use `spring.ai.ollama.base-url`
  (`${OLLAMA_BASE_URL:http://localhost:11434}`) — an Ollama of your own, which needs `qwen3:8b`.
- `AI_TEST_PROFILE=openai` runs them against the OpenAI API instead.

The container is marked reusable and the three AI modules set `TESTCONTAINERS_REUSE_ENABLE=true` in
their failsafe configuration, so **one** container serves every Spring context — without that, each
context starts its own Ollama and their resident models exhaust the machine's RAM. It outlives the
build on purpose; `docker rm -f $(docker ps -q --filter ancestor=ai-grid-filter/ollama:qwen3-8b)`
removes it. See `docs/adr/0002-ollama-as-a-testcontainer.md`.

**The app is never run inside the sandbox** — only its tests are. `spring-boot:run` and the
Playwright screenshots below belong on the development machine.

## Benchmark

`benchmark/benchmark.sh` compares the four variants by speed, tokens and correctness: it runs each
variant's IT class against every named model and writes one log per model/variant/repetition to
`benchmark/results/<timestamp>/` (git-ignored). Not a Maven module — the reactor ignores it.
`benchmark/README.md` documents it, including the prompt that turns the logs into a report.

```bash
OLLAMA_MODELS=qwen3:8b,llama3.2:3b ./benchmark/benchmark.sh   # BENCHMARK_RUNS defaults to 3
```

It needs a **local Ollama** with those models installed, not the Testcontainer — it sets
`OLLAMA_TESTCONTAINER=false` and `OLLAMA_MODEL` per run, which the three
`application-ollama.properties` pick up via `${OLLAMA_MODEL:qwen3:8b}`. It deliberately does not
parse the logs: `TestNameLoggingExtension` already writes `OK`/`FAIL`/`SKIP` per test and
`TokenUsageAdvisor` the tokens and milliseconds, so summarizing them is a separate step. A failing
test never aborts the run — for a weaker model that failure *is* the measurement.

## Verification — Definition of Done

A task is only finished when:

1. `./mvnw verify -pl <affected modules> -am` passes.
2. For UI changes: on the development machine, the app has been started and the change verified
   via a Playwright screenshot (save screenshots to `~/screenshots/`). In the sandbox, where the app
   is not run, the browserless IT takes its place.
3. For changes to filter/AI logic: the affected module's IT classes pass against the Ollama
   Testcontainer (they run in plain `verify`). Every AI module has two kinds: the `*CustomerSearchIT`
   (through the service — the eight canonical queries and the five robustness cases) and the
   browserless IT (the same eight through the UI). 02 has one of each per variant, so four.
4. For new filter capabilities: the query goes into `docs/canonical-query-set.md` first, then into
   every AI module's two IT classes as one named `@Test` — the prompt as a string literal, the
   expected customer set computed from the seeded data. Where a variant's filter type cannot express
   it, the test still spells out what it would assert and carries `@Disabled` with the reason. No
   compile-time gate enforces this; the table in that document is the checklist.

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
- **One exception to that duplication:** `00-commons`, a compile dependency of all four apps. It
  owns the domain layer, the shared Vaadin components, the `CustomerSearchAgent` seam and the token
  measurement. **Never** the AI services, the `Criteria`/`Condition`/`Specifications` types, the
  `SYSTEM_PROMPT`s, the tool signatures or any `@Route` view. The rule when in doubt: if a reader of
  the talk would need to see it on a slide to understand the difference between two approaches, it
  stays in its module.
- The test layer is the second exception, and it is shared through `00-commons`' **test-jar**
  (`<type>test-jar</type><scope>test</scope>`), never through `src/main` — otherwise JUnit and
  browserless would land in all four apps' runtime classpath. It owns **only mechanism**:
  `AbstractCustomerSearchViewIT.search(query)` (navigate, type, await, read the grid),
  `TokenUsageExtension` and `TestNameLoggingExtension`. Every query, every expectation and every
  `@Disabled` reason stays in the module's own IT class, spelled out — that is what a reader looks at.
- Comments are one-liners: a single-line Javadoc per class, and per method or field only where the
  name is not enough. No multi-paragraph rationale in code — this repository is read by beginners at a
  conference, and a wall of prose above a five-line method is what they stop reading at.
- CSS belongs in theme files, not inline in Java components.
- Commit after every completed, verified step (Conventional Commits, no push).
- Never commit logs or other generated artifacts unless the task explicitly says
    so — they are covered by .gitignore; verify the staged file list (`git status`)
    before every commit.
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
