# Canonical query set

The natural-language queries every AI module is tested with — one per capability, so the result reads
as a capability ladder rather than a list of anecdotes.

Each AI module contains one `@Test` per query, in both of its IT classes (through the service and
through the UI), with the query as a string literal. Queries a variant's filter type cannot express are
`@Disabled` with the reason. **This table and those test methods are kept in sync by hand.**

✅ expressible · ❌ not expressible by that variant's filter type — architecturally impossible, not
unreliable: no prompt and no model can make a filter type carry a value it has no slot for.

| # | Query | Capability | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|---|---|
| C1 | `show me all customers in Berlin` | single value | ✅ | ✅ | ✅ | ✅ |
| C2 | `show me customers from Berlin or Hamburg` | multiple values for one field (OR) | ❌ | ❌ | ✅ | ✅ |
| C3 | `show me all customers except from Berlin` | negation | ❌ | ✅ | ✅ | ✅ |
| C4 | `show me all customers with an "m" as the first character in the contact name` | non-CONTAINS operator | ❌ | ✅ | ✅ | ✅ |
| C5 | `creditworthy customers in Hamburg` | combined AND across fields | ✅ | ✅ | ✅ | ✅ |
| C6 | `customers with revenue between 100000 and 200000` | numeric range | ❌ | ❌ | ✅ | ✅ |
| C7 | `show me all customers who placed an order in the last 12 months` | relative date | ❌ | ✅ | ✅ | ✅ |
| C8 | `customers who last ordered between 2024-07-01 and 2025-03-31` | date range | ❌ | ❌ | ✅ | ✅ |
| | | **Capabilities reached** | **2 / 8** | **5 / 8** | **8 / 8** | **8 / 8** |

## The robustness set

Input that asks for *no* filter. This does not depend on the filter type, so every AI module is
expected to pass all five — a failure here is a reliability finding, not a documented limit. Only the
service-level `*CustomerSearchIT` classes run these.

| # | Query | Expected |
|---|---|---|
| R1 | `Nice weather today, isn't it?` | every customer — no filter was asked for |
| R2 | `What's the capital of France?` | every customer |
| R3 | `show me all customers` | every customer |
| R4 | `remove the filter and show everything again` | every customer |
| R5 | `zeig mir alle Kunden aus Berlin` | the Berlin customers — C1 in a language the prompt is not written in |
