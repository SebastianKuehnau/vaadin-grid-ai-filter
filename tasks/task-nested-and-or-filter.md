# Task
Extend the CustomerFilter structure in module 04 to support arbitrarily nested AND/OR combinations.
Currently only same-field OR and cross-field AND are possible; cross-field OR
(e.g. "in Berlin OR revenue > 1M") is not achievable.

# Context
- Affected modules: `04-advanced-ai-filter`, `04-local-ai-filter`
- Purpose: Demo for conference talks — code must remain **understandable and presentable**
  despite increased complexity
- Current structure (problem):
  - `CustomerFilter` is a flat list of `FilterCriterion`
  - Logic in `CustomerFilterSpecifications` is hard-coded:
    - Same-field with CONTAINS/EQUALS → OR
    - Same-field with other operators → AND
    - Cross-field → always AND
  - **Cross-field OR is not possible** (e.g. `city=Berlin OR revenue > 1000000`)
  - Documented design goal (CustomerFilter.java:9-12): "simple enough for smaller, local LLMs"
- Goal: Nested structure with explicit AND/OR nodes
  - Enables arbitrary combinations: `(city=Berlin OR city=Hamburg) AND (revenue > 500k OR creditRating=GOOD)`
  - LLMs must produce more complex structured outputs (potentially higher model quality requirements)
- Relevant existing parts:
  - `CustomerFilter.java` (both modules)
  - `FilterCriterion.java` (both modules)
  - `Operator.java` (both modules)
  - `CustomerFilterSpecifications.java` (both modules)
  - `CustomerSearchIT.java` (04-local-ai-filter) — test cases

# Approach
1. First create a plan (Plan Mode) and show it to me. Wait for my OK.
2. Implement the plan. Work in logical steps, commit after each verified step
   (Conventional Commits, no push).
3. Verify autonomously according to Definition of Done in CLAUDE.md.
   Iterate on errors independently — don't present error messages for analysis
   that you can reproduce and fix yourself.

# Implementation (framework, details belong in your plan)

## 1. Design new filter structure

**Approach A: Recursive FilterCriterion**
- `FilterCriterion` becomes a union type with three variants:
  - Leaf: Condition with field/operator/value
  - AND: List of children (all must match)
  - OR: List of children (at least one must match)
- JSON schema via `@JsonSubTypes` / `@JsonTypeInfo`

**Approach B: Separate FilterGroup type**
- `CustomerFilter` contains a `FilterGroup root`
- `FilterGroup` has: `LogicalOperator operator` (AND/OR) + `List<FilterNode> children`
- `FilterNode` is an interface with two implementations:
  - `ConditionNode` (field/operator/value)
  - `GroupNode` (nested FilterGroup)

**Decide on an approach** (justify in your plan: clarity, JSON structure, LLM-friendliness).

## 2. Refactor CustomerFilterSpecifications

- Remove the hard-coded same-field/cross-field logic
- Implement recursive tree traversal:
  - AND nodes → `cb.and(children...)`
  - OR nodes → `cb.or(children...)`
  - Leaf nodes → `toPredicate()` as before
- Keep existing predicate builders (`textPredicate`, `datePredicate`, etc.)

## 3. Ensure backward compatibility (optional)

If possible:
- Continue supporting the old flat structure as a shorthand (implicit AND)
- Or: Migrate old prompts/tests explicitly to the new structure

## 4. Extend tests

**Add new test cases in `CustomerSearchIT`:**
- Cross-field OR: `city=Berlin OR revenue > 1000000`
- Nested combination: `(city=Berlin OR city=Hamburg) AND (revenue > 500k OR creditRating=GOOD)`
- Negated group: `NOT (city=Berlin AND revenue < 100k)`
- Empty groups: `AND []` / `OR []` should be handled gracefully

**Adapt existing tests:**
- Check if old tests are compatible with the new structure
- If necessary: Convert old flat filters to the new nested structure

## 5. Tests for different model sizes

Categorize all tests by expected model complexity using JUnit @Tag:

**Test categories:**
- `@Tag("small-model-query")`: Same-field OR, cross-field AND → 7B+ parameters
  - Example: "Berlin or Hamburg" (same-field OR)
  - Example: "customers in Berlin with revenue > 100k" (cross-field AND)

- `@Tag("medium-model-query")`: Nested combinations → 14B+ parameters
  - Example: "Berlin or Hamburg with revenue between 100k and 500k"
  - Example: "(city=Berlin OR city=Hamburg) AND (revenue > 500k OR creditRating=GOOD)"

- `@Tag("large-model-query")`: Cross-field OR → 32B+ parameters
  - Example: "customers in Berlin or with revenue above 1 million" (cross-field OR)
  - Example: "Hamburg or good credit rating" (cross-field OR)

**Timeout requirement:**
- All tests (especially large-model-query) with `@Timeout(value = 60, unit = TimeUnit.SECONDS)`
- If a small model cannot process a complex test, the test fails after 60s
- This is **expected behavior** and documents model limitations

**Documentation of expected results:**
Document in the module README how many tests should pass for each model size:
- qwen3.5:9b (small): ~15/23 passing (all small-model-query + some medium)
- qwen3.5:14b (medium): ~20/23 passing (small + medium + some large)
- qwen3.5:32b (large): 23/23 passing (all categories)

The test tags show in the test report which queries require which model size.

## 6. Update documentation

**In `CustomerFilter.java` Javadoc:**
- Remove/replace the note about "flat filter ... simple enough for smaller, local LLMs"
- Describe the new nested structure with example JSON

**In module READMEs (`04-advanced-ai-filter/README.md`, `04-local-ai-filter/README.md`):**
- Explain the new filter structure
- Example JSON for a nested query
- Note on potentially higher requirements for LLM models (smaller models might make more errors)

**Optional: Extend COMPARISON.md (if present):**
- Difference between module 03 (Tool Calling) and module 04 (nested structured output)

# Definition of Done (in addition to CLAUDE.md)

## Structure verification:
- The new filter structure supports arbitrarily nested AND/OR combinations
- Cross-field OR is possible: `city=Berlin OR revenue > 1M` works
- Nested groups work: `(A OR B) AND (C OR D)`
- Negated groups work (if implemented): `NOT (A AND B)`

## Test verification:
- All existing tests in `CustomerSearchIT` pass (in both modules)
- New test cases for cross-field OR are added and passing
- New test cases for nested combinations are added and passing
- Edge cases (empty groups, single-child groups) are handled gracefully
- `./mvnw verify -pl 04-advanced-ai-filter` is green
- `./mvnw verify -pl 04-local-ai-filter` is green
- Tests demonstrate the difference between small and large models (see section 5)

## Code quality:
- The implementation is **understandable and presentable** (no "clever" code)
- Javadoc explains the new structure with examples
- No code duplication between 04-advanced-ai-filter and 04-local-ai-filter
- Logging for parse errors (if LLM delivers invalid structure)

## Documentation verification:
- Module READMEs describe the new structure with example JSON
- Javadoc of `CustomerFilter` and `CustomerFilterSpecifications` is updated
- Note on increased LLM requirements is documented

## UI verification (only 04-advanced-ai-filter, if UI exists there):
- Start app with `./mvnw spring-boot:run -pl 04-advanced-ai-filter`
- Manual tests with nested queries via the UI
- Save screenshot of successful filtering (~/screenshots/)

# Final Report
Summarize at the end:
- What was changed (per commit)
- What new filter capabilities are now available (with examples)
- Which tests are passing (`CustomerSearchIT`, `./mvnw verify`)
- How do the tests demonstrate the difference between small and large models?
- Potential limitations: Could smaller LLMs (e.g. llama3:8b) have problems with the more
  complex structure? Recommendation for minimum model size?
- Open points/decisions I need to make (e.g.: also support old flat structure?
  Implement negation? NOT operator at group level?)
