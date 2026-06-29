package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the shared {@link CustomerSearchIT} tests against an Ollama <b>Testcontainer</b> (Docker),
 * using a pre-built image with {@link #MODEL} baked in — build it once via
 * {@code src/test/docker/build-images.sh}. Skipped automatically when Docker is unavailable.
 * <p>
 * Uses {@link GenericContainer} because the Testcontainers {@code ollama} module is only published for
 * 1.x and is incompatible with the resolved 2.x core, so {@code @ServiceConnection} cannot be used; the
 * base-url is wired via {@link DynamicPropertySource}. Vaadin's autoconfig is excluded because
 * {@code webEnvironment=NONE} has no {@code WebApplicationContext}.
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
class CustomerSearchServiceTestContainerIT extends CustomerSearchIT {

    /** Pre-built local image with {@link #MODEL} baked in (tag: model name with ':' -> '-'). */
    static final String IMAGE = "ai-grid-filter/ollama:" + MODEL.replace(":", "-");

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
}
