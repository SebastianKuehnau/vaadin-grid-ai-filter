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
 * Standalone Java benchmark comparing local models — Ollama (default) or an OpenAI-compatible MLX
 * server ({@code mlx_lm.server}) — on the natural-language -&gt; CustomerFilter task. Replicates the
 * {@code CustomerSearchAgentIT}/{@code CustomerSearchAgentExtraIT} test cases (a flat list of
 * conditions, ALL combined with AND) as raw HTTP calls (no Maven/JUnit, no Spring context) and
 * reports accuracy, latency/throughput, and basic resource usage.
 *
 * <p>Run directly with Java's single-file source launcher (no external dependencies, JDK stdlib only):
 * <pre>
 *   cd 04-ollama-benchmark
 *   java BenchmarkLocalModels.java [options] [model1] [model2] ...
 * </pre>
 * Without model arguments, candidates are auto-discovered — from Ollama's {@code GET /api/tags}
 * (models whose capabilities include "tools") by default, or from {@code mlx_lm.server}'s
 * {@code GET /v1/models} with {@code --backend=mlx}. Base URL via {@code OLLAMA_BASE_URL}/
 * {@code MLX_BASE_URL} env vars or {@code --base-url=<url>}; run with {@code --help} for the full
 * flag list. Reports ({@code benchmark-report-<timestamp>.md/.txt}) are written to the current
 * working directory. Not a CI gate — for model comparison during demos/development. Benchmarks
 * {@code 03-ai-structured-filter}'s AI layer; replaces the previous {@code benchmark_models.py}.
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
                       long evalDurationNs, String error, String gotJson, String rawBody) {
    }

    record ModelResult(String model, List<CaseResult> cases, Long modelSizeBytes, Long vramBytes,
                        double avgCpuLoadPercent, long heapUsedBeforeBytes, long heapUsedAfterBytes,
                        String gpuInfo, Long ttftMs, String fatalError) {
    }

    /**
     * Normalized chat outcome shared by every {@link ApiClient} implementation. {@code tokenCount}/
     * {@code tokenDurationNs} carry Ollama's native {@code eval_count}/{@code eval_duration} (generation-only
     * timing) or, for OpenAI-compatible backends without an equivalent field, the response's token usage and
     * measured wall-clock request duration.
     */
    record ChatResult(String content, long tokenCount, long tokenDurationNs, String rawBody) {
    }

    /** Backend abstraction so the benchmarking/reporting code doesn't care which API it's talking to. */
    interface ApiClient {
        String backendName();

        List<String> discoverModels() throws IOException, InterruptedException;

        ChatResult chat(String model, String systemPrompt, String query) throws IOException, InterruptedException;

        /** Best-effort: null if the measurement fails or streaming isn't available. */
        Long timeToFirstTokenMs(String model, String systemPrompt, String query);

        /** Best-effort: null if unknown or not exposed by this backend. */
        Long modelSizeBytes(String model);

        /** Best-effort: null if unknown or not exposed by this backend. */
        Long modelVramBytes(String model);
    }

    private static final String OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String MLX_DEFAULT_BASE_URL = "http://localhost:8090";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record CliArgs(String backend, String baseUrlOverride, List<String> modelNames, boolean thinkDisabled,
                    boolean debugRaw, String mode) {
    }

    public static void main(String[] args) throws Exception {
        CliArgs cli = parseArgs(args);
        String baseUrl = resolveBaseUrl(cli);
        if (cli.thinkDisabled() && cli.backend().equals("ollama")) {
            System.err.println("WARN: --think has no effect on --backend=ollama "
                    + "(Ollama's native \"think\":false is unconditional already)");
        }
        boolean schemaMode = cli.mode().equals("schema");
        ApiClient client = cli.backend().equals("mlx")
                ? new MlxClient(baseUrl, cli.thinkDisabled(), schemaMode, cli.debugRaw())
                : new OllamaClient(baseUrl, schemaMode, cli.debugRaw());

        LocalDate today = LocalDate.now();
        String systemPrompt = buildSystemPrompt(today);
        List<TestCase> cases = testCases(today);

        List<String> models = resolveModels(client, cli.modelNames());
        if (models.isEmpty()) {
            System.err.println("No candidate models found/given. Pass model names explicitly, e.g.:");
            System.err.println("  java BenchmarkLocalModels.java llama3.2:1b qwen3.5:4b-mlx");
            System.err.println("  java BenchmarkLocalModels.java --backend=mlx");
            System.exit(1);
        }

        System.out.println(backendLabel(client.backendName()) + " base URL: " + baseUrl);
        System.out.println("Models: " + String.join(", ", models));
        System.out.println("Test cases: " + cases.size());
        System.out.println();

        List<ModelResult> results = new ArrayList<>();
        for (String model : models) {
            System.out.println("=== " + model + " ===");
            ModelResult result = benchmarkModel(client, model, systemPrompt, cases);
            results.add(result);
            printSummaryLine(result, cases.size());
        }

        printTable(results, cases.size());
        printFailures(results);

        String timestamp = today.format(DateTimeFormatter.ISO_LOCAL_DATE) + "-"
                + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        Path mdPath = Path.of("benchmark-report-" + timestamp + ".md");
        Path txtPath = Path.of("benchmark-report-" + timestamp + ".txt");
        Files.writeString(mdPath,
                renderMarkdown(results, cases.size(), client.backendName(), baseUrl, cli.thinkDisabled()),
                StandardCharsets.UTF_8);
        Files.writeString(txtPath, renderText(results, cases.size(), client.backendName(), baseUrl),
                StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Reports written: " + mdPath.toAbsolutePath() + ", " + txtPath.toAbsolutePath());
    }

    private static String backendLabel(String backendName) {
        return backendName.equals("mlx") ? "MLX server" : "Ollama";
    }

    // ---------------------------------------------------------------------------------------------
    // CLI argument parsing
    // ---------------------------------------------------------------------------------------------

    private static CliArgs parseArgs(String[] args) {
        String backend = "ollama";
        String baseUrlOverride = null;
        List<String> models = new ArrayList<>();
        boolean thinkDisabled = false;
        boolean debugRaw = false;
        String mode = "freeform";
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                System.exit(0);
            } else if (arg.startsWith("--backend=")) {
                backend = arg.substring("--backend=".length());
            } else if (arg.startsWith("--base-url=")) {
                baseUrlOverride = arg.substring("--base-url=".length());
            } else if (arg.startsWith("--think=")) {
                String value = arg.substring("--think=".length());
                if (!value.equals("on") && !value.equals("off")) {
                    System.err.println("Unknown --think value: " + value + " (expected 'on' or 'off')");
                    System.exit(1);
                }
                thinkDisabled = value.equals("off");
            } else if (arg.equals("--debug-raw")) {
                debugRaw = true;
            } else if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length());
            } else if (arg.startsWith("--")) {
                System.err.println("Unknown flag: " + arg);
                System.err.println("Run with --help for usage.");
                System.exit(1);
            } else {
                models.add(arg);
            }
        }
        if (!backend.equals("ollama") && !backend.equals("mlx")) {
            System.err.println("Unknown --backend value: " + backend + " (expected 'ollama' or 'mlx')");
            System.exit(1);
        }
        if (!mode.equals("freeform") && !mode.equals("schema")) {
            System.err.println("Unknown --mode value: " + mode + " (expected 'freeform' or 'schema')");
            System.exit(1);
        }
        return new CliArgs(backend, baseUrlOverride, models, thinkDisabled, debugRaw, mode);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java BenchmarkLocalModels.java [options] [model1] [model2] ...

                Options:
                  --backend=ollama|mlx   Backend to benchmark against (default: ollama)
                  --base-url=<url>       Override the backend's base URL
                  --think=on|off         Disable Qwen3-style <think> reasoning by appending /no_think
                                         to the user query (MLX backend only; default: on/unchanged).
                                         Ollama already always sends "think":false, so this flag is a
                                         no-op there.
                  --debug-raw            Capture each call's full raw HTTP response body (before any
                                         parsing) and include it in the generated report as a
                                         "Raw responses" appendix, for failed cases only.
                  --mode=freeform|schema Freeform JSON completion (default) or schema-constrained
                                         output. In schema mode, Ollama gets the flat conditions-list
                                         JSON Schema via its native "format" field (grammar-constrained
                                         decoding, works for any model); the MLX backend attempts the
                                         OpenAI-style "response_format":{"type":"json_schema",...}
                                         field, best-effort — mlx_lm.server's support for it is
                                         version-dependent, and a rejection surfaces as a normal
                                         per-case/per-model failure like any other HTTP error.
                  --help, -h             Show this help and exit

                Backend defaults:
                  ollama: http://localhost:11434 (override via OLLAMA_BASE_URL env var)
                  mlx:    http://localhost:8090  (override via MLX_BASE_URL env var)
                          mlx_lm.server serves one model per process — see README for setup.

                Examples:
                  java BenchmarkLocalModels.java
                  java BenchmarkLocalModels.java llama3.1:8b qwen3:8b
                  java BenchmarkLocalModels.java --backend=mlx
                  java BenchmarkLocalModels.java --backend=mlx --base-url=http://localhost:9000
                  java BenchmarkLocalModels.java --backend=mlx --think=off mlx-community/Qwen3-14B-4bit""");
    }

    private static String resolveBaseUrl(CliArgs cli) {
        if (cli.baseUrlOverride() != null && !cli.baseUrlOverride().isBlank()) {
            return cli.baseUrlOverride();
        }
        boolean mlx = cli.backend().equals("mlx");
        String env = System.getenv(mlx ? "MLX_BASE_URL" : "OLLAMA_BASE_URL");
        String defaultUrl = mlx ? MLX_DEFAULT_BASE_URL : OLLAMA_DEFAULT_BASE_URL;
        return (env == null || env.isBlank()) ? defaultUrl : env;
    }

    /**
     * Resolves which models to benchmark. Ollama can enumerate/switch between many pulled models, so
     * requested names are trusted as-is (unchanged behavior) and, like before, a discovery failure with
     * no requested names propagates uncaught. {@code mlx_lm.server} serves exactly one loaded model per
     * process, so requested names are validated against what's actually loaded — mismatches are skipped
     * with a warning instead of silently mislabeling results. If that validation call itself fails (e.g.
     * unreachable server), fall back to trusting the requested names as-is, so the failure surfaces once,
     * gracefully, per model in {@link #benchmarkModel} instead of crashing discovery outright.
     */
    private static List<String> resolveModels(ApiClient client, List<String> requested)
            throws IOException, InterruptedException {
        if (requested.isEmpty()) {
            return client.discoverModels();
        }
        if (!(client instanceof MlxClient)) {
            return requested;
        }
        List<String> available;
        try {
            available = client.discoverModels();
        } catch (Exception e) {
            System.err.println("WARN: could not verify requested model(s) against mlx_lm.server ("
                    + e.getMessage() + "); proceeding without validation.");
            return requested;
        }
        List<String> valid = new ArrayList<>();
        for (String name : requested) {
            if (available.contains(name)) {
                valid.add(name);
            } else {
                System.err.println("WARN: requested model '" + name + "' is not the model loaded in "
                        + "mlx_lm.server (loaded: " + available + "); skipping.");
            }
        }
        return valid;
    }

    // ---------------------------------------------------------------------------------------------
    // System prompt: extracted from the real CustomerSearchStructuredOutputService.java so the
    // benchmark cannot drift from production behaviour, mirroring benchmark_models.py's approach.
    // ---------------------------------------------------------------------------------------------

    private static String buildSystemPrompt(LocalDate today) throws IOException {
        Path source = locateCustomerSearchStructuredOutputService();
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
        // Same argument order as CustomerSearchStructuredOutputService.systemPrompt(...).formatted(...).
        String prompt = template.formatted(today, today, yesterday, today, today, today, thisWeekMonday,
                lastWeekMonday, today, lastMonthStart);

        return prompt + "\n\nRespond ONLY with a JSON object of this exact shape, nothing else:\n"
                + "{\"conditions\": [ {\"field\":\"...\",\"operator\":\"...\",\"values\":[\"...\"],"
                + "\"negate\":true|false}, ... ]}";
    }

    private static Path locateCustomerSearchStructuredOutputService() {
        String relative = "dev/demo/vaadin/aigridfilter/ai/CustomerSearchStructuredOutputService.java";
        List<Path> candidates = List.of(
                Path.of("../03-ai-structured-filter/src/main/java/" + relative),          // run from 04-ollama-benchmark/
                Path.of("03-ai-structured-filter/src/main/java/" + relative)               // run from repo root
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate CustomerSearchStructuredOutputService.java from cwd="
                + Path.of("").toAbsolutePath() + "; tried " + candidates);
    }

    // ---------------------------------------------------------------------------------------------
    // Test cases: 1:1 port of CustomerSearchAgentIT.java + CustomerSearchAgentExtraIT.java (flat
    // conditions list — cross-field-OR/nested-tree cases from the old FilterNode tree don't have a
    // flat-schema equivalent and were dropped, not ported).
    // ---------------------------------------------------------------------------------------------

    private static List<TestCase> testCases(LocalDate today) {
        List<TestCase> cases = new ArrayList<>();
        cases.add(TestCase.of("singleCity", "show me all customers in Berlin",
                ExpectedCriterion.of("city", "CONTAINS", "berlin")));
        cases.add(TestCase.of("singleFalseCity", "show me all customers except from Berlin",
                ExpectedCriterion.of("city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "berlin")));
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
        cases.add(TestCase.of("citiesWithRevenueRange",
                "Berlin or Hamburg with revenue between 100000 and 500000",
                ExpectedCriterion.of("city", "CONTAINS", "berlin"),
                ExpectedCriterion.of("city", "CONTAINS", "hamburg"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "100000"),
                ExpectedCriterion.of("annualRevenue", "LESS_OR_EQUAL", "500000")));

        // --- negation + AND-across-fields + a bare-year closed range, all flat-expressible ---
        cases.add(TestCase.of("notInCityWithRevenueAndYear",
                "customers who are not from Berlin, have at least 1000 in revenue, and last ordered in 2024",
                ExpectedCriterion.of("city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "berlin"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "1000"),
                ExpectedCriterion.of("lastOrderDate", "GREATER_OR_EQUAL", "2024-01-01"),
                ExpectedCriterion.of("lastOrderDate", "LESS_OR_EQUAL", "2024-12-31")));
        cases.add(TestCase.of("notInCityWithRevenueAndYear_German",
                "Kunden, die nicht aus Berlin kommen und mind. 1000 € Umsatz haben und 2024 zuletzt gekauft haben",
                ExpectedCriterion.of("city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "berlin"),
                ExpectedCriterion.of("annualRevenue", "GREATER_OR_EQUAL", "1000"),
                ExpectedCriterion.of("lastOrderDate", "GREATER_OR_EQUAL", "2024-01-01"),
                ExpectedCriterion.of("lastOrderDate", "LESS_OR_EQUAL", "2024-12-31")));
        return cases;
    }

    // ---------------------------------------------------------------------------------------------
    // Benchmarking a single model
    // ---------------------------------------------------------------------------------------------

    private static ModelResult benchmarkModel(ApiClient client, String model, String systemPrompt,
            List<TestCase> cases) throws Exception {
        try {
            client.chat(model, systemPrompt, "warm up"); // load model, excluded from timings
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
                    ttft = client.timeToFirstTokenMs(model, systemPrompt, tc.query());
                }
                ChatResult chatResult = client.chat(model, systemPrompt, tc.query());
                long durationMs = (System.nanoTime() - t0) / 1_000_000;
                String content = chatResult.content();
                List<Map<String, Object>> criteria = parseCriteria(content);
                boolean passed = caseCorrect(criteria, tc.expected());
                caseResults.add(new CaseResult(tc.name(), tc.query(), passed, durationMs, chatResult.tokenCount(),
                        chatResult.tokenDurationNs(), null, content, chatResult.rawBody()));
            } catch (Exception e) {
                long durationMs = (System.nanoTime() - t0) / 1_000_000;
                caseResults.add(new CaseResult(tc.name(), tc.query(), false, durationMs, 0, 0,
                        e.getClass().getSimpleName() + ": " + e.getMessage(), null, null));
            }
        }

        sampling.set(false);
        sampler.join(2000);
        long heapAfter = usedHeapBytes();
        double avgCpu = cpuSamples.isEmpty() ? 0
                : cpuSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100;

        Long modelSize = client.modelSizeBytes(model);
        Long vram = client.modelVramBytes(model);
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
    // Hand-rolled JSON Schema for the flat conditions list (mirrors dev.demo.vaadin.aigridfilter.ai.
    // filter.CustomerFilter/Condition/Operator in 03-ai-structured-filter exactly — 15 fields, 6
    // operators, values array, negate boolean). Used with --mode=schema to constrain model output
    // instead of relying on free-text prompt instructions; additionalProperties:false targets the
    // duplicate-key/malformed-JSON bugs seen in earlier (tree-schema) benchmark reports. No $ref/
    // oneOf/recursion at all — the flat shape needs none.
    // ---------------------------------------------------------------------------------------------

    private static final String FLAT_CONDITIONS_SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["conditions"],
              "properties": {
                "conditions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["field", "operator", "values", "negate"],
                    "properties": {
                      "field": { "type": "string", "enum": [
                        "companyName","contactName","email","phone","annualRevenue","creditRating",
                        "customerSince","lastOrderDate","country","city","postalCode","street",
                        "houseNumber","state","countryCode"
                      ]},
                      "operator": { "type": "string", "enum": [
                        "CONTAINS","EQUALS","GREATER_OR_EQUAL","LESS_OR_EQUAL","STARTS_WITH","ENDS_WITH"
                      ]},
                      "values": { "type": "array", "items": { "type": "string" } },
                      "negate": { "type": "boolean" }
                    }
                  }
                }
              }
            }
            """;

    // ---------------------------------------------------------------------------------------------
    // Ollama API client (native /api/chat, /api/tags, /api/ps endpoints — chosen over Ollama's
    // OpenAI-compatible surface for the extra eval_count/eval_duration/VRAM metrics it exposes).
    // ---------------------------------------------------------------------------------------------

    static final class OllamaClient implements ApiClient {
        private final String baseUrl;
        private final boolean schemaMode;
        private final boolean debugRaw;

        OllamaClient(String baseUrl, boolean schemaMode, boolean debugRaw) {
            this.baseUrl = baseUrl;
            this.schemaMode = schemaMode;
            this.debugRaw = debugRaw;
        }

        /** "json" (generic JSON-mode) in freeform mode, or the flat conditions JSON Schema object in
         * schema mode — Ollama's native "format" field accepts either, spliced in unescaped (it's a
         * JSON value, not a JSON string). */
        private String formatField() {
            return schemaMode ? FLAT_CONDITIONS_SCHEMA_JSON : "\"json\"";
        }

        @Override
        public String backendName() {
            return "ollama";
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<String> discoverModels() throws IOException, InterruptedException {
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

        @Override
        public ChatResult chat(String model, String systemPrompt, String query)
                throws IOException, InterruptedException {
            String payload = """
                    {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                    "think":false,"stream":false,"format":%s,
                    "options":{"temperature":0,"num_ctx":4096,"num_predict":512}}
                    """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(query), formatField());
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
            String content = "";
            if (map.get("message") instanceof Map<?, ?> m && m.get("content") instanceof String s) {
                content = s;
            }
            return new ChatResult(content, asLong(map.get("eval_count")), asLong(map.get("eval_duration")),
                    debugRaw ? response.body() : null);
        }

        @Override
        public Long timeToFirstTokenMs(String model, String systemPrompt, String query) {
            String payload = """
                    {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                    "think":false,"stream":true,"format":%s,
                    "options":{"temperature":0,"num_ctx":4096,"num_predict":512}}
                    """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(query), formatField());
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

        @Override
        public Long modelSizeBytes(String model) {
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

        @Override
        public Long modelVramBytes(String model) {
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
    }

    // ---------------------------------------------------------------------------------------------
    // MLX server API client — talks to a local mlx_lm.server (https://github.com/ml-explore/mlx-lm,
    // Apple Silicon only) via its OpenAI-compatible /v1 surface. Unlike Ollama, mlx_lm.server serves
    // exactly one model per process (whatever it was started with via --model), so there is no
    // per-request model switching and no on-disk-size/VRAM endpoint to query.
    // ---------------------------------------------------------------------------------------------

    static final class MlxClient implements ApiClient {
        private final String baseUrl;
        private final boolean thinkDisabled;
        private final boolean schemaMode;
        private final boolean debugRaw;

        MlxClient(String baseUrl, boolean thinkDisabled, boolean schemaMode, boolean debugRaw) {
            this.baseUrl = baseUrl;
            this.thinkDisabled = thinkDisabled;
            this.schemaMode = schemaMode;
            this.debugRaw = debugRaw;
        }

        /** Qwen3's documented, server-version-independent soft-switch: plain user-turn text, never the
         * system prompt, so there's no risk of mlx_lm.server rejecting an unrecognized JSON field. */
        private String effectiveQuery(String query) {
            return thinkDisabled ? query + "\n\n/no_think" : query;
        }

        /** OpenAI-style structured-outputs field, best-effort — mlx_lm.server's support for it is
         * version-dependent; omitted entirely in freeform mode (unchanged existing payload). */
        private String responseFormatField() {
            return schemaMode
                    ? ",\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{"
                            + "\"name\":\"customer_filter\",\"strict\":true,\"schema\":"
                            + FLAT_CONDITIONS_SCHEMA_JSON + "}}"
                    : "";
        }

        @Override
        public String backendName() {
            return "mlx";
        }

        @Override
        public List<String> discoverModels() throws IOException, InterruptedException {
            Object parsed = get(baseUrl + "/v1/models");
            if (!(parsed instanceof Map<?, ?> map) || !(map.get("data") instanceof List<?> data)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Object o : data) {
                if (o instanceof Map<?, ?> m && m.get("id") instanceof String id) {
                    result.add(id);
                }
            }
            return result;
        }

        @Override
        public ChatResult chat(String model, String systemPrompt, String query)
                throws IOException, InterruptedException {
            String payload = """
                    {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                    "temperature":0,"max_tokens":512,"stream":false%s}
                    """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(effectiveQuery(query)),
                    responseFormatField());
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl.replaceAll("/$", "") + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            long t0 = System.nanoTime();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long wallClockNs = System.nanoTime() - t0;
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            Object parsed = Json.parse(response.body());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Unexpected /v1/chat/completions response shape: " + response.body());
            }
            String content = "";
            if (map.get("choices") instanceof List<?> choices && !choices.isEmpty()
                    && choices.get(0) instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> msg
                    && msg.get("content") instanceof String c) {
                content = c;
            }
            long tokenCount = 0;
            if (map.get("usage") instanceof Map<?, ?> usage) {
                tokenCount = asLong(usage.get("completion_tokens"));
            }
            // No native generation-only duration in the OpenAI-compatible response, unlike Ollama's
            // eval_duration — tok/s for this backend is therefore based on wall-clock request time
            // and includes network + prompt-evaluation overhead (see README for this caveat).
            return new ChatResult(content, tokenCount, wallClockNs, debugRaw ? response.body() : null);
        }

        @Override
        public Long timeToFirstTokenMs(String model, String systemPrompt, String query) {
            String payload = """
                    {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                    "temperature":0,"max_tokens":512,"stream":true%s}
                    """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(effectiveQuery(query)),
                    responseFormatField());
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(baseUrl.replaceAll("/$", "") + "/v1/chat/completions"))
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
                        // SSE frames: "data: {json}", terminated by a literal "data: [DONE]" line.
                        String data = line.startsWith("data:") ? line.substring(5).stripLeading() : line;
                        if (data.equals("[DONE]")) break;
                        Object chunk = Json.parse(data);
                        if (chunk instanceof Map<?, ?> m && m.get("choices") instanceof List<?> choices
                                && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> choice
                                && choice.get("delta") instanceof Map<?, ?> delta
                                && delta.get("content") instanceof String c && !c.isEmpty()) {
                            return (System.nanoTime() - t0) / 1_000_000;
                        }
                    }
                }
            } catch (Exception ignored) {
                // TTFT is a best-effort metric; a failure here doesn't fail the case.
            }
            return null;
        }

        @Override
        public Long modelSizeBytes(String model) {
            return null; // no equivalent endpoint in the OpenAI-compatible API
        }

        @Override
        public Long modelVramBytes(String model) {
            return null; // no equivalent endpoint in the OpenAI-compatible API
        }
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
    // Response parsing & tolerant matching (port of CustomerSearchAgentIT.hasCondition/flatten)
    // ---------------------------------------------------------------------------------------------

    /**
     * Best-effort: expands the model's {@code conditions} array into one leaf per value (field/
     * operator/value), [] if the shape is off. Also accepts a bare top-level array for models that
     * skip the {@code conditions} wrapper key, so a model's degree of non-compliance shows up as
     * lower accuracy rather than being silently masked.
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
        List<?> conditions = null;
        if (data instanceof Map<?, ?> map && map.get("conditions") instanceof List<?> list) {
            conditions = list;
        } else if (data instanceof List<?> list) {          // bare array for models that skip the wrapper key
            conditions = list;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (conditions != null) {
            for (Object o : conditions) {
                if (o instanceof Map<?, ?> m) {
                    expandCondition((Map<String, Object>) m, result);
                }
            }
        }
        return result;
    }

    /**
     * Expands one {@code {field, operator, values:[...], negate}} condition into one leaf per value
     * (field/operator/value). A negated condition's operator gets a synthetic {@code NOT_} prefix
     * (e.g. {@code NOT_CONTAINS}), so existing {@link ExpectedCriterion} assertions phrased in
     * {@code NOT_EQUALS}/{@code NOT_CONTAINS} terms keep working unchanged — purely a benchmark-
     * matching convenience; production's {@code Operator} enum no longer has those values. Also
     * accepts a bare {@code "value"} string instead of a {@code "values"} array, for models that
     * ignore the array contract.
     */
    private static void expandCondition(Map<String, Object> condition, List<Map<String, Object>> into) {
        Object field = condition.get("field");
        Object operator = condition.get("operator");
        boolean negate = Boolean.TRUE.equals(condition.get("negate"));
        String effectiveOperator = operator == null ? null : (negate ? "NOT_" + operator : String.valueOf(operator));

        List<?> values;
        if (condition.get("values") instanceof List<?> list) {
            values = list;
        } else if (condition.get("value") != null) {
            values = List.of(condition.get("value"));
        } else {
            values = List.of();
        }
        for (Object value : values) {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("field", field);
            leaf.put("operator", effectiveOperator);
            leaf.put("value", value);
            into.add(leaf);
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

    /** Shared cell values for one model's row, reused by printTable/renderMarkdown/renderText. */
    private record RowCells(String model, String accuracy, String medianLat, String ttft, String tokS,
                             String ram, String cpu, String modelSize) {
    }

    private static RowCells buildRow(ModelResult r, int totalCases) {
        long passed = r.cases().stream().filter(CaseResult::passed).count();
        double medianMs = median(r.cases().stream().map(CaseResult::durationMs).collect(Collectors.toList()));
        return new RowCells(
                r.model(),
                passed + "/" + totalCases,
                "%.0f ms".formatted(medianMs),
                r.ttftMs() != null ? r.ttftMs() + " ms" : "n/a",
                "%.1f".formatted(tokensPerSecond(r)),
                formatBytes(r.heapUsedAfterBytes() - r.heapUsedBeforeBytes()),
                "%.0f%%".formatted(r.avgCpuLoadPercent()),
                r.modelSizeBytes() != null ? formatBytes(r.modelSizeBytes()) : "n/a");
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
            RowCells row = buildRow(r, totalCases);
            System.out.printf("%-22s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                    row.model(), row.accuracy(), row.medianLat(), row.ttft(), row.tokS(), row.ram(), row.cpu(),
                    row.modelSize());
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

    private static String renderMarkdown(List<ModelResult> results, int totalCases, String backendName,
            String baseUrl, boolean thinkDisabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Local Model Benchmark\n\n");
        sb.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n\n");
        sb.append("Backend: ").append(backendLabel(backendName)).append(", Base URL: ").append(baseUrl)
          .append("\n\n");
        if (thinkDisabled) {
            sb.append("Thinking: disabled (--think=off; /no_think appended to MLX queries)\n\n");
        }
        sb.append("Not a CI gate — for model comparison during demos/development. ")
          .append(totalCases).append(" test cases per model, ported from `CustomerSearchAgentIT`.\n\n");
        sb.append("| Model | Accuracy | Median Latency | TTFT | Tokens/s | RAM (JVM) | CPU | Model Size |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (ModelResult r : results) {
            if (r.fatalError() != null) {
                sb.append("| ").append(r.model()).append(" | ERROR: ").append(r.fatalError())
                  .append(" | | | | | | |\n");
                continue;
            }
            RowCells row = buildRow(r, totalCases);
            sb.append("| ").append(row.model())
              .append(" | ").append(row.accuracy())
              .append(" | ").append(row.medianLat())
              .append(" | ").append(row.ttft())
              .append(" | ").append(row.tokS())
              .append(" | ").append(row.ram())
              .append(" | ").append(row.cpu())
              .append(" | ").append(row.modelSize())
              .append(" |\n");
        }
        sb.append("\nGPU: ").append(results.isEmpty() ? "n/a" : results.get(0).gpuInfo())
          .append(" (nvidia-smi; \"n/a\" on hosts without an NVIDIA GPU, e.g. Apple Silicon)\n");

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

        for (ModelResult r : results) {
            List<CaseResult> rawFailures = r.cases().stream()
                    .filter(c -> !c.passed() && c.rawBody() != null).collect(Collectors.toList());
            if (rawFailures.isEmpty()) continue;
            sb.append("\n## Raw responses: ").append(r.model()).append("\n\n");
            sb.append("Full unprocessed HTTP response body for each failed case, before any parsing.\n\n");
            for (CaseResult f : rawFailures) {
                sb.append("### ").append(f.name()).append(" — ").append(f.query()).append("\n\n");
                sb.append("```\n").append(truncateForReport(f.rawBody())).append("\n```\n\n");
            }
        }
        return sb.toString();
    }

    private static final int RAW_BODY_REPORT_CAP = 4000;

    private static String truncateForReport(String rawBody) {
        if (rawBody.length() <= RAW_BODY_REPORT_CAP) {
            return rawBody;
        }
        return rawBody.substring(0, RAW_BODY_REPORT_CAP)
                + "\n...[truncated, " + rawBody.length() + " bytes total]";
    }

    private static String renderText(List<ModelResult> results, int totalCases, String backendName,
            String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Backend: ").append(backendLabel(backendName)).append(", Base URL: ").append(baseUrl)
          .append("\n\n");
        sb.append(String.format("%-22s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                "Model", "Accuracy", "Median Lat.", "TTFT", "tok/s", "RAM", "CPU", "Model Size"));
        sb.append("-".repeat(98)).append("\n");
        for (ModelResult r : results) {
            if (r.fatalError() != null) {
                sb.append(String.format("%-22sERROR: %s%n", r.model(), r.fatalError()));
                continue;
            }
            RowCells row = buildRow(r, totalCases);
            sb.append(String.format("%-22s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                    row.model(), row.accuracy(), row.medianLat(), row.ttft(), row.tokS(), row.ram(), row.cpu(),
                    row.modelSize()));
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
