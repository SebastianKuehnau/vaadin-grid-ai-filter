# Task
Enable automatic execution of Ollama-based integration tests via Maven profiles, and refactor
the test structure to a scalable @Nested architecture that supports multiple use cases (e.g.,
CustomerSearch, ProductValidation) without creating 3×N test classes.

# Context
- Affected modules: `04-local-ai-filter` (all ITs are located there)
- Purpose: Simplified CI/CD integration and local development — developers/pipelines should be
  able to choose via Maven profile whether to test against a local Ollama instance
  (`http://host.docker.internal:11434`) or an Ollama Testcontainer.
- Current structure (problem):
  - `CustomerSearchIT`: abstract base class with shared test cases
  - `CustomerSearchServiceLocalOllamaIT`: extends CustomerSearchIT, tests against local Ollama
  - `CustomerSearchServiceTestContainerIT`: extends CustomerSearchIT, tests with Testcontainer
  - **Scaling problem**: Adding ProductValidation, OrderProcessing, etc. would require
    3 classes each → 3N classes total for N use cases
- Goal: Refactor to **N+2 classes** using `@Nested` tests:
  - 2 infrastructure classes (LocalOllamaTests, TestContainerOllamaTests)
  - N abstract test suites (CustomerSearchIT, ProductValidationIT, ...)
  - Infrastructure classes include test suites as `@Nested` inner classes

# Approach
1. First create a plan (Plan Mode) and show it to me. Wait for my OK.
2. Implement the plan. Work in logical steps, commit after each verified step
   (Conventional Commits, no push).
3. Verify autonomously according to Definition of Done in CLAUDE.md.
   Iterate on errors independently — don't present error messages for analysis
   that you can reproduce and fix yourself.

# Implementation (framework, details belong in your plan)

## 1. Refactor test structure to @Nested architecture

**New structure:**
```
ai/
├── CustomerSearchIT.java              (abstract, test cases only)
├── LocalOllamaTests.java              (infrastructure, reusable)
│   └── @Nested CustomerSearch         (extends CustomerSearchIT)
└── TestContainerOllamaTests.java      (infrastructure, reusable)
    └── @Nested CustomerSearch         (extends CustomerSearchIT)
```

**LocalOllamaTests.java** (new):
- Outer class with `@SpringBootTest` and all common properties
- `@DynamicPropertySource` for Ollama base-url configuration
- `@BeforeAll` to check Ollama availability (with `assumeTrue`)
- `@Nested` inner class `CustomerSearch extends CustomerSearchIT`
- Spring Context is started ONCE and shared by all @Nested classes

**TestContainerOllamaTests.java** (new):
- Outer class with `@SpringBootTest`, `@Testcontainers`
- `@Container static GenericContainer OLLAMA` (shared across @Nested classes)
- `@DynamicPropertySource` for Testcontainer base-url
- `@Nested` inner class `CustomerSearch extends CustomerSearchIT`

**CustomerSearchIT.java** (refactor):
- Remove abstract modifier if not already abstract
- Keep all test methods unchanged
- Keep `@Autowired` fields (inherited Context works!)
- Keep helper methods (hasCriterion, etc.)
- This class should NOT have `@SpringBootTest` (inherited from outer class)

**Delete (after refactoring):**
- `CustomerSearchServiceLocalOllamaIT.java`
- `CustomerSearchServiceTestContainerIT.java`

## 2. Configure Maven profiles in pom.xml

Add profiles to `04-local-ai-filter/pom.xml`:

**Profile `it-local-ollama`:**
- Uses `maven-failsafe-plugin` with `<includes>`
- Include pattern: `**/LocalOllamaTests.java`
- Runs only tests against local Ollama instance (requires Ollama running at base-url)

**Profile `it-testcontainer`:**
- Uses `maven-failsafe-plugin` with `<includes>`
- Include pattern: `**/TestContainerOllamaTests.java`
- Runs only tests with Testcontainer (Docker required)

**Default behavior** (no profile):
- `./mvnw verify` should NOT run the heavy Ollama ITs (performance)
- Exclude `**/LocalOllamaTests.java` and `**/TestContainerOllamaTests.java` by default

**Profile combination:**
- Both profiles can be activated together: `-Pit-local-ollama,it-testcontainer`

## 3. Verify Spring profile compatibility

Ensure the Maven profiles are compatible with existing Spring profiles (`ollama`):
- Tests should work with and without `-Dspring.profiles.active=ollama`
- Properties in `@SpringBootTest` should override defaults correctly

## 4. Document the new structure

Update `04-local-ai-filter/README.md`:
- Explain the @Nested architecture and scalability benefits
- Document Maven profile usage:
  ```bash
  # Run tests against local Ollama
  ./mvnw verify -pl 04-local-ai-filter -Pit-local-ollama

  # Run tests with Testcontainer
  ./mvnw verify -pl 04-local-ai-filter -Pit-testcontainer

  # Run both
  ./mvnw verify -pl 04-local-ai-filter -Pit-local-ollama,it-testcontainer

  # Run specific nested class only
  ./mvnw verify -pl 04-local-ai-filter -Dit.test=LocalOllamaTests\$CustomerSearch
  ```
- Explain how to add new use cases (e.g., ProductValidation):
  1. Create `ProductValidationIT.java` (abstract, test cases only)
  2. Add `@Nested class ProductValidation extends ProductValidationIT {}` to LocalOllamaTests
  3. Add `@Nested class ProductValidation extends ProductValidationIT {}` to TestContainerOllamaTests
  4. Done! (only +1 abstract class and +2 lines, not +2 full IT classes)

## 5. Extract common utilities (optional, if duplication exists)

If there's duplication in base-url resolution, reachability checks, etc.:
- Create `OllamaTestUtils` with static helper methods
- Use from both infrastructure classes

# Definition of Done (in addition to CLAUDE.md)

## Refactoring verification:
- `./mvnw verify -pl 04-local-ai-filter` runs green WITHOUT profiles and executes
  **neither** LocalOllamaTests **nor** TestContainerOllamaTests (only fast standard tests)
- All original test methods from `CustomerSearchIT` still exist and pass
- No test case logic was duplicated during refactoring

## Maven profile verification:
- `./mvnw verify -pl 04-local-ai-filter -Pit-local-ollama` runs exclusively
  `LocalOllamaTests` and is green (with running Ollama)
- `./mvnw verify -pl 04-local-ai-filter -Pit-testcontainer` runs exclusively
  `TestContainerOllamaTests` and is green (Testcontainer starts Ollama)
- Both profiles together `-Pit-local-ollama,it-testcontainer` run both test classes
- Individual nested classes can be run: `-Dit.test=LocalOllamaTests\$CustomerSearch`

## Structure verification:
- Old classes `CustomerSearchServiceLocalOllamaIT` and `CustomerSearchServiceTestContainerIT`
  are deleted
- New structure has exactly 3 classes (CustomerSearchIT, LocalOllamaTests, TestContainerOllamaTests)
- Spring Context starts only ONCE per infrastructure class (not per @Nested class)
- Testcontainer starts only ONCE and is shared by all @Nested classes

## Documentation verification:
- README documents the new architecture with scalability benefits (N+2 vs 3N)
- README contains example commands for all profile combinations
- README explains how to add new use cases (step-by-step)

# Final Report
Summarize: what was changed (per commit), which tests are green, example invocations
of the new profiles, open points/decisions (e.g., should profiles be activated by default
in CI pipelines, which use cases should be added next).
