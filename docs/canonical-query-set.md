# Canonical query set

The **single source of truth** for the natural-language queries every AI module of this repository is
tested with. Eight queries, one per capability category, each with the customer set it must produce.

Two places spell these queries out **verbatim**:

| Copy | File |
|---|---|
| the shared enum | `demo-commons/src/test/java/.../canonicalquery/CanonicalQuery.java` |
| benchmark | `ollama-benchmark/BenchmarkLocalModels.java` |

`CanonicalQuery` carries each query together with the reference predicate a correct answer must satisfy,
and is read by every AI module through the service (`*AiFilterIT`) and through the UI
(`*CustomerListViewBrowserlessIT`) — eight queries, two paths, four variants, one text. What it
deliberately does *not* carry is the expected result: that depends on the asking variant's filter type,
so each variant states it in its own `expectedResultFor` method. The benchmark script keeps a separate copy because
it is deliberately standalone and dependency-free.

Both copies have to match this document verbatim, wording **and** order — that is what makes the
token/latency figures from the benchmark line up query-for-query with the pass/fail reliability results
from the IT suites.

## Why these eight

The four AI approaches differ in what their filter type can express, not in how hard the queries are.
Each query below is the cheapest possible probe for one capability, so the resulting matrix reads as a
capability ladder rather than a list of anecdotes:

| # | Category | 02(a) flat | 02(b) value+operator+negate | 03 structured | 04 hybrid |
|---|---|---|---|---|---|
| C1 | single value | ✅ | ✅ | ✅ | ✅ |
| C2 | multiple values for one field (OR) | ❌ | ❌ | ✅ | ✅ |
| C3 | negation | ❌ | ✅ | ✅ | ✅ |
| C4 | non-CONTAINS operator (starts-with) | ❌ | ✅ | ✅ | ✅ |
| C5 | combined AND across fields | ✅ | ✅ | ✅ | ✅ |
| C6 | revenue range | ❌ | ❌ | ✅ | ✅ |
| C7 | relative date | ❌ | ✅ | ✅ | ✅ |
| C8 | date range | ❌ | ❌ | ✅ | ✅ |

**❌ means "architecturally impossible", not "unreliable".** 02(a) has one scalar value per field and no
operator; 02(b) has one value, one operator and one negate flag per field — neither can hold a second
value (C2) or a second bound (C6, C8) for one field. Those cells are asserted as **expected failures**:
the IT records that the produced customer set differs from the expected one, without an exception. If
such a case unexpectedly *passes*, the test fails loudly — an accidental capability is as interesting as
a missing one.

02(a) additionally fails C7 because it has no operator — a date always means its whole calendar year, so
a range like "the last 12 months" cannot be expressed even though 02(a) also has a `currentLocalDateTime()`
tool (it resolves *which* date to use, not the range semantics C7 needs); 02(b) resolves relative dates
through the same tool, 03 and 04 through the "today" baked into their prompts.

## Expected customer sets

Every query's expected result is defined as a **predicate over `Customer`**, evaluated against whatever
`data.sql` currently seeds, never as a hard-coded list of IDs. Two reasons:
C7 depends on today's date,
and each app's startup sets "Berlin Data Works"'s last order date to yesterday (see any
`*Application.java`), so one row is deliberately not static.

The counts below are for the committed `data.sql` (100 customers) as of 2026-07-29 and are informative
only — the tests compute them, they do not hard-code them.

## The queries

Each entry names where its wording came from. Several cite `CustomerSearchAgentIT` /
`CustomerSearchAgentExtraIT` — 03's pre-canonical LLM integration tests, which this query set
superseded and which have since been removed. Those citations are **historical**: they record which
earlier test's wording a canonical query inherited, so the two are known to be comparable. Look them up
in the git history; nothing in the repository runs them any more.

### C1 — single value

Category: single value. Wording reused from 02's and 03's aligned `CustomerSearchAgentIT#singleCity`.

```text
show me all customers in Berlin
```

Expected: `city` contains "Berlin" (case-insensitive) — 18 customers.

### C2 — multiple values for one field (OR)

Category: multi-value OR. Wording reused from `CustomerSearchAgentIT#multiValueCities`.

```text
show me customers from Berlin or Hamburg
```

Expected: `city` is "Berlin" **or** "Hamburg" — 35 customers.

### C3 — negation

Category: negation. Wording reused from 03's `CustomerSearchAgentExtraIT#singleFalseCity`.

```text
show me all customers except from Berlin
```

Expected: `city` does **not** contain "Berlin" — 82 customers.

### C4 — non-CONTAINS operator (starts-with)

Category: operator precision. Wording reused from 03's
`CustomerSearchAgentExtraIT#contactNameStartsWith`.

```text
show me all customers with an "m" as the first character in the contact name
```

Expected: `contactName` starts with "m" (case-insensitive) — 6 customers (Max Mustermann, Mia Jansen,
Mia Meyer, Mia Schmidt). A CONTAINS match would return far more rows (every "…Mustermann", "…Martin",
"…Meyer"), which is what makes this a real operator probe rather than a spelling exercise.

### C5 — combined AND across fields

Category: AND across fields. Wording reused from `CustomerSearchAgentIT#creditworthyInCity`.

```text
creditworthy customers in Hamburg
```

Expected: `city` contains "Hamburg" **and** credit rating is GOOD (credit score ≥ 70) — 9 customers.

### C6 — revenue range

Category: value range on a numeric field. New wording, derived from
`CustomerSearchAgentIT#citiesWithRevenueRange` by dropping its city part, so the query probes the range
and nothing else.

```text
customers with revenue between 100000 and 200000
```

Expected: `annualRevenue` between 100000 and 200000 inclusive — 37 customers. Needs **two** bounds on one
field, so it is impossible for 02(a) (revenue is always a minimum) and for 02(b) (one operator per
field): the lower bound alone returns 58 customers, the upper bound alone 79.

The upper bound is deliberately 200000, not the 500000 of the query this wording is derived from: the
highest revenue in `data.sql` is 249900, so a 500000 ceiling would be vacuous and 02(a)/02(b) would pass
the case by accident, with a filter that does not actually express a range.

### C7 — relative date

Category: relative date. New wording, following the pattern of 03's
`CustomerSearchAgentExtraIT#orderedInTheLastWeek` but over a window that the seeded data actually
populates.

```text
show me all customers who placed an order in the last 12 months
```

Expected: `lastOrderDate` on or after today minus one year — 15 customers on 2026-07-29 (plus the
startup-updated "Berlin Data Works" row, which is inside the window anyway).

Both readings of "the last 12 months" are accepted as a pass:

- the **open-ended** one, `lastOrderDate >= today - 1 year` (15 customers), and
- the **closed** one, `lastOrderDate >= today - 1 year AND <= today` (14 customers) — the seeded data
  contains exactly one future-dated order (2026-12-13).

Both are correct readings of the phrase, so the expected set is "either of these two", and a model is
not penalised for choosing one over the other.

### C8 — date range

Category: value range on a date field. Explicit ISO bounds, in the style of 03's
`CustomerSearchAgentExtraIT#lastOrderBeforeDate` ("last order was before 2024-01-01"), so nothing about
the range depends on how a month name is rounded.

```text
customers who last ordered between 2024-07-01 and 2025-03-31
```

Expected: `lastOrderDate` between 2024-07-01 and 2025-03-31 inclusive — 4 customers
(2024-07-19, 2024-07-23, 2024-11-07, 2025-02-14). The next order after the window is 2025-04-01, so an
off-by-one on the upper bound changes the result set and is caught.

The window deliberately **spans two calendar years**: that is what makes it impossible for 02(a), whose
dates always mean the whole calendar year they fall in (its best attempt returns all of 2024, 5
customers), and for 02(b), which has one operator per field and can therefore give only one of the two
bounds (`>= 2024-07-01` alone returns 21 customers).
