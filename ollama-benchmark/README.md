# ollama-benchmark

A standalone **prompt-reliability eval** comparing local models — Ollama (default) or an MLX
Server — for accuracy and speed on the natural-language-to-filter task, covering **all four** AI
approaches this project demos:

| `--approach=` | Module | Filter type | Delivery |
| --- | --- | --- | --- |
| `02a` | `02-ai-agent-filter`, flat variant | one scalar value per field | tool call, 13 parameters |
| `02b` | `02-ai-agent-filter`, operator variant | value + `Operator` + `negate` per field | tool call, 39 parameters |
| `03` | `03-ai-structured-filter` | `CustomerFilter` = `List<Condition>` | structured output |
| `04` | `04-ai-hybrid-filter` | the same `List<Condition>` | tool call, 1 parameter |

Every case runs `--runs` times, so per-case **pass-rate** (not just a single pass/fail) is measurable —
the point being to answer, after editing a system prompt or a
`@ToolParam`/`@JsonPropertyDescription`, "does this still produce the correct filter with high
probability, and did any case regress?" on a fast inner loop (`--quick`).

This is **not a Maven module** — it is not listed in the root `pom.xml`'s `<modules>` and has no
`pom.xml` of its own. `BenchmarkLocalModels.java` is a dependency-free, single-file Java program
(JDK stdlib only), run directly with Java's source-file launcher — no Maven, no JUnit, no Spring
context.

## Running

```bash
cd ollama-benchmark
java BenchmarkLocalModels.java                                    # auto-discovers tool-capable models from Ollama
java BenchmarkLocalModels.java llama3.1:8b qwen3:8b                # or benchmark specific models
java BenchmarkLocalModels.java --runs=5                            # all four approaches (default), 5 runs/case
java BenchmarkLocalModels.java --approach=02b,04 --runs=3          # only 02(b) and 04
java BenchmarkLocalModels.java --quick --runs=3                    # fast edit-loop subset (4 canonical cases)
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

### Four AI approaches, no drift

`--approach=all` (the default) or a comma-separated list of `02a,02b,03,04` selects which production AI
layers to evaluate. Each one is driven by the **real** system prompt — and, for the three tool-calling
approaches, the real `searchCustomers` tool/argument schema — extracted at runtime from that module's
production source, never hard-coded, so the eval cannot drift from what the apps do:

- **`02a`**: `SYSTEM_PROMPT` plus the 13 scalar `@ToolParam`s of
  `../02-ai-agent-filter/.../ai/flat/CustomerSearchService.java`. Gets the second tool,
  `currentLocalDateTime()`, same as `02b` — offered only because the source declares it. The tool fixes
  which date value the model fills in; it does not lift the whole-year/minimum-only semantics of
  `CustomerSpecifications`, so a range query is still architecturally out of reach for this approach.
- **`02b`**: `SYSTEM_PROMPT` plus the 39 `@ToolParam`s of
  `../02-ai-agent-filter/.../ai/operator/CustomerSearchService.java`. Its operator/negate
  descriptions are shared `static final String` constants in that class (13 fields would otherwise repeat
  them); the extractor resolves those constants, so the model sees exactly the app's text.
- **`03`**: the `systemPrompt(LocalDate)` text block of
  `../03-ai-structured-filter/.../ai/CustomerSearchService.java`, with the relative dates
  resolved the same way the module resolves them, plus the response-shape reminder Spring AI adds for
  structured output.
- **`04`**: the `systemPrompt(LocalDate)` text block of
  `../04-ai-hybrid-filter/.../ai/CustomerSearchService.java` (no JSON-shape tail — it
  calls a tool), and a `List<Condition>` parameter schema built from `Condition.java`'s own
  `@JsonClassDescription`/`@JsonPropertyDescription` texts, i.e. from the same annotations Spring AI reads
  when it generates that tool's schema at runtime.

The JSON-Schema *structure* per Java type (`String` → string, `CreditRating`/`Operator` → enum,
`BigDecimal` → number, `List<Condition>` → array of condition objects) is inherent
Java-type-to-schema plumbing, not "the prompt". Each run's console line and report row is labeled with its
approach, and a log line names the source file each prompt was extracted from (proving no hard-coded
copy).

**One deliberate limitation:** this harness talks to the backend's native tool-calling API in a *single*
round trip — it reads the first `searchCustomers` call and stops. It therefore does not execute a chained
tool call, so `02b` cannot pass `C7_RELATIVE_DATE` here (that query needs its `currentLocalDateTime()` hop
first); the module's canonical-query IT, which runs the real Spring AI tool loop, does. The generated
report repeats this caveat next to the matrix, so a `0/1` there is never mistaken for a model failure.

#### Three case groups

- **Canonical query set** (primary): the eight queries of `../docs/canonical-query-set.md`, the same ones
  all four modules' ITs run. Their wording lives verbatim in this script — the second and last copy
  besides `00-commons`' `CanonicalQuery` enum, because this script is deliberately standalone and
  dependency-free. Keeping it verbatim is what makes these token/latency figures comparable
  query-for-query with the ITs' pass/fail results.
  Each canonical case names the approaches whose filter type can express it at all; for the others the
  case is reported as `n/a` (and listed on stdout), because an architectural limit is not a reliability
  problem. The report's "Canonical query set" section is the resulting matrix: one row per query, one
  column per approach.
- **Robustness set**: the five cases of `00-commons`' `RobustnessQuery`, worded verbatim here — the same
  five the modules' `*CustomerSearchIT` assert, so together with the canonical eight this script covers
  exactly the 13 cases those ITs run. Four ask for *no* filter at all (small talk, an unrelated question,
  "show me all customers", an explicit reset) and are scored on every field staying empty; `GERMAN_QUERY`
  must filter exactly as its English equivalent `C1_SINGLE_VALUE` does.
  There is no `n/a` in this group: none of these needs a filter type, so all four approaches run all five
  and are expected to pass them. A failing cell is therefore a reliability finding — a hallucinated
  condition slipped through the prompt — and not an architectural ceiling. That is the failure mode a live
  demo hits first, which is why it gets its own matrix in the report rather than being folded into the
  legacy set.
- **Legacy set**: the older prompt-regression cases that used to mirror `03-ai-structured-filter`'s
  `CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT`. Those two IT classes were superseded by the
  canonical query set and removed, so this script is now the only place the cases still run — which is
  the main reason to keep them. Unlike the canonical set, they run against **all
  four** approaches without pre-classifying which ones can express them — they were written for the
  condition-list filter type, so a case needing negation, a second value for one field, or a range shows
  up as a low or zero pass rate for `02a`/`02b` rather than as `n/a`. They are kept because they are the
  accumulated prompt-tuning safety net, and running them everywhere makes the per-field variants'
  ceiling visible on a much larger case set than the eight canonical queries alone. The report's "Legacy
  set" section is the same kind of matrix as the canonical one (one row per case, one column per
  approach).

### Approach performance summary

The report's "Approach performance summary" table aggregates **across every tested model**, one row
per approach: passed test runs (canonical, robustness and legacy separately), total prompt tokens, total completion
tokens, total tokens, and total wall-clock time — all true sums over every call actually made for that
approach (not medians), so it reflects the approach's real cost independent of which models were
benchmarked. The pass columns read `passed/performed (share)` rather than a bare percentage, because
the percentage alone hides how many runs it rests on: one run is one case sent once to one model, so a
group's run count is its case count times `--runs`, summed over every tested model — and a model that
failed outright (an `ERROR` row) performs no runs and is not counted under "Models tested". A group
that ran no cases at all (e.g. the legacy and robustness groups under `--cases=canonical`) reads `n/a`
instead of `0%`.
Which individual runs failed is in the per-case tables further down the report. This is what makes e.g. `02b-operator`'s 39-parameter tool schema's prompt-token overhead
visible against `04-hybrid`'s single-parameter one, and lets 03/04's combined canonical+legacy pass
rate and total time be compared directly against 02(a)/02(b)'s. Ollama's native API reports
`prompt_eval_count`/`eval_count`; the MLX (OpenAI-compatible) backend reports the equivalent
`usage.prompt_tokens`/`usage.completion_tokens`.

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

`--quick` runs four canonical cases instead of the full set: `C1_SINGLE_VALUE` (plain text),
`C5_COMBINED_AND` (multi-field AND) and the two cases only the condition-list approaches can express,
`C2_MULTI_VALUE_OR` and `C6_REVENUE_RANGE` — so a quick run exercises both the shared ground and the
capability gap. This is the loop to run after every prompt edit; the full set is the considered verdict
before committing.

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

Three robustness/anti-hallucination cases in the legacy group exercise this: `smalltalk_noCriteria` and
`unrelatedRequest_noCriteria` (small talk / an off-topic query must yield an empty filter, i.e. every
field stays empty) and `revenueExact_notOverGenerated` ("exactly 100000 in annual revenue" — `EQUALS` on a
numeric field, exact value, with every other field required to stay empty by the universal
field-precision check).

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
same shape production defines in `CustomerFilter.java`/`Condition.java` (whose nested `Operator` carries the six values):

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

**Predates the flat-schema migration, the canonical query set and the four-approach setup** (32 cases,
old recursive `FilterNode` tree, `--mode=freeform`, single-shot accuracy — no `--runs`/`--approach` yet)
— see "Flat schema migration: before/after" above for the directly-comparable old-tree-vs-new-flat
numbers. Kept here as a multi-model accuracy/speed comparison of the *models*, which is still what it is
useful for. For up-to-date, per-approach figures run:

```bash
java BenchmarkLocalModels.java --approach=all --runs=5 qwen3:8b llama3.1:8b
```

and paste the report's "Canonical query set" matrix into `../docs/capability-matrix.md` (whose
"Reliability across models" table now holds a 2026-08-03 `--runs=5` measurement of all four approaches
over four models).

> **Known bug — `04-hybrid` scores 0% for models that stringify the tool argument.** `conditionLeaves`
> accepts `conditions` only as a JSON array; `llama3.1:8b` sends the correct condition list as a
> JSON-*encoded string*, and every such call normalizes to an empty filter. Structured output's parser
> already tolerates this class of deviation, tool calling's does not, and the asymmetry makes tool calling
> look worse than it is. Reproduce with `--approach=04 --quick --debug-raw llama3.1:8b`; fix planned in
> `../tasks/benchmark-argument-parsing-and-ram-readout.md`.

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
| `qwen3:8b` (module default) | 29/32 | 1796 ms | 291 ms | 30.3 | 4.9 GB |
| `gemma4:26b-mlx` | 31/32 | 1571 ms | 6149 ms | 40.5 | 15.5 GB |
| `llama3.1:8b` | 27/32 | 1561 ms | **290 ms** | 33.0 | 4.6 GB |

Takeaways:

- **`gemma4:26b-mlx` is the most accurate** (31/32), but at 15.5 GB it's the heaviest model tested.
- **`qwen3:8b`, the module's configured default, balances accuracy and responsiveness** (29/32 at a
  fast, consistent 291 ms time-to-first-token and 4.9 GB) — only `gemma4:12b-mlx` (30/32) and
  `gemma4:26b-mlx` (31/32) score higher, both larger and with far higher TTFT. `llama3.1:8b` (27/32)
  was the earlier default and is kept here as the weaker-model reference point.
- **`llama3.2:1b` is unsuitable** (12/32) — too small to reliably produce the structured
  multi-condition filter.
- **High TTFT hurts the "MLX" quantizations** despite otherwise-decent accuracy — `gemma4:12b-mlx`
  (16.4 s) and `qwen3.5:9b-mlx` (9.3 s) feel slow to first response even though their token throughput
  is fine once generation starts.
- Alternative models are available by uncommenting the corresponding line in
  `../03-ai-structured-filter/src/main/resources/application.properties`.

Results are non-deterministic and hardware-dependent, so treat them as a trend rather than fixed numbers.
