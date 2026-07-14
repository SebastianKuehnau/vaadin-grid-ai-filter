package dev.demo.vaadin.aigridfilter.ai;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Shared setup for the AI-backend-dependent integration test infrastructure classes, currently
 * {@link LocalOllamaTests} and {@code ui.CustomerListViewBrowserlessIT} (public since it's used
 * from that other package too).
 * <p>
 * Which of the app's {@code ollama}/{@code mlx}/{@code cloud} Spring profiles these tests target
 * is driven by the {@code AI_TEST_PROFILE} environment variable (default {@code ollama}) — the
 * exact same profile names used by {@code -Dspring-boot.run.profiles=...} when running the app
 * itself. All three speak the OpenAI-compatible {@code /v1/models} endpoint, so a single
 * reachability check works across all of them.
 * <p>
 * Note: the {@code @SpringBootTest(properties = ...)} array itself cannot be extracted here since
 * annotation attributes require compile-time constant literals, not a reference to a field.
 */
public final class OllamaTestSupport {

    private OllamaTestSupport() {
    }

    public static String profile() {
        String env = System.getenv("AI_TEST_PROFILE");
        return (env == null || env.isBlank()) ? "ollama" : env;
    }

    public static String baseUrl() {
        return switch (profile()) {
            case "mlx" -> envOr("MLX_BASE_URL", "http://localhost:8090");
            case "cloud" -> "https://api.openai.com";
            default -> envOr("OLLAMA_BASE_URL", "http://localhost:11434");
        };
    }

    public static String apiKey() {
        if ("cloud".equals(profile())) {
            String key = System.getenv("OPENAI_API_KEY");
            return key == null ? "" : key;
        }
        return "not-needed";
    }

    public static boolean reachable() {
        try {
            HttpURLConnection con = (HttpURLConnection) URI.create(baseUrl() + "/v1/models").toURL().openConnection();
            con.setConnectTimeout(1500);
            con.setReadTimeout(1500);
            con.setRequestMethod("GET");
            con.setRequestProperty("Authorization", "Bearer " + apiKey());
            return con.getResponseCode() == 200;
        }
        catch (Exception e) {
            return false;
        }
    }

    private static String envOr(String name, String fallback) {
        String env = System.getenv(name);
        return (env == null || env.isBlank()) ? fallback : env;
    }
}
