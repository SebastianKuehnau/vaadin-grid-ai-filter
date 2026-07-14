# 04-ollama-benchmark

A standalone benchmark comparing local models — Ollama (default) or an MLX Server — for accuracy
and speed on the natural-language-to-filter task used by `03-ai-structured-filter`.

This is **not a Maven module** — it is not listed in the root `pom.xml`'s `<modules>` and has no
`pom.xml` of its own. `BenchmarkLocalModels.java` is a dependency-free, single-file Java program
(JDK stdlib only), run directly with Java's source-file launcher — no Maven, no JUnit, no Spring
context.

## Running

```bash
cd 04-ollama-benchmark
java BenchmarkLocalModels.java                          # auto-discovers tool-capable models from Ollama
java BenchmarkLocalModels.java llama3.1:8b qwen3:8b      # or benchmark specific models
java BenchmarkLocalModels.java --backend=mlx             # benchmark the model loaded in mlx_lm.server
java BenchmarkLocalModels.java --backend=mlx --base-url=http://localhost:9000
java BenchmarkLocalModels.java --help                    # full usage/flags
```

By default it talks to Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434`), so start
Ollama and pull the models to compare first. With `--backend=mlx`, it talks to a local
[`mlx_lm.server`](https://github.com/ml-explore/mlx-lm) instance at `MLX_BASE_URL` (default
`http://localhost:8090`) instead — see [MLX Server backend](#mlx-server-backend) below. Either
base URL can be overridden with `--base-url=<url>`. Results are printed to the console and written
as `benchmark-report-<timestamp>.md`/`.txt` in the current directory.

The script extracts the real system prompt directly from
`../03-ai-structured-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java`,
so it cannot drift from production behaviour, and runs the same 32 natural-language queries as
that module's `CustomerSearchAgentIT` (including the 4 German ones) — see that module's README for
what each query tests.

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

## Recorded results

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
