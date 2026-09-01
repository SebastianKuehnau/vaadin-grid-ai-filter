package dev.demo.vaadin.aigridfilter.benchmark.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The numbers a reader compares: pass rate, precision, percentiles and the case matrix cells. */
class AggregatorTest {

    @Test
    void countsPassesOverEveryRunOfAnApproach() {
        BenchmarkReport.ApproachSummary structured = summary(ReportFixture.report(), "03");

        assertThat(structured.executions()).isEqualTo(6);
        assertThat(structured.passed()).isEqualTo(5);
        assertThat(structured.failed()).isEqualTo(1);
        assertThat(structured.passRate()).isEqualTo(5.0 / 6);
    }

    @Test
    void averagesPrecisionAndRecallOverTheAnsweredExecutionsOnly() {
        BenchmarkReport.ApproachSummary flat = summary(ReportFixture.report(), "02a");

        // The timed-out execution has no answer to score, so only the other two count.
        assertThat(flat.meanPrecision()).isEqualTo(0.5);
        assertThat(flat.timedOut()).isEqualTo(1);
    }

    @Test
    void reportsTheMedianAndTheNinetyFifthPercentileOfTheWholeCall() {
        BenchmarkReport.ApproachSummary structured = summary(ReportFixture.report(), "03");

        assertThat(structured.p50TimeToValidResultMs()).isEqualTo(3000);
        assertThat(structured.p95TimeToValidResultMs()).isEqualTo(6000);
    }

    @Test
    void countsSkippedCasesFromTheApproachesCapabilityGaps() {
        // 02(a) cannot express C3, so of the two selected cases one is skipped.
        assertThat(summary(ReportFixture.report(), "02a").casesSkipped()).isEqualTo(1);
        assertThat(summary(ReportFixture.report(), "03").casesSkipped()).isZero();
    }

    @Test
    void writesPassesOutOfRunsIntoEveryCellAndADashWhereNothingRan() {
        BenchmarkReport report = ReportFixture.report();
        // Columns are 02a first, then 03 - the order the workers ran in.
        assertThat(row(report, "C1").cells()).containsExactly("1/3", "3/3");
        assertThat(row(report, "C3").cells()).containsExactly("-", "2/3");
    }

    @Test
    void listsEverySkippedCaseWithTheReasonFromItsItClass() {
        assertThat(ReportFixture.report().skipped())
                .singleElement()
                .satisfies(skipped -> {
                    assertThat(skipped.approachId()).isEqualTo("02a");
                    assertThat(skipped.caseId()).isEqualTo("C3");
                    assertThat(skipped.reason()).contains("negate flag");
                });
    }

    @Test
    void takesTheModelSizeFromWhicheverWorkerReportedIt() {
        assertThat(ReportFixture.report().models())
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.model()).isEqualTo("qwen3:8b");
                    assertThat(model.sizeBytes()).isEqualTo(5_000_000_000L);
                });
    }

    @Test
    void picksTheNearestRankForAPercentile() {
        List<Long> values = List.of(10L, 20L, 30L, 40L);

        assertThat(Aggregator.percentile(values, 0.50)).isEqualTo(20);
        assertThat(Aggregator.percentile(values, 0.95)).isEqualTo(40);
        assertThat(Aggregator.percentile(List.of(), 0.50)).isZero();
    }

    private static BenchmarkReport.ApproachSummary summary(BenchmarkReport report, String approachId) {
        return report.summaries().stream()
                .filter(candidate -> candidate.approachId().equals(approachId))
                .findFirst().orElseThrow();
    }

    private static BenchmarkReport.CaseRow row(BenchmarkReport report, String caseId) {
        return report.caseMatrix().stream()
                .filter(candidate -> candidate.caseId().equals(caseId))
                .findFirst().orElseThrow();
    }
}
