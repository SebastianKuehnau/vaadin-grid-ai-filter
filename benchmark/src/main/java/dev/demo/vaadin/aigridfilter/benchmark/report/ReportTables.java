package dev.demo.vaadin.aigridfilter.benchmark.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the four tables every compact report shows, and the number formatting they share. */
public final class ReportTables {

    private ReportTables() {
    }

    /** Which models answered, and how much memory each of them occupies in Ollama. */
    public static Table models(BenchmarkReport report) {
        List<List<String>> rows = report.models().stream()
                .map(model -> List.of(model.model(), bytes(model.sizeBytes()), bytes(model.vramBytes())))
                .toList();
        return new Table("Models", List.of("Model", "Resident size", "of that in VRAM"), rows);
    }

    /** Correctness per model and approach: how often the returned customer set was right. */
    public static Table correctness(BenchmarkReport report) {
        List<List<String>> rows = new ArrayList<>();
        for (BenchmarkReport.ApproachSummary summary : report.summaries()) {
            rows.add(List.of(summary.model(), summary.approachId(),
                    String.valueOf(summary.casesExecuted()),
                    summary.casesSkipped() == 0 ? "-" : String.valueOf(summary.casesSkipped()),
                    summary.passed() + "/" + summary.executions()
                            + " (" + percent(summary.passRate()) + ")",
                    percent(summary.meanPrecision()),
                    percent(summary.meanRecall()),
                    zeroAsDash(summary.timedOut()),
                    zeroAsDash(summary.errors()),
                    summary.fatalError() == null ? "-" : summary.fatalError()));
        }
        return new Table("Correctness",
                List.of("Model", "Approach", "Cases", "Skipped", "Passed", "Precision", "Recall",
                        "Timeouts", "Errors", "Fatal"),
                rows);
    }

    /** Speed and token cost per model and approach. */
    public static Table speed(BenchmarkReport report) {
        List<List<String>> rows = new ArrayList<>();
        for (BenchmarkReport.ApproachSummary summary : report.summaries()) {
            rows.add(List.of(summary.model(), summary.approachId(),
                    millis(summary.p50TimeToValidResultMs()),
                    millis(summary.p95TimeToValidResultMs()),
                    millis(summary.p50LatencyMs()),
                    millis(summary.p95LatencyMs()),
                    summary.p50TimeToFirstToolMs() == null
                            ? "n/a" : millis(summary.p50TimeToFirstToolMs()),
                    rounded(summary.meanPromptTokens()),
                    rounded(summary.meanCompletionTokens()),
                    summary.meanTokensPerSecond() == null
                            ? "n/a" : oneDecimal(summary.meanTokensPerSecond())));
        }
        return new Table("Speed and tokens",
                List.of("Model", "Approach", "Time-to-valid p50", "Time-to-valid p95",
                        "LLM latency p50", "LLM latency p95", "Time-to-first-tool p50",
                        "Prompt tokens", "Output tokens", "Tokens/s"),
                rows);
    }

    /** Every case against every measured column: passes out of runs, or "-" where it was skipped. */
    public static Table caseMatrix(BenchmarkReport report) {
        List<String> headers = new ArrayList<>(List.of("Case", "Group", "Query"));
        report.columns().forEach(column -> headers.add(column.header()));

        List<List<String>> rows = new ArrayList<>();
        for (BenchmarkReport.CaseRow caseRow : report.caseMatrix()) {
            List<String> row = new ArrayList<>();
            row.add(caseRow.caseId() + (caseRow.knownFailure() ? " *" : ""));
            row.add(caseRow.group().equals("CANONICAL") ? "canonical" : "robustness");
            row.add(caseRow.query());
            row.addAll(caseRow.cells());
            rows.add(row);
        }
        return new Table("Case matrix", headers, rows);
    }

    public static String percent(double share) {
        return oneDecimal(share * 100) + " %";
    }

    public static String millis(long value) {
        return value + " ms";
    }

    public static String rounded(double value) {
        return String.valueOf(Math.round(value));
    }

    public static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Gigabytes, because every model measured here is counted in them; 0 VRAM means CPU-only. */
    public static String bytes(Long value) {
        if (value == null) {
            return "n/a";
        }
        return value == 0 ? "none (CPU only)"
                : String.format(Locale.ROOT, "%.2f GB", value / 1_073_741_824.0);
    }

    private static String zeroAsDash(int value) {
        return value == 0 ? "-" : String.valueOf(value);
    }
}
