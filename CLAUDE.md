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

Each of the four modules above is a standalone Spring Boot app (`<ModuleName>Application`) with
its own `data.sql`. For a module's architecture details, see `<module>/README.md` — do **not**
duplicate them here.

`ollama-benchmark` is **not** a Maven module (no `pom.xml`, not in the root `<modules>` list):
it's a standalone, dependency-free script benchmarking local Ollama models against all four AI
approaches. See `ollama-benchmark/README.md`.

The eight natural-language queries all AI modules are measured with live in
`docs/canonical-query-set.md` — the single source of truth; see the Definition of Done below.

## Build & Run

```bash
./mvnw verify -pl <module>                 # build + all tests of one module
./mvnw spring-boot:run -pl <module>        # start the app (on that module's port, see the table above)
./mvnw test -pl <module> -Dtest=<Class>    # run a single test class
```

AI provider is selected via Spring profiles: `openai` (default) or `ollama` (expects Ollama at
`OLLAMA_BASE_URL`; inside the dev container this is `http://host.docker.internal:11434`).

## Verification — Definition of Done

A task is only finished when:

1. `./mvnw verify -pl <affected modules>` passes.
2. For UI changes: the app has been started and the change verified via a Playwright screenshot
   (save screenshots to `~/screenshots/`).
3. For changes to filter/AI logic: the affected module's IT classes pass, run via `-Pit-local-ollama`
   (against a native Ollama instance) — the canonical-query IT (`StructuredCanonicalQueryIT` in 03/04,
   `ScalarCanonicalQueryIT`/`OperatorCanonicalQueryIT` in 02), plus the module's browserless IT for
   UI→AI changes. 03 additionally has `CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT`.
4. For new filter capabilities: the query goes into `docs/canonical-query-set.md` first, then into
   every module's canonical-query IT and into `ollama-benchmark/BenchmarkLocalModels.java` —
   verbatim in all copies. `CanonicalQuerySetConsistencyTest` fails the build if they drift apart,
   so all copies are always updated together.

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
  mechanism — there is no shared Maven module, the domain classes are duplicated per module on purpose).
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