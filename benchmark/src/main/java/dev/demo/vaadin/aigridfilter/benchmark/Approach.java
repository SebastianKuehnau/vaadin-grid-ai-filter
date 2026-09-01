package dev.demo.vaadin.aigridfilter.benchmark;

import java.util.Arrays;
import java.util.Map;

/**
 * The four filtering approaches under measurement — one per module, with 02 contributing two variants.
 * The unsupported cases are the {@code @Disabled} cases of that variant's IT class, verbatim from
 * {@code docs/canonical-query-set.md}: they are architecturally inexpressible, not unreliable.
 */
public enum Approach {

    FLAT_02A("02a", "02(a) tool calling, one scalar per field", "02-ai-agent-filter",
            "flatSearchAgent", true,
            Map.of(
                    "C2", "02(a) holds one value per field - 'Berlin or Hamburg' needs two",
                    "C3", "02(a) has no negate flag",
                    "C4", "02(a) has no start operator",
                    "C6", "02(a) holds one value per field - a range needs a lower and an upper bound",
                    "C7", "02(a) has no operator - a date can only be matched exactly, not as 'on or after'",
                    "C8", "02(a) holds one value per field - a date range needs two bounds",
                    "C10", "02(a)'s annualRevenue is a minimum - an upper bound cannot be expressed")),

    OPERATOR_02B("02b", "02(b) tool calling, value + operator + negate per field", "02-ai-agent-filter",
            "operatorSearchAgent", true,
            Map.of(
                    "C2", "02(b) holds one value per field - 'Berlin or Hamburg' needs two",
                    "C6", "02(b) holds one value and one operator per field - a range needs two bounds",
                    "C8", "02(b) holds one value and one operator per field - a date range needs two bounds")),

    STRUCTURED_03("03", "03 structured output (CustomerFilter)", "03-ai-structured-filter",
            null, false, Map.of()),

    HYBRID_04("04", "04 tool calling with a condition list", "04-ai-hybrid-filter",
            null, true, Map.of());

    private final String id;
    private final String label;
    private final String moduleDirectory;
    private final String beanName;
    private final boolean toolBased;
    private final Map<String, String> unsupportedCases;

    Approach(String id, String label, String moduleDirectory, String beanName, boolean toolBased,
             Map<String, String> unsupportedCases) {
        this.id = id;
        this.label = label;
        this.moduleDirectory = moduleDirectory;
        this.beanName = beanName;
        this.toolBased = toolBased;
        this.unsupportedCases = unsupportedCases;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The module whose compiled classes the worker JVM gets on its classpath. */
    public String moduleDirectory() {
        return moduleDirectory;
    }

    /** The bean to ask for, where a module holds more than one agent; {@code null} means "the only one". */
    public String beanName() {
        return beanName;
    }

    /** Tool calling, so time-to-first-tool-call is observable; false for 03's structured output. */
    public boolean toolBased() {
        return toolBased;
    }

    /** Why this approach cannot express a case, keyed by case id; empty for 03 and 04. */
    public Map<String, String> unsupportedCases() {
        return unsupportedCases;
    }

    public static Approach byId(String id) {
        return Arrays.stream(values())
                .filter(approach -> normalized(approach.id).equals(normalized(id)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown approach '" + id + "'; known ids: " + ids()));
    }

    /** Drops the leading zero, so an unquoted yaml list that turned "03" into 3 still resolves. */
    private static String normalized(String id) {
        String trimmed = id.trim().toLowerCase();
        return trimmed.startsWith("0") ? trimmed.substring(1) : trimmed;
    }

    public static String ids() {
        return Arrays.stream(values()).map(Approach::id).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
