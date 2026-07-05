# 04-local-ai-filter

AI filtering via structured output (`CustomerFilter` → JPA Specifications), running against local
Ollama models instead of a hosted provider (see `spring.ai.model.chat=ollama`).

## Ollama integration test architecture

The Ollama-backed integration tests use a `@Nested`-based, **N+2** class structure instead of one
set of classes per use case (which would scale as `3×N`):

```
ai/
├── CustomerSearchIT.java              (abstract-free, test cases only)
├── LocalOllamaTests.java              (infrastructure: native Ollama)
│   └── @Nested CustomerSearch         (extends CustomerSearchIT)
└── TestContainerOllamaTests.java      (infrastructure: Ollama Testcontainer)
    └── @Nested CustomerSearch         (extends CustomerSearchIT)
```

- `CustomerSearchIT` holds the actual test cases and assertions. It declares no
  `@SpringBootTest` of its own — it relies on the Spring context of whichever infrastructure
  class nests it.
- `LocalOllamaTests` and `TestContainerOllamaTests` are the two infrastructure classes. Each
  starts **one** Spring context (and, for the Testcontainer variant, **one** container) that is
  shared by all of its `@Nested` suites.
- Adding a second use case (e.g. `ProductValidation`) only needs one new abstract test-case class
  plus one `@Nested` line in each infrastructure class — not three new classes.

### Adding a new use case

1. Create `ProductValidationIT.java` (test cases only, same shape as `CustomerSearchIT`).
2. Add to `LocalOllamaTests`:
   ```java
   @Nested
   class ProductValidation extends ProductValidationIT {}
   ```
3. Add the same `@Nested` class to `TestContainerOllamaTests`.
4. Done — one new class, two new lines, not two new full IT classes.

## Running the Ollama integration tests

These tests are excluded from the default `./mvnw verify` (they need a real or containerized
Ollama and are comparatively slow). Run them explicitly via Maven profiles:

```bash
# Run tests against a native Ollama instance (requires OLLAMA_BASE_URL reachable, default
# http://localhost:11434)
./mvnw verify -pl 04-local-ai-filter -Pit-local-ollama

# Run tests with an Ollama Testcontainer (requires Docker; build the image once via
# src/test/docker/build-images.sh)
./mvnw verify -pl 04-local-ai-filter -Pit-testcontainer

# Run both
./mvnw verify -pl 04-local-ai-filter -Pit-local-ollama,it-testcontainer

# Run a single nested suite only
./mvnw verify -pl 04-local-ai-filter -Pit-local-ollama -Dit.test=LocalOllamaTests\$CustomerSearch
```

Both infrastructure classes skip gracefully (rather than failing) when their target isn't
available: `LocalOllamaTests` via an `assumeTrue` reachability check, `TestContainerOllamaTests`
via `@Testcontainers(disabledWithoutDocker = true)`.
