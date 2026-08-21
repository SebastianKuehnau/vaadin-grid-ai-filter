# Every query is spelled out in every IT class, prompt included

Each AI module's IT classes contain one named `@Test` per natural-language query, with the prompt as
a string literal and the expected customer set computed right below it. The eight canonical queries and
five robustness queries therefore appear **eight times** across the repository (four variants × service
IT and UI IT), duplicated by hand.

This looks like an obvious refactoring opportunity. It is not — **please do not deduplicate it.**

## Why

The repository exists to be read at a conference, by an audience that includes beginners. Before this
decision the queries lived in a `CanonicalQuery` enum in `00-commons`' test-jar, the assertion lived in
an `AbstractCustomerSearchIT`, and each module contributed only an `expectedResultFor` method mapping
enum constants to `MATCH` / `NO_MATCH_BY_DESIGN`. Opening `FlatCustomerSearchIT` told a reader neither
which query ran, nor what was expected, nor what the module actually did — three files had to be
opened and mentally joined, none of them the test class.

Something similar applies to the whole repository: 01 → 02(a) → 02(b) → 03 → 04 duplicate their AI
service, filter type and prompt on purpose, because each step has to be readable on its own. The test
layer now follows the same rule.

## Consequences

- ~500 lines of test code where ~150 would do, and a prompt change means editing eight files. The
  table in `docs/canonical-query-set.md` is the checklist; nothing enforces it at compile time.
- The previous design had an exhaustive `switch` that stopped every module from compiling until a new
  query's expectation was decided. That gate is gone, deliberately: it bought maintenance safety at
  the cost of the thing a reader looks at first.
- Queries a variant's filter type cannot express are `@Disabled` with the reason, so the test still
  shows what it would assert. The previous design instead asserted that the result must *differ* from
  the expected one — correct, but the most confusing construct in the repository.

## What may be shared

Mechanism only, through `00-commons`' test-jar: `AbstractCustomerSearchViewIT.search(query)` (navigate,
type, await the async callback, read the grid) and the logging/token extensions. Never a query, an
expectation, or a reason.
