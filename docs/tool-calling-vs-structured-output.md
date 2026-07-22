# Tool calling vs. structured output

Two ways to turn a natural-language query into a Vaadin-`Grid` filter, both implemented in this repo
against the *same* domain and the *same* aligned tests, so the trade-off is concrete rather than
theoretical. This document states the pros and cons; every claim points at a specific test method,
an `CustomerSearchAgentExtraIT` case, or a `04-ollama-benchmark` figure. The per-query-type
breakdown lives in the companion [capability matrix](capability-matrix.md).

## The two approaches in one paragraph each

**Tool calling — `02-ai-agent-filter`.** `CustomerSearchToolCallingService` builds a `ChatClient`
and exposes `@Tool searchCustomers(...)` with one flat `List` parameter per field plus a second
tool, `@Tool currentLocalDateTime()`. The model *calls* the tool; the tool body fills a flat
`CustomerSearchCriteria`, which `CustomerSpecifications.from(...)` turns into a JPA `Specification`.
There is no operator or negation — each field's predicate builder bakes in its own semantics
(substring match, whole-year date range, revenue bound).

**Structured output — `03-ai-structured-filter`.** `CustomerSearchStructuredOutputService` calls
`.call().entity(CustomerFilter.class)`: the model returns *one* JSON object — a `CustomerFilter`
holding a flat `List<Condition>`, each condition a `(field, Operator, values, negate)` tuple —
which `CustomerFilterSpecifications.from(...)` translates. "Today" is baked into the prompt via
`systemPrompt(LocalDate today)`; there is no live date tool call.

Both filter types are deliberately **flat** (no AND/OR/NOT tree). The essential difference is that a
`Condition` carries an explicit `Operator` (`CONTAINS`, `EQUALS`, `GREATER_OR_EQUAL`,
`LESS_OR_EQUAL`, `STARTS_WITH`, `ENDS_WITH`) and a `negate` flag, whereas `CustomerSearchCriteria`
carries neither.

## Where they agree

For the 18 aligned cases in `CustomerSearchAgentIT` — single field, multi-field AND, multi-value OR,
credit-rating labels, revenue thresholds/ranges, no-op/reset/smalltalk → empty filter — both
approaches produce the right filter. In the recorded `-Pit-local-ollama` run on the configured
default `qwen3:8b`, **both modules passed every IT**: 02 = 18 (`CustomerSearchAgentIT`) + 7
(`CustomerListViewBrowserlessIT`); 03 = 18 + 18 (`CustomerSearchAgentExtraIT`) + 7. The paired
benchmark (`--runs=5`) agrees: on all **18 shared cases both approaches scored 5/5** — on `qwen3:8b`
*and* on `llama3.1:8b`.

So on a capable local model the two are interchangeable for the common cases. The differences show
up at the edges.

## What only structured output can express

These are **architectural**, not reliability, differences: 02's `CustomerSearchCriteria` has no
operator and no negation, so no prompt or model can make it produce them. All are covered by
`CustomerSearchAgentExtraIT`, which passed 18/18 on `qwen3:8b`.

- **Negation** — "customers except from Berlin", "email does not contain gmail".
  `CustomerSearchAgentExtraIT#singleFalseCity`, `#emailNotContains`,
  `#notInCityWithRevenueAndYear`. In 02 there is no `negate`; the query cannot be represented.
- **Operator precision** — STARTS_WITH / ENDS_WITH / exact EQUALS: "name starts with M", "email ends
  with .com", "name *is* Sofia". `#contactNameStartsWith`, `#emailEndsWith`, `#contactNameAndCity`,
  `#companyNameStartsWith`. 02 only ever does case-insensitive CONTAINS.
- **Arbitrary date bounds** — "last order before 2024-01-01", "ordered yesterday".
  `#lastOrderBeforeDate`, `#orderedYesterday`. 02 interprets every date as the *whole year* it falls
  in (`CustomerSpecifications` `addYearAnyOf`), so an open/closed day-level bound is inexpressible.
- **Exact value + anti-hallucination guard** — "exactly 100000 in revenue", with a check that no
  extra conditions were invented. `#revenueExact_notOverGenerated`.

This is the headline reason to prefer structured output when the filter vocabulary is rich.

## Where tool calling has (or had) an edge

- **Relative dates through a live clock.** 02 can resolve "yesterday"/"last week" by chaining
  `currentLocalDateTime()` and computing an offset. It's a genuine capability, but a *two-hop* one,
  and model-dependent: 02 deliberately ships **no** relative-date IT because a weaker model fails the
  chain (documented in `CustomerSearchAgentIT`'s in-body comment). 03 avoids the chain by baking
  "today" into the prompt and covers relative dates directly (`CustomerSearchAgentExtraIT`
  `relative-date` cases). So this is less "02 wins" than "02 pays for a live clock with a fragile
  extra hop that 03 doesn't need".
- **Model-dependent wins during alignment.** Three cases were dropped from the shared IT set because
  03 failed them on the then-default `llama3.1:8b` while 02 passed (recorded in both modules'
  `CustomerSearchAgentIT` Javadocs): `germanPhoneNumberNormalizedToE164` (03 echoed the raw phone
  string), `multiValueCustomerSinceYears` (03 collapsed "2020 or 2021" into one range), and
  `citiesAndCreditRating_German` (03 returned `conditions=null`); plus `customersSince2020` at the
  grid level (`CustomerListViewBrowserlessIT` Javadoc). These are model-dependent, not
  approach-inherent — see reliability below.

## Reliability and model dependence

The "reliable vs. flaky" question is answered by the benchmark pass-rates (`--approach=both
--runs=5`, full 36-case set), not by single JUnit runs.

Mean pass-rate is over each approach's full case set — tool calling runs the 18 shared cases,
structured runs all 36 (18 shared + 18 structured-only).

| Model | Tool calling (mean / median latency) | Structured (mean / median latency) |
|---|---|---|
| `qwen3:8b` (configured default) | 100% / 1083 ms | 100% / 1154 ms |
| `llama3.1:8b` (weaker) | 100% / 937 ms | 97% / 1004 ms |

On `qwen3:8b` both approaches score 100% across all 36 cases — no divergence at all. On
`llama3.1:8b` tool calling is also 100% (over the 18 shared cases it runs) and structured output is
97%: a **single** case, `notInCityWithRevenueAndYear` ("not from Berlin, ≥ 1000 revenue, last
ordered in 2024"), failed 0/5 because the model emitted `lastOrderDate GREATER_OR_EQUAL 2024-01-01`
but dropped the upper bound `LESS_OR_EQUAL 2024-12-31`, leaking later years in. That is exactly the
"a weaker model occasionally drops one bound of a date range" behaviour documented in
`03-ai-structured-filter/README.md`, now reproduced quantitatively (`--runs=5`, 0/5). The gap is
model-dependent: it closes completely on `qwen3:8b` (5/5).

The one case that reverses the direction — `phoneNumberContains` ("…5020000001 or similar") — is
expressible in *both* filter types, yet 02's tool-calling layer hallucinated an unrelated number on
`llama3.1:8b`, so it lives only in 03's `CustomerSearchAgentExtraIT#phoneNumberContains`. Structured
output's single-shot JSON is simply less prone to that kind of drift than a free-form tool call.

## Takeaway for the talk

- Need **negation, operator precision, or arbitrary date bounds**? Structured output — 02 cannot
  express them at all (`CustomerSearchAgentExtraIT`).
- Running a **small/local model**? Structured output's one-JSON-object contract is easier to produce
  reliably than a multi-step tool call; the divergence cases above all bit tool calling *or*
  structured output on the weaker `llama3.1:8b` and mostly vanish on `qwen3:8b`.
- Need a value the model can't know at prompt time (a **live clock**, an external lookup)? That is
  the natural home of tool calling — but budget for the extra hop's model-dependence.

See the [capability matrix](capability-matrix.md) for the full per-query-type table with test
citations.
