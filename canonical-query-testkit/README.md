# canonical-query-testkit

Shared test infrastructure for the canonical query set. Consumed by `02-ai-agent-filter`,
`03-ai-structured-filter` and `04-ai-hybrid-filter` as a `test`-scope dependency.

No numeric prefix, unlike `01`–`04`: this is not a step of the talk, it is the scaffolding the
talk's comparison stands on.

## Why this module exists at all

Everywhere else in this repository, duplication is deliberate — each module is a standalone app with
its own copy of the domain classes, so a reader can follow one approach without cross-referencing the
others. This module is the **one exception**, and only for test infrastructure.

The reason is drift. The four canonical-query ITs used to carry a byte-identical copy of the query
strings, the expected result predicates and the assert/log mechanics. Five copies of eight query
strings is five chances to get them out of step, and the guard against that was itself duplicated
three times. Runtime and domain code stay duplicated; this piece does not.

## What lives here

| Type | Role |
|---|---|
| `CanonicalQuery` | The eight queries (C1–C8) and, per query, the customer set(s) that count as correct |
| `CanonicalCustomer` | The six-field projection the expectations are written against |
| `Outcome` | `PASSES` / `FAILS_BY_DESIGN` — the type only; the mapping stays per module |
| `CanonicalQueryRunner` | The shared assert-and-log step |
| `CanonicalQuerySetConsistencyTest` | Guards `CanonicalQuery` and the benchmark script against `docs/canonical-query-set.md` |

## What deliberately stays in each module

- **Which queries a variant can express** — the `Outcome` per query. That is the actual finding each
  IT demonstrates, and the whole point of the comparison in the talk, so it stays visible in the IT
  itself, as an exhaustive `switch` that stops compiling if a query is ever added without a decision.
- **How a query string becomes a set of customer ids** — each module's own AI mechanism.
- **The `Customer` → `CanonicalCustomer` mapping** — a few lines of field access against that
  module's own entity.

## Build

```bash
./mvnw verify -pl canonical-query-testkit
```

Because `02`/`03`/`04` depend on this module (and all four apps on `demo-commons`), building one of
them on its own needs `-am` (also-make) so Maven builds the shared modules first:

```bash
./mvnw verify -pl 03-ai-structured-filter -am
```
