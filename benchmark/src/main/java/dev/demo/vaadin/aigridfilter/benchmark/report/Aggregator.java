package dev.demo.vaadin.aigridfilter.benchmark.report;

import dev.demo.vaadin.aigridfilter.benchmark.Approach;
import dev.demo.vaadin.aigridfilter.benchmark.cases.BenchmarkCase;
import dev.demo.vaadin.aigridfilter.benchmark.cases.CaseCatalog;
import dev.demo.vaadin.aigridfilter.benchmark.run.Measurement;
import dev.demo.vaadin.aigridfilter.benchmark.run.WorkerResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.DoubleStream;

/** Turns the workers' raw executions into the summary tables and the case matrix. */
public final class Aggregator {

    private Aggregator() {
    }

    public static BenchmarkReport aggregate(BenchmarkReport.Configuration configuration,
                                            List<WorkerResult> results, List<String> caseIds,
                                            Instant startedAt, Instant finishedAt) {
        List<BenchmarkReport.Column> columns = results.stream()
                .map(result -> new BenchmarkReport.Column(result.model(), result.approachId()))
                .toList();

        return new BenchmarkReport(startedAt.toString(), finishedAt.toString(),
                Duration.between(startedAt, finishedAt).toSeconds(),
                configuration, models(results), columns,
                results.stream().map(result -> summarize(result, caseIds)).toList(),
                caseMatrix(results, caseIds), skipped(results, caseIds), results);
    }

    private static List<BenchmarkReport.ModelInfo> models(List<WorkerResult> results) {
        List<BenchmarkReport.ModelInfo> models = new ArrayList<>();
        for (WorkerResult result : results) {
            boolean known = models.stream().anyMatch(info -> info.model().equals(result.model()));
            if (!known && result.modelSizeBytes() != null) {
                models.add(new BenchmarkReport.ModelInfo(result.model(),
                        result.modelSizeBytes(), result.modelVramBytes()));
            }
        }
        // Models whose size Ollama never reported still belong in the list, without a size.
        results.stream().map(WorkerResult::model).distinct()
                .filter(model -> models.stream().noneMatch(info -> info.model().equals(model)))
                .forEach(model -> models.add(new BenchmarkReport.ModelInfo(model, null, null)));
        return models;
    }

    private static BenchmarkReport.ApproachSummary summarize(WorkerResult result, List<String> caseIds) {
        Approach approach = Approach.byId(result.approachId());
        List<Measurement> measurements = result.measurements();

        List<Long> timeToValid = sorted(measurements.stream()
                .filter(Measurement::counted).map(Measurement::timeToValidResultMs).toList());
        List<Long> latencies = sorted(measurements.stream()
                .filter(Measurement::counted).flatMap(m -> m.llmLatenciesMs().stream()).toList());

        int passed = count(measurements, Measurement.Status.PASS);
        int skippedCases = (int) caseIds.stream()
                .filter(id -> approach.unsupportedCases().containsKey(id)).count();

        return new BenchmarkReport.ApproachSummary(result.model(), approach.id(), approach.label(),
                (int) measurements.stream().map(Measurement::caseId).distinct().count(),
                skippedCases, measurements.size(), passed,
                count(measurements, Measurement.Status.FAIL),
                count(measurements, Measurement.Status.TIMEOUT),
                count(measurements, Measurement.Status.ERROR),
                measurements.isEmpty() ? 0 : (double) passed / measurements.size(),
                mean(measurements.stream().filter(Measurement::counted)
                        .mapToDouble(Measurement::precision)),
                mean(measurements.stream().filter(Measurement::counted)
                        .mapToDouble(Measurement::recall)),
                percentile(timeToValid, 0.50), percentile(timeToValid, 0.95),
                percentile(latencies, 0.50), percentile(latencies, 0.95),
                percentileOrNull(sorted(measurements.stream()
                        .map(Measurement::timeToFirstToolMs).filter(Objects::nonNull).toList())),
                mean(measurements.stream().filter(Measurement::counted)
                        .mapToDouble(Measurement::promptTokens)),
                mean(measurements.stream().filter(Measurement::counted)
                        .mapToDouble(Measurement::completionTokens)),
                meanValueOrNull(measurements.stream().filter(m -> m.tokensPerSecond() != null)
                        .mapToDouble(Measurement::tokensPerSecond)),
                result.workerPeakHeapBytes(), result.fatalError());
    }

    private static List<BenchmarkReport.CaseRow> caseMatrix(List<WorkerResult> results,
                                                            List<String> caseIds) {
        List<BenchmarkReport.CaseRow> rows = new ArrayList<>();
        for (String caseId : caseIds) {
            BenchmarkCase benchmarkCase = CaseCatalog.byId(caseId);
            List<String> cells = results.stream().map(result -> cell(result, caseId)).toList();
            rows.add(new BenchmarkReport.CaseRow(caseId, benchmarkCase.group().name(),
                    benchmarkCase.displayQuery(), benchmarkCase.itTestMethod(),
                    benchmarkCase.knownFailure(), cells));
        }
        return rows;
    }

    /** "2/3" passes, or "-" where the approach never ran this case. */
    private static String cell(WorkerResult result, String caseId) {
        List<Measurement> forCase = result.measurements().stream()
                .filter(measurement -> measurement.caseId().equals(caseId)).toList();
        if (forCase.isEmpty()) {
            return "-";
        }
        long passes = forCase.stream()
                .filter(measurement -> measurement.status() == Measurement.Status.PASS).count();
        return passes + "/" + forCase.size();
    }

    private static List<BenchmarkReport.SkippedCase> skipped(List<WorkerResult> results,
                                                             List<String> caseIds) {
        List<BenchmarkReport.SkippedCase> skipped = new ArrayList<>();
        results.stream().map(WorkerResult::approachId).distinct().forEach(approachId -> {
            Approach approach = Approach.byId(approachId);
            caseIds.stream()
                    .filter(caseId -> approach.unsupportedCases().containsKey(caseId))
                    .forEach(caseId -> skipped.add(new BenchmarkReport.SkippedCase(
                            approachId, caseId, approach.unsupportedCases().get(caseId))));
        });
        return skipped;
    }

    private static int count(List<Measurement> measurements, Measurement.Status status) {
        return (int) measurements.stream()
                .filter(measurement -> measurement.status() == status).count();
    }

    private static List<Long> sorted(List<Long> values) {
        return values.stream().sorted().toList();
    }

    /** Nearest-rank percentile; 0 when nothing was measured. */
    static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    private static double mean(DoubleStream values) {
        OptionalDouble average = values.average();
        return average.isPresent() ? average.getAsDouble() : 0.0;
    }

    /** {@code null} rather than 0 when an approach has no such measurement at all — 03 has no tool. */
    private static Long percentileOrNull(List<Long> sorted) {
        return sorted.isEmpty() ? null : percentile(sorted, 0.50);
    }

    private static Double meanValueOrNull(DoubleStream values) {
        OptionalDouble average = values.average();
        return average.isPresent() ? average.getAsDouble() : null;
    }
}
