Read every *.log and run-info.txt in <RESULT_DIR>/ and write me a
comparison report as Markdown to <RESULT_DIR>/report.md.

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
- Check the "resident" lines in run-info.txt. One model is healthy; a line naming two means that
  repetition shared its RAM with another model, so its speed numbers are not comparable - flag it
  and leave it out of the medians instead of averaging it in.
- Quote the model digests and the git commit from run-info.txt at the top of the report, so it is
  clear which models and which code state were measured.
- If something in the logs is missing or ambiguous, say so instead of guessing.
