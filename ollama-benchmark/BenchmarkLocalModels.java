import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Standalone Java prompt-reliability eval comparing local models — Ollama (default) or an
 * OpenAI-compatible MLX server ({@code mlx_lm.server}) — on the natural-language -&gt; filter task, for
 * <b>all four</b> AI approaches this project demos:
 * <ol>
 *   <li>{@code 02-ai-agent-filter} 02(a): a tool call with one scalar value per field,</li>
 *   <li>{@code 02-ai-agent-filter} 02(b): a tool call with a value, an operator and a negate flag per
 *       field (39 flat parameters),</li>
 *   <li>{@code 03-ai-structured-filter}: one {@code CustomerFilter} returned as structured output,</li>
 *   <li>{@code 04-ai-hybrid-filter}: that same condition list, delivered as a tool call.</li>
 * </ol>
 * Every approach's system prompt — and, for the tool-calling ones, its tool/argument schema — is
 * extracted from that module's production source at runtime, never hard-coded, so the eval cannot drift
 * from the running apps.
 *
 * <p>The primary case list is the canonical query set ({@code docs/canonical-query-set.md}), the same
 * eight queries all four modules' canonical-query ITs run — so the token and latency figures here line up
 * query-for-query with those suites' pass/fail results. A second, legacy case list mirrors
 * {@code 03-ai-structured-filter}'s {@code CustomerSearchAgentIT}/{@code CustomerSearchAgentExtraIT} and
 * runs against <b>all four</b> approaches: many of these cases need negation, a second value for one
 * field, or a range, which 02(a)/02(b) cannot express at all, so those show up as a low or zero pass rate
 * for those approaches rather than as "n/a" (unlike the canonical set, this eval does not pre-classify
 * legacy cases as architecturally out of reach for a given approach — see the "Legacy set" section of the
 * generated report). Everything is plain HTTP (no Maven/JUnit, no Spring context), each case run
 * {@code --runs} times so per-case pass-rate (not just single-shot pass/fail) becomes measurable — the
 * point being to answer, quantitatively, "does this prompt produce the correct filter with high
 * probability, and did any case regress?" after editing a system prompt or a
 * {@code @ToolParam}/{@code @JsonPropertyDescription}.
 *
 * <p>Run directly with Java's single-file source launcher (no external dependencies, JDK stdlib only):
 * <pre>
 *   cd ollama-benchmark
 *   java BenchmarkLocalModels.java [options] [model1] [model2] ...
 * </pre>
 * Without model arguments, candidates are auto-discovered — from Ollama's {@code GET /api/tags}
 * (models whose capabilities include "tools") by default, or from {@code mlx_lm.server}'s
 * {@code GET /v1/models} with {@code --backend=mlx}. Base URL via {@code OLLAMA_BASE_URL}/
 * {@code MLX_BASE_URL} env vars or {@code --base-url=<url>}; run with {@code --help} for the full
 * flag list. Reports ({@code benchmark-report-<timestamp>.md/.txt}) are written to the current
 * working directory. Not a CI gate — for model comparison and prompt-tuning during development.
 */
public class BenchmarkLocalModels {

    // ---------------------------------------------------------------------------------------------
    // Case model: approach-agnostic expected outcome, scored against a normalized list of (field,
    // operator-or-null, value) leaves built identically from either approach's raw response (see
    // Leaf/normalization below). This is what lets one case list drive both approaches.
    // ---------------------------------------------------------------------------------------------

    /** One (field, operator, value) fact extracted from a model response; operator is {@code null}
     * when the approach doesn't expose one (tool calling — see {@link #normalizeToolCallArgs}). */
    record Leaf(String field, String operator, String value) {
    }

    sealed interface Expectation permits TextExpectation, NumericAtLeast, NumericAtMost, NumericExact {
        String field();
    }

    /** Field must have a leaf whose operator (if the approach exposes one) is one of {@code operators}
     * (empty = any) and whose value contains one of {@code substrings} (case-insensitive; a blank
     * substring accepts any non-null value — used for relative-date cases where the exact value
     * depends on "today"). */
    record TextExpectation(String field, List<String> operators, List<String> substrings) implements Expectation {
        static TextExpectation of(String field, String operator, String... substrings) {
            return new TextExpectation(field, operator == null ? List.of() : List.of(operator), List.of(substrings));
        }

        static TextExpectation of(String field, String[] operators, String... substrings) {
            return new TextExpectation(field, List.of(operators), List.of(substrings));
        }
    }

    /** Field must have a leaf whose value parses as a number {@code >= min} (headroom-tolerant: small
     * local models round/estimate thresholds, so the exact literal from the query is not required —
     * same philosophy and thresholds as the aligned {@code CustomerSearchAgentIT}s). */
    record NumericAtLeast(String field, BigDecimal min) implements Expectation {
    }

    /** Symmetric to {@link NumericAtLeast}: field's value must parse as a number {@code <= max}. */
    record NumericAtMost(String field, BigDecimal max) implements Expectation {
    }

    /**
     * Strict counterpart of {@link NumericAtLeast}/{@link NumericAtMost}: the field's value must
     * parse to the exact same number as {@code value} (currency/thousands-separator formatting
     * tolerated), instead of a range/threshold. Use only for a genuinely exact query (e.g. "exactly
     * 100000 in annual revenue") — never retrofitted onto the range-style cases above, which are
     * deliberately headroom-tolerant.
     */
    record NumericExact(String field, BigDecimal value) implements Expectation {
    }

    /** Whether a case belongs to the canonical query set or to the older prompt-regression set. */
    enum CaseGroup {
        CANONICAL, LEGACY
    }

    /**
     * One eval case, together with the approaches it is run against.
     * <p>
     * {@link CaseGroup#CANONICAL} cases are the queries of {@code docs/canonical-query-set.md}, the
     * single source of truth shared with all four modules' canonical-query ITs. Their wording here must
     * stay verbatim — {@code CanonicalQuerySetConsistencyTest} fails the build otherwise — so the token
     * and latency figures measured here line up query-for-query with those suites' pass/fail results.
     * Each canonical case names the approaches whose filter type can express it at all; for the others it
     * is reported as {@code n/a} rather than as a failure, because an architectural limit is not a
     * reliability problem (the ITs are where those expected failures are asserted).
     * <p>
     * {@link CaseGroup#LEGACY} cases are the older set mirroring {@code 03-ai-structured-filter}'s
     * {@code CustomerSearchAgentIT}/{@code CustomerSearchAgentExtraIT}. They were written for the
     * condition-list filter type, but run against <b>all four</b> approaches: unlike the canonical set,
     * a legacy case is not pre-classified as architecturally out of reach for 02(a)/02(b), so a case
     * needing negation, a second value for one field, or a range shows up as a low or zero pass rate for
     * those approaches — a deliberately blunt view, kept simple on purpose (see the "Legacy set" section
     * of the generated report for the resulting matrix). They are kept because they are the accumulated
     * prompt-tuning safety net.
     */
    record EvalCase(String name, String query, Set<String> tags, CaseGroup group,
                     Set<Approach> approaches, List<Expectation> expected) {

        /**
         * A canonical query. The first two arguments are read verbatim out of this source by
         * {@code CanonicalQuerySetConsistencyTest} — keep them literal, never build them from constants.
         */
        static EvalCase canonical(String name, String query, Set<Approach> approaches, Expectation... expected) {
            return new EvalCase(name, query, Set.of("canonical"), CaseGroup.CANONICAL, approaches,
                    List.of(expected));
        }

        static EvalCase legacy(String name, String query, Expectation... expected) {
            return new EvalCase(name, query, Set.of(), CaseGroup.LEGACY, ALL_APPROACHES,
                    List.of(expected));
        }

        static EvalCase legacy(String name, String query, String tag, Expectation... expected) {
            return new EvalCase(name, query, Set.of(tag), CaseGroup.LEGACY, ALL_APPROACHES,
                    List.of(expected));
        }
    }

    /** Every approach — used by the canonical cases that no filter type struggles with. */
    private static final Set<Approach> ALL_APPROACHES = Set.of(Approach.values());

    /** 03 and 04: the two approaches sharing the {@code CustomerFilter}/{@code Condition} type. */
    private static final Set<Approach> CONDITION_LIST_APPROACHES =
            Set.of(Approach.STRUCTURED, Approach.CONDITION_TOOL_CALLING);

    /** Everything except 02(a), whose flat-only tool has neither an operator nor a negate flag. */
    private static final Set<Approach> EXCEPT_FLAT = Set.of(Approach.OPERATOR_TOOL_CALLING,
            Approach.STRUCTURED, Approach.CONDITION_TOOL_CALLING);

    /** The 13 fields every approach can express (the per-field variants' tool parameters, and the subset
     * of the condition-list approaches' {@code Condition.field} vocabulary that has a per-field
     * counterpart — {@code state}/{@code countryCode} exist only there and are touched by no case here,
     * so they're excluded from the "must stay empty" check rather than always counted as a trivial
     * pass). */
    private static final List<String> CANONICAL_FIELDS = List.of(
            "companyName", "contactName", "email", "phone", "customerSince", "lastOrderDate",
            "country", "city", "postalCode", "street", "houseNumber", "creditRating", "annualRevenue");

    /** Representative subset for {@code --quick}: canonical cases only, covering one query every
     * approach can express (C1), one multi-field AND (C5), one that needs a second value for a field
     * (C2) and one that needs two bounds (C6) — so a quick run still exercises both the shared ground
     * and the capability gap between the per-field and condition-list approaches. */
    private static final Set<String> QUICK_CASE_NAMES = Set.of(
            "C1_SINGLE_VALUE", "C2_MULTI_VALUE_OR", "C5_COMBINED_AND", "C6_REVENUE_RANGE");

    private static List<EvalCase> buildCases(LocalDate today) {
        List<EvalCase> cases = new ArrayList<>();

        // --- the canonical query set: docs/canonical-query-set.md is the single source of truth for
        // every query string below, and the same eight queries drive all four modules' canonical-query
        // ITs. Keep wording, order and case names in sync with that document — the modules'
        // CanonicalQuerySetConsistencyTest fails the build on any drift. ---
        cases.add(EvalCase.canonical("C1_SINGLE_VALUE", "show me all customers in Berlin",
                ALL_APPROACHES,
                TextExpectation.of("city", "CONTAINS", "berlin")));
        cases.add(EvalCase.canonical("C2_MULTI_VALUE_OR", "show me customers from Berlin or Hamburg",
                CONDITION_LIST_APPROACHES,
                TextExpectation.of("city", "CONTAINS", "berlin"),
                TextExpectation.of("city", "CONTAINS", "hamburg")));
        cases.add(EvalCase.canonical("C3_NEGATION", "show me all customers except from Berlin",
                EXCEPT_FLAT,
                TextExpectation.of("city", "NOT_CONTAINS", "berlin")));
        cases.add(EvalCase.canonical("C4_OPERATOR_PRECISION",
                "show me all customers with an \"m\" as the first character in the contact name",
                EXCEPT_FLAT,
                TextExpectation.of("contactName", "STARTS_WITH", "m")));
        cases.add(EvalCase.canonical("C5_COMBINED_AND", "creditworthy customers in Hamburg",
                ALL_APPROACHES,
                TextExpectation.of("city", "CONTAINS", "hamburg"),
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));
        cases.add(EvalCase.canonical("C6_REVENUE_RANGE", "customers with revenue between 100000 and 200000",
                CONDITION_LIST_APPROACHES,
                new NumericAtLeast("annualRevenue", BigDecimal.valueOf(75_000)),
                new NumericAtMost("annualRevenue", BigDecimal.valueOf(250_000))));
        cases.add(EvalCase.canonical("C7_RELATIVE_DATE",
                "show me all customers who placed an order in the last 12 months",
                EXCEPT_FLAT,
                TextExpectation.of("lastOrderDate", "GREATER_OR_EQUAL", "")));
        cases.add(EvalCase.canonical("C8_DATE_RANGE",
                "customers who last ordered between 2024-07-01 and 2025-03-31",
                CONDITION_LIST_APPROACHES,
                TextExpectation.of("lastOrderDate", "GREATER_OR_EQUAL", "2024-07-01"),
                TextExpectation.of("lastOrderDate", "LESS_OR_EQUAL", "2025-03-31")));

        // --- legacy prompt-regression set, all four approaches: mirrors
        // 03-ai-structured-filter's CustomerSearchAgentIT ... ---
        cases.add(EvalCase.legacy("singleCity", "show me all customers in Berlin",
                TextExpectation.of("city", "CONTAINS", "berlin")));
        cases.add(EvalCase.legacy("creditworthyCustomers", "show me all creditworthy customers",
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));
        cases.add(EvalCase.legacy("atRiskCustomers", "show me all customers that are at risk",
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "poor", "risk")));
        cases.add(EvalCase.legacy("creditworthyInCity", "creditworthy customers in Hamburg",
                TextExpectation.of("city", "CONTAINS", "hamburg"),
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy")));
        cases.add(EvalCase.legacy("contactNameContains", "show me all customers with \"meyer\" in the contact name",
                TextExpectation.of("contactName", "CONTAINS", "meyer")));
        cases.add(EvalCase.legacy("companyNameContains", "customers whose company name contains data",
                TextExpectation.of("companyName", "CONTAINS", "data")));
        cases.add(EvalCase.legacy("customerSinceYear", "customers since 2020",
                TextExpectation.of("customerSince", "GREATER_OR_EQUAL", "2020")));
        cases.add(EvalCase.legacy("multiValueCities", "show me customers from Berlin or Hamburg",
                TextExpectation.of("city", "CONTAINS", "berlin"),
                TextExpectation.of("city", "CONTAINS", "hamburg")));
        cases.add(EvalCase.legacy("multiValueCreditRating", "show me customers with GOOD or MEDIUM credit rating",
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy"),
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "medium", "moderate")));
        cases.add(EvalCase.legacy("annualRevenueOverThreshold", "show me customers with annual revenue over 200000",
                new NumericAtLeast("annualRevenue", BigDecimal.valueOf(150_000))));
        cases.add(EvalCase.legacy("citiesAndRevenue_keepsEveryCondition",
                "show me all customers in Berlin or Hamburg with a minimal revenue of 100000",
                TextExpectation.of("city", "CONTAINS", "berlin"),
                TextExpectation.of("city", "CONTAINS", "hamburg"),
                new NumericAtLeast("annualRevenue", BigDecimal.valueOf(75_000))));
        cases.add(EvalCase.legacy("citiesWithRevenueRange", "Berlin or Hamburg with revenue between 100000 and 500000",
                TextExpectation.of("city", "CONTAINS", "berlin"),
                TextExpectation.of("city", "CONTAINS", "hamburg"),
                new NumericAtLeast("annualRevenue", BigDecimal.valueOf(75_000)),
                new NumericAtMost("annualRevenue", BigDecimal.valueOf(550_000))));
        cases.add(EvalCase.legacy("country", "customers in Germany",
                TextExpectation.of("country", new String[]{"CONTAINS", "EQUALS"}, "germany")));
        cases.add(EvalCase.legacy("resetTheFilter_German", "setze den Filter zurück"));
        cases.add(EvalCase.legacy("contactNameAndCity_German",
                "Zeigen mir Kunden deren Kontaktname Julia ist und die in Berlin sind.",
                TextExpectation.of("contactName", new String[]{"EQUALS", "CONTAINS"}, "julia"),
                TextExpectation.of("city", "CONTAINS", "berlin")));
        cases.add(EvalCase.legacy("showAllCustomers_noCriteria", "show all customers"));

        // --- ... and its CustomerSearchAgentExtraIT (negation, operator precision, arbitrary date
        // bounds, anti-hallucination). Same group, same all-four-approaches treatment. ---
        cases.add(EvalCase.legacy("phoneNumberContains",
                "show me the customer with the phone number 5020000001 or similar", "fuzzy-match",
                TextExpectation.of("phone", "CONTAINS", "5020000001")));
        cases.add(EvalCase.legacy("singleFalseCity", "show me all customers except from Berlin", "negation",
                TextExpectation.of("city", "NOT_CONTAINS", "berlin")));
        cases.add(EvalCase.legacy("contactNameStartsWith",
                "show me all customers with an \"m\" as the first character in the contact name", "operator-precision",
                TextExpectation.of("contactName", "STARTS_WITH", "m")));
        cases.add(EvalCase.legacy("contactNameEndsWith",
                "show me all customers their contact name ends with \"schmidt\"", "operator-precision",
                TextExpectation.of("contactName", "ENDS_WITH", "schmidt")));
        cases.add(EvalCase.legacy("contactNameAndCity",
                "customers whose contact name is Sofia and who are from Berlin", "operator-precision",
                TextExpectation.of("contactName", "EQUALS", "sofia"),
                TextExpectation.of("city", "CONTAINS", "berlin")));
        cases.add(EvalCase.legacy("orderedInTheLastWeek",
                "Show me all customers who placed an order in the last week", "relative-date",
                TextExpectation.of("lastOrderDate", "GREATER_OR_EQUAL", "")));
        cases.add(EvalCase.legacy("orderedYesterday",
                "show me all customers who made an order yesterday", "relative-date",
                TextExpectation.of("lastOrderDate", "EQUALS", today.minusDays(1).toString())));
        cases.add(EvalCase.legacy("customerSinceThisYear",
                "customers who became customers this year", "relative-date",
                TextExpectation.of("customerSince", "GREATER_OR_EQUAL", "")));
        cases.add(EvalCase.legacy("lastOrderBeforeDate",
                "customers whose last order was before 2024-01-01", "relative-date",
                TextExpectation.of("lastOrderDate", new String[]{"LESS_OR_EQUAL"}, "2024-01-01", "2023-12-31")));
        cases.add(EvalCase.legacy("notInCityWithRevenueRange_keepsEveryCondition",
                "companies not in Munich with revenue between 100000 and 500000", "negation",
                TextExpectation.of("city", "NOT_CONTAINS", "munich"),
                TextExpectation.of("annualRevenue", "GREATER_OR_EQUAL", "100000"),
                TextExpectation.of("annualRevenue", "LESS_OR_EQUAL", "500000")));
        cases.add(EvalCase.legacy("emailEndsWith",
                "customers whose email ends with .com", "operator-precision",
                TextExpectation.of("email", "ENDS_WITH", ".com")));
        cases.add(EvalCase.legacy("emailNotContains",
                "customers whose email does not contain gmail", "negation",
                TextExpectation.of("email", new String[]{"NOT_CONTAINS", "NOT_EQUALS"}, "gmail")));
        cases.add(EvalCase.legacy("companyNameStartsWith",
                "customers whose company name starts with A", "operator-precision",
                TextExpectation.of("companyName", "STARTS_WITH", "a")));
        cases.add(EvalCase.legacy("creditRatingTwoValues_staySeparateCriteria",
                "show me all customers in Berlin with a good and an at-risk credit rating", "operator-precision",
                TextExpectation.of("city", "CONTAINS", "berlin"),
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "good", "creditworthy"),
                TextExpectation.of("creditRating", new String[]{"EQUALS", "CONTAINS"}, "poor", "risk")));
        cases.add(EvalCase.legacy("notInCityWithRevenueRange_keepsEveryCondition_German",
                "Alle Kunden ausser aus Hamburg mit einem Umsatz von 500000 bis 1000000", "negation",
                TextExpectation.of("city", "NOT_CONTAINS", "hamburg"),
                TextExpectation.of("annualRevenue", "GREATER_OR_EQUAL", "500000"),
                TextExpectation.of("annualRevenue", "LESS_OR_EQUAL", "1000000")));
        cases.add(EvalCase.legacy("notInCityWithRevenueAndYear",
                "customers who are not from Berlin, have at least 1000 in revenue, and last ordered in 2024", "negation",
                TextExpectation.of("city", "NOT_CONTAINS", "berlin"),
                TextExpectation.of("annualRevenue", "GREATER_OR_EQUAL", "1000"),
                TextExpectation.of("lastOrderDate", "GREATER_OR_EQUAL", "2024-01-01"),
                TextExpectation.of("lastOrderDate", "LESS_OR_EQUAL", "2024-12-31")));
        cases.add(EvalCase.legacy("notInCityWithRevenueAndYear_German",
                "Kunden, die nicht aus Berlin kommen und mind. 1000 € Umsatz haben und 2024 zuletzt gekauft haben", "negation",
                TextExpectation.of("city", "NOT_CONTAINS", "berlin"),
                TextExpectation.of("annualRevenue", "GREATER_OR_EQUAL", "1000"),
                TextExpectation.of("lastOrderDate", "GREATER_OR_EQUAL", "2024-01-01"),
                TextExpectation.of("lastOrderDate", "LESS_OR_EQUAL", "2024-12-31")));

        // --- robustness / anti-hallucination cases (same group and approaches as the rest of the
        // legacy set) ---
        cases.add(EvalCase.legacy("smalltalk_noCriteria", "Nice weather today, isn't it?",
                "anti-hallucination"));
        cases.add(EvalCase.legacy("unrelatedRequest_noCriteria", "What's the capital of France?",
                "anti-hallucination"));
        cases.add(EvalCase.legacy("revenueExact_notOverGenerated",
                "customers with exactly 100000 in annual revenue", "anti-hallucination",
                new NumericExact("annualRevenue", BigDecimal.valueOf(100000))));
        return cases;
    }

    // ---------------------------------------------------------------------------------------------
    // Approach abstraction
    // ---------------------------------------------------------------------------------------------

    /**
     * The four AI approaches of this repository, in escalation-ladder order. {@code module} and
     * {@code serviceSource} say where each one's real system prompt (and, for the tool-calling ones, its
     * tool schema) is extracted from at runtime, so the eval can never drift from the running apps.
     */
    enum Approach {
        FLAT_TOOL_CALLING("02a-flat", "02-ai-agent-filter", "flat/CustomerSearchService.java"),
        OPERATOR_TOOL_CALLING("02b-operator", "02-ai-agent-filter", "operator/CustomerSearchService.java"),
        STRUCTURED("03-structured", "03-ai-structured-filter", "CustomerSearchStructuredOutputService.java"),
        CONDITION_TOOL_CALLING("04-hybrid", "04-ai-hybrid-filter", "CustomerSearchHybridToolCallingService.java");

        final String label;
        final String module;
        final String serviceSource;

        Approach(String label, String module, String serviceSource) {
            this.label = label;
            this.module = module;
            this.serviceSource = serviceSource;
        }

        /** Everything except 03 delivers the filter through a tool call. */
        boolean isToolCalling() {
            return this != STRUCTURED;
        }

        static Approach byFlag(String flag) {
            for (Approach approach : values()) {
                if (approach.label.startsWith(flag) || approach.label.equals(flag)) {
                    return approach;
                }
            }
            return null;
        }
    }

    /** {@code --approach=all} or a comma-separated list of {@code 02a,02b,03,04}. */
    private static List<Approach> resolveApproaches(String flag) {
        if (flag.equals("all")) {
            return List.of(Approach.values());
        }
        List<Approach> approaches = new ArrayList<>();
        for (String part : flag.split(",")) {
            Approach approach = Approach.byFlag(part.strip());
            if (approach == null) {
                throw new IllegalStateException("Unknown approach: " + part);
            }
            approaches.add(approach);
        }
        return approaches;
    }

    // ---------------------------------------------------------------------------------------------
    // Per-run / aggregated results
    // ---------------------------------------------------------------------------------------------

    record CaseRunResult(boolean passed, long durationMs, long tokenCount, long tokenDurationNs, String error,
                          List<Leaf> actual, Map<String, Boolean> fieldCorrect) {
    }

    record CaseAggregate(String name, String query, CaseGroup group, int passes, int runs,
                          double medianDurationMs, double medianTokS, List<String> sampleErrors,
                          List<String> sampleRawBodies, long totalDurationMs, long totalPromptTokens,
                          long totalCompletionTokens) {
        String passRateLabel() {
            return passes + "/" + runs;
        }
    }

    record ModelApproachResult(String model, Approach approach, List<CaseAggregate> cases,
                                Map<String, Double> fieldAccuracy, Long modelSizeBytes, Long vramBytes,
                                double avgCpuLoadPercent, long heapUsedBeforeBytes, long heapUsedAfterBytes,
                                String gpuInfo, Long ttftMs, String fatalError) {
        double meanPassRate() {
            return meanPassRate(cases);
        }

        /** Mean pass rate over one case group only, or 0 if this approach ran none of them. */
        double meanPassRate(CaseGroup group) {
            return meanPassRate(cases.stream().filter(c -> c.group() == group).toList());
        }

        private static double meanPassRate(List<CaseAggregate> cases) {
            if (cases.isEmpty()) return 0;
            return cases.stream().mapToDouble(c -> c.runs() == 0 ? 0 : (double) c.passes() / c.runs()).average()
                    .orElse(0);
        }

        /**
         * How many individual test runs this model/approach performed in one case group: one run is one
         * case sent to the model once, so this is the group's case count times {@code --runs}.
         */
        int performedRuns(CaseGroup group) {
            return cases.stream().filter(c -> c.group() == group).mapToInt(CaseAggregate::runs).sum();
        }

        /** How many of {@link #performedRuns} produced exactly the expected filter, every field included. */
        int passedRuns(CaseGroup group) {
            return cases.stream().filter(c -> c.group() == group).mapToInt(CaseAggregate::passes).sum();
        }

        /** Wall-clock time spent on every call of every case/run for this model+approach, successes and
         * failures alike — the real cost of running this approach, not a per-case median. */
        long totalDurationMs() {
            return cases.stream().mapToLong(CaseAggregate::totalDurationMs).sum();
        }

        long totalPromptTokens() {
            return cases.stream().mapToLong(CaseAggregate::totalPromptTokens).sum();
        }

        long totalCompletionTokens() {
            return cases.stream().mapToLong(CaseAggregate::totalCompletionTokens).sum();
        }

        long totalTokens() {
            return totalPromptTokens() + totalCompletionTokens();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Normalized chat outcome shared by every ApiClient implementation
    // ---------------------------------------------------------------------------------------------

    record ChatResult(String content, long tokenCount, long promptTokenCount, long tokenDurationNs, String rawBody) {
    }

    /** Backend abstraction so the eval/reporting code doesn't care which API it's talking to. */
    interface ApiClient {
        String backendName();

        List<String> discoverModels() throws IOException, InterruptedException;

        /** Structured-output approach: freeform/schema-constrained JSON completion (03's shape). */
        ChatResult chat(String model, String systemPrompt, String query) throws IOException, InterruptedException;

        /** Tool-calling approach (02's shape): returns the arguments object of the first {@code
         * searchCustomers} tool call, or {@code null} if the model didn't call it. */
        ChatResult chatTools(String model, String systemPrompt, String toolsJson, String query)
                throws IOException, InterruptedException;

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
                   boolean debugRaw, String mode, int runs, String approach, boolean quick, Double minPassRate,
                   String cases) {
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
        List<EvalCase> allCases = buildCases(today);
        List<EvalCase> selectedCases = allCases.stream()
                .filter(c -> switch (cli.cases()) {
                    case "canonical" -> c.group() == CaseGroup.CANONICAL;
                    case "legacy" -> c.group() == CaseGroup.LEGACY;
                    default -> true;
                })
                .filter(c -> !cli.quick() || QUICK_CASE_NAMES.contains(c.name()))
                .toList();
        List<Approach> approaches = resolveApproaches(cli.approach());

        List<String> models = resolveModels(client, cli.modelNames());
        if (models.isEmpty()) {
            System.err.println("No candidate models found/given. Pass model names explicitly, e.g.:");
            System.err.println("  java BenchmarkLocalModels.java llama3.2:1b qwen3.5:4b-mlx");
            System.err.println("  java BenchmarkLocalModels.java --backend=mlx");
            System.exit(1);
        }

        Map<Approach, String> systemPrompts = new EnumMap<>(Approach.class);
        Map<Approach, String> toolsJsonByApproach = new EnumMap<>(Approach.class);
        Map<Approach, String> promptSources = new EnumMap<>(Approach.class);
        for (Approach approach : approaches) {
            Path source = locateSource(approach.module, approach.serviceSource);
            String src = Files.readString(source, StandardCharsets.UTF_8);
            promptSources.put(approach, source.toString());
            switch (approach) {
                // 03 and 04 share one prompt shape: a systemPrompt(LocalDate) text block with the
                // resolved dates spliced in. Only 03 needs the "respond with JSON" tail, because only
                // 03 has the model return the filter instead of calling a tool with it.
                case STRUCTURED -> systemPrompts.put(approach,
                        formattedSystemPrompt(src, source, today) + STRUCTURED_OUTPUT_SHAPE_INSTRUCTION);
                case CONDITION_TOOL_CALLING -> {
                    systemPrompts.put(approach, formattedSystemPrompt(src, source, today));
                    toolsJsonByApproach.put(approach, buildSearchCustomersToolJson(src,
                            Files.readString(locateSource(approach.module, "filter/Condition.java"),
                                    StandardCharsets.UTF_8)));
                }
                // 02(a)/02(b): a constant SYSTEM_PROMPT plus a flat, per-field tool signature.
                default -> {
                    systemPrompts.put(approach, extractToolCallingSystemPrompt(src));
                    toolsJsonByApproach.put(approach, buildSearchCustomersToolJson(src, null));
                }
            }
            System.out.println("Extracted " + approach.label + " prompt/schema from: " + promptSources.get(approach));
        }

        System.out.println(backendLabel(client.backendName()) + " base URL: " + baseUrl);
        System.out.println("Models: " + String.join(", ", models));
        System.out.println("Approaches: " + approaches.stream().map(a -> a.label).collect(Collectors.joining(", ")));
        System.out.println("Runs per case: " + cli.runs());
        System.out.println("Cases: " + selectedCases.size() + " (" + cli.cases()
                + (cli.quick() ? ", --quick subset" : "") + ")");
        System.out.println();

        long wallClockStart = System.nanoTime();
        List<ModelApproachResult> results = new ArrayList<>();
        for (String model : models) {
            for (Approach approach : approaches) {
                List<EvalCase> casesForApproach = selectedCases.stream()
                        .filter(c -> c.approaches().contains(approach)).toList();
                if (casesForApproach.isEmpty()) continue;
                List<String> skipped = selectedCases.stream()
                        .filter(c -> !c.approaches().contains(approach)).map(EvalCase::name).toList();
                System.out.println("=== " + model + " [" + approach.label + "] === "
                        + casesForApproach.size() + " cases"
                        + (skipped.isEmpty() ? "" : ", not expressible by this approach: " + skipped));
                ModelApproachResult result = runModelApproach(client, model, approach, systemPrompts.get(approach),
                        toolsJsonByApproach.get(approach), casesForApproach, cli.runs());
                results.add(result);
                printSummaryLine(result);
            }
        }
        long wallClockMs = (System.nanoTime() - wallClockStart) / 1_000_000;

        printApproachSummary(results);
        printTable(results);
        printFieldAccuracy(results);
        printFailures(results);
        printFieldPrecisionExample(results);

        String timestamp = today.format(DateTimeFormatter.ISO_LOCAL_DATE) + "-"
                + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        Path mdPath = Path.of("benchmark-report-" + timestamp + ".md");
        Path txtPath = Path.of("benchmark-report-" + timestamp + ".txt");
        Files.writeString(mdPath,
                renderMarkdown(results, client.backendName(), baseUrl, cli, selectedCases.size(), wallClockMs),
                StandardCharsets.UTF_8);
        Files.writeString(txtPath, renderText(results, client.backendName(), baseUrl), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Reports written: " + mdPath.toAbsolutePath() + ", " + txtPath.toAbsolutePath());
        System.out.println("Wall clock: " + formatWallClock(wallClockMs) + " for " + selectedCases.size() + " cases"
                + (cli.quick() ? " (--quick)" : ""));

        if (cli.minPassRate() != null) {
            double worst = results.stream().filter(r -> r.fatalError() == null)
                    .mapToDouble(ModelApproachResult::meanPassRate).min().orElse(0);
            System.out.println();
            System.out.printf("--min-pass-rate=%.2f: worst observed mean pass rate = %.2f%n", cli.minPassRate(), worst);
            if (worst < cli.minPassRate()) {
                System.out.println("GATE FAILED: below threshold.");
                System.exit(1);
            }
            System.out.println("GATE PASSED.");
        }
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
        int runs = 1;
        String approach = "all";
        boolean quick = false;
        Double minPassRate = null;
        String cases = "all";
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
            } else if (arg.startsWith("--runs=")) {
                runs = Integer.parseInt(arg.substring("--runs=".length()));
                if (runs < 1) {
                    System.err.println("--runs must be >= 1");
                    System.exit(1);
                }
            } else if (arg.startsWith("--approach=")) {
                approach = arg.substring("--approach=".length());
            } else if (arg.startsWith("--cases=")) {
                cases = arg.substring("--cases=".length());
            } else if (arg.equals("--quick")) {
                quick = true;
            } else if (arg.startsWith("--min-pass-rate=")) {
                minPassRate = Double.parseDouble(arg.substring("--min-pass-rate=".length()));
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
        try {
            resolveApproaches(approach);
        } catch (IllegalStateException e) {
            System.err.println("Unknown --approach value: " + approach
                    + " (expected 'all' or a comma-separated list of 02a,02b,03,04)");
            System.exit(1);
        }
        if (!cases.equals("all") && !cases.equals("canonical") && !cases.equals("legacy")) {
            System.err.println("Unknown --cases value: " + cases + " (expected 'all', 'canonical' or 'legacy')");
            System.exit(1);
        }
        return new CliArgs(backend, baseUrlOverride, models, thinkDisabled, debugRaw, mode, runs, approach, quick,
                minPassRate, cases);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java BenchmarkLocalModels.java [options] [model1] [model2] ...

                Options:
                  --approach=all|02a,02b,03,04
                                         Which AI approach(es) to evaluate (default: all four).
                                           02a = 02-ai-agent-filter, one scalar value per field
                                           02b = 02-ai-agent-filter, value + operator + negate per field
                                           03  = 03-ai-structured-filter, CustomerFilter as structured output
                                           04  = 04-ai-hybrid-filter, the same condition list as a tool call
                                         Every approach's system prompt (and, for the tool-calling ones,
                                         its tool/argument schema) is extracted from that module's
                                         production source at runtime — never hard-coded — so the eval
                                         cannot drift from the apps.
                  --runs=N               Run every case N times and report a per-case pass-rate
                                         (passes/N) plus an aggregate mean, instead of a single
                                         pass/fail (default: 1, i.e. today's single-shot behavior).
                  --cases=all|canonical|legacy
                                         Which case group to run (default: all). 'canonical' is the
                                         eight queries of docs/canonical-query-set.md that all four
                                         approaches share; 'legacy' is the older prompt-regression set,
                                         which now also runs against all four approaches (many cases are
                                         architecturally out of reach for 02a/02b and score low/zero).
                  --quick                Evaluate only a small representative subset of the canonical
                                         query set (a single-value case, a multi-field AND case, and
                                         the two cases only the condition-list approaches can express)
                                         for a fast edit-loop check.
                  --min-pass-rate=<X>    Exit non-zero if any model/approach's aggregate mean pass
                                         rate falls below X (0..1); exit zero otherwise. Meant for
                                         scripting a regression gate on top of this eval.
                  --backend=ollama|mlx   Backend to benchmark against (default: ollama)
                  --base-url=<url>       Override the backend's base URL
                  --think=on|off         Disable Qwen3-style <think> reasoning by appending /no_think
                                         to the user query (MLX backend only; default: on/unchanged).
                                         Ollama already always sends "think":false, so this flag is a
                                         no-op there.
                  --debug-raw            Capture each call's full raw HTTP response body (before any
                                         parsing) and include it in the generated report as a
                                         "Raw responses" appendix, for failed cases only.
                  --mode=freeform|schema Structured approach only: freeform JSON completion (default)
                                         or schema-constrained output. In schema mode, Ollama gets the
                                         flat conditions-list JSON Schema via its native "format" field
                                         (grammar-constrained decoding); the MLX backend attempts the
                                         OpenAI-style "response_format":{"type":"json_schema",...}
                                         field, best-effort.
                  --help, -h             Show this help and exit

                Backend defaults:
                  ollama: http://localhost:11434 (override via OLLAMA_BASE_URL env var)
                  mlx:    http://localhost:8090  (override via MLX_BASE_URL env var)
                          mlx_lm.server serves one model per process — see README for setup.

                Examples:
                  java BenchmarkLocalModels.java
                  java BenchmarkLocalModels.java --approach=all --runs=5 llama3.1:8b
                  java BenchmarkLocalModels.java --cases=canonical --runs=3 qwen3:8b
                  java BenchmarkLocalModels.java --quick --runs=3 llama3.1:8b
                  java BenchmarkLocalModels.java --min-pass-rate=0.8 --runs=5 llama3.1:8b
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
     * gracefully, per model in {@link #benchmarkChat} instead of crashing discovery outright.
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
    // Structured-output approach: system prompt extracted from the real
    // CustomerSearchStructuredOutputService.java so the eval cannot drift from production behaviour.
    // ---------------------------------------------------------------------------------------------

    /**
     * The response-shape reminder that only the structured-output approach needs: 03 asks the model to
     * <em>return</em> the filter, and Spring AI appends an equivalent instruction plus the generated JSON
     * schema to the prompt at runtime. The tool-calling approaches get the shape from their tool schema
     * instead, so they must not receive this.
     */
    private static final String STRUCTURED_OUTPUT_SHAPE_INSTRUCTION =
            "\n\nRespond ONLY with a JSON object of this exact shape, nothing else:\n"
                    + "{\"conditions\": [ {\"field\":\"...\",\"operator\":\"...\",\"values\":[\"...\"],"
                    + "\"negate\":true|false}, ... ]}";

    /**
     * Extracts a {@code systemPrompt(LocalDate)} text block and resolves its relative dates against
     * {@code today}, exactly as the module does at runtime. Used for 03 and 04, whose prompts share this
     * shape (04's is 03's prompt with tool-call framing).
     */
    private static String formattedSystemPrompt(String src, Path source, LocalDate today) {
        Matcher m = Pattern.compile("return\\s*\"\"\"(.*?)\"\"\"\\s*\\.formatted", Pattern.DOTALL).matcher(src);
        if (!m.find()) {
            throw new IllegalStateException("Could not extract system prompt template from " + source);
        }
        String template = dedentTextBlock(m.group(1));

        LocalDate yesterday = today.minusDays(1);
        LocalDate thisWeekMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate lastWeekMonday = thisWeekMonday.minusWeeks(1);
        LocalDate lastMonthStart = today.withDayOfMonth(1).minusMonths(1);
        // Same argument order as the module's systemPrompt(...).formatted(...) call.
        return template.formatted(today, today, yesterday, today, today, today, thisWeekMonday,
                lastWeekMonday, today, lastMonthStart);
    }

    private static Path locateSource(String module, String fileRelativeToMainJava) {
        String relative = "dev/demo/vaadin/aigridfilter/ai/" + fileRelativeToMainJava;
        List<Path> candidates = List.of(
                Path.of("../" + module + "/src/main/java/" + relative),   // run from ollama-benchmark/
                Path.of(module + "/src/main/java/" + relative)            // run from repo root
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate " + fileRelativeToMainJava + " from cwd="
                + Path.of("").toAbsolutePath() + "; tried " + candidates);
    }

    // ---------------------------------------------------------------------------------------------
    // Tool-calling approaches: system prompt + searchCustomers tool/argument schema extracted from the
    // real service source of 02(a), 02(b) or 04 (the @Tool/@ToolParam descriptions are literally what the
    // model sees in production; the JSON-Schema *structure* per Java type is inherent Java-type ->
    // JSON-Schema plumbing, not "the prompt" — same spirit as the hand-rolled
    // FLAT_CONDITIONS_SCHEMA_JSON below for the structured approach's shape).
    // ---------------------------------------------------------------------------------------------

    private static String extractToolCallingSystemPrompt(String src) {
        Matcher m = Pattern.compile("SYSTEM_PROMPT\\s*=\\s*\"\"\"(.*?)\"\"\"", Pattern.DOTALL).matcher(src);
        if (!m.find()) {
            throw new IllegalStateException("Could not extract SYSTEM_PROMPT from the service source");
        }
        return dedentTextBlock(m.group(1));
    }

    /**
     * Reproduces the Java text-block processing the app's compiler applies to a {@code """..."""}
     * literal, so a prompt extracted verbatim from source matches the string the app actually sends.
     * Drops the line terminator right after the opening delimiter, then removes incidental leading
     * white space per line via {@link String#stripIndent()}. Without this the extracted prompt keeps
     * the source-code indentation (~16 spaces per line), which measurably degrades some models — e.g.
     * on {@code qwen3:8b} the indented structured prompt returned an empty filter for "creditworthy
     * customers" where the real (dedented) prompt returns the correct {@code creditRating=GOOD}.
     */
    private static String dedentTextBlock(String rawBlock) {
        return rawBlock.replaceFirst("^\\R", "").stripIndent();
    }

    private record ToolParamSpec(String name, String type, String description) {
    }

    /**
     * Builds the {@code tools} array for a tool-calling approach out of its real {@code searchCustomers}
     * signature: the {@code @Tool}/{@code @ToolParam} description texts are literally what the model sees
     * in production, and the JSON-Schema structure per Java type is the inherent Java-type -&gt;
     * JSON-Schema plumbing Spring AI performs (verified against the schema Spring AI generates for
     * {@code List<Condition>}).
     * <p>
     * {@code conditionSrc} is the source of {@code Condition.java} for the one approach whose parameter is
     * a {@code List<Condition>} (04); {@code null} for the per-field variants. The second tool,
     * {@code currentLocalDateTime}, is only offered if the service actually declares it — 02(a) and 02(b)
     * both do, 04 does not (03 has no tool schema at all — it is structured output).
     */
    private static String buildSearchCustomersToolJson(String src, String conditionSrc) {
        int methodIdx = src.indexOf("void searchCustomers(");
        if (methodIdx < 0) {
            throw new IllegalStateException("Could not locate searchCustomers(...) in the service source");
        }
        String toolDescription = extractPrecedingToolDescription(src, methodIdx);
        String paramsBlock = extractBalancedParens(src, methodIdx);
        List<ToolParamSpec> params = extractToolParams(paramsBlock, src);

        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            ToolParamSpec p = params.get(i);
            if (i > 0) {
                properties.append(",");
            }
            properties.append(jsonString(p.name())).append(":").append(jsonSchemaForParam(p, conditionSrc));
        }

        String searchCustomersFunction = """
                {"type":"function","function":{"name":"searchCustomers","description":%s,
                "parameters":{"type":"object","properties":{%s},"required":[]}}}
                """.formatted(jsonString(toolDescription), properties);
        if (!src.contains("LocalDateTime currentLocalDateTime()")) {
            return "[" + searchCustomersFunction.strip() + "]";
        }
        String currentDateTimeFunction = """
                {"type":"function","function":{"name":"currentLocalDateTime",
                "description":"Current date and time","parameters":{"type":"object","properties":{}}}}
                """;
        return "[" + searchCustomersFunction.strip() + "," + currentDateTimeFunction.strip() + "]";
    }

    private static String extractPrecedingToolDescription(String src, int methodIdx) {
        int toolIdx = src.lastIndexOf("@Tool(", methodIdx);
        if (toolIdx < 0) {
            throw new IllegalStateException("Could not locate @Tool(...) preceding searchCustomers(...)");
        }
        String segment = src.substring(toolIdx, methodIdx);
        Matcher m = Pattern.compile("description\\s*=\\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")",
                Pattern.DOTALL).matcher(segment);
        if (!m.find()) {
            throw new IllegalStateException("Could not extract @Tool description before searchCustomers(...)");
        }
        return (m.group(1) != null ? m.group(1) : unescapeJavaString(m.group(2))).strip();
    }

    /** Extracts the substring between the parenthesis following {@code src.indexOf(..., fromIdx)} and
     * its balanced matching close, tracking nested parens (each {@code @ToolParam(...)} call has its
     * own inner pair) so a stray '(' inside a description doesn't truncate the parameter list early. */
    private static String extractBalancedParens(String src, int fromIdx) {
        int open = src.indexOf('(', fromIdx);
        int depth = 0;
        int i = open;
        do {
            char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            i++;
        } while (depth > 0);
        return src.substring(open + 1, i - 1);
    }

    /**
     * Extracts every {@code @ToolParam}-annotated parameter of the signature: its description (a text
     * block, a plain literal, or the name of a {@code static final String} constant — 02(b) shares the
     * operator/negate descriptions across its 13 fields that way), its type and its name.
     */
    private static List<ToolParamSpec> extractToolParams(String paramsBlock, String src) {
        Pattern p = Pattern.compile(
                "@ToolParam\\(description\\s*=\\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\"|([A-Z][A-Z0-9_]*))\\)\\s*"
                        + "((?:List<[\\w.]+>)|[\\w.]+)\\s+(\\w+)",
                Pattern.DOTALL);
        Matcher m = p.matcher(paramsBlock);
        List<ToolParamSpec> params = new ArrayList<>();
        while (m.find()) {
            String desc;
            if (m.group(1) != null) {
                desc = m.group(1).strip();
            } else if (m.group(2) != null) {
                desc = unescapeJavaString(m.group(2));
            } else {
                desc = resolveStringConstant(src, m.group(3));
            }
            params.add(new ToolParamSpec(m.group(5), m.group(4), desc));
        }
        if (params.isEmpty()) {
            throw new IllegalStateException("Found searchCustomers(...) but no @ToolParam entries in it");
        }
        return params;
    }

    /** Resolves {@code private static final String NAME = "..."} (text block or literal) in the source. */
    private static String resolveStringConstant(String src, String name) {
        Matcher m = Pattern.compile("static\\s+final\\s+String\\s+" + Pattern.quote(name)
                + "\\s*=\\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")\\s*;", Pattern.DOTALL).matcher(src);
        if (!m.find()) {
            throw new IllegalStateException("Could not resolve @ToolParam description constant: " + name);
        }
        return m.group(1) != null ? dedentTextBlock(m.group(1)).strip() : unescapeJavaString(m.group(2));
    }

    private static String unescapeJavaString(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
    }

    /** The six {@code Operator} values, mirrored from the modules' enum (type plumbing, not prompt text). */
    private static final String OPERATOR_ENUM_JSON =
            "\"enum\":[\"CONTAINS\",\"EQUALS\",\"GREATER_OR_EQUAL\",\"LESS_OR_EQUAL\",\"STARTS_WITH\",\"ENDS_WITH\"]";

    /** Java type -&gt; JSON Schema for one tool parameter, with its description attached. */
    private static String jsonSchemaForParam(ToolParamSpec p, String conditionSrc) {
        String withoutDescription = switch (p.type()) {
            case "String", "LocalDate" -> "{\"type\":\"string\"";
            case "BigDecimal" -> "{\"type\":\"number\"";
            case "Boolean", "boolean" -> "{\"type\":\"boolean\"";
            case "Operator" -> "{\"type\":\"string\"," + OPERATOR_ENUM_JSON;
            case "CreditRating" -> "{\"type\":\"string\",\"enum\":[\"GOOD\",\"MEDIUM\",\"POOR\"]";
            case "List<String>", "List<LocalDate>" -> "{\"type\":\"array\",\"items\":{\"type\":\"string\"}";
            case "List<Condition>" -> "{\"type\":\"array\",\"items\":" + conditionItemSchema(conditionSrc);
            default -> throw new IllegalStateException("Unknown @ToolParam type: " + p.type());
        };
        return withoutDescription + ",\"description\":" + jsonString(p.description()) + "}";
    }

    /**
     * The {@code Condition} object schema, built from {@code Condition.java}'s own
     * {@code @JsonPropertyDescription}/{@code @JsonClassDescription} texts — the same annotations Spring
     * AI reads when it generates this approach's tool schema at runtime, so the eval sees what the app
     * sends rather than a hand-written copy of it.
     */
    private static String conditionItemSchema(String conditionSrc) {
        if (conditionSrc == null) {
            throw new IllegalStateException("A List<Condition> parameter needs Condition.java's source");
        }
        Map<String, String> descriptions = new LinkedHashMap<>();
        Matcher m = Pattern.compile("@JsonPropertyDescription\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)\\s*"
                + "(?:[\\w<>., ]+?)\\s+(\\w+)[,)]", Pattern.DOTALL).matcher(conditionSrc);
        while (m.find()) {
            descriptions.put(m.group(2), unescapeJavaString(m.group(1)));
        }
        for (String component : List.of("field", "operator", "values", "negate")) {
            if (!descriptions.containsKey(component)) {
                throw new IllegalStateException("Condition.java has no @JsonPropertyDescription for: " + component);
            }
        }
        Matcher classDescription = Pattern.compile("@JsonClassDescription\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)",
                Pattern.DOTALL).matcher(conditionSrc);
        String description = classDescription.find() ? unescapeJavaString(classDescription.group(1)) : "One condition";

        return "{\"type\":\"object\",\"description\":" + jsonString(description) + ","
                + "\"required\":[\"field\",\"operator\",\"values\",\"negate\"],\"additionalProperties\":false,"
                + "\"properties\":{"
                + "\"field\":{\"type\":\"string\",\"description\":" + jsonString(descriptions.get("field")) + "},"
                + "\"operator\":{\"type\":\"string\"," + OPERATOR_ENUM_JSON + ",\"description\":"
                + jsonString(descriptions.get("operator")) + "},"
                + "\"values\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":"
                + jsonString(descriptions.get("values")) + "},"
                + "\"negate\":{\"type\":\"boolean\",\"description\":" + jsonString(descriptions.get("negate")) + "}"
                + "}}";
    }

    // ---------------------------------------------------------------------------------------------
    // Running a single (model, approach) pair over every case, --runs times each
    // ---------------------------------------------------------------------------------------------

    private static ModelApproachResult runModelApproach(ApiClient client, String model, Approach approach,
            String systemPrompt, String toolsJson, List<EvalCase> cases, int runs) throws Exception {
        try {
            if (approach.isToolCalling()) {
                client.chatTools(model, systemPrompt, toolsJson, "warm up");
            } else {
                client.chat(model, systemPrompt, "warm up");
            }
        } catch (Exception e) {
            return new ModelApproachResult(model, approach, List.of(), Map.of(), null, null, 0, 0, 0, "n/a", null,
                    e.getMessage());
        }

        long heapBefore = usedHeapBytes();
        AtomicBoolean sampling = new AtomicBoolean(true);
        List<Double> cpuSamples = new ArrayList<>();
        Thread sampler = startCpuSampler(sampling, cpuSamples);

        List<CaseAggregate> aggregates = new ArrayList<>();
        Map<String, int[]> fieldTally = new LinkedHashMap<>(); // field -> [correct, total]
        Long ttft = null;

        for (EvalCase tc : cases) {
            List<Long> durations = new ArrayList<>();
            List<Double> tokRates = new ArrayList<>();
            int passes = 0;
            List<String> sampleErrors = new ArrayList<>();
            List<String> sampleRawBodies = new ArrayList<>();
            long totalDurationMs = 0;
            long totalPromptTokens = 0;
            long totalCompletionTokens = 0;
            for (int run = 0; run < runs; run++) {
                long t0 = System.nanoTime();
                try {
                    if (ttft == null) {
                        ttft = client.timeToFirstTokenMs(model, systemPrompt, tc.query());
                    }
                    List<Leaf> actual;
                    ChatResult chatResult;
                    if (approach.isToolCalling()) {
                        chatResult = client.chatTools(model, systemPrompt, toolsJson, tc.query());
                        actual = normalizeToolCallArgs(approach, chatResult.content());
                    } else {
                        chatResult = client.chat(model, systemPrompt, tc.query());
                        actual = normalizeStructuredResponse(chatResult.content());
                    }
                    long durationMs = (System.nanoTime() - t0) / 1_000_000;
                    durations.add(durationMs);
                    totalDurationMs += durationMs;
                    totalPromptTokens += chatResult.promptTokenCount();
                    totalCompletionTokens += chatResult.tokenCount();
                    if (chatResult.tokenDurationNs() > 0) {
                        tokRates.add(chatResult.tokenCount() / (chatResult.tokenDurationNs() / 1e9));
                    }
                    Map<String, Boolean> fieldCorrect = fieldCorrectness(actual, tc.expected());
                    boolean passed = fieldCorrect.values().stream().allMatch(Boolean::booleanValue);
                    if (passed) passes++;
                    fieldCorrect.forEach((field, correct) -> {
                        int[] tally = fieldTally.computeIfAbsent(field, f -> new int[2]);
                        tally[1]++;
                        if (correct) tally[0]++;
                    });
                    if (!passed) {
                        if (sampleErrors.size() < 2) {
                            sampleErrors.add("got: " + actual);
                        }
                        if (chatResult.rawBody() != null && sampleRawBodies.size() < 2) {
                            sampleRawBodies.add(chatResult.rawBody());
                        }
                    }
                } catch (Exception e) {
                    long durationMs = (System.nanoTime() - t0) / 1_000_000;
                    durations.add(durationMs);
                    totalDurationMs += durationMs;
                    if (sampleErrors.size() < 2) {
                        sampleErrors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    for (String field : CANONICAL_FIELDS) {
                        int[] tally = fieldTally.computeIfAbsent(field, f -> new int[2]);
                        tally[1]++;
                    }
                }
            }
            aggregates.add(new CaseAggregate(tc.name(), tc.query(), tc.group(), passes, runs, median(durations),
                    median(tokRates.stream().mapToDouble(Double::doubleValue).boxed().collect(Collectors.toList())),
                    sampleErrors, sampleRawBodies, totalDurationMs, totalPromptTokens, totalCompletionTokens));
        }

        sampling.set(false);
        sampler.join(2000);
        long heapAfter = usedHeapBytes();
        double avgCpu = cpuSamples.isEmpty() ? 0
                : cpuSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100;

        Map<String, Double> fieldAccuracy = new LinkedHashMap<>();
        fieldTally.forEach((field, tally) -> fieldAccuracy.put(field, tally[1] == 0 ? 0 : (double) tally[0] / tally[1]));

        return new ModelApproachResult(model, approach, aggregates, fieldAccuracy, client.modelSizeBytes(model),
                client.modelVramBytes(model), avgCpu, heapBefore, heapAfter, gpuInfo(), ttft, null);
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
    // Field-precise scoring (shared by both approaches once normalized to List<Leaf>): a case passes
    // for a run only if every expected field/parameter matches (value tolerant substring; field +
    // operator + negate exact where the approach exposes an operator) AND every field that must stay
    // unset is empty — so a model populating an unexpected field (e.g. a company-name query leaking
    // into "email") is scored as a failure for that case, not a pass.
    // ---------------------------------------------------------------------------------------------

    private static Map<String, Boolean> fieldCorrectness(List<Leaf> actual, List<Expectation> expected) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        Set<String> expectedFields = new LinkedHashSet<>();
        for (Expectation e : expected) {
            expectedFields.add(e.field().toLowerCase());
        }
        for (Expectation e : expected) {
            boolean ok = switch (e) {
                case TextExpectation te -> actual.stream().anyMatch(leaf -> matchesText(leaf, te));
                case NumericAtLeast na -> actual.stream().anyMatch(leaf -> matchesNumeric(leaf, na.field(),
                        "GREATER_OR_EQUAL", v -> v.compareTo(na.min()) >= 0));
                case NumericAtMost na -> actual.stream().anyMatch(leaf -> matchesNumeric(leaf, na.field(),
                        "LESS_OR_EQUAL", v -> v.compareTo(na.max()) <= 0));
                case NumericExact ne -> actual.stream().anyMatch(leaf -> matchesExactNumeric(leaf, ne.field(), ne.value()));
            };
            // If a field has multiple expectations (e.g. a revenue range's at-least AND at-most), the
            // field is only correct if ALL of them hold — merge with AND, not overwrite.
            result.merge(e.field().toLowerCase(), ok, (a, b) -> a && b);
        }
        for (String field : CANONICAL_FIELDS) {
            String key = field.toLowerCase();
            if (!expectedFields.contains(key)) {
                boolean stray = actual.stream().anyMatch(leaf -> leaf.field() != null && leaf.field().equalsIgnoreCase(field));
                result.put(key, !stray);
            }
        }
        return result;
    }

    private static boolean matchesText(Leaf leaf, TextExpectation e) {
        if (leaf.field() == null || !leaf.field().equalsIgnoreCase(e.field())) return false;
        if (!e.operators().isEmpty() && leaf.operator() != null) {
            boolean opOk = e.operators().stream().anyMatch(op -> op.equalsIgnoreCase(leaf.operator()));
            if (!opOk) return false;
        }
        if (leaf.value() == null) return false;
        if (e.substrings().isEmpty() || e.substrings().stream().anyMatch(String::isBlank)) {
            return true; // blank substring = "any non-null value" (relative-date placeholders)
        }
        String value = leaf.value().toLowerCase();
        return e.substrings().stream().anyMatch(s -> value.contains(s.toLowerCase()));
    }

    private static boolean matchesNumeric(Leaf leaf, String field, String requiredOperator,
            java.util.function.Predicate<BigDecimal> threshold) {
        if (leaf.field() == null || !leaf.field().equalsIgnoreCase(field)) return false;
        if (leaf.operator() != null && !leaf.operator().equalsIgnoreCase(requiredOperator)) return false;
        return parseNumeric(leaf.value()).filter(threshold).isPresent();
    }

    /** Strict counterpart of {@link #matchesNumeric}: the value must parse to the exact same number
     * as {@code expected} (currency/thousands-separator formatting tolerated by {@link #parseNumeric}),
     * instead of satisfying a threshold — so a required {@code annualRevenue} of {@code 1000} does not
     * match a returned {@code 1000000}. */
    private static boolean matchesExactNumeric(Leaf leaf, String field, BigDecimal expected) {
        if (leaf.field() == null || !leaf.field().equalsIgnoreCase(field)) return false;
        if (leaf.operator() != null && !leaf.operator().equalsIgnoreCase("EQUALS")) return false;
        return parseNumeric(leaf.value()).filter(v -> v.compareTo(expected) == 0).isPresent();
    }

    private static java.util.Optional<BigDecimal> parseNumeric(String value) {
        if (value == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(new BigDecimal(value.replaceAll("[^0-9.\\-]", "")));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Response normalization: both approaches' raw output collapses into the same List<Leaf>, so
    // scoring above is written once.
    // ---------------------------------------------------------------------------------------------

    /**
     * Tool calling: {@code content} is the JSON object of {@code searchCustomers}' arguments (already
     * parsed server-side by Ollama; for OpenAI-compatible backends it arrives as a JSON-encoded string and
     * is re-parsed by the client before reaching here). Each approach shapes those arguments differently,
     * so each gets its own reduction to the shared {@link Leaf} form the scoring works on.
     */
    private static List<Leaf> normalizeToolCallArgs(Approach approach, String content) {
        if (content == null || content.isBlank()) return List.of();
        Object parsed;
        try {
            parsed = Json.parse(content);
        } catch (Exception e) {
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> args)) return List.of();
        return switch (approach) {
            case FLAT_TOOL_CALLING -> flatLeaves(args);
            case OPERATOR_TOOL_CALLING -> operatorLeaves(args);
            case CONDITION_TOOL_CALLING -> conditionLeaves(args);
            case STRUCTURED -> throw new IllegalStateException("Structured output does not call a tool");
        };
    }

    /** 02(a): one scalar value per field, no operator and no negate flag anywhere. */
    private static List<Leaf> flatLeaves(Map<?, ?> args) {
        List<Leaf> leaves = new ArrayList<>();
        for (Map.Entry<?, ?> entry : args.entrySet()) {
            if (entry.getValue() == null) continue;
            String value = String.valueOf(entry.getValue());
            if (value.isBlank() || value.equals("null")) continue;
            leaves.add(new Leaf(String.valueOf(entry.getKey()), null, value));
        }
        return leaves;
    }

    /**
     * 02(b): three flat parameters per field — {@code city}, {@code cityOperator}, {@code cityNegate} —
     * which collapse into one leaf per field, with {@code negate} folded into a synthetic {@code NOT_}
     * operator prefix exactly as for the condition-list approaches. A parameter without its value
     * ({@code cityOperator} alone) contributes nothing, mirroring what the module itself does with it.
     */
    private static List<Leaf> operatorLeaves(Map<?, ?> args) {
        Map<String, String> byKey = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if (value != null) byKey.put(String.valueOf(key), String.valueOf(value));
        });
        List<Leaf> leaves = new ArrayList<>();
        for (Map.Entry<String, String> entry : byKey.entrySet()) {
            String field = entry.getKey();
            if (field.endsWith("Operator") || field.endsWith("Negate")) continue;
            String value = entry.getValue();
            if (value.isBlank() || value.equals("null")) continue;
            String operator = byKey.get(field + "Operator");
            if (operator != null && (operator.isBlank() || operator.equals("null"))) {
                operator = null;
            }
            boolean negate = "true".equalsIgnoreCase(byKey.getOrDefault(field + "Negate", "false"));
            if (negate) {
                operator = "NOT_" + (operator == null ? "CONTAINS" : operator);
            }
            leaves.add(new Leaf(field, operator, value));
        }
        return leaves;
    }

    /** 04: the single {@code conditions} parameter — the same payload 03 returns, so the same expansion. */
    @SuppressWarnings("unchecked")
    private static List<Leaf> conditionLeaves(Map<?, ?> args) {
        List<Leaf> leaves = new ArrayList<>();
        if (!(args.get("conditions") instanceof List<?> conditions)) return leaves;
        for (Object condition : conditions) {
            if (condition instanceof Map<?, ?> m) {
                expandCondition((Map<String, Object>) m, leaves);
            }
        }
        return leaves;
    }

    /** Structured output: port of {@code CustomerSearchAgentIT.flatten} — expands the {@code
     * conditions} array into one leaf per value, negation folded into a synthetic {@code NOT_}
     * operator prefix. Also accepts a bare top-level array for models that skip the wrapper key, so
     * non-compliance shows up as lower accuracy rather than being silently masked. */
    @SuppressWarnings("unchecked")
    private static List<Leaf> normalizeStructuredResponse(String content) {
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
        } else if (data instanceof List<?> list) {
            conditions = list;
        }
        List<Leaf> result = new ArrayList<>();
        if (conditions != null) {
            for (Object o : conditions) {
                if (o instanceof Map<?, ?> m) {
                    expandCondition((Map<String, Object>) m, result);
                }
            }
        }
        return result;
    }

    private static void expandCondition(Map<String, Object> condition, List<Leaf> into) {
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
            into.add(new Leaf(field == null ? null : String.valueOf(field), effectiveOperator, String.valueOf(value)));
        }
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
            HttpResponse<String> response = post(payload);
            Object parsed = Json.parse(response.body());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Unexpected /api/chat response shape: " + response.body());
            }
            String content = "";
            if (map.get("message") instanceof Map<?, ?> m && m.get("content") instanceof String s) {
                content = s;
            }
            return new ChatResult(content, asLong(map.get("eval_count")), asLong(map.get("prompt_eval_count")),
                    asLong(map.get("eval_duration")), debugRaw ? response.body() : null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ChatResult chatTools(String model, String systemPrompt, String toolsJson, String query)
                throws IOException, InterruptedException {
            String payload = """
                    {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                    "think":false,"stream":false,"tools":%s,
                    "options":{"temperature":0,"num_ctx":4096,"num_predict":512}}
                    """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(query), toolsJson);
            HttpResponse<String> response = post(payload);
            Object parsed = Json.parse(response.body());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Unexpected /api/chat response shape: " + response.body());
            }
            Map<?, ?> message = map.get("message") instanceof Map<?, ?> m ? m : Map.of();
            String argsJson = "{}";
            if (message.get("tool_calls") instanceof List<?> toolCalls) {
                for (Object call : toolCalls) {
                    if (call instanceof Map<?, ?> c && c.get("function") instanceof Map<?, ?> fn
                            && "searchCustomers".equals(fn.get("name")) && fn.get("arguments") != null) {
                        argsJson = toJsonString(fn.get("arguments"));
                        break;
                    }
                }
            }
            return new ChatResult(argsJson, asLong(map.get("eval_count")), asLong(map.get("prompt_eval_count")),
                    asLong(map.get("eval_duration")), debugRaw ? response.body() : null);
        }

        private HttpResponse<String> post(String payload) throws IOException, InterruptedException {
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
            return response;
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
            HttpResponse<String> response = post(payload);
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
            long promptTokenCount = 0;
            if (map.get("usage") instanceof Map<?, ?> usage) {
                tokenCount = asLong(usage.get("completion_tokens"));
                promptTokenCount = asLong(usage.get("prompt_tokens"));
            }
            return new ChatResult(content, tokenCount, promptTokenCount, 0, debugRaw ? response.body() : null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ChatResult chatTools(String model, String systemPrompt, String toolsJson, String query)
                throws IOException, InterruptedException {
            String payload = """
                    {"model":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}],
                    "temperature":0,"max_tokens":512,"stream":false,"tools":%s}
                    """.formatted(jsonString(model), jsonString(systemPrompt), jsonString(effectiveQuery(query)),
                    toolsJson);
            HttpResponse<String> response = post(payload);
            Object parsed = Json.parse(response.body());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Unexpected /v1/chat/completions response shape: " + response.body());
            }
            String argsJson = "{}";
            long tokenCount = 0;
            long promptTokenCount = 0;
            if (map.get("usage") instanceof Map<?, ?> usage) {
                tokenCount = asLong(usage.get("completion_tokens"));
                promptTokenCount = asLong(usage.get("prompt_tokens"));
            }
            if (map.get("choices") instanceof List<?> choices && !choices.isEmpty()
                    && choices.get(0) instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> msg
                    && msg.get("tool_calls") instanceof List<?> toolCalls) {
                for (Object call : toolCalls) {
                    if (call instanceof Map<?, ?> c && c.get("function") instanceof Map<?, ?> fn
                            && "searchCustomers".equals(fn.get("name")) && fn.get("arguments") instanceof String argsStr) {
                        // OpenAI-compatible protocol: unlike Ollama's native API, arguments arrive as a
                        // JSON-encoded STRING that needs a second parse, not an object directly.
                        Object argsParsed = Json.parse(argsStr);
                        argsJson = toJsonString(argsParsed);
                        break;
                    }
                }
            }
            return new ChatResult(argsJson, tokenCount, promptTokenCount, 0, debugRaw ? response.body() : null);
        }

        private HttpResponse<String> post(String payload) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl.replaceAll("/$", "") + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return response;
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

    /** Re-serializes a value already parsed by {@link Json#parse} back into a compact JSON string —
     * used to normalize tool-call arguments (an Object graph) into text for {@link ChatResult#content()},
     * so both approaches expose the same {@code String content} shape to the caller. */
    @SuppressWarnings("unchecked")
    private static String toJsonString(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return jsonString(s);
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(jsonString(String.valueOf(e.getKey()))).append(":").append(toJsonString(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJsonString(list.get(i)));
            }
            return sb.append("]").toString();
        }
        return jsonString(String.valueOf(value));
    }

    // ---------------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------------

    private static void printSummaryLine(ModelApproachResult r) {
        if (r.fatalError() != null) {
            System.out.println("  ERROR: " + r.fatalError());
            return;
        }
        System.out.printf("  mean pass rate %.0f%%, %s%n", r.meanPassRate() * 100, accuracySuffix(r));
    }

    private static String accuracySuffix(ModelApproachResult r) {
        double medianMs = median(r.cases().stream().map(c -> (long) c.medianDurationMs()).collect(Collectors.toList()));
        double tokS = median(r.cases().stream().map(CaseAggregate::medianTokS).collect(Collectors.toList()));
        return "median %.0f ms, %.1f tok/s".formatted(medianMs, tokS);
    }

    private static double median(List<? extends Number> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = values.stream().map(Number::doubleValue).sorted().collect(Collectors.toList());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** Shared cell values for one model/approach's row, reused by printTable/renderMarkdown/renderText. */
    private record RowCells(String model, String approach, String passRate, String medianLat, String ttft,
                             String tokS, String ram, String cpu, String modelSize) {
    }

    private static RowCells buildRow(ModelApproachResult r) {
        double medianMs = median(r.cases().stream().map(c -> (long) c.medianDurationMs()).collect(Collectors.toList()));
        double tokS = median(r.cases().stream().map(CaseAggregate::medianTokS).collect(Collectors.toList()));
        return new RowCells(
                r.model(),
                r.approach().label,
                "%.0f%%".formatted(r.meanPassRate() * 100),
                "%.0f ms".formatted(medianMs),
                r.ttftMs() != null ? r.ttftMs() + " ms" : "n/a",
                "%.1f".formatted(tokS),
                formatBytes(r.heapUsedAfterBytes() - r.heapUsedBeforeBytes()),
                "%.0f%%".formatted(r.avgCpuLoadPercent()),
                r.modelSizeBytes() != null ? formatBytes(r.modelSizeBytes()) : "n/a");
    }

    private static void printTable(List<ModelApproachResult> results) {
        System.out.println();
        System.out.printf("%-22s%-14s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                "Model", "Approach", "Pass rate", "Median Lat.", "TTFT", "tok/s", "RAM", "CPU", "Model Size");
        System.out.println("-".repeat(112));
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) {
                System.out.printf("%-22s%-14sERROR: %s%n", r.model(), r.approach().label, r.fatalError());
                continue;
            }
            RowCells row = buildRow(r);
            System.out.printf("%-22s%-14s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                    row.model(), row.approach(), row.passRate(), row.medianLat(), row.ttft(), row.tokS(), row.ram(),
                    row.cpu(), row.modelSize());
        }
    }

    /** Console counterpart of {@link #renderApproachSummary}. */
    private static void printApproachSummary(List<ModelApproachResult> results) {
        List<ModelApproachResult> usable = results.stream().filter(r -> r.fatalError() == null).toList();
        if (usable.isEmpty()) return;
        System.out.println();
        System.out.println("Approach performance summary (across all tested models):");
        System.out.println("  Pass columns count individual test runs as passed/performed: one run is one case "
                + "sent once");
        System.out.println("  to one model, so a group's run count is its cases times --runs, summed over every "
                + "tested model.");
        System.out.printf("  %-16s%-8s%-17s%-17s%-14s%-14s%-14s%-10s%n",
                "Approach", "Models", "Canonical", "Legacy", "Prompt tok", "Compl. tok", "Tokens", "Time");
        for (Approach approach : Approach.values()) {
            List<ModelApproachResult> forApproach = usable.stream().filter(r -> r.approach() == approach).toList();
            if (forApproach.isEmpty()) continue;
            int canonicalPassed = forApproach.stream().mapToInt(r -> r.passedRuns(CaseGroup.CANONICAL)).sum();
            int canonicalPerformed = forApproach.stream().mapToInt(r -> r.performedRuns(CaseGroup.CANONICAL)).sum();
            int legacyPassed = forApproach.stream().mapToInt(r -> r.passedRuns(CaseGroup.LEGACY)).sum();
            int legacyPerformed = forApproach.stream().mapToInt(r -> r.performedRuns(CaseGroup.LEGACY)).sum();
            long promptTokens = forApproach.stream().mapToLong(ModelApproachResult::totalPromptTokens).sum();
            long completionTokens = forApproach.stream().mapToLong(ModelApproachResult::totalCompletionTokens).sum();
            long totalMs = forApproach.stream().mapToLong(ModelApproachResult::totalDurationMs).sum();
            System.out.printf("  %-16s%-8d%-17s%-17s%-14d%-14d%-14d%-10s%n",
                    approach.label, forApproach.size(),
                    formatPassCount(canonicalPassed, canonicalPerformed),
                    formatPassCount(legacyPassed, legacyPerformed),
                    promptTokens, completionTokens, promptTokens + completionTokens, formatDuration(totalMs));
        }
    }

    /** Per-case pass-rate breakdown, one table per model/approach, canonical cases first. */
    private static void printPerCaseTable(StringBuilder sb, ModelApproachResult r) {
        sb.append("\n### ").append(r.model()).append(" [").append(r.approach().label).append("]\n\n");
        for (CaseGroup group : CaseGroup.values()) {
            List<CaseAggregate> cases = r.cases().stream().filter(c -> c.group() == group).toList();
            if (cases.isEmpty()) continue;
            sb.append("**").append(group == CaseGroup.CANONICAL ? "Canonical query set" : "Legacy set")
              .append("** — mean ").append("%.0f%%".formatted(r.meanPassRate(group) * 100)).append("\n\n");
            sb.append("| Case | Pass rate | Query |\n|---|---|---|\n");
            for (CaseAggregate c : cases) {
                sb.append("| ").append(c.name()).append(" | ").append(c.passRateLabel()).append(" | `")
                  .append(c.query()).append("` |\n");
            }
            sb.append("\n");
        }
    }

    private static void printFieldAccuracy(List<ModelApproachResult> results) {
        System.out.println();
        System.out.println("Per-field accuracy (annotation-tuning readout):");
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) continue;
            System.out.println("  " + r.model() + " [" + r.approach().label + "]:");
            r.fieldAccuracy().forEach((field, acc) -> System.out.printf("    %-16s%.0f%%%n", field, acc * 100));
        }
    }

    /** Demonstrates the field-precise scoring's ability to distinguish "right field set" from "wrong
     * field also set" using the {@code companyNameContains} case (a company-name query that could
     * plausibly leak into email/contactName) if present in the results. */
    private static void printFieldPrecisionExample(List<ModelApproachResult> results) {
        for (ModelApproachResult r : results) {
            r.cases().stream().filter(c -> c.name().equals("companyNameContains")).findFirst().ifPresent(c -> {
                System.out.println();
                System.out.println("Field-precision example (" + r.model() + " [" + r.approach().label + "], "
                        + "companyNameContains — 'customers whose company name contains data'):");
                System.out.printf("  companyName accuracy: %.0f%%, email accuracy: %.0f%%, contactName accuracy: %.0f%%%n",
                        r.fieldAccuracy().getOrDefault("companyname", 0.0) * 100,
                        r.fieldAccuracy().getOrDefault("email", 0.0) * 100,
                        r.fieldAccuracy().getOrDefault("contactname", 0.0) * 100);
                System.out.println("  (a run where the model populated email/contactName instead of/besides "
                        + "companyName is scored as a failure for that case, not a pass)");
            });
        }
    }

    private static void printFailures(List<ModelApproachResult> results) {
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) continue;
            List<CaseAggregate> failing = r.cases().stream().filter(c -> c.passes() < c.runs()).collect(Collectors.toList());
            if (failing.isEmpty()) continue;
            System.out.println();
            System.out.println("Cases with failures for " + r.model() + " [" + r.approach().label + "]:");
            for (CaseAggregate c : failing) {
                System.out.println("  " + c.passRateLabel() + "  " + c.name() + " — " + c.query());
                for (String err : c.sampleErrors()) {
                    System.out.println("        " + err);
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

    /**
     * A pass figure of the approach summary as {@code passed/performed (share)}. The counts are what make
     * the figure checkable — a bare percentage hides whether it rests on two runs or two hundred. Reads
     * {@code n/a} when the group ran no cases at all (e.g. under {@code --cases=canonical}), so an
     * untested group cannot be misread as a 0% failure.
     */
    private static String formatPassCount(int passedRuns, int performedRuns) {
        if (performedRuns == 0) return "n/a";
        return "%d/%d (%.0f%%)".formatted(passedRuns, performedRuns, 100.0 * passedRuns / performedRuns);
    }

    /**
     * The run's total wall clock as {@code 1h 05m 18s 773ms}, with the raw millisecond count kept in
     * parentheses. Unlike {@link #formatDuration}, which drops to the coarsest fitting unit for the
     * per-approach time column, this always spells out all four units, so the figure has the same shape
     * in every report and stays comparable at a glance across runs of very different length.
     */
    private static String formatWallClock(long ms) {
        long hours = ms / 3_600_000;
        long minutes = ms / 60_000 % 60;
        long seconds = ms / 1000 % 60;
        return "%dh %02dm %02ds %03dms (%d ms)".formatted(hours, minutes, seconds, ms % 1000, ms);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        double seconds = ms / 1000.0;
        if (seconds < 60) return "%.1f s".formatted(seconds);
        long totalSeconds = ms / 1000;
        return "%dm %02ds".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    /**
     * One row per {@link Approach}, aggregated across every model tested — the model-independent view of
     * "how expensive/reliable is this approach", complementing the per-model rows above (which mix models
     * of very different sizes/speeds). Token and time figures are true sums over every call of every case
     * and run actually executed for that approach (not medians), so they reflect the approach's real
     * cost — including that a 39-parameter tool schema (02(b)) costs far more prompt tokens per request
     * than a single-parameter one (04), independent of the model used.
     */
    private static void renderApproachSummary(StringBuilder sb, List<ModelApproachResult> results) {
        List<ModelApproachResult> usable = results.stream().filter(r -> r.fatalError() == null).toList();
        if (usable.isEmpty()) return;

        sb.append("\n## Approach performance summary (across all tested models)\n\n");
        sb.append("The pass columns read `passed/performed (share)` and count individual test runs: one run is ")
          .append("one case sent once to one model, so a group's run count is its case count times `--runs`, ")
          .append("summed over every tested model. A run counts as passed only if every field of the expected ")
          .append("filter matches — see the per-case tables below for which runs failed. A model that failed ")
          .append("outright (an `ERROR` row further down) performs no runs and is not counted under *Models ")
          .append("tested*, so both counts describe only what actually ran. Token and time totals are true ")
          .append("sums over every call made for that approach, across every model, case and run.\n\n");
        sb.append("| Approach | Models tested | Canonical passed | Legacy passed | ")
          .append("Prompt tokens (Σ) | Completion tokens (Σ) | Tokens (Σ) | Time (Σ) |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Approach approach : Approach.values()) {
            List<ModelApproachResult> forApproach = usable.stream().filter(r -> r.approach() == approach).toList();
            if (forApproach.isEmpty()) continue;
            int canonicalPassed = forApproach.stream().mapToInt(r -> r.passedRuns(CaseGroup.CANONICAL)).sum();
            int canonicalPerformed = forApproach.stream().mapToInt(r -> r.performedRuns(CaseGroup.CANONICAL)).sum();
            int legacyPassed = forApproach.stream().mapToInt(r -> r.passedRuns(CaseGroup.LEGACY)).sum();
            int legacyPerformed = forApproach.stream().mapToInt(r -> r.performedRuns(CaseGroup.LEGACY)).sum();
            long promptTokens = forApproach.stream().mapToLong(ModelApproachResult::totalPromptTokens).sum();
            long completionTokens = forApproach.stream().mapToLong(ModelApproachResult::totalCompletionTokens).sum();
            long totalMs = forApproach.stream().mapToLong(ModelApproachResult::totalDurationMs).sum();
            sb.append("| ").append(approach.label)
              .append(" | ").append(forApproach.size())
              .append(" | ").append(formatPassCount(canonicalPassed, canonicalPerformed))
              .append(" | ").append(formatPassCount(legacyPassed, legacyPerformed))
              .append(" | ").append(promptTokens)
              .append(" | ").append(completionTokens)
              .append(" | ").append(promptTokens + completionTokens)
              .append(" | ").append(formatDuration(totalMs))
              .append(" |\n");
        }
    }

    private static String renderMarkdown(List<ModelApproachResult> results, String backendName, String baseUrl,
            CliArgs cli, int caseCount, long wallClockMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Local Model Prompt-Reliability Eval\n\n");
        sb.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n\n");
        sb.append("Backend: ").append(backendLabel(backendName)).append(", Base URL: ").append(baseUrl)
          .append("\n\n");
        sb.append("Runs per case: ").append(cli.runs())
          .append(", Cases: ").append(caseCount).append(" (").append(cli.cases())
          .append(cli.quick() ? ", --quick" : "").append(")")
          .append(", Wall clock: ").append(formatWallClock(wallClockMs)).append("\n\n");
        if (cli.thinkDisabled()) {
            sb.append("Thinking: disabled (--think=off; /no_think appended to MLX queries)\n\n");
        }
        sb.append("Not a CI gate — for model comparison and prompt-tuning during development. ")
          .append("The canonical cases are the eight queries of `docs/canonical-query-set.md`, the same ")
          .append("ones all four modules' canonical-query ITs run, so these figures line up ")
          .append("query-for-query with those suites' pass/fail results; the legacy cases mirror ")
          .append("`03-ai-structured-filter`'s `CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT` and ")
          .append("run against all four approaches — several of them need negation, a second value for ")
          .append("one field, or a range, which 02(a)/02(b) cannot express at all, so those show up as a ")
          .append("low or zero pass rate for those approaches rather than as \"n/a\".\n\n");

        renderApproachSummary(sb, results);

        sb.append("\n| Model | Approach | Pass rate | Median Latency | TTFT | Tokens/s | RAM (JVM) | CPU | Model Size |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) {
                sb.append("| ").append(r.model()).append(" | ").append(r.approach().label)
                  .append(" | ERROR: ").append(r.fatalError()).append(" | | | | | | |\n");
                continue;
            }
            RowCells row = buildRow(r);
            sb.append("| ").append(row.model())
              .append(" | ").append(row.approach())
              .append(" | ").append(row.passRate())
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

        renderCanonicalMatrix(sb, results);
        renderLegacyMatrix(sb, results);

        sb.append("\n## Per-case pass rate\n");
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) continue;
            printPerCaseTable(sb, r);
        }

        sb.append("\n## Per-field accuracy\n\n");
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) continue;
            sb.append("\n### ").append(r.model()).append(" [").append(r.approach().label).append("]\n\n");
            sb.append("| Field | Accuracy |\n|---|---|\n");
            r.fieldAccuracy().forEach((field, acc) -> sb.append("| ").append(field).append(" | ")
                    .append("%.0f%%".formatted(acc * 100)).append(" |\n"));
        }

        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) continue;
            List<CaseAggregate> failing = r.cases().stream().filter(c -> c.passes() < c.runs()).collect(Collectors.toList());
            if (failing.isEmpty()) continue;
            sb.append("\n## Cases with failures: ").append(r.model()).append(" [").append(r.approach().label).append("]\n\n");
            for (CaseAggregate c : failing) {
                sb.append("- **").append(c.name()).append("** (").append(c.passRateLabel()).append(") — ")
                  .append(c.query()).append("\n");
                for (String err : c.sampleErrors()) {
                    sb.append("  - `").append(err).append("`\n");
                }
            }
        }

        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) continue;
            List<CaseAggregate> withRaw = r.cases().stream().filter(c -> !c.sampleRawBodies().isEmpty())
                    .collect(Collectors.toList());
            if (withRaw.isEmpty()) continue;
            sb.append("\n## Raw responses (--debug-raw): ").append(r.model()).append(" [")
              .append(r.approach().label).append("]\n\n");
            sb.append("Full unprocessed HTTP response body for each failed run, before any parsing.\n\n");
            for (CaseAggregate c : withRaw) {
                for (int i = 0; i < c.sampleRawBodies().size(); i++) {
                    sb.append("### ").append(c.name()).append(" — ").append(c.query())
                      .append(" (failed run ").append(i + 1).append(")\n\n");
                    sb.append("```\n").append(truncateForReport(c.sampleRawBodies().get(i))).append("\n```\n\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * The canonical query set as a matrix: one row per query, one column per approach, for every model —
     * the view the repository's documentation is built on. It makes two different things legible side by
     * side: where an approach's filter type cannot express a query at all ({@code n/a}, an architectural
     * limit) and where it can but the model gets it wrong (a pass rate below the run count, a reliability
     * limit). The per-approach sections elsewhere in this report never put the same query side by side.
     */
    private static void renderCanonicalMatrix(StringBuilder sb, List<ModelApproachResult> results) {
        renderCaseMatrix(sb, results, CaseGroup.CANONICAL, "Canonical query set",
                "The eight queries of `docs/canonical-query-set.md`, run against every selected "
                        + "approach. `n/a` = the approach's filter type cannot express that query at all, so it "
                        + "is not run (the modules' canonical-query ITs assert those expected failures against "
                        + "the resulting customer set instead).\n\n"
                        + "Two things this harness measures differently from those ITs, both worth knowing before "
                        + "reading a cell as a verdict on the approach:\n\n"
                        + "- **Single round trip.** It reads the first `searchCustomers` tool call and does not "
                        + "execute chained tool calls, so `02b-operator` cannot pass `C7_RELATIVE_DATE` here: that "
                        + "query needs its `currentLocalDateTime()` hop first. Its canonical-query IT, which runs "
                        + "the real Spring AI tool loop, does pass it.\n"
                        + "- **Field-precise scoring.** A case fails if the model populates a field the query did "
                        + "not ask for, even when the extra condition does not change the resulting rows (e.g. "
                        + "adding `country=Germany` to \"creditworthy customers in Hamburg\"). The ITs score the "
                        + "customer set, so they accept that. Neither view is wrong — this one is stricter about "
                        + "the filter, the ITs are stricter about the answer.\n");
    }

    /**
     * The legacy set as the same kind of matrix as {@link #renderCanonicalMatrix}. Unlike the canonical
     * set, a legacy case is not pre-classified as architecturally out of reach for a given approach — every
     * case runs against all four, so a {@code 0/N} for 02(a)/02(b) on e.g. a negation or multi-value-OR
     * case means "cannot express this at all", not "unreliable"; read those cells with that in mind.
     */
    private static void renderLegacyMatrix(StringBuilder sb, List<ModelApproachResult> results) {
        renderCaseMatrix(sb, results, CaseGroup.LEGACY, "Legacy set",
                "The older prompt-regression cases mirroring `03-ai-structured-filter`'s "
                        + "`CustomerSearchAgentIT`/`CustomerSearchAgentExtraIT`, run against every approach. "
                        + "Unlike the canonical matrix above, there is no `n/a` here: every case actually runs "
                        + "against every approach, so a low or zero pass rate for `02a-flat`/`02b-operator` on a "
                        + "case needing negation, a second value for one field, or a range reflects an "
                        + "architectural ceiling, not unreliability — these approaches were never designed to "
                        + "express that case at all.\n");
    }

    /** Shared rendering for {@link #renderCanonicalMatrix} and {@link #renderLegacyMatrix}: one row per
     * case, one column per approach, for every model, plus mean-pass-rate/median-latency/median-tok/s
     * summary rows. */
    private static void renderCaseMatrix(StringBuilder sb, List<ModelApproachResult> results, CaseGroup group,
            String heading, String intro) {
        List<ModelApproachResult> groupResults = results.stream()
                .filter(r -> r.fatalError() == null)
                .filter(r -> r.cases().stream().anyMatch(c -> c.group() == group))
                .toList();
        if (groupResults.isEmpty()) return;

        sb.append("\n## ").append(heading).append("\n\n").append(intro);

        for (String model : groupResults.stream().map(ModelApproachResult::model).distinct().toList()) {
            List<ModelApproachResult> perApproach = groupResults.stream()
                    .filter(r -> r.model().equals(model)).toList();
            if (perApproach.isEmpty()) continue;

            List<String> caseNames = new ArrayList<>();
            for (ModelApproachResult r : perApproach) {
                for (CaseAggregate c : r.cases()) {
                    if (c.group() == group && !caseNames.contains(c.name())) {
                        caseNames.add(c.name());
                    }
                }
            }

            sb.append("\n### ").append(model).append("\n\n| Case |");
            for (ModelApproachResult r : perApproach) {
                sb.append(" ").append(r.approach().label).append(" |");
            }
            sb.append("\n|---|").append("---|".repeat(perApproach.size())).append("\n");
            for (String caseName : caseNames) {
                sb.append("| ").append(caseName).append(" |");
                for (ModelApproachResult r : perApproach) {
                    sb.append(" ").append(r.cases().stream().filter(c -> c.name().equals(caseName))
                            .findFirst().map(CaseAggregate::passRateLabel).orElse("n/a")).append(" |");
                }
                sb.append("\n");
            }
            sb.append("| **Mean (expressible cases)** |");
            for (ModelApproachResult r : perApproach) {
                sb.append(" **").append("%.0f%%".formatted(r.meanPassRate(group) * 100))
                  .append("** |");
            }
            sb.append("\n| **Median latency** |");
            for (ModelApproachResult r : perApproach) {
                sb.append(" ").append("%.0f ms".formatted(median(r.cases().stream()
                        .filter(c -> c.group() == group)
                        .map(c -> (long) c.medianDurationMs()).collect(Collectors.toList())))).append(" |");
            }
            sb.append("\n| **Median tokens/s** |");
            for (ModelApproachResult r : perApproach) {
                sb.append(" ").append("%.1f".formatted(median(r.cases().stream()
                        .filter(c -> c.group() == group)
                        .map(CaseAggregate::medianTokS).collect(Collectors.toList())))).append(" |");
            }
            sb.append("\n");
        }
    }

    private static ModelApproachResult pick(List<ModelApproachResult> results, String model, Approach approach) {
        return results.stream()
                .filter(r -> r.model().equals(model) && r.approach() == approach && r.fatalError() == null)
                .findFirst().orElse(null);
    }

    private static final int RAW_BODY_REPORT_CAP = 4000;

    private static String truncateForReport(String rawBody) {
        if (rawBody.length() <= RAW_BODY_REPORT_CAP) {
            return rawBody;
        }
        return rawBody.substring(0, RAW_BODY_REPORT_CAP)
                + "\n...[truncated, " + rawBody.length() + " bytes total]";
    }

    private static String renderText(List<ModelApproachResult> results, String backendName, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Backend: ").append(backendLabel(backendName)).append(", Base URL: ").append(baseUrl)
          .append("\n\n");
        sb.append(String.format("%-22s%-14s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                "Model", "Approach", "Pass rate", "Median Lat.", "TTFT", "tok/s", "RAM", "CPU", "Model Size"));
        sb.append("-".repeat(112)).append("\n");
        for (ModelApproachResult r : results) {
            if (r.fatalError() != null) {
                sb.append(String.format("%-22s%-14sERROR: %s%n", r.model(), r.approach().label, r.fatalError()));
                continue;
            }
            RowCells row = buildRow(r);
            sb.append(String.format("%-22s%-14s%-12s%-14s%-10s%-10s%-10s%-8s%-12s%n",
                    row.model(), row.approach(), row.passRate(), row.medianLat(), row.ttft(), row.tokS(), row.ram(),
                    row.cpu(), row.modelSize()));
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
