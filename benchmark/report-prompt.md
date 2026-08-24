Read every *.log and run-info.txt in <RESULT_DIR>/ and write a comparison report to
<RESULT_DIR>/report.md, the same content as HTML to <RESULT_DIR>/report.html, and the raw
per-call numbers to <RESULT_DIR>/report-data.json.

# What the report is for

One question, in this order: **which invocation method performs best, and which model/method
combination performs best** — measured by correctness, latency and token cost. Nothing else. It is
not a regression report between two runs, and not a diagnosis of a single variant.

Write it in English.

# The data

Each log is named <model>__<variant>__run<N>.log and holds exactly one IT class. The variants are
four ways of turning a natural-language query into a JPA Specification:

| Variant | Method (use this wording in the report) |
|---|---|
| 02a | tool calling, one scalar per field |
| 02b | tool calling, value + operator + negate |
| 03 | structured output |
| 04 | tool calling, 03's filter type |

Per test the logs contain "OK  <name>()", "FAIL <name>() - <reason>" or "SKIP <name>() - <reason>";
a FAIL reason may run over several following lines. Per model call: "Token usage for '<query>':
prompt=…, completion=…, total=…, time=… ms". Per class: "Token summary [<Class>]: N requests, …".
The queries are defined in docs/canonical-query-set.md.

**Compute, do not count by hand.** Write a throwaway script that parses all logs into rows of
(model, variant, run, query, call index, prompt, completion, total, ms) plus the test outcomes, and
derive every figure from it. Medians over 24 logs done in your head are where silent errors come
from. Write those rows to report-data.json — one object per model call — so every table in the
report can be recomputed without reading the logs again.

# Metrics

- **Test Reach** = (OK + FAIL) / all test methods. **Derive it from the logs; never hardcode it.**
  Cross-check it against the capability table in docs/canonical-query-set.md and say in the method
  section whether the two agree. If they disagree, say so plainly — someone added a test and did not
  update the document.
- **Pass** = OK / (OK + FAIL).
- **Median Latency/Query** = per query, sum the "time=" of its calls, take the median over the
  repetitions, then the median over that variant's queries.
- **Median Tokens/Query** = the same, over "total=".
- **Calls/Query** = the same, over the number of calls.
- **Model Size** and quantisation from the "installed:" JSON in run-info.txt.

Do not report tokens per second: it is close to a constant per model and does not separate the
invocation methods.

# Structure

Nothing above the appendix except these five blocks, in this order.

**1. One provenance line** — git commit (short), each model with the first 12 characters of its
digest, the number of runs, the Ollama version. The full digests go in the appendix.

**2. Verdict** — exactly these five items, in this order, always this shape:

- **Recommendation:** the winning model + method, with its three numbers.
- **Best invocation method:** which, and *why in terms of mechanism*. Up to three sentences — this
  is the only place where the comparison of the invocation methods is explained, so say what the
  round trips actually do, especially how the tool-calling variants' call count compares to 03's
  single call.
- **Best model:** which, and where it loses.
- **Surprise:** the one finding an audience would not guess from the setup. One line.
- **Do not demo live:** the model/method combination that would fail on stage, and how. One line.

**3. Table: invocation methods** — one row per variant, both models pooled. Columns: Variant,
Method, Test Reach, Pass, Median Latency/Query, Median Tokens/Query, Calls/Query.

**4. Table: model × method** — one row per model and variant, **grouped by variant**, not by model.
Columns as above plus Model Size. Mark the best value per column **bold**, but only among the rows
with full Test Reach — comparing cost across different reach compares different workloads, and the
cheapest row is usually the one that attempted the fewest queries. Put that warning under the table
as one sentence whenever a low-reach row holds a column minimum.

**5. Failures** — one line per failed test, no more: variant, model, test name, kind, and what came
back instead. State up front whether any test timed out.

Then a horizontal rule and the appendix: measurement quality, full-pass totals, prompt sizes, full
provenance, method. Nothing else.

# Length

The prose is capped, the tables are not — tables are data, and they grow with the number of models.

- Verdict: at most 9 lines.
- Each table: at most one sentence of prose under it.
- Measurement quality: at most 20 lines.
- Method: at most 3 paragraphs.
- No section of free prose anywhere else.

**Never repeat a number in prose that already stands in a table.** That single rule is what keeps
the report short; without it the tables get retold sentence by sentence.

# Rules, they matter

- SKIP is never a failure. It means that variant's filter type cannot express the query at all -
  an architectural limit, not a model mistake. It belongs in Test Reach and never in an error count.
- Separate timeouts from wrong answers. A FAIL whose reason contains "timed out after" is a speed
  problem; a FAIL with an assertion message is a wrong answer; a FAIL carrying an exception from the
  conversion layer is a third kind. Name the kind per failure, do not lump them together.
- Take the speed numbers from the advisor's "time=" values, not from the wall-clock seconds in
  run-info.txt - those include the Maven and Vaadin build.
- Flag any query where completion=512, that is the num-predict cap: the answer was cut off. **Say
  whether that test passed or failed.** A cut-off answer is not automatically a wrong result - the
  truncation can fall in the closing prose after the tool result, with the filter already applied.
- Flag repetitions of the same query that differ by more than a factor of two in time, and say so
  plainly rather than averaging the difference away. Name which repetition was the outlier: if it is
  the first query of run 1, it is the model being loaded, not a property of that query.
- Check the "resident" lines in run-info.txt. One model is healthy; a line naming two means that
  repetition shared its RAM with another model, so its speed numbers are not comparable - flag it and
  leave it out of the medians instead of averaging it in. If **every** repetition of a model/variant
  is affected, report no median for it at all rather than one taken from a single repetition.
- Quote the model digests and the git commit from run-info.txt, so it is clear which models and which
  code state were measured.
- If something in the logs is missing or ambiguous, say so instead of guessing. Name what the logs
  cannot answer - the tool call arguments, for instance, are not logged.
