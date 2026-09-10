# Canonical query set

The natural-language queries every AI module is tested with — one per capability, so the result reads
as a capability ladder rather than a list of anecdotes.

**This is the `demo` branch.** Modules 02, 03 and 04 filter on three fields — `city`, `lastOrderDate`
and `creditRating` — so that their tool signatures, prompts and specifications stay readable on a
conference slide. `main` carries the full thirteen. The three fields were chosen because each rung of
the ladder is carried twice by them; see the table below. Module 01 is untouched and still filters on
thirteen dimensions — that its hand-built form is the most expressive thing in this repository is the
point of the first step, not an oversight.

Each AI module contains one `@Test` per query, with the query as a string literal: all eight
canonical cases in its service-level IT class, and two of them in its browserless IT — C1 plus one
case the variant can express, because those test the wiring between view and agent, not capability.
Queries a variant's filter type cannot express are `@Disabled` with the reason. **This table and
those test methods are kept in sync by hand.**

The case ids are the ones from `main`, gaps included: C4, C6, C9 and C10 needed `contactName`,
`annualRevenue` or `country` and exercised no capability the remaining cases do not. Keeping the gaps
visible keeps both branches' tables comparable.

✅ expressible · ❌ not expressible by that variant's filter type — architecturally impossible, not
unreliable: no prompt and no model can make a filter type carry a value it has no slot for.

| # | Query | Capability | IT test method | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|---|---|---|
| C1 | `show me all customers in Berlin` | single value | `findsCustomersInOneCity` | ✅ | ✅ | ✅ | ✅ |
| C2 | `show me customers from Berlin or Hamburg` | multiple values for one field (OR) | `findsCustomersInEitherOfTwoCities` | ❌ | ❌ | ✅ | ✅ |
| C3 | `show me all customers except from Berlin` | negation | `findsCustomersOutsideOneCity` | ❌ | ✅ | ✅ | ✅ |
| C5 | `creditworthy customers in Hamburg` | combined AND across fields | `findsCreditworthyCustomersInOneCity` | ✅ | ✅ | ✅ | ✅ |
| C7 | `show me all customers who placed an order in the last 12 months` | relative date | `findsCustomersWithAnOrderInTheLastTwelveMonths` | ❌ | ✅ | ✅ | ✅ |
| C8 | `customers who last ordered between 2024-07-01 and 2025-03-31` | date range | `findsCustomersWhoLastOrderedWithinADateRange` | ❌ | ❌ | ✅ | ✅ |
| C11 | `Kunden, die zuletzt am 18.11.2025 bestellt haben` | exact day, German date format | `findsCustomersWhoLastOrderedOnAGermanFormattedDate` | ✅ | ✅ | ✅ | ✅ |
| C12 | `show me all customers who are not creditworthy` | rating stated as a negation | `findsCustomersWhoAreNotCreditworthy` | ✅ | ✅ | ✅ | ✅ |
| | | **Capabilities reached** | | **4 / 8** | **6 / 8** | **8 / 8** | **8 / 8** |

Each step of the ladder is carried by two cases, so no single flaky run can erase a rung:

| Step | is proven by |
|---|---|
| 02(a) → 02(b) | C3 (negate flag) and C7 (an operator, not just equality) |
| 02(b) → 03 | C2 (two values for one field) and C8 (two bounds for one field) |

The `@Disabled` reasons, verbatim from the test classes, are what each ❌ means:

| # | 02(a) | 02(b) |
|---|---|---|
| C2 | 02(a) holds one value per field - 'Berlin or Hamburg' needs two | 02(b) holds one value per field - 'Berlin or Hamburg' needs two |
| C3 | 02(a) has no negate flag | — |
| C7 | 02(a) has no operator - a date can only be matched exactly, not as 'on or after' | — |
| C8 | 02(a) holds one value per field - a date range needs two bounds | 02(b) holds one value and one operator per field - a date range needs two bounds |

## The robustness set

Input that exercises no new capability — phrasing and language. This does not depend on the filter
type, so every AI module is expected to pass all of them — a failure here is a reliability finding,
not a documented limit. Only the service-level `*CustomerSearchIT` classes run these.

`main` runs ten of these (R1–R10), because comparing models against each other is what they are for
and that is `main`'s job. This branch keeps the three that earn their place in a talk, plus one new
one.

| # | Query | Expected | IT test method | 02(a) | 02(b) | 03 | 04 |
|---|---|---|---|---|---|---|---|
| R1 | `Nice weather today, isn't it?` | every customer — no filter was asked for | `ignoresSmallTalk` | ✅ | ✅ | ✅ | ✅ |
| R3 | `show me all customers` | every customer | `showsEveryCustomerWhenAskedForAll` | ✅ | ✅ | ✅ | ✅ |
| R5 | `zeig mir alle Kunden aus Berlin` | the Berlin customers — C1 in a language the prompt is not written in | `understandsAGermanQuery` | ✅ | ✅ | ✅ | ✅ |
| R11 | `zeig mir alle Kunden aus München` | the Munich customers | `translatesAGermanCityName` | ✅ | ✅ | ✅ | ✅ |

R8, the prompt injection, is not run here. It fails in all four variants, it is measured and
documented in `main`, and a slide stating that result is worth more than a `@Disabled` test nobody
sees.

### R11 and the rule that makes it pass

`data.sql` stores city names in English: `Berlin`, `Hamburg`, `Munich`, `Frankfurt`, `Cologne`,
`Dusseldorf`. `München` therefore matches nothing unless the model translates the value **before** it
reaches the filter. One line in each system prompt does that, and switching it off in front of an
audience is the cheapest demonstration of what a prompt actually buys.

**It is also the riskiest line in this branch**, for the reason the next section spells out: it is a
value-rewriting rule, and the last value-rewriting rule this project tried bled into a neighbouring
field. C3 and C12 — the two negation cases — are what to watch. They run in the same `./mvnw verify`,
so a regression shows up immediately.

## Why there is no misspelling case

A misspelled query (`show me all custmers in Brelin`) was measured in all four variants and then
dropped, together with the "fix obvious typos" line 02(a) briefly carried in its system prompt
(commit `c2dc5ed`). Spelling correction is a model capability, not a filter capability: every variant
matches its values against the stored text, so a misspelled *city* can only match if the model spells
it correctly before the value reaches the filter — and asking for that in the prompt cost more than
it bought. In 02(b) the same line made C3 ("all customers except from Berlin") call the tool with no
arguments at all; C3 and the typo case were never green together over three full runs. In 03 the
model corrected the typo but set `negate=true` on it, turning "in Brelin" into "not in Berlin".

R11's translation rule is built the same way — rewrite the value before passing it — which is why it is
carried deliberately and watched, rather than assumed to be free.

## Measuring models against this set

The tables above say what a *filter type* can express. What a *model* actually gets right is measured
by the `benchmark` module, **which lives in `main`, not here**: model comparison, token counts and
latency are `main`'s subject, and this branch drops the module so that nothing in it needs a second
copy of the query list.

One lesson from that measurement is worth keeping in mind when reading any row above: **a single
green run proves nothing here.** The same prompt, byte for byte, produced opposite results in an
isolated run and in a full class run — the model is not deterministic in practice even at
`temperature=0`, because Ollama reuses a cached prefix whose state depends on what ran before. Every
✅ above rests on full class runs, repeated.
