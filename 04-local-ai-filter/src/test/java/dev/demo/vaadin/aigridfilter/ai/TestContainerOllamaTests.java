package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.Nested;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Infrastructure for running Ollama-backed integration test suites against an Ollama
 * <b>Testcontainer</b> (Docker), using a pre-built image with {@link CustomerSearchIT#MODEL} baked in —
 * build it once via {@code src/test/docker/build-images.sh}. Skipped automatically when Docker is
 * unavailable.
 * <p>
 * Uses {@link GenericContainer} because the Testcontainers {@code ollama} module is only published for
 * 1.x and is incompatible with the resolved 2.x core, so {@code @ServiceConnection} cannot be used; the
 * base-url is wired via {@link DynamicPropertySource}. Vaadin's autoconfig is excluded because
 * {@code webEnvironment=NONE} has no {@code WebApplicationContext}.
 * <p>
 * The container and Spring context are started once for this class and shared by all {@code @Nested}
 * suites. Add a new use case by adding a {@code @Nested} class here that extends its shared {@code *IT}
 * suite, e.g. {@code @Nested class ProductValidation extends ProductValidationIT {}}.
 * <p>
 * Run with {@code -Pit-testcontainer}, or a single suite with
 * {@code -Dit.test=TestContainerOllamaTests\$CustomerSearch}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.model.chat=ollama",
        "spring.ai.ollama.chat.model=" + CustomerSearchIT.MODEL,
        "spring.ai.ollama.chat.think=false",
        "spring.ai.ollama.chat.num-ctx=4096",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class TestContainerOllamaTests {

    /** Pre-built local image with {@link CustomerSearchIT#MODEL} baked in (tag: model name with ':' -> '-'). */
    static final String IMAGE = "ai-grid-filter/ollama:" + CustomerSearchIT.MODEL.replace(":", "-");

    @Container
    static final GenericContainer<?> OLLAMA =
            new GenericContainer<>(DockerImageName.parse(IMAGE))
                    .withExposedPorts(11434)
                    .withReuse(true);

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url",
                () -> "http://" + OLLAMA.getHost() + ":" + OLLAMA.getMappedPort(11434));
    }

    @Nested
    class CustomerSearch extends CustomerSearchIT {
    }
}
