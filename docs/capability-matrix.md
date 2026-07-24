# Capability matrix: tool calling (02) vs. structured output (03)

Which query types each approach can express and how reliably it does so, derived strictly from the
two modules' integration-test suites and the `04-ollama-benchmark` harness. Companion to
[tool-calling-vs-structured-output.md](tool-calling-vs-structured-output.md).

- **02 = `02-ai-agent-filter`** — tool calling; the LLM calls `@Tool searchCustomers(...)` (one flat
  `List` per field) → `CustomerSearchCriteria` → `CustomerSpecifications`.
- **03 = `03-ai-structured-filter`** — structured output; the LLM returns one `CustomerFilter`
  (a flat `List<Condition>`, each `field / operator / values / negate`) → `CustomerFilterSpecifications`.

**Cell legend:** ✅ reliably handled · ⚠️ model-dependent · ❌ not expressible by the approach's
filter type. Every cell points at a test method (`Class#method`) or a benchmark figure.

Configured default model for **both** modules: `qwen3:8b`, temperature 0
(`src/main/resources/application-ollama.properties`). Reliability figures below are mean pass-rates
from `04-ollama-benchmark` at `--approach=both --runs=5` (full 36-case set) on `qwen3:8b` and, to
show the model-dependence, `llama3.1:8b`. **Token-consumption and per-request duration** figures are
*not* in this matrix (they do not change what a query type can express or how reliably); they are
measured by the `TokenUsageRecorder` on the real application path and reported in
[tool-calling-vs-structured-output.md § Token cost and request duration](tool-calling-vs-structured-output.md#token-cost-and-request-duration).

## Shared cases — both approaches express them

These 18 cases use identical method names, wording and source order in both modules'
`CustomerSearchAgentIT` (the aligned set), so results are directly comparable.

| Query type (example) | 02 tool calling | 03 structured | Evidence |
|---|---|---|---|
| Single text field ("customers in Berlin") | ✅ | ✅ | `CustomerSearchAgentIT#singleCity` (both) |
| Credit-rating label ("creditworthy" / "at risk") | ✅ | ✅ | `#creditworthyCustomers`, `#atRiskCustomers` (both) |
| Two fields AND'd ("creditworthy customers in Hamburg") | ✅ | ✅ | `#creditworthyInCity` (both) |
| Contact / company name substring | ✅ | ✅ | `#contactNameContains`, `#companyNameContains` (both) |
| Single year ("customers since 2020") | ✅ | ✅ | `#customerSinceYear` (both) |
| Multi-value OR within a field (cities; ratings) | ✅ | ✅ | `#multiValueCities`, `#multiValueCreditRating` (both) |
| Revenue threshold / range | ✅ | ✅ | `#annualRevenueOverThreshold`, `#citiesWithRevenueRange`, `#citiesAndRevenue_keepsEveryCondition` (both) |
| Country | ✅ | ✅ | `#country` (both) |
| German multi-condition query | ✅ | ✅ | `#contactNameAndCity_German` (both) |
| No-op / reset / smalltalk / unrelated → empty filter | ✅ | ✅ | `#resetTheFilter_German`, `#smalltalk_noCriteria`, `#unrelatedRequest_noCriteria`, `#showAllCustomers_noCriteria` (both) |

Benchmark reliability, `--runs=5` (paired report `## Tool-calling vs. structured (paired)`): on the
**18 shared cases every combination scored 5/5** — tool calling *and* structured, on `qwen3:8b`
*and* `llama3.1:8b`. There is no divergence on the shared set on either model. (Overall means across
each approach's full case set are in the [comparison document](tool-calling-vs-structured-output.md#reliability-and-model-dependence).)

## Structured-output-only — 02's flat `CustomerSearchCriteria` cannot express these

`CustomerSearchCriteria` has one `List` per field and no per-field operator or negation, so these
are architecturally impossible in 02 — not merely unreliable. All are covered by
`CustomerSearchAgentExtraIT` (03), which passed 18/18 on `qwen3:8b` in the recorded IT run.

| Query type (example) | 02 tool calling | 03 structured | Evidence |
|---|---|---|---|
| **Negation** ("except from Berlin"; "email does not contain gmail") | ❌ no `negate` | ✅ | `CustomerSearchAgentExtraIT#singleFalseCity`, `#emailNotContains`, `#notInCityWithRevenueRange_keepsEveryCondition`(+`_German`), `#notInCityWithRevenueAndYear`(+`_German`) |
| **Operator precision** STARTS_WITH / ENDS_WITH / EQUALS ("name starts with M"; "email ends with .com"; "name is Sofia") | ❌ CONTAINS only | ✅ | `#contactNameStartsWith`, `#contactNameEndsWith`, `#emailEndsWith`, `#companyNameStartsWith`, `#contactNameAndCity`, `#creditRatingTwoValues_staySeparateCriteria` |
| **Arbitrary date bounds** ("last order before 2024-01-01"; "ordered yesterday"; "this year") | ❌ whole-year equality only | ✅ | `#lastOrderBeforeDate`, `#orderedYesterday`, `#orderedInTheLastWeek`, `#customerSinceThisYear` |
| **Exact-numeric + no-extras guard** ("exactly 100000 in revenue") | ❌ no exact-value contract | ✅ | `#revenueExact_notOverGenerated` |

> Cross-field OR and arbitrary nesting are **not** in this column: they were removed from
> `CustomerFilter` too (a deliberate simplification for small models), so neither approach expresses
> them — see `CustomerSearchAgentExtraIT`'s class Javadoc.

## Where tool calling has (or had) an edge

| Query type (example) | 02 tool calling | 03 structured | Evidence |
|---|---|---|---|
| **Relative date via a live clock** ("ordered yesterday") | ⚠️ chained `currentLocalDateTime()` tool call — model-dependent | ✅ "today" baked into `systemPrompt(LocalDate)` | 02: `@Tool currentLocalDateTime()`; no 02 IT case by design (see `CustomerSearchAgentIT` in-body comment). 03: `CustomerSearchAgentExtraIT` `relative-date` cases |

02 *can* resolve relative dates by chaining `currentLocalDateTime()` then computing an offset. That
two-hop chain is model-dependent: it is why 02 has **no** relative-date IT (documented in
`02-.../CustomerSearchAgentIT`'s in-body comment). 03 sidesteps the chain entirely by putting the
date into the prompt text.

## Bidirectional divergence observed during alignment (model-dependent, `llama3.1:8b`)

These cases are in **neither** current suite: they were tried during test alignment and dropped
because one approach failed them on the then-default `llama3.1:8b` while the other passed. They are
the sharpest illustration that reliability is model-dependent, not approach-inherent. Recorded in
the `CustomerSearchAgentIT` / `CustomerListViewBrowserlessIT` Javadocs of both modules.

| Case | 02 tool calling | 03 structured | What happened (on `llama3.1:8b`) |
|---|---|---|---|
| `germanPhoneNumberNormalizedToE164` | ✅ passed | ❌ failed | 03 echoed the raw phone string instead of E.164 |
| `multiValueCustomerSinceYears` ("2020 or 2021") | ✅ passed | ❌ failed | 03 emitted one `[2020-01-01, 2021-12-31]` range, not two lower-bound conditions |
| `citiesAndCreditRating_German` | ✅ passed | ❌ failed | 03 returned `conditions=null` |
| `customersSince2020` (grid, "since &lt;year&gt;") | ✅ passed | ❌ failed | 03 omitted the upper date bound, leaking later years into the grid |
| `phoneNumberContains` ("…5020000001 or similar") | ❌ failed | ✅ passed | 02 hallucinated an unrelated phone number; 03 keeps it (`CustomerSearchAgentExtraIT#phoneNumberContains`) |

These divergences were observed on `llama3.1:8b`; on the configured default `qwen3:8b` both
approaches pass the entire current suite (02: 18 + 7; 03: 18 + 18 + 7 IT cases, all green in the
recorded run). The paired benchmark confirms it: on the shared cases both approaches score 5/5 on
*both* models, and the only residual `--runs=5` miss anywhere is structured output on `llama3.1:8b`
dropping one bound of a date range in the structured-only `notInCityWithRevenueAndYear` (0/5) — a
gap that closes on `qwen3:8b` (5/5).
