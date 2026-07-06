import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Standalone Java benchmark comparing local Ollama models on the natural-language -&gt; CustomerFilter
 * task. Replicates the {@code CustomerSearchIT} test cases (including the nested AND/OR/NOT tree
 * cases) as raw Ollama HTTP calls (no Maven/JUnit, no Spring context) and reports accuracy,
 * latency/throughput, and basic resource usage.
 *
 * <p>Run directly with Java's single-file source launcher (no external dependencies, JDK stdlib only):
 * <pre>
 *   cd 04-local-ai-filter/src/test/scripts
 *   java BenchmarkLocalModels.java [model1] [model2] ...
 * </pre>
 * Without model arguments, candidates are auto-discovered from {@code GET /api/tags} (models whose
 * capabilities include "tools"). Ollama base URL via {@code OLLAMA_BASE_URL} (default
 * {@code http://localhost:11434}). Reports ({@code benchmark-report-<timestamp>.md/.txt}) are written to
 * the current working directory. Not a CI gate — for model comparison during demos/development.
 * Replaces the previous {@code benchmark_models.py}.
 */
public class BenchmarkLocalModels {

    record ExpectedCriterion(String field, String[] operators, String[] valueSubstrings) {
        static ExpectedCriterion of(String field, String operator, String value) {
            return new ExpectedCriterion(field, operator == null ? new String[0] : new String[]{operator},
                    new String[]{value});
        }

        static ExpectedCriterion of(String field, String[] operators, String... values) {
            return new ExpectedCriterion(field, operators, values);
        }
    }

    record TestCase(String name, String query, List<ExpectedCriterion> expected) {
        static TestCase of(String name, String query, ExpectedCriterion... expected) {
            return new TestCase(name, query, List.of(expected));
        }
    }

    record CaseResult(String name, String query, boolean passed, long durationMs, long evalCount,
                       long evalDurationNs, String error, String gotJson) {
    }

    record ModelResult(String model, List<CaseResult> cases, Long modelSizeBytes, Long vramBytes,
                        double avgCpuLoadPercent, long heapUsedBeforeBytes, long heapUsedAfterBytes,
                        String gpuInfo, Long ttftMs, String fatalError) {
    }

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        String baseUrl = baseUrl();
        LocalDate today = LocalDate.now();
        String systemPrompt = buildSystemPrompt(today);
        List<TestCase> cases = testCases(today);

        List<String> models = args.length > 0 ? List.of(args) : discoverModels(baseUrl);
        if (models.isEmpty()) {
            System.err.println("No candidate models found/given. Pass model names explicitly, e.g.:");
            System.err.println("  java BenchmarkLocalModels.java llama3.2:1b qwen3.5:4b-mlx");
            System.exit(1);
        }

        System.out.println("Ollama base URL: " + baseUrl);
        System.out.println("Models: " + String.join(", ", models));
        System.out.println("Test cases: " + cases.size());
        System.out.println();

        List<ModelResult> results = new ArrayList<>();
        for (String model : models) {
            System.out.println("=== " + model + " ===");
            ModelResult result = benchmarkModel(baseUrl, model, systemPrompt, cases);
            results.add(result);
            printSummaryLine(result, cases.size());
        }

        printTable(results, cases.size());
        printFailures(results);

        String timestamp = today.format(DateTimeFormatter.ISO_LOCAL_DATE) + "-"
                + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        Path mdPath = Path.of("benchmark-report-" + timestamp + ".md");
        Path txtPath = Path.of("benchmark-report-" + timestamp + ".txt");
        Files.writeString(mdPath, renderMarkdown(results, cases.size()), StandardCharsets.UTF_8);
        Files.writeString(txtPath, renderText(results, cases.size()), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Reports written: " + mdPath.toAbsolutePath() + ", " + txtPath.toAbsolutePath());
    }

    private static String baseUrl() {
        String env = System.getenv("OLLAMA_BASE_URL");
        return (env == null || env.isBlank()) ? DEFAULT_BASE_URL : env;
    }

    // ---------------------------------------------------------------------------------------------
    // System prompt: extracted from the real CustomerSearchService.java so the benchmark cannot drift
    // from production behaviour, mirroring benchmark_models.py's approach.
    // ---------------------------------------------------------------------------------------------

    private static String buildSystemPrompt(LocalDate today) throws IOException {
        Path source = locateCustomerSearchService();
        String src = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("return\\s*\"\"\"(.*?)\"\"\"\\s*\\.formatted", Pattern.DOTALL).matcher(src);
        if (!m.find()) {
            throw new IllegalStateException("Could not extract system prompt template from " + source);
        }
        String template = m.group(1);

        LocalDate yesterday = today.minusDays(1);
        LocalDate thisWeekMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate lastWeekMonday = thisWeekMonday.minusWeeks(1);
        LocalDate lastMonthStart = today.withDayOfMonth(1).minusMonths(1);
        // Same argument order as CustomerSearchService.systemPrompt(...).formatted(...).
        String prompt = template.formatted(today, today, yesterday, today, today, today, thisWeekMonday,
                lastWeekMonday, today, lastMonthStart);

        return prompt + "\n\nRespond ONLY with a JSON object of this exact shape, nothing else:\n"
                + "{\"root\": {\"type\":\"AND\"|\"OR\"|\"NOT\"|\"CONDITION\", "
                + "\"children\":[...] (AND/OR only), \"child\":{...} (NOT only), "
                + "\"field\":\"...\",\"operator\":\"...\",\"value\":\"...\" (CONDITION only)}}";
    }

    private static Path locateCustomerSearchService() {
        String relative = "dev/demo/vaadin/aigridfilter/ai/CustomerSearchService.java";
        List<Path> candidates = List.of(
                Path.of("../../main/java/" + relative),                                  // run from scripts/
                Path.of("04-local-ai-filter/src/main/java/" + relative),                  // run from repo root
                Path.of("src/main/java/" + relative)                                      // run from module root
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate CustomerSearchService.java from cwd="
                + Path.of("").toAbsolutePath() + "; tried " + candidates);
    }

    // ---------------------------------------------------------------------------------------------
    // Test cases: 1:1 port of CustomerSearchIT.java (24 cases, incl. the 4 German ones).
    // ---------------------------------------------------------------------------------------------

    private static List<TestCase> testCases(LocalDate today) {
        List<TestCase> cases = new ArrayList<>();
        cases.add(TestCase.of("singleCity", "show me all customers in Berlin",
                ExpectedCriterion.of("city", "CONTAINS", "berlin")));
        cases.add(TestCase.of("singleFalseCity", "show me all customers except from Berlin",
                ExpectedCriterion.of("city", "NOT_EQUALS", "berlin")));
        cases.add(TestCase.of("multipleCities", "customers in Berlin or Hamburg",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("city", "CONTAINS", "hamburg")));
        cases.add(TestCase.of("citiesAndRevenue_keepsEveryCondition",
                "show me all customers in Berlin or Hamburg with a minimal revenue of 100000",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("city", "CONTAINS", "hamburg"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "100000")));
        cases.add(TestCase.of("contactNameStartsWith",
                "show me all customers with an \"m\" as the first character in the contact name",
                ExpectedCriterion.of("contactName", "STARTS_WITH", "m")));
        cases.add(TestCase.of("contactNameContains",
                "show me all customers with \"meyer\" in the contact name",
                ExpectedCriterion.of("contactName", "CONTAINS", "meyer")));
        cases.add(TestCase.of("contactNameEndsWith",
                "show me all customers their contact name ends with \"schmidt\"",
                ExpectedCriterion.of("contactName", "ENDS_WITH", "schmidt")));
        cases.add(TestCase.of("contactNameAndCity",
                "customers whose contact name is Sofia and who are from Berlin",
                ExpectedCriterion.of("contactName", "EQUALS", "sofia"),
                ExpectedCriterion.of("city", "CONTAINS", "berlin")));
        cases.add(TestCase.of("phoneNumberContains",
                "show me the customer with the phone number 5020000001 or similar",
                ExpectedCriterion.of("phone", "CONTAINS", "5020000001")));
        cases.add(TestCase.of("orderedInTheLastWeek",
                "Show me all customers who placed an order in the last week",
                ExpectedCriterion.of("lastOrderDate", "GREATER_OR_EQUAL", "")));
        cases.add(TestCase.of("orderedYesterday",
                "show me all customers who made an order yesterday",
                ExpectedCriterion.of("lastOrderDate", "EQUALS", today.minusDays(1).toString())));
        cases.add(TestCase.of("customerSinceThisYear",
                "customers who became customers this year",
                ExpectedCriterion.of("customerSince", "GREATER_OR_EQUAL", "")));
        cases.add(TestCase.of("customerSinceYear",
                "customers since 2020",
                ExpectedCriterion.of("customerSince", "GREATER_OR_EQUAL", "2020")));
        cases.add(TestCase.of("lastOrderBeforeDate",
                "customers whose last order was before 2024-01-01",
                ExpectedCriterion.of("lastOrderDate", new String[]{"LESS_OR_EQUAL"}, "2024-01-01", "2023-12-31")));
        cases.add(TestCase.of("revenueOverAMillion",
                "companies with annual revenue over 1 million",
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "1000000")));
        cases.add(TestCase.of("notInCityWithRevenueRange_keepsEveryCondition",
                "companies not in Munich with revenue between 100000 and 500000",
                ExpectedCriterion.of("city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "munich"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "100000"),
                ExpectedCriterion.of("annualRevenue", "LESS_OR_EQUAL", "500000")));
        cases.add(TestCase.of("country",
                "customers in Germany",
                ExpectedCriterion.of("country", new String[]{"CONTAINS", "EQUALS"}, "germany")));
        cases.add(TestCase.of("emailEndsWith",
                "customers whose email ends with .com",
                ExpectedCriterion.of("email", "ENDS_WITH", ".com")));
        cases.add(TestCase.of("emailNotContains",
                "customers whose email does not contain gmail",
                ExpectedCriterion.of("email", new String[]{"NOT_CONTAINS", "NOT_EQUALS"}, "gmail")));
        cases.add(TestCase.of("companyNameStartsWith",
                "customers whose company name starts with A",
                ExpectedCriterion.of("companyName", "STARTS_WITH", "a")));
        cases.add(TestCase.of("creditworthyInCity",
                "creditworthy customers in Berlin",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));
        cases.add(TestCase.of("creditRatingTwoValues_staySeparateCriteria",
                "show me all customers in Berlin with a good and an at-risk credit rating",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy"),
                ExpectedCriterion.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "poor", "risk")));
        cases.add(TestCase.of("showAllCustomers_noCriteria", "show all customers"));
        cases.add(TestCase.of("resetTheFilter_German", "setze den Filter zurück"));
        cases.add(TestCase.of("citiesAndCreditRating_German",
                "zeige mir Kunden aus Berlin oder Hamburg mit einer positiven Kreditwürdigkeit",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("city", "CONTAINS", "hamburg"),
                ExpectedCriterion.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));
        cases.add(TestCase.of("notInCityWithRevenueRange_keepsEveryCondition_German",
                "Alle Kunden ausser aus Hamburg mit einem Umsatz von 500000 bis 1000000",
                ExpectedCriterion.of("city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "hamburg"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "500000"),
                ExpectedCriterion.of("annualRevenue", "LESS_OR_EQUAL", "1000000")));
        cases.add(TestCase.of("contactNameAndCity_German",
                "Zeigen mir Kunden deren Kontaktname Julia ist und die in Berlin sind.",
                ExpectedCriterion.of("contactName", new String[]{"EQUALS", "CONTAINS"}, "julia"),
                ExpectedCriterion.of("city", "CONTAINS", "berlin")));

        // --- medium-model-query: nested combinations (an AND of two ORs) ---
        cases.add(TestCase.of("citiesWithRevenueRange_nestedCombination",
                "Berlin or Hamburg with revenue between 100000 and 500000",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("city", "CONTAINS", "hamburg"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "100000"),
                ExpectedCriterion.of("annualRevenue", "LESS_OR_EQUAL", "500000")));
        cases.add(TestCase.of("nestedOrOfOrs",
                "customers in Berlin or Hamburg who either have a revenue of at least 500000 or a good credit rating",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("city", "CONTAINS", "hamburg"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "500000"),
                ExpectedCriterion.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));

        // --- large-model-query: cross-field OR, only possible with the nested filter tree ---
        cases.add(TestCase.of("crossFieldOr_cityOrRevenue",
                "customers in Berlin or with revenue above 1 million",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "1000000")));
        cases.add(TestCase.of("crossFieldOr_cityOrCreditRating",
                "Hamburg or good credit rating",
                ExpectedCriterion.of("city", "CONTAINS", "hamburg"),
                ExpectedCriterion.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));
        cases.add(TestCase.of("negatedGroup",
                "show me customers that are not both from Berlin and have a revenue under 100000",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("annualRevenue", "LESS_OR_EQUAL", "100000")));
        return cases;
    }

    // ---------------------------------------------------------------------------------------------
    // Model discovery
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> discoverModels(String baseUrl) throws IOException, InterruptedException {
        Object tags = get(baseUrl + "/api/tags");
        if (!(tags instanceof Map<?, ?> map) || !(map.get("models") instanceof List<?> models)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object o : models) {
            if (!(o instanceof Map<?, ?> model)) continue;
            Object caps = model.get("capabilities");
            String name = String.valueOf(model.get("name"));
            if (caps instanceof List<?> capList && capList.stream().anyMatch(c -> "tools".equals(c))) {
                result.add(name);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Benchmarking a single model
    // ---------------------------------------------------------------------------------------------

    private static ModelResult benchmarkModel(String baseUrl, String model, String systemPrompt,
            List<TestCase> cases) throws Exception {
        try {
            chat(baseUrl, model, systemPrompt, "warm up"); // load model, excluded from timings
        } catch (Exception e) {
            return new ModelResult(model, List.of(), null, null, 0, 0, 0, "n/a", null, e.getMessage());
        }

        long heapBefore = usedHeapBytes();
        AtomicBoolean sampling = new AtomicBoolean(true);
        List<Double> cpuSamples = new ArrayList<>();
        Thread sampler = startCpuSampler(sampling, cpuSamples);

        List<CaseResult> caseResults = new ArrayList<>();
        Long ttft = null;
        for (TestCase tc : cases) {
            long t0 = System.nanoTime();
            try {
                if (ttft == null) {
                    ttft = timeToFirstTokenMs(baseUrl, model, systemPrompt, tc.query());
                }
                Map<String, Object> resp = chat(baseUrl, model, systemPrompt, tc.query());
                long durationMs = (System.nanoTime() - t0) / 1_000_000;
                String content = extractContent(resp);
                List<Map<String, Object>> criteria = parseCriteria(content);
                boolean passed = caseCorrect(criteria, tc.expected());
                long evalCount = asLong(resp.get("eval_count"));
                long evalDuration = asLong(resp.get("eval_duration"));
                caseResults.add(new CaseResult(tc.name(), tc.query(), passed, durationMs, evalCount,
                        evalDuration, null, content));
            } catch (Exception e) {
                long durationMs = (System.nanoTime() - t0) / 1_000_000;
                caseResults.add(new CaseResult(tc.name(), tc.query(), false, durationMs, 0, 0,
                        e.getClass().getSimpleName() + ": " + e.getMessage(), null));
            }
        }

        sampling.set(false);
        sampler.join(2000);
        long heapAfter = usedHeapBytes();
        double avgCpu = cpuSamples.isEmpty() ? 0
                : cpuSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100;

        Long modelSize = modelSizeBytes(baseUrl, model);
        Long vram = modelVramBytes(baseUrl, model);
        String gpuInfo = gpuInfo();

        return new ModelResult(model, caseResults, modelSize, vram, avgCpu, heapBefore, heapAfter, gpuInfo,
                ttft, null);
    }

    private static Thread startCpuSampler(AtomicBoolean running, List<Double> samples) {
        var osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        Thread t = new Thread(() -> {
            while (running.get()) {
                double load = osBean.getCpuLoad();
                if (load >= 0) {
                    synchronized (samples) {
                        samples.add(load);
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "cpu-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ---------------------------------------------------------------------------------------------
    // Ollama HTTP calls
    // ---------------------------------------------------------------------------------------------

    private static Map<String, Object> chat(String baseUrl, String model, String systemPrompt, String query)
            throws IOException, InterruptedException {
        String payload = """
                {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                "think":false,"stream":false,"format":"json",
                "options":{"temperature":0,"num_ctx":4096,"num_predict":512}}
                """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(query));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.stripTrailing().replaceAll("/$", "")
                        + "/api/chat"))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        Object parsed = Json.parse(response.body());
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("Unexpected /api/chat response shape: " + response.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private static Long timeToFirstTokenMs(String baseUrl, String model, String systemPrompt, String query) {
        String payload = """
                {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                "think":false,"stream":true,"format":"json",
                "options":{"temperature":0,"num_ctx":4096,"num_predict":512}}
                """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(query));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$", "") + "/api/chat"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            long t0 = System.nanoTime();
            HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Object chunk = Json.parse(line);
                    if (chunk instanceof Map<?, ?> m && m.get("message") instanceof Map<?, ?> msg
                            && msg.get("content") instanceof String c && !c.isEmpty()) {
                        return (System.nanoTime() - t0) / 1_000_000;
                    }
                }
            }
        } catch (Exception ignored) {
            // TTFT is a best-effort metric; a failure here doesn't fail the case.
        }
        return null;
    }

    private static Long modelSizeBytes(String baseUrl, String model) {
        try {
            Object tags = get(baseUrl + "/api/tags");
            if (tags instanceof Map<?, ?> map && map.get("models") instanceof List<?> models) {
                for (Object o : models) {
                    if (o instanceof Map<?, ?> m && model.equals(m.get("name"))) {
                        return asLong(m.get("size"));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Long modelVramBytes(String baseUrl, String model) {
        try {
            Object ps = get(baseUrl + "/api/ps");
            if (ps instanceof Map<?, ?> map && map.get("models") instanceof List<?> models) {
                for (Object o : models) {
                    if (o instanceof Map<?, ?> m && model.equals(m.get("name"))) {
                        return asLong(m.get("size_vram"));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String gpuInfo() {
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=utilization.gpu,memory.used", "--format=csv,noheader").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0 && !out.isBlank()) {
                return out;
            }
        } catch (Exception ignored) {
        }
        return "n/a";
    }

    private static Object get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return Json.parse(response.body());
    }

    // ---------------------------------------------------------------------------------------------
    // Response parsing & tolerant matching (port of CustomerSearchIT.hasCondition/flatten)
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> chatResponse) {
        Object message = chatResponse.get("message");
        if (message instanceof Map<?, ?> m && m.get("content") instanceof String s) {
            return s;
        }
        return "";
    }

    /**
     * Best-effort: returns every CONDITION leaf anywhere in the model's {@code root} filter tree, [] if
     * the shape is off. Also accepts a bare {@code {"criteria": [...]}} flat list for models that ignore
     * the nested schema, so a model's degree of non-compliance shows up as lower accuracy rather than
     * being silently masked.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseCriteria(String content) {
        Object data;
        try {
            data = Json.parse(content);
        } catch (Exception e) {
            Matcher m = Pattern.compile("\\{.*}|\\[.*]", Pattern.DOTALL).matcher(content);
            if (!m.find()) return List.of();
            try {
                data = Json.parse(m.group());
            } catch (Exception e2) {
                return List.of();
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (data instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    flatten((Map<String, Object>) m, result);
                }
            }
        } else if (data instanceof Map<?, ?> map) {
            if (map.get("criteria") instanceof List<?> list) {          // legacy flat shape
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        result.add((Map<String, Object>) m);
                    }
                }
            } else if (map.get("root") instanceof Map<?, ?> root) {
                flatten((Map<String, Object>) root, result);
            }
        }
        return result;
    }

    /** Collects every CONDITION leaf anywhere in a FilterNode tree, mirroring CustomerSearchIT.flatten. */
    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> node, List<Map<String, Object>> into) {
        if (node == null) {
            return;
        }
        String type = String.valueOf(node.getOrDefault("type", "")).toUpperCase();
        switch (type) {
            case "CONDITION" -> into.add(node);
            case "AND", "OR" -> {
                if (node.get("children") instanceof List<?> children) {
                    for (Object child : children) {
                        if (child instanceof Map<?, ?> m) {
                            flatten((Map<String, Object>) m, into);
                        }
                    }
                }
            }
            case "NOT" -> {
                if (node.get("child") instanceof Map<?, ?> child) {
                    flatten((Map<String, Object>) child, into);
                }
            }
            default -> {
                if (node.containsKey("field")) { // model returned a bare condition without "type"
                    into.add(node);
                }
            }
        }
    }

    private static boolean caseCorrect(List<Map<String, Object>> criteria, List<ExpectedCriterion> expected) {
        if (expected.isEmpty()) {
            return criteria.isEmpty();
        }
        return expected.stream().allMatch(e -> matches(criteria, e));
    }

    private static boolean matches(List<Map<String, Object>> criteria, ExpectedCriterion expected) {
        for (Map<String, Object> c : criteria) {
            String field = String.valueOf(c.get("field"));
            String operator = String.valueOf(c.get("operator"));
            String value = String.valueOf(c.get("value"));
            if (!field.equalsIgnoreCase(expected.field())) continue;
            if (expected.operators().length > 0) {
                boolean opOk = false;
                for (String op : expected.operators()) {
                    if (op.equalsIgnoreCase(operator)) {
                        opOk = true;
                        break;
                    }
                }
                if (!opOk) continue;
            }
            boolean valueOk = false;
            for (String v : expected.valueSubstrings()) {
                if (value.toLowerCase().contains(v.toLowerCase())) {
                    valueOk = true;
                    break;
                }
            }
            if (valueOk) return true;
        }
        return false;
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0;
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------------

    private static void printSummaryLine(ModelResult r, int totalCases) {
        if (r.fatalError() != null) {
            System.out.println("  ERROR: " + r.fatalError());
            return;
        }
        long passed = r.cases().stream().filter(CaseResult::passed).count();
        System.out.printf("  %d/%d passed, %s%n", passed, totalCases, accuracySuffix(r));
    }

    private static String accuracySuffix(ModelResult r) {
        double medianMs = median(r.cases().stream().map(CaseResult::durationMs).collect(Collectors.toList()));
        double tokS = tokensPerSecond(r);
        return "median %.0f ms, %.1f tok/s".formatted(medianMs, tokS);
    }

    private static double tokensPerSecond(ModelResult r) {
        List<Double> rates = new ArrayList<>();
        for (CaseResult c : r.cases()) {
            if (c.evalDurationNs() > 0) {
                rates.add(c.evalCount() / (c.evalDurationNs() / 1e9));
            }
        }
        return median(rates.stream().mapToDouble(Double::doubleValue).boxed().collect(Collectors.toList()));
    }

    private static double median(List<? extends Number> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = values.stream().map(Number::doubleValue).sorted().collect(Collectors.toList());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static void printTable(List<ModelResult> results, int totalCases) {
        System.out.println();
        System.out.printf("%-22s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                "Model", "Accuracy", "Median Lat.", "TTFT", "tok/s", "RAM", "CPU", "Model Size");
        System.out.println("-".repeat(98));
        for (ModelResult r : results) {
            if (r.fatalError() != null) {
                System.out.printf("%-22sERROR: %s%n", r.model(), r.fatalError());
                continue;
            }
            long passed = r.cases().stream().filter(CaseResult::passed).count();
            double medianMs = median(r.cases().stream().map(CaseResult::durationMs).collect(Collectors.toList()));
            System.out.printf("%-22s%-12s%-14s%-10s%-10.1f%-10s%-8s%-12s%n",
                    r.model(),
                    passed + "/" + totalCases,
                    "%.0f ms".formatted(medianMs),
                    r.ttftMs() != null ? r.ttftMs() + " ms" : "n/a",
                    tokensPerSecond(r),
                    formatBytes(r.heapUsedAfterBytes() - r.heapUsedBeforeBytes()),
                    "%.0f%%".formatted(r.avgCpuLoadPercent()),
                    r.modelSizeBytes() != null ? formatBytes(r.modelSizeBytes()) : "n/a");
        }
    }

    private static void printFailures(List<ModelResult> results) {
        for (ModelResult r : results) {
            List<CaseResult> failures = r.cases().stream().filter(c -> !c.passed()).collect(Collectors.toList());
            if (failures.isEmpty()) continue;
            System.out.println();
            System.out.println("Failed cases for " + r.model() + ":");
            for (CaseResult f : failures) {
                System.out.println("  FAIL  " + f.name() + " — " + f.query());
                if (f.error() != null) {
                    System.out.println("        error: " + f.error());
                } else {
                    System.out.println("        got: " + f.gotJson());
                }
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return "%.0f KB".formatted(kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return "%.0f MB".formatted(mb);
        return "%.1f GB".formatted(mb / 1024.0);
    }

    private static String renderMarkdown(List<ModelResult> results, int totalCases) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Local Ollama Model Benchmark\n\n");
        sb.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n\n");
        sb.append("Not a CI gate — for model comparison during demos/development. ")
          .append(totalCases).append(" test cases per model, ported from `CustomerSearchIT`.\n\n");
        sb.append("| Model | Accuracy | Median Latency | TTFT | Tokens/s | RAM (JVM) | CPU | Model Size |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (ModelResult r : results) {
            if (r.fatalError() != null) {
                sb.append("| ").append(r.model()).append(" | ERROR: ").append(r.fatalError())
                  .append(" | | | | | | |\n");
                continue;
            }
            long passed = r.cases().stream().filter(CaseResult::passed).count();
            double medianMs = median(r.cases().stream().map(CaseResult::durationMs).collect(Collectors.toList()));
            sb.append("| ").append(r.model())
              .append(" | ").append(passed).append("/").append(totalCases)
              .append(" | ").append("%.0f ms".formatted(medianMs))
              .append(" | ").append(r.ttftMs() != null ? r.ttftMs() + " ms" : "n/a")
              .append(" | ").append("%.1f".formatted(tokensPerSecond(r)))
              .append(" | ").append(formatBytes(r.heapUsedAfterBytes() - r.heapUsedBeforeBytes()))
              .append(" | ").append("%.0f%%".formatted(r.avgCpuLoadPercent()))
              .append(" | ").append(r.modelSizeBytes() != null ? formatBytes(r.modelSizeBytes()) : "n/a")
              .append(" |\n");
        }
        sb.append("\nGPU: ").append(results.isEmpty() ? "n/a" : results.get(0).gpuInfo())
          .append(" (nvidia-smi; \"n/a\" on hosts without an NVIDIA GPU, e.g. this Ollama host)\n");

        for (ModelResult r : results) {
            List<CaseResult> failures = r.cases().stream().filter(c -> !c.passed()).collect(Collectors.toList());
            if (failures.isEmpty()) continue;
            sb.append("\n## Failed cases: ").append(r.model()).append("\n\n");
            for (CaseResult f : failures) {
                sb.append("- **").append(f.name()).append("** — ").append(f.query()).append("\n");
                if (f.error() != null) {
                    sb.append("  - error: `").append(f.error()).append("`\n");
                } else {
                    sb.append("  - got: `").append(f.gotJson()).append("`\n");
                }
            }
        }
        return sb.toString();
    }

    private static String renderText(List<ModelResult> results, int totalCases) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-22s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                "Model", "Accuracy", "Median Lat.", "TTFT", "tok/s", "RAM", "CPU", "Model Size"));
        sb.append("-".repeat(98)).append("\n");
        for (ModelResult r : results) {
            if (r.fatalError() != null) {
                sb.append(String.format("%-22sERROR: %s%n", r.model(), r.fatalError()));
                continue;
            }
            long passed = r.cases().stream().filter(CaseResult::passed).count();
            double medianMs = median(r.cases().stream().map(CaseResult::durationMs).collect(Collectors.toList()));
            sb.append(String.format("%-22s%-12s%-14s%-10s%-10.1f%-10s%-8s%-12s%n",
                    r.model(),
                    passed + "/" + totalCases,
                    "%.0f ms".formatted(medianMs),
                    r.ttftMs() != null ? r.ttftMs() + " ms" : "n/a",
                    tokensPerSecond(r),
                    formatBytes(r.heapUsedAfterBytes() - r.heapUsedBeforeBytes()),
                    "%.0f%%".formatted(r.avgCpuLoadPercent()),
                    r.modelSizeBytes() != null ? formatBytes(r.modelSizeBytes()) : "n/a"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Minimal hand-rolled JSON parser (no external dependencies allowed). Supports objects, arrays,
    // strings (with escapes), numbers, booleans, null — enough for Ollama's API responses.
    // ---------------------------------------------------------------------------------------------

    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json p = new Json(s);
            p.skipWs();
            Object result = p.parseValue();
            p.skipWs();
            return result;
        }

        private Object parseValue() {
            skipWs();
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (peek() != ':') throw new IllegalStateException("Expected ':' at " + pos);
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new IllegalStateException("Expected ',' or '}' at " + (pos - 1));
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new IllegalStateException("Expected ',' or ']' at " + (pos - 1));
            }
            return list;
        }

        private String parseString() {
            if (s.charAt(pos) != '"') throw new IllegalStateException("Expected '\"' at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || "+-.eE".indexOf(s.charAt(pos)) >= 0)) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, pos)) throw new IllegalStateException("Expected '" + literal + "' at " + pos);
            pos += literal.length();
        }

        private char peek() {
            return s.charAt(pos);
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
