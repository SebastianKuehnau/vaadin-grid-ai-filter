package dev.demo.vaadin.aigridfilter.benchmark.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ollama's admin endpoints: which models exist, pulling a missing one, and what is resident. */
public class OllamaAdmin {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;

    public OllamaAdmin(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // NO_PROXY: Ollama is a local service, and a configured HTTP proxy cannot reach it.
        this.http = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** A model resident in Ollama right now, with the size the benchmark reports as "model RAM". */
    public record LoadedModel(String name, long sizeBytes, long vramBytes) {
    }

    /** Ollama's version, or empty when nothing answers at the base URL. */
    public Optional<String> version() {
        try {
            return Optional.ofNullable(get("/api/version").path("version").asText(null));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The model names Ollama has locally, exactly as it reports them (e.g. {@code qwen3:8b}). */
    public List<String> models() {
        List<String> names = new ArrayList<>();
        get("/api/tags").path("models").forEach(model -> names.add(model.path("name").asText()));
        return names;
    }

    /** True when {@code model} is already local; {@code llama3.1} also matches {@code llama3.1:latest}. */
    public boolean hasModel(String model) {
        String wanted = model.contains(":") ? model : model + ":latest";
        return models().stream().anyMatch(name -> name.equals(model) || name.equals(wanted));
    }

    /** Downloads a model; blocks until Ollama closes the progress stream, which can take minutes. */
    public void pull(String model) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + model + "\"}"))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().contains("\"error\"")) {
                throw new IllegalStateException("Could not pull '" + model + "': " + response.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pulling '" + model + "'", e);
        }
    }

    /** What {@code /api/ps} reports for {@code model}, or empty when it is not resident. */
    public Optional<LoadedModel> loaded(String model) {
        for (JsonNode node : get("/api/ps").path("models")) {
            String name = node.path("name").asText();
            if (name.equals(model) || name.equals(model + ":latest")) {
                return Optional.of(new LoadedModel(name,
                        node.path("size").asLong(), node.path("size_vram").asLong()));
            }
        }
        return Optional.empty();
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Ollama answered " + response.statusCode() + " for " + path);
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reach Ollama at " + baseUrl + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + path, e);
        }
    }
}
