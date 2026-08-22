package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the Ollama image with the demo model baked in; @ServiceConnection points Spring AI at it. */
@TestConfiguration(proxyBeanMethods = false)
public class OllamaContainerConfig {

    /** Stable tag, so Docker reuses the layer holding the model instead of pulling it again. */
    private static final String IMAGE_TAG = "ai-grid-filter/ollama:qwen3.5-4b";

    @Bean
    @ServiceConnection
    OllamaContainer ollama() {
        var image = new ImageFromDockerfile(IMAGE_TAG, false).withDockerfile(dockerfile());
        return new OllamaContainer(
                DockerImageName.parse(image.get()).asCompatibleSubstituteFor("ollama/ollama"));
    }

    /** Walks up from the module directory to the repository root, which holds .sbx/kit. */
    private static Path dockerfile() {
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var candidate = dir.resolve(".sbx/kit/Dockerfile");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Not found in any parent directory: .sbx/kit/Dockerfile");
    }
}
