package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Infrastructure for running AI-backed integration test suites against whichever of the app's
 * {@code ollama} (default)/{@code mlx}/{@code cloud} Spring profiles {@code AI_TEST_PROFILE}
 * selects — see {@link OllamaTestSupport}. The profile is resolved dynamically via
 * {@link ActiveProfilesResolver} rather than a fixed {@code @ActiveProfiles} value, since a plain
 * {@code @SpringBootTest(properties = "spring.profiles.active=...")} requires a compile-time
 * constant and a {@code @DynamicPropertySource} value is added too late in context startup to
 * influence profile resolution. Skips gracefully when that backend isn't reachable, so a plain
 * {@code ./mvnw verify} does not fail when it is not running.
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
@ActiveProfiles(resolver = LocalOllamaTests.AiTestProfileResolver.class)
class LocalOllamaTests {

    @BeforeAll
    static void requireReachableBackend() {
        assumeTrue(OllamaTestSupport.reachable(), "AI backend (profile '" + OllamaTestSupport.profile()
                + "') not reachable at " + OllamaTestSupport.baseUrl() + " — skipping");
    }

    static class AiTestProfileResolver implements ActiveProfilesResolver {

        @Override
        public String[] resolve(Class<?> testClass) {
            return new String[] { OllamaTestSupport.profile() };
        }

    }
}
