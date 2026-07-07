# CLAUDE.md — ai-grid-filter

Demo project for conference talks: filtering data with natural language (Spring AI + Vaadin).
Top priority for all code: **easy to understand, presentable, extensible** — clarity beats cleverness.

## Modules

| Module | Approach |
|---|---|
| `01-simple-filter` | Classic filtering without AI (baseline) |
| `02-lazy-filter` | Lazy loading / DataProvider-based filtering |
| `03-ai-filter` | AI filtering via tool calling |
| `04-local-ai-filter` | AI filtering via structured output (`CustomerFilter` → JPA Specifications), against local Ollama models |

Each module is a standalone Spring Boot app (`<ModuleName>Application`) with its own `data.sql`.
For a module's architecture details, see `<module>/README.md` — do **not** duplicate them here.

## Build & Run

```bash
./mvnw verify -pl <module>                 # build + all tests of one module
./mvnw spring-boot:run -pl <module>        # start the app (http://localhost:8080)
./mvnw test -pl <module> -Dtest=<Class>    # run a single test class
```

AI provider is selected via Spring profiles: `openai` (default) or `ollama` (expects Ollama at
`OLLAMA_BASE_URL`; inside the dev container this is `http://host.docker.internal:11434`).

## Verification — Definition of Done

A task is only finished when:

1. `./mvnw verify -pl <affected modules>` passes.
2. For UI changes: the app has been started and the change verified via a Playwright screenshot
   (save screenshots to `~/screenshots/`).
3. For changes to filter/AI logic: the relevant IT class passes —
   `CustomerSearchIT` (test cases, provider-agnostic), run via `LocalOllamaTests$CustomerSearch`
   (against a native Ollama instance, profile `it-local-ollama`).
4. For new filter capabilities: a corresponding test case has also been added to
   `04-local-ai-filter/src/test/scripts/benchmark_models.py`.

Iterate on your own until all points are met before reporting the task as done.

## Conventions

- Keep layers separated: view (Vaadin) / AI service / repository — no AI calls inside views.
- UI texts and code comments in English.
- Changes affecting multiple modules must be applied consistently in **all** affected modules
  (01 → 04 increase in complexity; same domain, different filtering mechanism).
- CSS belongs in theme files, not inline in Java components.
- Commit after every completed, verified step (Conventional Commits, no push).

## Guidelines & Skills

- For Vaadin-specific patterns, use the skills from the Vaadin Claude plugin (e.g. `responsive-layouts`)
  before inventing your own solutions.
- Additional project-specific guidelines: `guidelines/` (if present — check there first for tasks
  involving Grid, filtering, or AI configuration).

## What NOT to do

- No framework/dependency upgrades without an explicit request (the Spring AI version is pinned on purpose).
- Do not restructure `data.sql`, only extend it — the demo data is tailored to the talks.
- Do not rewrite this file on your own initiative; it is maintained via `/update-claude-md`.