# Benchmark

Compares the four AI variants by **speed, token consumption and correctness**, measured with the
integration tests that already exist, against **several local Ollama models**.

`benchmark.sh` orchestrates the runs, collects the raw logs, and then has Claude Code turn them into
`report.md` — see [The report](#the-report). No line of bash parses a log: the prompt does the
reading, so the summary can be argued with and changed without touching the script.

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
| `BENCHMARK_REPORT` | `true` | `false` leaves the logs unsummarized; needs `claude` on `PATH` |

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
before every repetition, using the same `num_ctx` as the apps so it warms the runner the tests use.

## Only one model at a time

Two 8B models are ~12 GB, and Ollama does not evict the old one to make room — it **fits the new
model around the memory the old one still holds**, which is then measured as the new model being
slow for its whole run. So the script frees every resident model — not only the ones it pinned —
at three points, each time waiting for `/api/ps` to confirm rather than assuming:

- **at the start**, against leftovers from an earlier run,
- **at every model switch**, before the next model is pinned,
- **in an `EXIT` trap**, so Ctrl-C out of an hours-long run leaves nothing behind.

That last one matters because the apps ask for `keep-alive=1h`: a model **outlives the process that
loaded it** by up to an hour. Stopping the demo app or aborting a benchmark does not free it, and
the next run would otherwise measure next to it. To check by hand:

```bash
ollama ps          # should be empty before a benchmark, one model during it
```

`run-info.txt` records what was resident before each Maven invocation, so a measurement taken next
to a second model stays recognizable afterwards instead of just looking slow.

## What it writes

```
benchmark/results/<timestamp>/
    run-info.txt                       # commit, branch, Ollama version, raw /api/tags, config
    qwen3-8b__02a__run1.log            # ":" and "/" in model names become "-" and "_"
    qwen3-8b__02a__run2.log
    …
```

Git-ignored — a single run is not a stable state of the project. `run-info.txt` also collects two
lines per combination: what was resident going in, and the exit code with the wall-clock seconds:

```
resident  qwen3:8b                 02a  run1   qwen3:8b
qwen3:8b                 02a  run1   exit=1   612s
```

One model on the `resident` line is the healthy case. Two means that measurement shared its RAM and
its numbers are not comparable.

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

## The report

When the last model has been freed, the script runs Claude Code over the result directory and writes
`report.md` next to the logs — it reads the logs from disk, so nothing has to be pasted into a chat.

The prompt it uses is [`report-prompt.md`](report-prompt.md), with `<RESULT_DIR>` replaced by the
run's directory. That one file is the only copy — edit it to change what the report contains, and
the script needs no change. Read it there rather than here, so the two can never drift apart.

The logs stay the artifact worth keeping. A missing `claude`, a failed report or
`BENCHMARK_REPORT=false` costs you the summary, never the measurement — the script says so and exits
normally. To summarize a run afterwards, or to redo one with a changed prompt:

```bash
claude -p "$(sed 's|<RESULT_DIR>|benchmark/results/<timestamp>|g' benchmark/report-prompt.md)" \
  --allowedTools Read Write Glob Grep
```

Then ask follow-up questions in that session — the context is loaded, so "which queries did every
model get wrong?" or "build me the slide table for 03 vs 04" is cheap.
