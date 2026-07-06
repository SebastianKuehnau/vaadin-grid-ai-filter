# Task
Create a benchmark tool to compare local Ollama models regarding performance, accuracy,
and resource usage. The tool should execute CustomerSearchIT test cases against various
local models and collect detailed metrics. Output is provided as a formatted table in the
terminal and as report files (Markdown + TXT). If tests fail, display the error message
and record it in the report as well.

# Context
- Affected modules: `04-local-ai-filter`
- Purpose: Objective comparison of different local LLMs for natural language filter
  translation (CustomerFilter). Basis for model selection in demos and talks.
- Relevant existing parts:
  - `CustomerSearchIT.java`: The 24 test cases to be replicated
  - `CustomerSearchService.java`: System prompt and AI logic
  - `benchmark_models.py`: Previous Python tool (will be replaced)

# Approach
1. First create a plan (Plan-Mode) and show it to me. Wait for my OK.
2. Implement the plan. Work in sensible steps, commit after each verified
   step (Conventional Commits, no push).
3. Verify independently according to the Definition of Done in CLAUDE.md.
   Iterate on errors independently — do not present error messages to me for
   analysis that you can reproduce and fix yourself.

# Technical Requirements

## Implementation
- **Language:** Java (Single-Source-File)
- **Execution:** Directly from console/terminal without IDE or Maven:
  ```bash
  cd 04-local-ai-filter/src/test/scripts
  java BenchmarkLocalModels.java [model1] [model2] ...
  ```
- **Ollama Communication:** HTTP requests to `$OLLAMA_BASE_URL` (default: `http://localhost:11434`)
- **Dependencies:** Only Java Standard Library (Java 25+), no external libraries

## Test Cases
Use all ~24 test cases from `CustomerSearchIT.java`, ideally without code replication:
- Exact same system prompt as in `CustomerSearchService.systemPrompt()`
- Same validation logic as `hasCriterion()`
- Tolerant operator matching (CONTAINS/EQUALS often interchangeable)
- Relative date calculations (yesterday, last week, this year)

## Metrics per Model

### Accuracy
- Number of passed tests / total tests
- List of failed test cases with Expected vs. Got

### Performance
- **Total Duration (Wallclock):** Time from start to end of all tests
- **TTFT (Time-To-First-Token):** Median of first token response time across all queries
- **Tokens/s:** Median from `eval_count / (eval_duration / 1e9)` (Ollama metrics)
- **Median Latency:** Median of `total_duration` per query

### Resources
- **Model Size:** Disk space of the model (from `ollama list` API)
- **RAM Usage:** Heap usage before/after benchmark run
- **CPU Load:** Average CPU load during execution
- **GPU Usage:** If available (nvidia-smi), memory and utilization

## Benchmark Start
- **Start:** Time of invocation of `java BenchmarkLocalModels.java`
- **Model Configuration:** List of models as parameters or stored as a list in the application
- **Number of Runs** (default: 1)

## Output

### Terminal
Formatted table with all metrics:
```
Model              Accuracy    Median Lat.  TTFT      tok/s    RAM      CPU    Model Size
------------------ ----------- ------------ --------- -------- -------- ------ ------------
llama3.1:8b        23/24       850 ms       120 ms    45.2     2.1 GB   78%    4.7 GB
qwen3.5:4b         22/24       620 ms       95 ms     62.1     1.8 GB   82%    2.3 GB
```

On errors: List of failed cases with details.

### Report Files
- **`benchmark-report-<timestamp>.md`**: Markdown table for documentation
- **`benchmark-report-<timestamp>.txt`**: Plain-text table for scripts

Reports are saved in the current directory.

# Definition of Done (in addition to CLAUDE.md)
- `BenchmarkLocalModels.java` can be executed directly with `java`
- All 24 CustomerSearchIT cases are implemented and correctly validated
- Metrics are collected for all configured models
- Terminal output and both report files are correctly generated
- Previous `benchmark_models.py` is deleted
- At least one test run against an available Ollama model successful

# Final Summary
Summarize at the end: what was changed (per commit), which tests are green,
open issues/decisions I need to make.