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

**02(a) — flat tool calling.** `CustomerSearchService` exposes
`@Tool searchCustomers(companyName, contactName, …, annualRevenue)`: 13 scalar parameters, no operator,
no negation, no second tool. The tool body fills a `CustomerCriteria`, which `CustomerSpecifications` turns
into a `Specification`. Every field's meaning is hard-wired there: text = substring, date = the whole
calendar year it falls in, revenue = a minimum.

**02(b) — value + operator + negate tool calling.** `CustomerSearchService` keeps the same delivery
and gives every field three parameters (`city`, `cityOperator`, `cityNegate`) — 39 in total — plus a
second tool, `currentLocalDateTime()`, for relative dates. `CustomerSpecifications` chooses the predicate
per field from its `Operator` and flips it when `negate` is set.

**03 — structured output.** `CustomerSearchService` calls
`.call().entity(CustomerFilter.class)`: the model returns *one* JSON object — a `CustomerFilter`
holding a flat `List<Condition>`, each condition a `(field, Operator, values, negate)` tuple — which
`CustomerFilterSpecifications` translates. "Today" is baked into the prompt via
`systemPrompt(LocalDate today)`; there is no live date tool call.

**04 — hybrid.** `CustomerSearchService` exposes
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

Measured by the **`TokenUsageAdvisor`** (shared via `00-commons`) on the *real application path*: it sits
innermost in the advisor chain, so it sees every model call including the tool loop's follow-ups, logs
prompt / completion / total tokens and the wall-clock time of each, and prints a per-IT-class summary. The
figures below are that summary for each module's canonical-query IT — the **same eight queries** in every
row — over **three consecutive `-Pit-local-ollama` runs** on the configured default `qwen3:8b`. Every
figure was identical in all three runs except 02(a)'s completion tokens (535 twice, 575 once); the rest is
the median.

**A query is not a call.** Tool calling asks the model, runs the tool, and asks again — and every call
bills its own prompt. Both columns are therefore given, and they answer different questions.

| Approach | Calls/query | Tokens/call | prompt | completion | **Tokens/query** | Duration/query | Categories |
|---|---|---|---|---|---|---|---|
| 02(a) flat (13 parameters) | 2.5 | 1341 | 1314 | 27 | **3353** | 2970 ms | 2 / 8 |
| 02(b) value+operator+negate (39 parameters) | 2.5 | 3648 | 3608 | 40 | **9120** | 4395 ms | 5 / 8 |
| 03 structured output | 1 | 2306 | 2243 | 64 | **2306** | 4087 ms | 8 / 8 |
| 04 hybrid (1 parameter) | 2 | 2296 | 2254 | 42 | **4593** | 3694 ms | 8 / 8 |

Four things the numbers show:

- **The bill is prompt-dominated.** Completion is 27–64 tokens per call — the filter object is compact.
  What you pay for is the system prompt plus the schema, sent again on *every* call, roughly fixed
  regardless of how complex the query is. Which is exactly why an extra round trip is expensive.
- **The filter type is token-neutral across delivery mechanisms; the mechanism is not.** Per call, 03 and
  04 send **2243 vs 2254 prompt tokens (+0.5 %)** for the same `Condition` type — a response-format schema
  and a tool-parameter schema cost the same. Per query, the same type costs **2306 against 4593**, because
  tool calling resends the whole conversation to collect an answer the filter no longer needs. If you are
  choosing for cost reasons, choose on round trips, not on schema shape.
- **Per-field operator plumbing is the expensive option.** 02(b) sends **9120** tokens per query — four
  times 03 — and reaches five categories instead of eight. Tripling 02(a)'s parameter count
  (3353 → 9120 per query, **+172 %**) buys negation and operator precision and stops short of OR and
  ranges. The condition list is both cheaper *and* more expressive.
- **Duration does not track tokens.** 04 (3694 ms/query) is *faster* than 03 (4087 ms) despite costing
  twice the tokens: its two calls each generate very little, while 03 produces the whole JSON object in one
  pass, and generation dominates. 02(b) is slowest of the tool-calling variants (4395 ms) because its
  39-parameter schema is the largest prompt to read.

Per-query detail — every model call a query caused, summed. Median of the three runs, duration in ms
(total tokens in brackets):

| Query | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|
| C1 single value | 1993 (2618) | 2796 (7211) | 3469 (2283) | 2449 (4532) |
| C2 multi-value OR | 3463 (**3996**) | 5099 (**10938**) | 3459 (2284) | 2951 (4552) |
| C3 negation | 1837 (2616) | 2777 (7221) | 3343 (2280) | 2859 (4546) |
| C4 operator precision | 2417 (2656) | 2900 (7242) | 3521 (2292) | 3175 (4572) |
| C5 combined AND | 2391 (2635) | 2938 (7228) | 4516 (2313) | 4289 (4590) |
| C6 revenue range | 3814 (**4060**) | 6675 (**11043**) | 5063 (2340) | 4589 (4652) |
| C7 relative date | 3062 (**4070**) | 4063 (**10957**) | 3864 (2301) | 3590 (4594) |
| C8 date range | 4773 (**4172**) | 7895 (**11120**) | 5394 (2359) | 5634 (4706) |

Three lessons hide in this table, and all three are about the *tool-calling* mechanism rather than any
filter type:

- **A missing *slot* costs an extra round trip; a missing *operator* costs nothing.** The bold cells are
  C2, C6, C7 and C8 in both 02 variants — one full extra call each (02(a) ~2620 → ~4000, 02(b) ~7220 →
  ~11000). Those are the queries needing a second value (C2), a second bound (C6, C8) or a clock reading
  (C7): the model calls the tool, notices it had nowhere to put the rest, and calls again. C3 and C4 are
  just as inexpressible for 02(a) and yet cost exactly a normal query — the model fills the one slot it has
  and stops, producing a confidently wrong filter. **The cheap failures are the dangerous ones**: a price
  spike at least tells you something was dropped.
- **The runaway generation this table used to show is gone, and that is a fixed bug rather than a
  measurement artefact.** An earlier version of this section recorded 02(b)/C2 at **18.1 s** and, before
  `spring.ai.ollama.chat.num-predict=512` was configured, at **107 seconds and 3064 completion tokens**,
  with one run hitting a 300 s test timeout. With the cap in place the same query is 5.1 s. The cost of an
  architectural limit is now one extra round trip, not an open-ended monologue.
- **The epilogue after a tool call is the whole reason 04 costs double.** 04 spends 4532–4706 tokens per
  query against 03's 2280–2359 for the same filter type, and the difference is one further call whose
  completion is a handful of tokens nobody reads — the filter was already applied by the tool. 03 cannot do
  this, because its answer *is* the filter. Note what this does *not* cost: 04 is **faster** than 03 on
  seven of eight queries, because two small generations beat one large one even though they bill two
  prompts.

## Reliability and model dependence

"Reliable vs. flaky" is a different question from "expressible vs. not", and it is per model. It is
answered by the benchmark's pass-rates (`--approach=all --runs=5`), not by single JUnit runs.

**Measured 2026-08-04**, `--approach=all --cases=canonical --runs=5`, one invocation per model. The full
table, including the legacy set and latencies, lives in the
[capability matrix](capability-matrix.md#reliability-across-models); the canonical-set means are:

| Model | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|
| `qwen3.5:4b-mlx` | 100% | 60% | 100% | 100% |
| `qwen3:8b` | 100% | 80% | 100% | 100% |
| `gemma4:26b-mlx` | 100% | 80% | 100% | ⚠️ 88% |
| `llama3.1:8b` | 100% | 80% | 100% | 100% |

Each column is scored only on the queries that approach can express (02(a): 2 of 8, 02(b): 5 of 8, 03 and
04: all 8), so a 100% for 02(a) describes that selection and not its quality.

> **The `llama3.1:8b` 04 cell reads 100% where it used to read `⚠️ 0%`.** That model sends 04's
> `conditions` argument as a JSON-encoded *string* rather than a JSON array, and the harness accepted only
> an array, scoring the correct filter as empty — a harness bug, now fixed: the stringified form is parsed
> and the coercion is reported (40 of them in this run). The module's own IT always passed 11 of 13 cases
> on this model, because Spring AI binds that argument without complaint.
>
> **The `gemma4:26b-mlx` 04 cell is a flake, not a finding.** It is `C7_RELATIVE_DATE` returning an empty
> condition list 0/5, on a query 03 passes 5/5 on every model with the identical prompt. The same cell read
> 0/10 on 2026-07-31, 5/5 on 2026-08-03 and 0/5 here. Across those runs the honest reading is that this one
> query is less robust through a tool argument than through a response *on this model* — not that either
> delivery mechanism wins. Details in the [capability matrix](capability-matrix.md#reliability-across-models).

On the configured default `qwen3:8b` the ITs agree with this: every expressible canonical query produced
the exact expected customer set in all four approaches, and every inexpressible one failed in the
documented way. Older, model-dependent divergences (recorded on the weaker `llama3.1:8b`, when 02 still had
a single list-based tool call) are summarised at the end of the [capability matrix](capability-matrix.md).

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
  condition list. Both deliveries carry it equally well per model call (03 and 04: 2243 vs 2254 prompt
  tokens), but tool calling bills a second call, so per query it is 2306 against 4593 tokens.
- Tempted to add operators **field by field** to an existing flat tool? 02(b) is that idea, finished: 39
  parameters and 9120 tokens per query — four times the condition list — for five of eight categories. More
  expensive and less capable: the most useful negative result in the repository.
- Running a **small/local model**? All four work on `qwen3:8b`; the differences that used to bite on
  weaker models were reliability, not capability — re-measure with the benchmark before assuming.
- Need a **live value** (clock, lookup) at request time? Tool calling, with a budget for the extra hop.
- Counting **tokens**? Everything is prompt-dominated, so the schema plus system prompt *is* the bill.
  Trimming those is the highest-leverage lever; picking a delivery mechanism is not.

See the [capability matrix](capability-matrix.md) for the per-query-type table with test citations, and
`04-ai-hybrid-filter/README.md` for how that module came to exist — it is a change this repository
analysed in a design note before making it.
