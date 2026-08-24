# Benchmark

Compares the four AI variants by **speed, token consumption and correctness**, measured with the
integration tests that already exist, against **several local Ollama models**.

`benchmark.sh` only orchestrates and collects raw logs. It deliberately does not parse them — see
[Turning the logs into a report](#turning-the-logs-into-a-report) for the AI prompt that does.

## Prerequisites

A **local Ollama** with the models you want to measure — not the Testcontainer the ITs normally use
(that container bakes in exactly one model, while a benchmark has to pick the model):

```bash
ollama pull qwen3:8b
ollama pull llama3.2:3b
ollama list          # the names you pass must match this output exactly
```

Nothing else. The script builds through Maven, so no `install` beforehand is needed.

## Running it

```bash
OLLAMA_MODELS=qwen3:8b ./benchmark/benchmark.sh
```

| Variable | Default | Meaning |
|---|---|---|
| `OLLAMA_MODELS` | — (**required**) | comma-separated model names, exactly as `ollama list` prints them |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | the Ollama daemon to measure against |
| `BENCHMARK_RUNS` | `3` | repetitions per model and variant |

Several models, three repetitions each — the usual full run:

```bash
OLLAMA_MODELS=qwen3:8b,llama3.2:3b,mistral:7b ./benchmark/benchmark.sh
```

One repetition, for a quick look:

```bash
OLLAMA_MODELS=qwen3:8b BENCHMARK_RUNS=1 ./benchmark/benchmark.sh
```

Five repetitions against an Ollama on another machine:

```bash
OLLAMA_MODELS=qwen3:8b BENCHMARK_RUNS=5 \
  OLLAMA_BASE_URL=http://192.168.1.42:11434 ./benchmark/benchmark.sh
```

Spaces around the commas are fine. Every model is checked against `/api/tags` **before** the first
build, so a typo fails in a second instead of after an hour.

## What it runs

One `mvn verify` per model, variant and repetition, with `OLLAMA_TESTCONTAINER=false` and
`OLLAMA_MODEL=<model>` — which the three `application-ollama.properties` pick up through
`${OLLAMA_MODEL:qwen3:8b}`.

| Variant | Module | IT class | enabled | `@Disabled` |
|---|---|---|---|---|
| 02a | `02-ai-agent-filter` | `FlatCustomerSearchIT` | 7 | 6 |
| 02b | `02-ai-agent-filter` | `OperatorCustomerSearchIT` | 10 | 3 |
| 03 | `03-ai-structured-filter` | `StructuredCustomerSearchIT` | 13 | 0 |
| 04 | `04-ai-hybrid-filter` | `HybridCustomerSearchIT` | 13 | 0 |

All four classes hold the same 13 test methods with the same names — the eight canonical queries plus
the five robustness cases of [`docs/canonical-query-set.md`](../docs/canonical-query-set.md). The
`@Disabled` ones are **limits of that variant's filter type, not model failures**: no prompt and no
model can make a filter type carry a value it has no slot for. A report must never count them as
errors. The browserless UI ITs are excluded — they exercise the same logic behind browser overhead.

So one repetition is 43 test executions across four Maven invocations, and the default of three
repetitions makes 129 per model. On CPU that is **hours per model**, not minutes. Model loading is
kept out of the measurement: the script pins the weights via `/api/generate` with `keep_alive: 30m`
before every repetition and unloads them before switching models, so two models never compete for RAM.

## What it writes

```
benchmark/results/<timestamp>/
    run-info.txt                       # commit, branch, Ollama version, raw /api/tags, config
    qwen3-8b__02a__run1.log            # ":" and "/" in model names become "-" and "_"
    qwen3-8b__02a__run2.log
    …
```

Git-ignored — a single run is not a stable state of the project. `run-info.txt` also collects one
overview line per combination, with the exit code and the wall-clock seconds:

```
qwen3:8b                 02a  run1   exit=1   612s
```

A failing test never aborts the run. For a weaker model a failure *is* the measurement.

## Reading the logs

Four line shapes carry everything. `TestNameLoggingExtension` writes the outcome per test:

```
--> findsCustomersInOneCity()
OK  findsCustomersInOneCity()
FAIL findsCustomersInEitherOfTwoCities() - <assertion message, or "… timed out after 300 seconds">
SKIP findsCustomersWithinARevenueRange() - 02(a) holds one value per field - a range needs a lower and an upper bound
```

`TokenUsageAdvisor` writes the cost, per model call and per test class:

```
Token usage for 'show me all customers in Berlin': prompt=2108, completion=512, total=2620, time=44181 ms
Token summary [HybridCustomerSearchIT]: 1 requests, 2620 total tokens (prompt=2108, completion=512), avg 2620 tokens/request, 44181 ms total, avg 44181 ms/request
```

`requests` is where the variants differ structurally: the tool-calling variants (02a, 02b, 04) need
several round trips per query, the structured-output variant (03) needs one.

Three traps when interpreting the numbers:

- **Use `time=` from the advisor for speed, not the wall-clock in `run-info.txt`.** The latter includes
  Maven startup, the `00-commons` build, Vaadin's `build-frontend` goal and the Spring context boot.
- **`@Timeout(300s)` reports, it does not abort.** A test can run far longer than 300 s and still fail
  on its assertion, in which case the timeout never surfaces. Where it does, the message says
  `timed out after 300 seconds` — so a timeout is distinguishable from a wrong answer, but only if
  you look for it.
- **The same query varies a lot.** One measured example: an identical call took 44 s in one repetition
  and 206 s in the next, on the same model and machine. That is why the default is three repetitions
  and why a report should use medians, not means.

## Turning the logs into a report

Point Claude Code at a result directory and paste the prompt below. It reads the logs, so run it in
the repository (`claude` in the repo root) rather than pasting log contents into a chat.

````text
Read every *.log and run-info.txt in benchmark/results/<timestamp>/ and write me a
comparison report as Markdown to benchmark/results/<timestamp>/report.md.

Context you need:
- Each file is named <model>__<variant>__run<N>.log. The variants 02a, 02b, 03 and 04 are four
  ways of turning a natural-language query into a JPA Specification: 02a tool calling with one
  scalar per field, 02b tool calling with value + operator + negate, 03 structured output, 04 tool
  calling with 03's filter type. See docs/canonical-query-set.md for the queries.
- Per test the logs contain "OK  <name>()", "FAIL <name>() - <reason>" or "SKIP <name>() - <reason>".
- Per model call: "Token usage for '<query>': prompt=…, completion=…, total=…, time=… ms".
  Per class: "Token summary [<Class>]: N requests, …".

Extract per model, variant and repetition: which tests passed, failed or were skipped, and the
tokens, milliseconds and request count per query.

Then produce:
1. A correctness table, variant x model, as "passed / enabled". Give the enabled count per variant
   (02a 7, 02b 10, 03 13, 04 13) so the columns are comparable, and list which named tests failed.
2. A cost table, variant x model: median total tokens per query, median ms per query, and median
   model calls per query. Use medians across the repetitions, not means.
3. A short section per variant on what the numbers say about that invocation method - especially
   how the tool-calling variants' request count compares to 03's single call.
4. A list of the findings I should be careful about, see the rules below.

Rules, they matter:
- SKIP is never a failure. It means that variant's filter type cannot express the query at all -
  an architectural limit, not a model mistake. Report skips separately and never in an error count.
- Separate timeouts from wrong answers. A FAIL whose reason contains "timed out after" is a speed
  problem; a FAIL with an assertion message is a wrong answer. Do not lump them together.
- Take the speed numbers from the advisor's "time=" values, not from the wall-clock seconds in
  run-info.txt - those include the Maven and Vaadin build.
- Flag any query where completion=512, that is the num-predict cap: the answer was cut off, which
  is a likely cause of the wrong result and not a normal wrong answer.
- Flag repetitions of the same query that differ by more than a factor of two in time, and say so
  plainly rather than averaging the difference away.
- Quote the model digests and the git commit from run-info.txt at the top of the report, so it is
  clear which models and which code state were measured.
- If something in the logs is missing or ambiguous, say so instead of guessing.
````

Ask follow-up questions in the same session — the context is already loaded, so
"which queries did every model get wrong?" or "build me the slide table for 03 vs 04" is cheap.
