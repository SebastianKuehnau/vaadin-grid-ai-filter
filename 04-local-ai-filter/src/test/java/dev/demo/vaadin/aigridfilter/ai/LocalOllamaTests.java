package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Infrastructure for running Ollama-backed integration test suites against a <b>native</b> Ollama
 * (no Docker) at {@code OLLAMA_BASE_URL} (default {@code http://localhost:11434}); the required models
 * must be pulled there. Skips gracefully when no Ollama is reachable, so a plain {@code ./mvnw verify}
 * does not fail when it is not running.
 * <p>
 * The Spring context is started once for this class and shared by all {@code @Nested} suites. Add a new
 * use case by adding a {@code @Nested} class here that extends its shared {@code *IT} suite, e.g.
 * {@code @Nested class ProductValidation extends ProductValidationIT {}}.
 * <p>
 * Run with {@code -Pit-local-ollama}, or a single suite with
 * {@code -Dit.test=LocalOllamaTests\$CustomerSearch}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.model.chat=ollama",
        "spring.ai.ollama.chat.model=" + CustomerSearchIT.MODEL,
        "spring.ai.ollama.chat.think=false",
        "spring.ai.ollama.chat.num-ctx=4096",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
class LocalOllamaTests {

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", OllamaTestSupport::localBaseUrl);
    }

    @BeforeAll
    static void requireReachableOllama() {
        String baseUrl = OllamaTestSupport.localBaseUrl();
        assumeTrue(OllamaTestSupport.reachable(baseUrl), "native Ollama not reachable at " + baseUrl + " — skipping");
    }

    @Nested
    class CustomerSearch extends CustomerSearchIT {
    }
}
