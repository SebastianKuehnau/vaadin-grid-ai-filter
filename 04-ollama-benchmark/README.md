# 04-ollama-benchmark

A standalone **prompt-reliability eval** comparing local models — Ollama (default) or an MLX
Server — for accuracy and speed on the natural-language-to-filter task, covering **both** AI
approaches this project demos: `02-ai-agent-filter`'s tool calling and `03-ai-structured-filter`'s
structured output. Every case runs `--runs` times, so per-case **pass-rate** (not just a single
pass/fail) is measurable — the point being to answer, after editing a system prompt or a
`@ToolParam`/`@JsonPropertyDescription`, "does this still produce the correct filter with high
probability, and did any case regress?" on a fast inner loop (`--quick`).

This is **not a Maven module** — it is not listed in the root `pom.xml`'s `<modules>` and has no
`pom.xml` of its own. `BenchmarkLocalModels.java` is a dependency-free, single-file Java program
(JDK stdlib only), run directly with Java's source-file launcher — no Maven, no JUnit, no Spring
context.

## Running

```bash
cd 04-ollama-benchmark
java BenchmarkLocalModels.java                                    # auto-discovers tool-capable models from Ollama
java BenchmarkLocalModels.java llama3.1:8b qwen3:8b                # or benchmark specific models
java BenchmarkLocalModels.java --approach=both --runs=5            # both AI approaches, 5 runs/case, full set
java BenchmarkLocalModels.java --approach=tool-calling --runs=3     # only 02's tool-calling approach
java BenchmarkLocalModels.java --quick --runs=3                    # fast edit-loop subset (5 cases)
java BenchmarkLocalModels.java --min-pass-rate=0.8 --runs=5         # exit non-zero if any pass rate < 0.8
java BenchmarkLocalModels.java --backend=mlx                       # benchmark the model loaded in mlx_lm.server
java BenchmarkLocalModels.java --backend=mlx --base-url=http://localhost:9000
java BenchmarkLocalModels.java --mode=schema                       # schema-constrained instead of free-text JSON
java BenchmarkLocalModels.java --backend=mlx --think=off --debug-raw mlx-community/Qwen3-14B-4bit
java BenchmarkLocalModels.java --help                              # full usage/flags
```

By default it talks to Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434`), so start
Ollama and pull the models to compare first. With `--backend=mlx`, it talks to a local
[`mlx_lm.server`](https://github.com/ml-explore/mlx-lm) instance at `MLX_BASE_URL` (default
`http://localhost:8090`) instead — see [MLX Server backend](#mlx-server-backend) below. Either
base URL can be overridden with `--base-url=<url>`. Results are printed to the console and written
as `benchmark-report-<timestamp>.md`/`.txt` in the current directory.

### Both AI approaches, no drift

`--approach=tool-calling|structured|both` (default `structured`, unchanged from before) selects
which of the two production AI layers to evaluate, each driven by the **real** system prompt (and,
for tool calling, the real `searchCustomers` tool/argument schema) extracted at runtime from that
module's production source — never hard-coded, so the eval cannot drift from what the app does:

- **`structured`**: extracted from
  `../03-ai-structured-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java`
  (the `CustomerFilter`/flat-conditions-list shape).
- **`tool-calling`**: extracted from
  `../02-ai-agent-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchToolCallingService.java`
  — the `SYSTEM_PROMPT` constant plus the `searchCustomers` `@Tool`/`@ToolParam` descriptions, from
  which the tool's JSON Schema is built at runtime (the JSON-Schema *structure* per Java type —
  `List<String>` → array of strings, `List<CreditRating>` → enum, `List<RevenueRange>` → object with
  `atLeast`/`atMost` — is inherent Java-type-to-schema plumbing, not "the prompt"). Talks to
  Ollama's/the OpenAI-compatible API's native tool-calling (`tools`/`tool_calls`), single round trip
  — it does not implement the two-hop `currentLocalDateTime()` chain relative-date queries need,
  matching the same capability gap already documented in `02`'s own `CustomerSearchAgentIT`.

Each run's console line and report row is labeled with its approach, and a log line names the
source file each prompt was extracted from (proving no hard-coded copy).

The case list mirrors the aligned `CustomerSearchAgentIT` suites (see
`tasks/align-ai-integration-tests.md`) exactly — 16 cases shared by both approaches (same method
names/wording as `02`'s and `03`'s `CustomerSearchAgentIT`), plus 20 structured-only cases mirroring
`03`'s `CustomerSearchAgentExtraIT` (negation, operator precision, relative dates — capabilities
tool calling's flat `CustomerSearchCriteria` can't express — plus 3 anti-hallucination cases from
`tasks/harden-filter-test-assertions.md`, never verified against tool calling either).
`--approach=tool-calling` therefore runs 16 cases; `structured` and `both` run all 36.

### Pass-rate over K runs (`--runs`)

`--runs=N` (default `1`, i.e. today's single-shot pass/fail) runs every case `N` times and reports,
per case, a pass-rate of the form `passes/N`, plus an aggregate mean pass-rate per model/approach —
this is what makes a prompt-reliability *regression* visible (a case dropping from 3/3 to 2/3 across
edits) instead of a single non-deterministic pass/fail.

### Field-precise scoring

Each case has one exact expected outcome (which field(s) must be set, to which value, and — where
the approach exposes it — which operator/negation; every other field must stay empty). A run passes
a case only if **every** expected field is correct **and every unexpected field is empty** — so a
model that populates an unexpected field (e.g. a company-name query leaking into `email`) is scored
as a failure for that case, not a pass. Value matching stays tolerant (case-insensitive substring,
and revenue thresholds accept headroom instead of the literal number, mirroring the aligned ITs'
tolerance); field placement, operator, and negation are scored exactly. The report's "Per-field
accuracy" table (per model × approach, across the case suite × runs) is the annotation-tuning
readout — it localizes exactly which `@ToolParam`/`@JsonPropertyDescription` is weak.

### Fast edit-loop subset (`--quick`)

`--quick` runs 5 representative cases instead of the full 36/16: `singleCity` (plain text),
`companyNameContains` (a cross-field-leak risk case — company name could leak into
`email`/`contactName`), `annualRevenueOverThreshold` (numeric-tolerant revenue threshold),
`citiesAndRevenue_keepsEveryCondition` (multi-field AND), and `singleFalseCity` (structured-only
negation, so `--approach=both --quick` still exercises the capability gap). This is the loop to run
after every prompt edit; the full set is the considered verdict before committing.

### Scriptable gate (`--min-pass-rate`)

`--min-pass-rate=<X>` (0..1) makes the program exit non-zero if any evaluated model/approach's
aggregate mean pass rate falls below `X`, and exit zero otherwise — for wiring this eval into a
regression check on top of manual runs (not a CI gate on its own; see "Not a CI gate" below).

### Exact-numeric matching: catching wrong-magnitude numbers

Field-precise scoring above already catches over-generation (any field not in a case's expected set
must stay empty, for every case, always). One further opt-in, per-criterion strict behavior closes a
gap that field-precision alone doesn't: value matching normally stays tolerant (case-insensitive
substring, and revenue thresholds accept headroom instead of the literal number). **Exact-numeric
matching** (`new NumericExact(field, value)`) instead requires the value to parse to the exact same
number as expected (formatting/currency/thousands-separators tolerated), instead of a substring or
headroom-tolerant threshold — so a required `1000` no longer accepts a returned `1000000`. Applied
only to a genuinely exact query (`revenueExact_notOverGenerated`); the range-style revenue cases
(`citiesAndRevenue_keepsEveryCondition`, `citiesWithRevenueRange`, the `notInCityWithRevenue...`
cases) keep their deliberately headroom-tolerant `NumericAtLeast`/`NumericAtMost` matching.

Three robustness/anti-hallucination cases exercise this: `smalltalk_noCriteria` and
`unrelatedRequest_noCriteria` run against **both** approaches (small talk / an off-topic query must
yield an empty filter, i.e. every field stays empty — verified for tool-calling and structured
output alike), and `revenueExact_notOverGenerated` stays structured-only ("exactly 100000 in annual
revenue" — `EQUALS` on a numeric field, exact value, with every other field required to stay empty
by the universal field-precision check), since `EQUALS` precision is not expressible in `02`'s flat
`CustomerSearchCriteria`.

## MLX Server backend

`--backend=mlx` talks to a local [`mlx_lm.server`](https://github.com/ml-explore/mlx-lm) instance
(Apple Silicon only) via its OpenAI-compatible API, instead of Ollama's native API. Start it
manually on the host first, e.g. (same command as `application-mlx.properties` in
`02-ai-agent-filter`/`03-ai-structured-filter`):

```bash
pip install mlx-lm
mlx_lm.server --model mlx-community/Meta-Llama-3.1-8B-Instruct-4bit --port 8090
```

**Note:** `mlx_lm.server` serves exactly *one* loaded model per process — unlike Ollama, which can
enumerate and switch between many pulled models per request. `--backend=mlx` (no model argument)
auto-detects whichever model the server reports via `GET /v1/models`. If you pass a positional
model name that doesn't match what's actually loaded, it's skipped with a `WARN:` message instead
of silently mislabeling results — to compare multiple MLX models, restart `mlx_lm.server` with a
different `--model` between runs.

Metrics that have no equivalent in the OpenAI-compatible API are reported as `n/a` for this
backend: model size and VRAM (no `/api/tags`/`/api/ps`-style endpoint exposes them). Token
throughput (tok/s) is computed from the response's `usage.completion_tokens` divided by measured
wall-clock request duration, since there's no equivalent to Ollama's native `eval_duration`
(generation-only timing) — MLX tok/s therefore includes network and prompt-evaluation overhead and
isn't directly comparable to Ollama's on-device-only timing.

**Unrelated to the `-mlx`-suffixed models in the results table below** (e.g. `qwen3.5:4b-mlx`,
`gemma4:26b-mlx`) — those are Apple-Silicon-optimized quantizations run *through Ollama's own
runtime* (`--backend=ollama`, the default), not through this MLX Server backend.

### Diagnosing `<think>`-block/reasoning-mode issues (`--think`, `--debug-raw`)

Reasoning-capable models (Qwen3 and others) can spend their entire `max_tokens`/`num_predict` budget
on an internal `<think>...</think>` block before ever emitting the JSON answer — on the MLX backend
this showed up as `mlx-community/Qwen3-14B-4bit` scoring 22/32 with a 22011 ms median latency and
several completely empty responses (see `benchmark-report-2026-07-14-214443.md`).

- `--think=on|off` (MLX backend only, default `on`): when `off`, appends Qwen3's documented
  `/no_think` soft-switch to the user query, disabling its internal reasoning step. This is plain
  user-turn text, not an API parameter, so it works regardless of the installed `mlx_lm.server`
  version. Ollama already always sends `"think":false` natively — the flag is a no-op there.
- `--debug-raw`: captures each call's full, unprocessed HTTP response body (before any JSON parsing
  or regex fallback) and includes it in the generated `.md` report as a
  `## Raw responses (--debug-raw): <model> [<approach>]` appendix, for failed runs only. Use this
  together with `--think=off` to inspect exactly what a reasoning model returned when a case fails —
  this is also how the tool-calling approach's JSON-encoded-string-instead-of-array quirk for
  `annualRevenue` (see `normalizeToolCallArgs` in the source) was originally found.

See `benchmark-report-2026-07-15-*.md` for the thinking-disabled re-run and its conclusion.

## Schema-constrained output (`--mode=schema`)

By default (`--mode=freeform`, unchanged), the model is asked to produce JSON purely through prompt
instructions — every one of the three 2026-07-14 reports shows this breaking down in the same ways
across nearly every model/backend tested: duplicate JSON keys (e.g. two `"children"` keys in one
object, where the second silently wins and the first list is lost), `NOT` emitting `children`
instead of `child`, truncated/unbalanced JSON, or fields returned as unstructured strings.

`--mode=schema` instead constrains generation with a hand-rolled JSON Schema for the flat
conditions list (a single `conditions` array, no `$ref`/`oneOf`/recursion at all), enforcing the
same shape production defines in `CustomerFilter.java`/`Condition.java`/`Operator.java`:

- **Ollama**: the schema is passed directly in the native `/api/chat` request's `"format"` field
  (grammar-constrained decoding) instead of the generic `"format":"json"` string. This works for
  *any* model Ollama can serve — no tool-calling capability required — so enabling schema mode for a
  new Ollama model needs no code change, just `--mode=schema <model>`.
- **MLX server**: sent as an OpenAI-style `"response_format":{"type":"json_schema","json_schema":
  {...,"strict":true}}` field, best-effort — `mlx_lm.server`'s support for this is version-dependent.
  If the server rejects it, that surfaces as a normal per-case/per-model failure (same as any other
  HTTP error), not a crash.

Historically (before the flat-schema migration below), an A/B of freeform vs. schema mode against
the old recursive `FilterNode` tree found accuracy parity but **zero malformed-JSON symptoms** in
schema mode (no duplicate keys, no truncation, no `child`/`children` confusion) — schema-constrained
output fixes the *shape* problem but not the *reasoning* problem (a model can still emit valid JSON
that drops or misplaces a condition).

### Flat schema migration: before/after

The `FilterNode` AND/OR/NOT/CONDITION tree was replaced with a flat list of conditions (values
OR-combined per field, a `negate` flag instead of `NOT_*` operators, no cross-field OR, no nesting)
— see `03-ai-structured-filter`'s README for the schema itself. Measured on the same host, same
`llama3.1:8b`, same `--mode=schema`, directly before/after the migration (this container's
hardware, not the M2 Pro Mac numbers in [Recorded results](#recorded-results) below — treat the two
tables as separate baselines, not comparable to each other):

| Schema | Cases | Accuracy | Median latency |
| --- | --- | --- | --- |
| Old recursive `FilterNode` tree | 32 | 27/32 (84%) | 1577 ms |
| New flat conditions list | 30 | 29/30 (97%) | 1256 ms |

**~20% lower median latency, and higher accuracy** with the smallest/fastest configured model —
the old tree's 5 failures were exactly the kind of nesting/placement mistakes the flat schema
structurally rules out (e.g. flattening `NOT`+`CONDITION` into a bare `CONDITION`, or dropping a
condition when translating an intended `AND` into a single `OR`). The one remaining flat-schema
failure (`notInCityWithRevenueAndYear`, a 3-condition query needing negation + a two-sided year
range) is a case where `llama3.1:8b` sometimes drops one bound of the range — `qwen3:8b` handles it
correctly every time; see `03-ai-structured-filter`'s README for that model-capability note.

## Recorded results

**Predates both the flat-schema migration and the case-set alignment with `02`/`03`'s ITs** (32
cases, old recursive `FilterNode` tree, `--mode=freeform`, single-shot accuracy — no `--runs`/
`--approach` yet) — see "Flat schema migration: before/after" above for the directly-comparable
old-tree-vs-new-flat numbers. Kept here as a multi-model accuracy/speed comparison; re-run with the
current case set (36 for `--approach=structured`, 16 for `tool-calling`; see "Both AI approaches, no
drift" above) if
you want up-to-date numbers for a model not covered above.

**Test system:** MacBook Pro, Apple **M2 Pro** (12 cores: 8 performance + 4 efficiency), 32 GB
unified memory, macOS 26.5.1 (build 25F80), Ollama 0.30.11. Apple-Silicon-optimized `mlx` variants
were preferred where available. Run on 2026-07-06 with `BenchmarkLocalModels.java`, all with the
default `--backend=ollama` (see [MLX Server backend](#mlx-server-backend) above for the distinct
`--backend=mlx` feature — no results for that backend are recorded here yet; run it against your
own `mlx_lm.server` and add a row/table if you'd like to include it).

| Model | Accuracy | Median latency | TTFT | Tokens/s | Model size |
| --- | --- | --- | --- | --- | --- |
| `llama3.2:1b` | 12/32 | 1195 ms | 179 ms | 113.9 | 1.2 GB |
| `qwen3.5:9b-mlx` | 26/32 | 3898 ms | 9270 ms | 26.8 | 8.3 GB |
| `qwen3.5:4b-mlx` | 29/32 | 1366 ms | 5164 ms | 45.0 | 3.7 GB |
| `gemma4:e4b` | 29/32 | 1726 ms | 442 ms | 41.2 | 8.9 GB |
| `gemma4:e4b-mlx` | 28/32 | 1664 ms | 3582 ms | 42.5 | 9.0 GB |
| `gemma4:12b-mlx` | 30/32 | 2814 ms | 16431 ms | 20.2 | 6.3 GB |
| `qwen3:8b` | 29/32 | 1796 ms | 291 ms | 30.3 | 4.9 GB |
| `gemma4:26b-mlx` | 31/32 | 1571 ms | 6149 ms | 40.5 | 15.5 GB |
| `llama3.1:8b` (module default) | 27/32 | 1561 ms | **290 ms** | 33.0 | 4.6 GB |

Takeaways:

- **`gemma4:26b-mlx` is the most accurate** (31/32), but at 15.5 GB it's the heaviest model tested.
- **`llama3.1:8b`, the module's configured default, is not the most accurate** (27/32) — `qwen3:8b`,
  `gemma4:e4b`, `qwen3.5:4b-mlx` (all 29/32) and `gemma4:12b-mlx` (30/32) score higher at a similar or
  smaller size; it remains the default mainly for its fast, consistent time-to-first-token (290 ms).
- **`llama3.2:1b` is unsuitable** (12/32) — too small to reliably nest AND/OR/NOT filter trees.
- **High TTFT hurts the "MLX" quantizations** despite otherwise-decent accuracy — `gemma4:12b-mlx`
  (16.4 s) and `qwen3.5:9b-mlx` (9.3 s) feel slow to first response even though their token throughput
  is fine once generation starts.
- Alternative models are available by uncommenting the corresponding line in
  `../03-ai-structured-filter/src/main/resources/application.properties`.

Results are non-deterministic and hardware-dependent, so treat them as a trend rather than fixed numbers.
