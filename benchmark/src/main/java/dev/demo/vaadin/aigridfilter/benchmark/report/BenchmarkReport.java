package dev.demo.vaadin.aigridfilter.benchmark.report;

import dev.demo.vaadin.aigridfilter.benchmark.run.WorkerResult;

import java.util.List;

/**
 * The finished measurement, in the shape the four renderers read.
 *
 * <p>{@code rawResults} is what the JSON report carries and the compact formats leave out: every
 * single execution, so a run can be re-analysed later without measuring again.
 */
public record BenchmarkReport(String startedAt, String finishedAt, long durationSeconds,
                              Configuration configuration, List<ModelInfo> models,
                              List<Column> columns, List<ApproachSummary> summaries,
                              List<CaseRow> caseMatrix, List<SkippedCase> skipped,
                              List<WorkerResult> rawResults) {

    /** What the run was configured with — so a report explains itself without its yaml. */
    public record Configuration(String ollamaBaseUrl, String ollamaVersion, List<String> models,
                                List<String> approaches, List<String> cases, int runs, boolean warmup,
                                boolean runUnsupported, int queryTimeoutSeconds, double temperature,
                                int numCtx, int numPredict, boolean think, String keepAlive) {
    }

    /** A model's resident size in Ollama, read from {@code /api/ps} after the warm-up. */
    public record ModelInfo(String model, Long sizeBytes, Long vramBytes) {
    }

    /** One column of the case matrix: an approach measured against a model. */
    public record Column(String model, String approachId) {

        public String header() {
            return model + " / " + approachId;
        }
    }

    /**
     * One model against one approach, aggregated over every case and run.
     *
     * @param passRate                 share of executions whose customer set was a correct answer
     * @param p50TimeToValidResultMs   median of the full {@code resolveFilter} + query duration
     * @param p50LatencyMs             median of the individual model calls
     * @param p50TimeToFirstToolMs     tool approaches only; {@code null} for 03
     * @param meanTokensPerSecond      generation rate from Ollama's own counters
     * @param peakHeapBytes            the worker JVM's peak heap — the footnote, not the headline
     */
    public record ApproachSummary(String model, String approachId, String approachLabel,
                                  int casesExecuted, int casesSkipped, int executions, int passed,
                                  int failed, int timedOut, int errors, double passRate,
                                  double meanPrecision, double meanRecall,
                                  long p50TimeToValidResultMs, long p95TimeToValidResultMs,
                                  long p50LatencyMs, long p95LatencyMs, Long p50TimeToFirstToolMs,
                                  double meanPromptTokens, double meanCompletionTokens,
                                  Double meanTokensPerSecond, long peakHeapBytes, String fatalError) {
    }

    /** One case across all columns; a cell is "2/3" passes, or "-" where the case was skipped. */
    public record CaseRow(String caseId, String group, String query, String itTestMethod,
                          boolean knownFailure, List<String> cells) {
    }

    /** A case an approach cannot express, with the reason from its IT class. */
    public record SkippedCase(String approachId, String caseId, String reason) {
    }
}
