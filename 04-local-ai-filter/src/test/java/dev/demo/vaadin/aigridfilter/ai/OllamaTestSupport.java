package dev.demo.vaadin.aigridfilter.ai;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Shared setup for the Ollama-backed integration test infrastructure classes
 * ({@link LocalOllamaTests} and {@link TestContainerOllamaTests}).
 * <p>
 * Note: the {@code @SpringBootTest(properties = ...)} array itself cannot be extracted here since
 * annotation attributes require compile-time constant literals, not a reference to a field.
 */
final class OllamaTestSupport {

    private OllamaTestSupport() {
    }

    static String localBaseUrl() {
        String env = System.getenv("OLLAMA_BASE_URL");
        return (env == null || env.isBlank()) ? "http://localhost:11434" : env;
    }

    static boolean reachable(String baseUrl) {
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
