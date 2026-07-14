package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Infrastructure for running AI-backed integration test suites against whichever of the app's
 * {@code ollama} (default)/{@code mlx}/{@code cloud} Spring profiles {@code AI_TEST_PROFILE}
 * selects — see {@link OllamaTestSupport}. Which profile is active comes from
 * {@code src/test/resources/application.properties}'s
 * {@code spring.profiles.active=${AI_TEST_PROFILE:ollama}}, resolved the same way Spring Boot
 * resolves any other placeholder-defaulted property — no custom {@code @ActiveProfiles} resolver
 * needed. Skips gracefully when that backend isn't reachable, so a plain {@code ./mvnw verify}
 * does not fail when it is not running.
 * <p>
 * Add a new use case by subclassing this class, e.g. {@link CustomerSearchAgentIT}, and including that
 * subclass in the failsafe {@code <includes>} of the {@code it-local-ollama} profile.
 * <p>
 * Run with {@code -Pit-local-ollama}, or a single suite with {@code -Dit.test=CustomerSearchAgentIT}.
 * Point it at mlx or cloud instead of the default ollama with {@code -DAI_TEST_PROFILE=mlx} (also
 * respects {@code MLX_BASE_URL}/{@code OPENAI_API_KEY}, same as the app itself).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
class LocalOllamaTests {

    @BeforeAll
    static void requireReachableBackend() {
        assumeTrue(OllamaTestSupport.reachable(), "AI backend (profile '" + OllamaTestSupport.profile()
                + "') not reachable at " + OllamaTestSupport.baseUrl() + " — skipping");
    }
}
