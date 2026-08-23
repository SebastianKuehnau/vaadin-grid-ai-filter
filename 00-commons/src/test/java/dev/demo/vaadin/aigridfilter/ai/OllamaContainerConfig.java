package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

/** Runs the Ollama image with the demo model baked in; @ServiceConnection points Spring AI at it. */
@TestConfiguration(proxyBeanMethods = false)
public class OllamaContainerConfig {

    /** Stable tag, so Docker reuses the layer holding the model instead of pulling it again. */
    private static final String IMAGE_TAG = "ai-grid-filter/ollama:qwen3-8b";

    /** Skipped by OLLAMA_TESTCONTAINER=false, which leaves the ITs on spring.ai.ollama.base-url. */
    @Bean
    @ServiceConnection
    @Profile("ollama")
    @ConditionalOnBooleanProperty(name = "ollama.testcontainer", matchIfMissing = true)
    OllamaContainer ollama() {
        // Reused across every Spring context, so one resident model serves them all; see the module poms.
        var image = new ImageFromDockerfile(IMAGE_TAG, false)
                .withFileFromClasspath("Dockerfile", "ollama/Dockerfile");
        return new OllamaContainer(
                DockerImageName.parse(image.get()).asCompatibleSubstituteFor("ollama/ollama"))
                .withReuse(true);
    }
}
