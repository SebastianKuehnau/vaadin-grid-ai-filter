# Capability matrix: the four AI approaches

Which query types each approach can express, and how reliably it does so — derived strictly from the
modules' integration-test suites and the `ollama-benchmark` harness. Companion to
[tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md) and
[canonical-query-set.md](canonical-query-set.md).

- **02(a) = `02-ai-agent-filter`, flat variant** — tool calling; the LLM calls
  `@Tool searchCustomers(...)` with **13** parameters, one scalar value per field →
  `FlatCriteria` → `FlatSpecifications`. No operator, no negation.
- **02(b) = `02-ai-agent-filter`, operator variant** — tool calling; the same tool with **39**
  parameters, a value + `Operator` + `negate` per field → `OperatorCriteria` →
  `OperatorSpecifications`.
- **03 = `03-ai-structured-filter`** — structured output; the LLM returns one `CustomerFilter`
  (a flat `List<Condition>`, each `field / operator / values / negate`) → `CustomerFilterSpecifications`.
- **04 = `04-ai-hybrid-filter`** — tool calling; the LLM calls
  `@Tool searchCustomers(List<Condition>)` — 03's filter type, copied 1:1, delivered as a tool call.

**Cell legend:** ✅ reliably handled · ⚠️ model-dependent · ❌ not expressible by the approach's filter
type. ❌ means *architecturally impossible*, not *unreliable*: no prompt and no model can make a filter
type carry a value it has no slot for.

Configured default model for all four modules: `qwen3:8b`, temperature 0
(`src/main/resources/application-ollama.properties`).

## The canonical query set — measured on the resulting customer set

These are the eight queries of [canonical-query-set.md](canonical-query-set.md), run by every module's
canonical-query IT with **identical wording** (a per-module `CanonicalQuerySetConsistencyTest` fails the
build if any copy drifts). Each case is scored on the *resulting customer set*: the returned
`Specification` is executed against the seeded database and the matching customer ids are compared with
those of a reference predicate — not on whether the extracted filter "looks right".

| Query type (canonical case) | 02(a) | 02(b) | 03 | 04 | Expected rows |
|---|---|---|---|---|---|
| Single value — "customers in Berlin" (`C1_SINGLE_VALUE`) | ✅ | ✅ | ✅ | ✅ | 18 |
| Multi-value OR — "Berlin or Hamburg" (`C2_MULTI_VALUE_OR`) | ❌ | ❌ | ✅ | ✅ | 35 |
| Negation — "except from Berlin" (`C3_NEGATION`) | ❌ | ✅ | ✅ | ✅ | 82 |
| Non-CONTAINS operator — "'m' as the first character" (`C4_OPERATOR_PRECISION`) | ❌ | ✅ | ✅ | ✅ | 6 |
| Combined AND — "creditworthy customers in Hamburg" (`C5_COMBINED_AND`) | ✅ | ✅ | ✅ | ✅ | 9 |
| Revenue range — "between 100000 and 200000" (`C6_REVENUE_RANGE`) | ❌ | ❌ | ✅ | ✅ | 37 |
| Relative date — "in the last 12 months" (`C7_RELATIVE_DATE`) | ❌ | ✅ | ✅ | ✅ | 15 (or 14) |
| Date range — "between 2024-07-01 and 2025-03-31" (`C8_DATE_RANGE`) | ❌ | ❌ | ✅ | ✅ | 4 |
| **Categories reached** | **2 / 8** | **5 / 8** | **8 / 8** | **8 / 8** | |

Evidence: `FlatCanonicalQueryIT`, `OperatorCanonicalQueryIT` (both in `02-ai-agent-filter`) and
`StructuredCanonicalQueryIT` (in `03-ai-structured-filter` and `04-ai-hybrid-filter`). The ❌ cells are not skipped
— they run, and the IT asserts that the resulting set *differs* from the expected one, so a ceiling is a
recorded, non-erroring failure. If such a case ever matched, the test would fail loudly.

What the ❌ cells actually returned in the recorded run, which is more instructive than the mark itself:

| Case | 02(a) returned | 02(b) returned | Why |
|---|---|---|---|
| `C2_MULTI_VALUE_OR` (35 expected) | 17 rows (one city) | 18 rows (one city) | one value parameter per field: the second city has nowhere to go |
| `C3_NEGATION` (82) | 18 rows (the Berlin customers) | ✅ 82 | 02(a) has no `negate` flag, so "except" inverts into nothing |
| `C4_OPERATOR_PRECISION` (6) | 58 rows (CONTAINS "m") | ✅ 6 | 02(a) always substring-matches; every name containing an "m" comes back |
| `C6_REVENUE_RANGE` (37) | 21 rows | 43 rows (one bound) | a range needs two bounds on one field; 02(a) has a minimum only, 02(b) one operator only |
| `C7_RELATIVE_DATE` (15/14) | 16 rows (whole calendar year) | ✅ 15 | 02(a) has no operator and no clock tool; a date always means its whole year |
| `C8_DATE_RANGE` (4) | 9 rows (whole year) | 21 rows (`>= 2024-07-01`) | same as C6, and the window deliberately spans two calendar years |

**03 and 04 agree on all eight categories**, with the exact expected row counts. They share the filter
type, so that is the expected outcome; it is worth stating explicitly because it is the measurement
behind "the delivery mechanism does not change what a filter can express".

## Token cost and latency of the same eight queries

Measured by the `TokenUsageRecorder` on the real application path (the same bean the app uses), over
**three consecutive `-Pit-local-ollama` runs** on `qwen3:8b`, averaged over all eight canonical queries —
so every row answers the identical questions. Token counts were identical in all three runs (temperature
0 makes each prompt deterministic); the duration column is the median of the three:

| Approach | Tool/schema shape | Tokens/request | prompt | completion | Duration/request |
|---|---|---|---|---|---|
| 02(a) flat | 13 flat parameters | **1154** | 1120 | 34 | 2856 ms |
| 02(b) value+operator+negate | 39 flat parameters | **3248** | 3154 | 94 | 6071 ms |
| 03 structured output | `CustomerFilter` response schema | **2307** | 2243 | 64 | 4089 ms |
| 04 hybrid | 1 `List<Condition>` parameter | **2358** | 2288 | 70 | 5128 ms |

Two things this table says that the capability columns cannot:

- **03 and 04 cost nearly the same** — 2243 vs 2288 prompt tokens/request (**+2.0 %**), 2307 vs 2358 in
  total (+2.2 %). The same filter type costs the same order of tokens whether Spring AI sends it as a
  response-format schema or as a tool-parameter schema; delivery is close to token-neutral, and the small
  remainder is prompt wording, not mechanism.
- **02(b) costs more than 03/04 and reaches fewer categories** — 3248 tokens for 5 of 8, against 2358 for
  8 of 8. Per-field operator plumbing is not a cheap shortcut to expressiveness; it is the expensive way
  to get less of it.

The per-query breakdown lives in
[tool-calling-vs-structured-output.md § Token cost and request duration](tool-calling-vs-structured-output.md#token-cost-and-request-duration).

## Reliability across models

Whether an approach *reliably* produces the right filter — as opposed to being able to express it at all
— is a per-model question, answered by `ollama-benchmark` (`--approach=all --runs=5`), which runs the
same canonical queries as the ITs plus its own legacy prompt-regression set.

> **Not yet re-measured for the four-approach setup.** The harness does run all four approaches (verified
> with `--quick --runs=1` on `qwen3:8b`), but the multi-model reliability table that used to stand here
> described the two-approach setup that no longer exists, and stale numbers are worse than none.
> Reproduce with:
>
> ```bash
> cd ollama-benchmark
> java BenchmarkLocalModels.java --approach=all --runs=5 qwen3:8b llama3.1:8b
> ```
>
> The report's "Canonical query set" section is the matrix to paste back here: one row per query, one
> column per approach, with mean pass rate, median latency and median tokens/s per column.

What *is* measured, from the recorded IT runs on `qwen3:8b`: every ✅ above passed and every ❌ failed in
the way the table describes — in **three consecutive runs**, with identical results each time.

When comparing those results with a benchmark report, note that the two harnesses score different things,
which shows up in exactly two places on the canonical set:

- The ITs score the **resulting customer set**; the benchmark scores the **extracted filter**, and counts a
  field the query did not ask for as a failure even when it changes nothing. In a recorded run 02(b) added
  `country=Germany` to "creditworthy customers in Hamburg" — harmless for the rows (every Hamburg customer
  in the seed data is German), so the IT passes and the benchmark does not.
- The benchmark performs a **single round trip** and does not execute chained tool calls, so 02(b)'s
  `C7_RELATIVE_DATE` — which needs its `currentLocalDateTime()` hop — fails there by construction. The IT,
  running the real Spring AI tool loop, passes it. That is why C7 is ✅ for 02(b) above.

## Where tool calling has an edge, and what it costs

| Query type | 02(a) | 02(b) | 03 | 04 | Evidence |
|---|---|---|---|---|---|
| **Relative date via a live clock** ("in the last 12 months") | ❌ | ⚠️ chained `currentLocalDateTime()` — model-dependent | ✅ "today" baked into `systemPrompt(LocalDate)` | ✅ same as 03 | `OperatorCanonicalQueryIT` C7 passes on `qwen3:8b`; a weaker model such as `llama3.1:8b` reliably fails the two-hop chain (see `02-ai-agent-filter/README.md`, "Relative dates need two chained tool calls") |

02(b) *can* resolve relative dates by chaining `currentLocalDateTime()` and computing an offset. It is a
genuine capability, but a two-hop one, and it is the only category where the per-field variants have
something the condition-list approaches lack — and they only lack it because they do not need it: both
put "today" into the prompt text.

Three failure modes are specific to tool calling as a *mechanism*, all observed on `qwen3:8b` while
building these modules:

| Failure mode | Where | What happened | Handled by |
|---|---|---|---|
| Operator without its value | 02(b) | `searchCustomers(companyNameOperator="CONTAINS")` — nothing to compare, so nothing was filtered | prompt + tool description now require the value explicitly; re-verified 3/3 |
| Same tool called twice | 04 (possible in 02 too) | a second call with an empty condition list wiped the filter the first call had built | all three tool-calling variants keep a filter they already have instead of letting a later empty call overwrite it |
| Unbounded answer on an inexpressible query | 02(a)/02(b) | for "Berlin or Hamburg" the model keeps trying to place the value it had to drop: **107 s and 3064 completion tokens** for that single query before the answer length was capped, and one run hit a 300 s test timeout | `spring.ai.ollama.chat.num-predict=512` in all three AI modules (the benchmark script's long-standing value) bounds it to 18 s / 512 tokens; the prompts additionally say "call the tool once, then stop" |
| A pointless epilogue after the tool call | 04 (any tool call) | after the filter was applied, the model still wrote a summary sentence — ~350 completion tokens and ~15 s for "customers in Berlin", deterministic at temperature 0 | not fixed, deliberately: it is measured and documented in [tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md) as the one place where the delivery mechanism costs real time |

Structured output cannot run into any of the three: one response, one filter, no repetition.

## Older observations, kept for context

These divergences were recorded while the repository still had a single, list-based tool-calling module
(the predecessor of today's 02(a)/02(b)) and are preserved in
`03-ai-structured-filter/src/test/java/.../CustomerSearchAgentIT`'s Javadoc. They remain the sharpest
illustration that *reliability* is model-dependent rather than approach-inherent — on the weaker
`llama3.1:8b`, structured output echoed a raw phone number instead of E.164, collapsed "2020 or 2021"
into a single range, and returned no conditions at all for one German query, while that tool-calling
layer passed all three; in the opposite direction, tool calling hallucinated an unrelated phone number
for a fuzzy phone query that structured output handled. Those cases live in
`CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT` (03's and 04's legacy sets), not in the canonical
set.
