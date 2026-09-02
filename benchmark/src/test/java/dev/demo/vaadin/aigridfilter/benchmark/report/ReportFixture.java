package dev.demo.vaadin.aigridfilter.benchmark.report;

import dev.demo.vaadin.aigridfilter.benchmark.run.Measurement;
import dev.demo.vaadin.aigridfilter.benchmark.run.WorkerResult;

import java.time.Instant;
import java.util.List;

/** Hand-built measurements, so the aggregation and the renderers can be tested without a model. */
final class ReportFixture {

    static final Instant STARTED_AT = Instant.parse("2026-01-01T10:00:00Z");
    static final Instant FINISHED_AT = Instant.parse("2026-01-01T10:05:30Z");

    private ReportFixture() {
    }

    static Measurement measurement(String caseId, int run, Measurement.Status status,
                                   double precision, double recall, long timeToValidResultMs,
                                   List<Long> latencies) {
        return new Measurement(caseId, run, status, precision, recall, 5, 5, timeToValidResultMs,
                latencies, latencies.isEmpty() ? null : latencies.getFirst(), 1000, 40, 12.5, null);
    }

    /** 03 against one model: C1 always right, C3 right in two of three runs. */
    static WorkerResult structuredResult() {
        return new WorkerResult("03", "qwen3:8b", List.of(
                measurement("C1", 1, Measurement.Status.PASS, 1.0, 1.0, 1000, List.of(900L)),
                measurement("C1", 2, Measurement.Status.PASS, 1.0, 1.0, 2000, List.of(1900L)),
                measurement("C1", 3, Measurement.Status.PASS, 1.0, 1.0, 3000, List.of(2900L)),
                measurement("C3", 1, Measurement.Status.PASS, 1.0, 1.0, 4000, List.of(3900L)),
                measurement("C3", 2, Measurement.Status.FAIL, 0.5, 0.5, 5000, List.of(4900L)),
                measurement("C3", 3, Measurement.Status.PASS, 1.0, 1.0, 6000, List.of(5900L))),
                5_000_000_000L, 0L, 90_000_000L, null);
    }

    /** 02(a) against the same model: C1 only, because C3 has no negate flag there. */
    static WorkerResult flatResult() {
        return new WorkerResult("02a", "qwen3:8b", List.of(
                measurement("C1", 1, Measurement.Status.PASS, 1.0, 1.0, 1500, List.of(1400L)),
                measurement("C1", 2, Measurement.Status.TIMEOUT, 0.0, 0.0, 300_000, List.of()),
                measurement("C1", 3, Measurement.Status.FAIL, 0.0, 0.0, 2500, List.of(2400L))),
                5_000_000_000L, 0L, 80_000_000L, null);
    }

    static BenchmarkReport.Configuration configuration() {
        return new BenchmarkReport.Configuration("http://localhost:11434", "0.33.2",
                List.of("qwen3:8b"), List.of("02a", "03"), List.of("C1", "C3"), 3, true, false,
                300, 12, 0.0, 4096, 512, false, "1h");
    }

    static BenchmarkReport report() {
        return Aggregator.aggregate(configuration(), List.of(flatResult(), structuredResult()),
                List.of("C1", "C3"), STARTED_AT, FINISHED_AT);
    }
}
