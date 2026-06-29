package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the shared {@link CustomerSearchIT} tests against a <b>native</b> Ollama (no Docker) at
 * {@code OLLAMA_BASE_URL} (default {@code http://localhost:11434}); {@link #MODEL} must be pulled there.
 * Skips gracefully when no Ollama is reachable, so a plain {@code ./mvnw test} does not fail when it is
 * not running. Run explicitly with {@code -Dtest=CustomerSearchServiceLocalOllamaIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.model.chat=ollama",
        "spring.ai.ollama.chat.model=" + CustomerSearchIT.MODEL,
        "spring.ai.ollama.chat.think=false",
        "spring.ai.ollama.chat.num-ctx=4096",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
class CustomerSearchServiceLocalOllamaIT extends CustomerSearchIT {

    static String baseUrl() {
        String env = System.getenv("OLLAMA_BASE_URL");
        return (env == null || env.isBlank()) ? "http://localhost:11434" : env;
    }

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", CustomerSearchServiceLocalOllamaIT::baseUrl);
    }

    @BeforeAll
    static void requireReachableOllama() {
        assumeTrue(reachable(baseUrl()), "native Ollama not reachable at " + baseUrl() + " — skipping");
    }

    private static boolean reachable(String baseUrl) {
        try {
            HttpURLConnection con = (HttpURLConnection) URI.create(baseUrl + "/api/tags").toURL().openConnection();
            con.setConnectTimeout(1500);
            con.setReadTimeout(1500);
            con.setRequestMethod("GET");
            return con.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
