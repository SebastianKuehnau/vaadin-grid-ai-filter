# Tool calling vs. structured output

Four ways to turn a natural-language query into a Vaadin-`Grid` filter, all implemented in this repo
against the *same* domain, the *same* eight queries and the *same* local model — so the trade-off is
concrete rather than theoretical. This document states the pros and cons; every claim points at a test
method, a measured figure, or both. The per-query-type breakdown lives in the companion
[capability matrix](capability-matrix.md); the queries themselves are in
[canonical-query-set.md](canonical-query-set.md).

The four are deliberately arranged so that each neighbouring pair differs in exactly one dimension:

| | Filter type | Delivery |
|---|---|---|
| **02(a)** `02-ai-agent-filter`, route `/` | one scalar value per field | tool call, 13 parameters |
| **02(b)** `02-ai-agent-filter`, route `/operator` | one value + `Operator` + `negate` per field | tool call, 39 parameters |
| **03** `03-ai-structured-filter` | `CustomerFilter` = `List<Condition>` | structured output |
| **04** `04-ai-hybrid-filter` | **the same** `List<Condition>` | tool call, 1 parameter |

02(a) → 02(b) changes only how much a *field* can say. 03 → 04 changes only how the finished filter
*travels*. That is what makes the comparison a measurement instead of an opinion.

## The four approaches in one paragraph each

**02(a) — flat tool calling.** `FlatToolCallingService` exposes
`@Tool searchCustomers(companyName, contactName, …, annualRevenue)`: 13 scalar parameters, no operator,
no negation, no second tool. The tool body fills a `FlatCriteria`, which `FlatSpecifications` turns
into a `Specification`. Every field's meaning is hard-wired there: text = substring, date = the whole
calendar year it falls in, revenue = a minimum.

**02(b) — value + operator + negate tool calling.** `OperatorToolCallingService` keeps the same delivery
and gives every field three parameters (`city`, `cityOperator`, `cityNegate`) — 39 in total — plus a
second tool, `currentLocalDateTime()`, for relative dates. `OperatorSpecifications` chooses the predicate
per field from its `Operator` and flips it when `negate` is set.

**03 — structured output.** `CustomerSearchStructuredOutputService` calls
`.call().responseEntity(CustomerFilter.class)`: the model returns *one* JSON object — a `CustomerFilter`
holding a flat `List<Condition>`, each condition a `(field, Operator, values, negate)` tuple — which
`CustomerFilterSpecifications` translates. "Today" is baked into the prompt via
`systemPrompt(LocalDate today)`; there is no live date tool call.

**04 — hybrid.** `CustomerSearchHybridToolCallingService` exposes
`@Tool searchCustomers(List<Condition> conditions)` — 03's `Condition`, `Operator`, `CustomerFilter` and
`CustomerFilterSpecifications`, copied 1:1, Jackson annotations included. Spring AI derives the tool's
parameter schema from those very annotations, so the model sees the same vocabulary as in 03. The prompt
is 03's prompt with "call the searchCustomers tool" in place of "return a CustomerFilter".

All four filter types are deliberately **flat** — no AND/OR/NOT tree. The essential difference is not
tool calling versus structured output; it is whether a *field* can carry more than one thing.

## Where they agree

For a single-field query and an AND across fields — `C1_SINGLE_VALUE` ("customers in Berlin") and
`C5_COMBINED_AND` ("creditworthy customers in Hamburg") — **all four produce the exact expected customer
set** (18 and 9 rows respectively), verified on the resulting rows rather than on the filter's shape.
Those are also the two categories a live demo shows first, and they are why 02(a) is a perfectly good
teaching step rather than a strawman.

The differences show up as soon as one field has to say two things.

## What only the condition-list type can express

These are **architectural**, not reliability, differences. 02(a) has one value slot per field, 02(b) has
one value plus one operator per field; neither can hold a second value or a second bound, so no prompt
and no model can make them produce these:

- **Multi-value OR** — "customers from Berlin or Hamburg" (`C2_MULTI_VALUE_OR`, 35 rows expected). 02(a)
  returned 17 rows, 02(b) 18 — one city each, because the second has nowhere to go. In 03/04 it is one
  condition with two `values`.
- **A value range on one field** — "revenue between 100000 and 200000" (`C6_REVENUE_RANGE`, 37 rows) and
  "last ordered between 2024-07-01 and 2025-03-31" (`C8_DATE_RANGE`, 4 rows). 02(a) returned 21 and 9
  rows (its revenue parameter is a minimum, its dates mean whole calendar years), 02(b) 43 and 21 (one
  operator per field means exactly one bound). In 03/04 a range is two sibling conditions on the same
  field, AND-combined like everything else.

And two categories that 02(a) alone cannot reach, which is exactly what 02(b) was built to fix:

- **Negation** — "all customers except from Berlin" (`C3_NEGATION`, 82 rows). 02(a) returned the 18
  Berlin customers; 02(b), 03 and 04 return 82.
- **Operator precision** — "an 'm' as the first character in the contact name" (`C4_OPERATOR_PRECISION`,
  6 rows). 02(a) substring-matches and returned 58 rows; the others return 6.

So the capability ladder is 2 → 5 → 8 → 8 of eight categories. The last step adds nothing, and that is
the finding: 04 has 03's capabilities with 02's delivery mechanism.

## Token cost and request duration

Measured by the **`TokenUsageRecorder`** (present in all four modules) on the *real application path*: it
reads Spring AI's `Usage` per request, logs prompt / completion / total tokens and the wall-clock time,
and prints a per-IT-class summary. The figures below are that summary for each module's canonical-query
IT — the **same eight queries** in every row — over **three consecutive `-Pit-local-ollama` runs** on the
configured default `qwen3:8b`. Token counts came out identical in all three runs (temperature 0 makes each
prompt deterministic), so only the duration column needs a median.

| Approach | Tokens/request | prompt | completion | Duration/request | Categories reached |
|---|---|---|---|---|---|
| 02(a) flat (13 parameters) | **1154** | 1120 | 34 | 2856 ms | 2 / 8 |
| 02(b) value+operator+negate (39 parameters) | **3248** | 3154 | 94 | 6071 ms | 5 / 8 |
| 03 structured output | **2307** | 2243 | 64 | 4089 ms | 8 / 8 |
| 04 hybrid (1 parameter) | **2358** | 2288 | 70 | 5128 ms | 8 / 8 |

Four things the numbers show:

- **The bill is prompt-dominated.** Completion is 34–94 tokens/request on average — the filter object is
  compact. What you pay for is the system prompt plus the schema sent on *every* request, which is
  roughly fixed regardless of how complex the query is.
- **Delivery is close to token-neutral.** 03 and 04 send **2243 vs 2288 prompt tokens (+2.0 %)** for the
  same `Condition` type — a response-format schema and a tool-parameter schema cost the same order of
  tokens, and the remaining 45 tokens are prompt wording (04 additionally tells the model to call the tool
  exactly once), not the mechanism. If you were choosing between structured output and tool calling for
  cost reasons: don't.
- **Per-field operator plumbing is the expensive option.** 02(b) sends **3248** tokens/request — 41 % more
  than 03 — and reaches five categories instead of eight. Tripling 02(a)'s parameter count
  (1154 → 3248, **+181 %**) buys negation and operator precision and stops short of OR and ranges. The
  condition list is both cheaper *and* more expressive.
- **Duration is dominated by how much the model *says*, not by what it is asked.** 02(a) is fastest
  (2856 ms) because it sends and receives the least. 04 (5128 ms) trails 03 (4089 ms) despite an almost
  identical prompt, for a reason visible in the per-query table below: after a tool call the model tends to
  add a natural-language epilogue that nobody reads.

Per-query detail, median of the three runs, duration in ms (total tokens in brackets):

| Query | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|
| C1 single value | 1960 (1119) | 3119 (3155) | 3477 (2283) | **14976 (2639)** |
| C2 multi-value OR | 2946 (1155) | **18093 (3591)** | 3461 (2284) | 2919 (2290) |
| C3 negation | 1635 (1111) | 2860 (3151) | 3304 (2280) | 2814 (2288) |
| C4 operator precision | 2411 (1145) | 2878 (3166) | 3494 (2292) | 2932 (2302) |
| C5 combined AND | 2724 (1141) | 3044 (3159) | 4490 (2313) | 3782 (2307) |
| C6 revenue range | 3530 (1180) | 6694 (3251) | 5070 (2340) | 4501 (2342) |
| C7 relative date | 2720 (1151) | 4037 (3229) | 3947 (2301) | 3533 (2315) |
| C8 date range | 4756 (1228) | 7921 (3280) | 5458 (2359) | 5733 (2377) |

The two bold cells are the two lessons hiding in this table, and both are about the *tool-calling*
mechanism rather than about any filter type:

- **02(b), C2 (18.1 s, 3591 tokens).** This is the query 02(b) cannot express. The model does not fail
  fast on it — it produces the maximum answer length it is allowed (`num-predict=512`, visible as the
  completion jumping from ~30 to 512 tokens) trying to place the second city somewhere. Before that cap
  was configured, the same query took **107 seconds and 3064 completion tokens**, and in one run it hit a
  300 s test timeout. An architectural limit costs 5× a normal query even when bounded.
- **04, C1 (15.0 s, 2639 tokens).** After the tool call succeeds, the model writes a summary sentence
  nobody consumes — ~350 completion tokens for "customers in Berlin". It is deterministic at temperature 0
  (identical in all three runs) and it is pure waste: the filter was already applied by the tool. 03 cannot
  do this, because its answer *is* the filter. This is the one place where the delivery mechanism costs
  real time, and it is a prompt-tuning problem, not an architectural one.

The C6 and C8 rows show the same effect more mildly: for both 02 variants, the queries they cannot express
are their slowest. The practical consequence is a configuration one — **cap the answer length**. All three
AI modules now set `spring.ai.ollama.chat.num-predict=512` (the value the benchmark script has always
used); without it the model generates until it decides to stop, and the numbers above turn into 107
seconds and 3064 completion tokens for a single query. Both 02 prompts additionally end with "call the tool
once, then stop", which reduces the retrying but does not remove it.

## Reliability and model dependence

"Reliable vs. flaky" is a different question from "expressible vs. not", and it is per model. It is
answered by the benchmark's pass-rates (`--approach=all --runs=5`), not by single JUnit runs.

> **Not yet re-measured for the four-approach setup.** The harness runs all four approaches (verified
> with `--quick --runs=1` on `qwen3:8b`), but the two-model reliability table that used to stand here
> described the old two-approach setup, and stale numbers are worse than none. Reproduce with:
>
> ```bash
> cd 05-ollama-benchmark
> java BenchmarkLocalModels.java --approach=all --runs=5 qwen3:8b llama3.1:8b
> ```

What is measured today, on `qwen3:8b`: every expressible canonical query produced the exact expected
customer set in all four approaches, and every inexpressible one failed in the documented way. Older,
model-dependent divergences (recorded on the weaker `llama3.1:8b`, when 02 still had a single list-based
tool call) are summarised at the end of the [capability matrix](capability-matrix.md).

## Where tool calling has (or had) an edge

- **A value the model cannot know at prompt time.** 02(b) resolves "yesterday" / "in the last 12 months"
  by chaining `currentLocalDateTime()` and computing an offset — a genuine capability, and the one thing
  the per-field variants have that 03/04 do not. But it is a *two-hop* capability and model-dependent: a
  weaker model such as `llama3.1:8b` reliably fails the chain (see `02-ai-agent-filter/README.md`). 03
  and 04 sidestep it by putting "today" into the prompt, which is why they pass `C7` without any extra
  round trip. So this is less "tool calling wins" than "tool calling can fetch things, and pays a hop
  for it".
- **A tool call *does* something.** Structured output ends with an object; a tool call ends with your code
  having run. In this demo the tool only stores a filter, but that is the axis on which tool calling is
  the natural fit — the model triggers behaviour instead of describing it.

And three costs that come with the mechanism, all observed on `qwen3:8b` during this work:

1. **An operator without its value.** For "customers whose company name contains data", 02(b) called
   `searchCustomers(companyNameOperator="CONTAINS")` and filtered nothing. More parameters mean more ways
   to fill them incompletely; the prompt and tool description now state that the value is mandatory.
2. **The same tool called twice.** A `void` tool is answered with a bare `"Done"`, and the model may read
   that as "nothing happened" and call it again — in 04's case with an empty condition list, wiping the
   filter it had just built. All three tool-calling variants now refuse to let a later empty call
   overwrite a filter they already have.
3. **Expensive failure on inexpressible queries** — see the per-query table above.

None of the three has an equivalent in structured output: one response, one filter, no repetition.

## Takeaway for the talk

- Need **negation, operator precision, OR, or ranges**? That is a property of the **filter type**: use a
  condition list. Both deliveries carry it equally well (03 and 04: 2243 vs 2288 prompt tokens/request).
- Tempted to add operators **field by field** to an existing flat tool? 02(b) is that idea, finished: 39
  parameters, 3248 tokens/request, five of eight categories — more expensive than the condition list and
  less capable. It is the most useful negative result in the repository.
- Running a **small/local model**? All four work on `qwen3:8b`; the differences that used to bite on
  weaker models were reliability, not capability — re-measure with the benchmark before assuming.
- Need a **live value** (clock, lookup) at request time? Tool calling, with a budget for the extra hop.
- Counting **tokens**? Everything is prompt-dominated, so the schema plus system prompt *is* the bill.
  Trimming those is the highest-leverage lever; picking a delivery mechanism is not.

See the [capability matrix](capability-matrix.md) for the per-query-type table with test citations, and
[extending-tool-calling-with-operators.md](extending-tool-calling-with-operators.md) for how module 04
came to exist — it is the change that document used to analyse without making.
