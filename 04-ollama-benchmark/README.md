# 04-ollama-benchmark

A standalone benchmark comparing local Ollama models for accuracy and speed on the
natural-language-to-filter task used by `03-ai-structured-filter`.

This is **not a Maven module** — it is not listed in the root `pom.xml`'s `<modules>` and has no
`pom.xml` of its own. `BenchmarkLocalModels.java` is a dependency-free, single-file Java program
(JDK stdlib only), run directly with Java's source-file launcher — no Maven, no JUnit, no Spring
context.

## Running

```bash
cd 04-ollama-benchmark
java BenchmarkLocalModels.java                          # auto-discovers tool-capable models from Ollama
java BenchmarkLocalModels.java llama3.1:8b qwen3:8b      # or benchmark specific models
```

It talks to Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434`), so start Ollama and
pull the models to compare first. Results are printed to the console and written as
`benchmark-report-<timestamp>.md`/`.txt` in the current directory.

The script extracts the real system prompt directly from
`../03-ai-structured-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java`,
so it cannot drift from production behaviour, and runs the same 32 natural-language queries as
that module's `CustomerSearchAgentIT` (including the 4 German ones) — see that module's README for
what each query tests.

## Recorded results

**Test system:** MacBook Pro, Apple **M2 Pro** (12 cores: 8 performance + 4 efficiency), 32 GB
unified memory, macOS 26.5.1 (build 25F80), Ollama 0.30.11. Apple-Silicon-optimized `mlx` variants
were preferred where available. Run on 2026-07-06 with `BenchmarkLocalModels.java`.

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
