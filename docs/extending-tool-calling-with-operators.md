# Extending tool calling (02) with operators and negation

A design note answering one question: *what would `02-ai-agent-filter` need in order to express
negation and operator precision (STARTS_WITH / ENDS_WITH / EQUALS, arbitrary date bounds) the way
`03-ai-structured-filter` does?* Companion to
[tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md) and the
[capability matrix](capability-matrix.md).

**This is analysis only — no such change is made.** Module 02 stays deliberately flat as the
teaching contrast (see `CLAUDE.md`: `01 → 02 → 03` increase in complexity). The point of the note is
what the answer *reveals* about the two approaches.

## Core point: flexibility lives in the filter type, not the delivery mechanism

Tool calling and structured output are two ways for the model to *deliver* a filter. What a filter
can *express* is decided by its **type**, independently of how it is delivered.

| | 02 today (tool calling) | 03 today (structured output) |
|---|---|---|
| Filter type | `CustomerSearchCriteria` — one `List` per field, **no operator, no negation** | `CustomerFilter` = `List<Condition>`, each `Condition(field, Operator, values, negate)` |
| Semantics | hard-wired in the predicate builder (text = CONTAINS, date = whole calendar year) | chosen per condition via `Operator` + `negate` |
| Delivery | model **calls** `@Tool searchCustomers(...)` | model **returns** one JSON object via `.call().entity(CustomerFilter.class)` |

Only the first row limits 02. To match 03's flexibility you swap 02's flat criteria for a
condition list — the tool call as the delivery mechanism can stay exactly as it is.

## What Module 02 would need to change

All in `02-ai-agent-filter/src/main/java/dev/demo/vaadin/aigridfilter/ai/`. Module 02 cannot import
Module 03's types — they are separate Spring Boot apps that happen to share the
`dev.demo.vaadin.aigridfilter` package name — so 02 needs its own copies.

1. **New `ai/filter/Operator.java`** — enum `CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL,
   STARTS_WITH, ENDS_WITH` (a mirror of 03's `Operator`).
2. **New `ai/filter/Condition.java`** — `record Condition(String field, Operator operator,
   List<String> values, boolean negate)` (a mirror of 03's `Condition`).
3. **`CustomerSearchCriteria.java`** — replace the 13 flat per-field lists with a condition list
   (e.g. `record CustomerSearchCriteria(List<Condition> conditions)`), i.e. the same shape as 03's
   `CustomerFilter`.
4. **`CustomerSpecifications.java`** — replace the field-by-field logic (`addLikeAnyOf`,
   `addYearAnyOf`, `revenueRangePredicate`) with 03's pattern: for each condition, OR the per-value
   predicates, apply `negate` via `cb.not(...)`, and pick the predicate by operator
   (`textPredicate` with CONTAINS / EQUALS / STARTS_WITH / ENDS_WITH, plus `datePredicate` /
   `numberPredicate`) with field routing. This is essentially a copy of
   `03-ai-structured-filter/.../CustomerFilterSpecifications.java`.
5. **`CustomerSearchToolCallingService.java`** — change the `@Tool searchCustomers(...)` signature
   from 13 flat `List` parameters to a single `List<Condition> conditions` parameter. Nested objects
   in a tool argument already work — that is exactly how today's `List<RevenueRange>` parameter is
   passed — so this is mechanically supported. The `SYSTEM_PROMPT` then has to teach the model the
   operators and negation (mirroring 03's `systemPrompt(...)` rules: "not / except" → `negate`, a
   range → two conditions on the same field, operator choice for date phrasings, and so on).
6. **`RevenueRange.java`** — drops out. "revenue over 200000" becomes
   `Condition("annualRevenue", GREATER_OR_EQUAL, ["200000"], false)`, and a bounded range becomes two
   conditions — exactly as 03 does it.
7. **`@Tool currentLocalDateTime()`** — stays. The live-clock tool is 02's genuine distinguishing
   feature (resolving relative dates such as "yesterday" through a chained tool call) and is
   orthogonal to operators and negation.

## The payoff

After that change, `02-ai-agent-filter` and `03-ai-structured-filter` would differ **only in how the
model returns the finished filter** — a tool-call argument versus `.entity(CustomerFilter.class)` —
and no longer in **what the filter can express**. Operator precision and negation are properties of
the schema; both delivery mechanisms can carry the same rich filter. That is the sharpest way to see
that "tool calling vs. structured output" is a question of *delivery*, not *capability*.

## Trade-offs — why it is analysis, not a change

- **It erases the teaching contrast.** Module 02's value in this tutorial is being the flat, simplest
  approach; making it as expressive as 03 turns it into "03 with a different delivery mechanism".
- **Reliability on small/local models.** A richer tool schema (`List<Condition>` with an operator
  enum and a negate flag) tends to be harder for a small model to fill correctly than a single
  structured JSON object. This is the same reliability axis measured in the comparison document —
  where structured output on the weaker `llama3.1:8b` already dropped one bound of a date range
  (`notInCityWithRevenueAndYear`, `04-ollama-benchmark`, `--runs=5`). A more complex tool schema would
  have to be re-benchmarked, not assumed to be free.
- **Effort is modest but not zero.** The predicate logic already exists in 03's
  `CustomerFilterSpecifications`; most of the work is copying `Operator` / `Condition` /
  `CustomerFilterSpecifications` into 02 and rewriting the tool signature and system prompt.

## See also

- [tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md) — the full pros/cons
  comparison, evidence-backed.
- [capability-matrix.md](capability-matrix.md) — which query types each approach can express and how
  reliably.
