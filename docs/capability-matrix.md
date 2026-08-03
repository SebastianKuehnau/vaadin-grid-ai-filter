# Capability matrix: the four AI approaches

Which query types each approach can express, and how reliably it does so — derived strictly from the
modules' integration-test suites and the `ollama-benchmark` harness. Companion to
[tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md) and
[canonical-query-set.md](canonical-query-set.md).

- **02(a) = `02-ai-agent-filter`, flat variant** — tool calling; the LLM calls
  `@Tool searchCustomers(...)` with **13** parameters, one scalar value per field →
  `CustomerCriteria` → `CustomerSpecifications`. No operator, no negation.
- **02(b) = `02-ai-agent-filter`, operator variant** — tool calling; the same tool with **39**
  parameters, a value + `Operator` + `negate` per field → `CustomerCriteria` →
  `CustomerSpecifications`.
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

These are the eight queries of [canonical-query-set.md](canonical-query-set.md), run by every module
from the shared `CanonicalQuery` enum — through the service *and* through the UI, so the wording is
**identical** by construction (`demo-commons`' `CanonicalQuerySetConsistencyTest` fails the build if the
enum or the benchmark script drifts from the document). Each case is scored on the *resulting customer set*: the returned
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
`StructuredCanonicalQueryIT` (in `03-ai-structured-filter`) and `HybridCanonicalQueryIT` (in
`04-ai-hybrid-filter`). The ❌ cells are not skipped
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

Measured by the `TokenUsageAdvisor` on the real application path (the same bean the app uses), over
**three consecutive `-Pit-local-ollama` runs** on `qwen3:8b`, over all eight canonical queries. Token
counts were identical in all three runs except 02(a)'s completion tokens (535 twice, 575 once — the model
varies how much it writes after the tool call); every other figure is the median of the three.

**Read the two right-hand columns first, because they answer different questions.** A tool-calling query
is not one model call: the model calls the tool, Java answers it, and the model is asked again — and each
of those calls bills its own prompt. So *per model call* the four approaches differ only by their schema
size, while *per query* the delivery mechanism shows its price.

| Approach | Tool/schema shape | Calls per query | Tokens/call | prompt | completion | **Tokens/query** | Duration/query |
|---|---|---|---|---|---|---|---|
| 02(a) flat | 13 flat parameters | 2.5 | 1341 | 1314 | 27 | **3353** | 2970 ms |
| 02(b) value+operator+negate | 39 flat parameters | 2.5 | 3648 | 3608 | 40 | **9120** | 4395 ms |
| 03 structured output | `CustomerFilter` response schema | 1 | 2306 | 2243 | 64 | **2306** | 4087 ms |
| 04 hybrid | 1 `List<Condition>` parameter | 2 | 2296 | 2254 | 42 | **4593** | 3694 ms |

Three things this table says that the capability columns cannot:

- **The filter type is token-neutral across delivery mechanisms — per call.** 03 and 04 share the filter
  type and cost 2243 vs 2254 prompt tokens per call (**+0.5 %**). Whether Spring AI sends that type as a
  response-format schema or as a tool-parameter schema makes no measurable difference.
- **The delivery mechanism is not free — per query.** The same filter type costs 2306 tokens as structured
  output and **4593 as a tool call**, almost exactly double, because the second round trip resends the
  whole conversation to collect an answer the filter no longer needs. That is the honest price of "the
  model can act", and it is invisible if you only look at per-call figures.
- **02(b) costs four times 03 and reaches fewer categories** — 9120 tokens per query for 5 of 8, against
  2306 for 8 of 8. Per-field operator plumbing is not a cheap shortcut to expressiveness; it is the
  expensive way to get less of it.

`Calls per query` is not a constant of the approach but of the model's behaviour: 02(a) and 02(b) average
2.5 because a relative-date query chains `currentLocalDateTime()` first, and 04's 2 is the tool call plus
the epilogue that [is measured and documented](tool-calling-vs-structured-output.md) as the one place
where delivery costs real time.

The per-query breakdown lives in
[tool-calling-vs-structured-output.md § Token cost and request duration](tool-calling-vs-structured-output.md#token-cost-and-request-duration).

## Reliability across models

Whether an approach *reliably* produces the right filter — as opposed to being able to express it at all
— is a per-model question, answered by `ollama-benchmark` (`--approach=all`), which runs the same
canonical queries as the ITs plus its own legacy prompt-regression set.

> **Measured 2026-07-31**, `--approach=all --runs=10` over four models: 44 cases per approach per
> model, 2 h 57 m wall clock. `Canonical` is the mean over the queries that approach can express, so the
> columns do not cover the same number of queries — 02(a) is scored on 2 of the 8, 02(b) on 5, 03 and 04
> on all 8. A 100% in the 02(a) column is therefore a property of that selection, not a quality verdict;
> its `Legacy` figure, which every approach runs in full, is the comparable one.

| Model | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|
| `qwen3.5:4b-mlx` | 100% · 64% · 790 ms | 60% · 64% · 1034 ms | 100% · 94% · 1031 ms | 100% · 97% · 1409 ms |
| `qwen3:8b` | 100% · 61% · 951 ms | 80% · 61% · 1252 ms | **100% · 100%** · 1243 ms | **100% · 100%** · 1900 ms |
| `gemma4:26b-mlx` | 100% · 61% · 929 ms | 80% · 67% · 1107 ms | **100% · 100%** · 1552 ms | 88% · 98% · 1097 ms |
| `llama3.1:8b` | 100% · 69% · 828 ms | 80% · 64% · 1320 ms | 100% · 97% · 1191 ms | **0% · 11%** · 2101 ms |

Each cell reads *canonical · legacy · median latency*. Reproduce with:

```bash
cd ollama-benchmark
java BenchmarkLocalModels.java --approach=all --runs=10 qwen3.5:4b-mlx qwen3:8b gemma4:26b-mlx llama3.1:8b
```

### Delivery mechanism vs. model strength

The `llama3.1:8b` row is the single most useful result in the table. That model returns an **empty filter
on every query** of 04 — including "show me all customers in Berlin". The only cases it "passes" are the
ones where empty is the correct answer. Yet the same model, in the same run:

| | Mechanism | Filter type | `llama3.1:8b` |
|---|---|---|---|
| 02(b) | tool call, 13 flat scalar parameters | field + operator | 64% |
| 03 | structured output | `List<Condition>` | **97%** |
| 04 | tool call, **one `List<Condition>` parameter** | `List<Condition>` | **0%** |

03 and 04 share the filter type, the prompt rules and the baked-in "today" — 04's `systemPrompt(LocalDate)`
is deliberately identical to 03's. What differs is that 04 asks the model to produce a **nested object
array as a tool argument**. `llama3.1:8b` cannot; flat tool parameters it handles fine.

So the escalation ladder's last step splits in two: *what* a filter can express is a property of its
**type**, but whether a given model can deliver that type at all is a property of the **mechanism**.
Structured output puts the condition list within reach of a model that cannot tool-call it.

`gemma4:26b-mlx` shows the same effect in miniature rather than in full: 03 passes `C7_RELATIVE_DATE`
10/10, 04 fails it 0/10, same prompt and same date. Counted per query-and-model over the canonical set,
03 passes 32 of 32 cells and 04 passes 23 of 32 — every one of the nine misses is a tool call that did not
carry its argument.

### Cost per query, across all models and cases

| Approach | Σ tokens / runs | Canonical reach |
|---|---|---|
| 02(a) flat | ~1 330 | 2 of 8 |
| 03 structured | ~1 860 | 8 of 8, 100% on every model |
| 04 hybrid | ~2 350 | 8 of 8, 96% excluding `llama3.1:8b` |
| 02(b) operator | **~3 730** | 5 of 8 |

02(b) is the most expensive approach measured and the weakest of the three expressive ones: 13 tool
parameters travel in the schema of every round trip. 04 costs ~26% more than 03 for identical results —
that is the extra round trip, not a worse filter.

### Where the ITs and the benchmark disagree

The ITs are the other source, and they agree with the table on the configured default model: on `qwen3:8b`
every ✅ in the capability matrix passed and every ❌ failed in the way it describes, in **three
consecutive runs** with identical results each time.

The two harnesses nevertheless score different things, which shows up in exactly two places on the
canonical set:

- The ITs score the **resulting customer set**; the benchmark scores the **extracted filter**, and counts a
  field the query did not ask for as a failure even when it changes nothing. In a recorded run 02(b) added
  `country=Germany` to "creditworthy customers in Hamburg" — harmless for the rows (every Hamburg customer
  in the seed data is German), so the IT passes and the benchmark does not.
- The benchmark performs a **single round trip** and does not execute chained tool calls, so 02(b)'s
  `C7_RELATIVE_DATE` — which needs its `currentLocalDateTime()` hop — fails there by construction. The IT,
  running the real Spring AI tool loop, passes it. That is why C7 is ✅ for 02(b) above.

The 10-run report contains two instances of the first kind, worth naming so they are not misread as model
failures: 02(b) on `qwen3.5:4b-mlx` answered `C5_COMBINED_AND` with `city EQUALS Hamburg` where the
expectation only allows `CONTAINS`, and 04 on `gemma4:26b-mlx` expressed "exactly 100000" as
`>= 100000 AND <= 100000` instead of `EQUALS`. Both select the correct rows; both are scored as failures
by the filter-shaped expectation and pass in the ITs.

## Where tool calling has an edge, and what it costs

| Query type | 02(a) | 02(b) | 03 | 04 | Evidence |
|---|---|---|---|---|---|
| **Relative date via a live clock** ("in the last 12 months") | ❌ | ⚠️ chained `currentLocalDateTime()` — model-dependent | ✅ "today" baked into `systemPrompt(LocalDate)` | ⚠️ same prompt as 03, but model-dependent in practice | `OperatorCanonicalQueryIT` C7 passes on `qwen3:8b`; a weaker model such as `llama3.1:8b` reliably fails the two-hop chain (see `02-ai-agent-filter/README.md`, "Relative dates need two chained tool calls"). Across four models the 10-run benchmark gives C7 **40/40 for 03 and 20/40 for 04** — `gemma4:26b-mlx` and `llama3.1:8b` fail it through the tool call while passing it through structured output, on the identical prompt |

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
(the predecessor of today's 02(a)/02(b)), on the weaker `llama3.1:8b` — 03's default model at the time;
it is `qwen3:8b` today. They remain the sharpest illustration that *reliability* is model-dependent
rather than approach-inherent.

Three cases structured output could not pass reliably (a single `-Pit-local-ollama` run, 100%
reproducible on retry) while that tool-calling layer passed them every time:

- **German phone number normalized to E.164** — the model echoed the raw, un-normalized phone string
  instead of the expected E.164 digits.
- **Multi-value "customer since" years** — "2020 or 2021" came back as a single
  `[2020-01-01, 2021-12-31]` range instead of two disjoint lower-bound conditions.
- **Cities plus credit rating, asked in German** — the model returned `conditions=null`.

In the opposite direction, tool calling hallucinated an unrelated phone number for a fuzzy phone query
that structured output handled.

None of this is part of the canonical query set, and none of it is covered by a test any more: it was
recorded by 03's pre-canonical `CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT`, which the canonical
query set superseded and which have since been removed — see the git history for the original suites.
The observations are kept here rather than in a Javadoc so they survive the test that produced them.
