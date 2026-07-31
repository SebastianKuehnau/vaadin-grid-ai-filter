# Giving tool calling the same filter type — the note, and the module that answers it

This document used to be a *design note*: it asked what `02-ai-agent-filter` would need in order to
express negation and operator precision the way `03-ai-structured-filter` does, sketched the change
under the heading "What Module 02 would need to change", and then said plainly: **"This is analysis
only — no such change is made."**

That is no longer true, and the way it became true is the interesting part.

**The change described in that note was built — as `04-ai-hybrid-filter`, not as module 02.** The note's
answer was "swap the flat per-field criteria for a condition list and keep the tool call as the delivery
mechanism". That is exactly, line for line, what module 04 is: `Condition`, `Operator`, `CustomerFilter`
and `CustomerFilterSpecifications` copied 1:1 out of module 03, behind one
`@Tool searchCustomers(List<Condition> conditions)`.

Module 02 went a different, deliberately smaller way instead — variant **02(b)**, which keeps
per-field parameters and merely adds an operator and a negate flag to each of them. So the repository
now contains *both* answers to the note's question, and they disagree about how far you get. That is the
point of this document now.

## Core point, unchanged: flexibility lives in the filter type, not the delivery mechanism

Tool calling and structured output are two ways for the model to *deliver* a filter. What a filter can
*express* is decided by its **type**, independently of how it is delivered.

| | 02(a) flat | 02(b) value+operator+negate | 03 structured output | 04 hybrid |
|---|---|---|---|---|
| Filter type | `CustomerCriteria` — one scalar value per field | `OperatorCriteria` — one value **+ operator + negate** per field | `CustomerFilter` = `List<Condition>` | **the same** `CustomerFilter` = `List<Condition>` |
| Semantics | hard-wired per field (text = CONTAINS, date = whole calendar year, revenue = minimum) | chosen per field via `Operator` + `negate` | chosen per condition via `Operator` + `negate` | chosen per condition via `Operator` + `negate` |
| Delivery | model **calls** `@Tool searchCustomers(...)`, 13 parameters | model **calls** `@Tool searchCustomers(...)`, **39** parameters | model **returns** one JSON object via `.responseEntity(CustomerFilter.class)` | model **calls** `@Tool searchCustomers(List<Condition>)`, **1** parameter |
| Multi-value OR / ranges | ❌ / ❌ | ❌ / ❌ | ✅ / ✅ | ✅ / ✅ |

Read the last two rows together: 03 and 04 differ in *delivery* and agree on *capability*; 02(b) and 04
agree on *delivery* and differ on *capability*. Capability tracks the type, not the mechanism. The
[capability matrix](capability-matrix.md) is the same statement with measurements attached.

## What the note proposed, and where each piece ended up

The original seven-step sketch, mapped onto what exists today:

| The note said | Where it happened |
|---|---|
| 1. New `Operator` enum (mirror of 03's) | **04**: copied 1:1 from 03. Also **02(b)**, which needed its own copy for its per-field operator parameter. |
| 2. New `Condition` record (mirror of 03's) | **04** only — copied 1:1, Jackson annotations included. 02(b) deliberately has no `Condition`. |
| 3. Replace the flat criteria with a condition list | **04** only: `CustomerFilter`, unchanged from 03. |
| 4. Replace the field-by-field predicate builder with 03's operator-driven one | **04**: `CustomerFilterSpecifications` copied 1:1. **02(b)** wrote its own per-field equivalent (`OperatorSpecifications`) — same operators, but one criterion per field. |
| 5. Change the tool signature to a single `List<Condition>` | **04**, exactly. 02(b) went to 39 flat parameters instead. |
| 6. `RevenueRange` drops out | Both: 02(b) has a plain value + operator, 04 uses two sibling conditions. No range-shaped value type exists anywhere any more. |
| 7. `currentLocalDateTime()` stays | **02(b)** keeps it. **04** does not need it: like 03, it bakes "today" into the prompt, so relative dates cost no extra round trip. |

Step 5 was the open question the note could not answer from the outside: *does Spring AI turn 03's
Jackson-annotated `Condition` into a usable **tool parameter** schema, the way it turns it into a
response-format schema?* It does. `ToolCallbacks.from(...)` produces the nested condition object with the
enumerated `Operator`, the `values` array and every `@JsonPropertyDescription` text carried over — the
model sees the same vocabulary in module 04's tool schema as in module 03's response format. This was
verified before module 04 was written, and it is why module 04 could be a copy rather than a redesign.

## Why 02(b) exists as well — the cost side of the same argument

If 04 settles the capability question, 02(b) settles the *effort* question. It is the honest version of
"just add operators to the tool call":

- Every field grows from one parameter to three (value, `<field>Operator`, `<field>Negate`), so the tool
  signature goes from 13 to **39** parameters — roughly 3× the plumbing of 02(a).
- What that buys: **negation** and **operator precision** (EQUALS / STARTS_WITH / ENDS_WITH, and real
  day-level date bounds instead of whole-calendar-year matching).
- What it still cannot do, and cannot be made to do without leaving the per-field shape: **multi-value
  OR** for one field, and **any range** — revenue or date — because a range needs two bounds on the same
  field and there is exactly one operator parameter per field. Adding a range-shaped value type would
  smuggle the second bound back in through the value and is deliberately not done.

So 02(b) is not a worse attempt at module 04. It is the measurement of what per-field parameters buy you
at their limit: **a lot of extra plumbing, two more capability categories, and two still out of reach.**
It is not free at runtime either — the 39-parameter schema costs measurably more per request than 02(a)'s
13, and the extra parameters give the model more ways to go wrong (see below). The measured token and
latency price is in [tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md).

## Two things only tool calling has to worry about

Both were observed while building these modules, on the configured default `qwen3:8b`, and both are
properties of the *delivery mechanism* — structured output cannot run into either:

- **An operator without its value.** For "customers whose company name contains data", 02(b)'s model
  called `searchCustomers(companyNameOperator="CONTAINS")` — the operator parameter filled, the value
  parameter left empty, so nothing was filtered. Adding the extra parameters made it possible to describe
  *how* to compare while forgetting *what* to compare; the prompt and the tool description now say
  explicitly that the value is mandatory. A returned JSON object has no equivalent failure: the operator
  lives inside a condition that must also carry values.
- **The same tool called twice.** A `void` tool is answered with a bare `"Done"`, and a model can read
  that as "nothing happened" and call the tool again — in module 04's case with an empty condition list,
  wiping the filter it had just built correctly. All three tool-calling variants now keep the filter they
  already have instead of letting a later empty call overwrite it. Structured output returns one
  response; there is nothing to repeat.

Neither is an argument against tool calling — they are the price of a mechanism whose whole point is that
the model can *act*, possibly more than once. They are worth knowing before choosing it.

## When to reach for which

- Need a rich filter vocabulary (negation, operator precision, OR, ranges)? Use a **condition-list
  type** — and then either delivery works: `03-ai-structured-filter` if the model should just answer,
  `04-ai-hybrid-filter` if a tool call fits the surrounding architecture better.
- Need the model to fetch something it cannot know at prompt time (a live clock, an external lookup)?
  That is tool calling's genuine edge — 02(b)'s `currentLocalDateTime()` — but budget for the extra round
  trip and its model-dependence.
- Tempted to add operators field by field to an existing flat tool? Read 02(b) first. It is that idea,
  finished, and it still cannot express "Berlin or Hamburg".

## See also

- [tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md) — the full pros/cons
  comparison with measured token cost, latency and reliability.
- [capability-matrix.md](capability-matrix.md) — which query types each of the four approaches can
  express, evidence-linked to test methods.
- [canonical-query-set.md](canonical-query-set.md) — the eight queries all four modules are measured
  with.
