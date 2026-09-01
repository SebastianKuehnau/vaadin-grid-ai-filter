package dev.demo.vaadin.aigridfilter.benchmark.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The settings table and the footnotes — what a reader needs to know what a number means. */
public final class ReportNotes {

    private ReportNotes() {
    }

    public static Table configuration(BenchmarkReport report) {
        BenchmarkReport.Configuration configuration = report.configuration();
        List<List<String>> rows = List.of(
                List.of("Ollama", configuration.ollamaVersion() == null
                        ? configuration.ollamaBaseUrl()
                        : configuration.ollamaVersion() + " at " + configuration.ollamaBaseUrl()),
                List.of("Models", String.join(", ", configuration.models())),
                List.of("Approaches", String.join(", ", configuration.approaches())),
                List.of("Cases", configuration.cases().size() + " ("
                        + String.join(", ", configuration.cases()) + ")"),
                List.of("Runs per case", String.valueOf(configuration.runs())),
                List.of("Warm-up call", configuration.warmup() ? "yes" : "no"),
                List.of("Unsupported cases", configuration.runUnsupported() ? "measured" : "skipped"),
                List.of("Query timeout", configuration.queryTimeoutSeconds() + " s"),
                List.of("Chat options", ("temperature=%s, num-ctx=%d, num-predict=%d, think=%s, "
                        + "keep-alive=%s").formatted(configuration.temperature(),
                        configuration.numCtx(), configuration.numPredict(),
                        configuration.think(), configuration.keepAlive())),
                List.of("Duration", report.durationSeconds() / 60 + " min "
                        + report.durationSeconds() % 60 + " s"));
        return new Table("Configuration", List.of("Setting", "Value"), rows);
    }

    public static Table skipped(BenchmarkReport report) {
        List<List<String>> rows = report.skipped().stream()
                .map(skipped -> List.of(skipped.approachId(), skipped.caseId(), skipped.reason()))
                .toList();
        return new Table("Skipped cases", List.of("Approach", "Case", "Why it cannot be expressed"), rows);
    }

    /** One line each, plain text; the renderers only add their own bullet syntax. */
    public static List<String> footnotes(BenchmarkReport report) {
        List<String> notes = new ArrayList<>();
        notes.add("Passed counts an execution as correct when the returned customer set contains "
                + "everything expected and nothing else; precision and recall are averaged over the "
                + "same executions.");
        notes.add("Time-to-valid-result is the whole resolveFilter call plus the database query - what "
                + "a user waits for. LLM latency is a single model call; a tool approach makes one call "
                + "per tool round trip, so a query can hold several.");
        notes.add("Time-to-first-tool is the first model call's duration - the point at which the model "
                + "has emitted its tool call. 03 returns structured output and calls no tool, so it has "
                + "no value. Where a model answered without calling a tool at all (small talk, say), "
                + "the value is simply that one call's duration.");
        notes.add("Tokens/s comes from Ollama's own eval-count over eval-duration - generation only, "
                + "without prompt evaluation or transport.");
        notes.add("Skipped cases are architecturally inexpressible for that approach, not failures. "
                + "Measure them anyway with benchmark.run-unsupported=true.");
        notes.add("R8, marked with an asterisk in the case matrix, is the prompt-injection case and is "
                + "@Disabled in all four IT classes. It is measured here on purpose: its cell says how "
                + "often the filter intent held against the injection - neither a pass nor a failure "
                + "there is a regression.");
        notes.add("A single green run proves nothing here - even at temperature 0 Ollama reuses a "
                + "cached prefix whose state depends on what ran before. Compare pass counts, not "
                + "single cells.");
        notes.add(heapNote(report));
        return notes;
    }

    /** The JVM memory footnote: the worker process, never the model's own memory. */
    private static String heapNote(BenchmarkReport report) {
        String perColumn = report.summaries().stream()
                .map(summary -> "%s/%s %s".formatted(summary.model(), summary.approachId(),
                        megabytes(summary.peakHeapBytes())))
                .reduce((a, b) -> a + ", " + b).orElse("none");
        return "Worker JVM peak heap (a footnote - the model's memory is in the Models table): "
                + perColumn + ".";
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1_048_576.0);
    }
}
