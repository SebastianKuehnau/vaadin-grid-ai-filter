package dev.demo.vaadin.aigridfilter.benchmark.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** All four formats have to come out of one measurement, and the HTML has to stand on its own. */
class ReportWriterTest {

    @Test
    void writesOneFilePerRequestedFormat(@TempDir Path directory) {
        List<Path> written = ReportWriter.write(ReportFixture.report(), directory,
                List.of("html", "md", "json", "txt"));

        assertThat(written).extracting(path -> path.getFileName().toString())
                .containsExactly("report.html", "report.md", "report.json", "report.txt");
        assertThat(written).allSatisfy(path -> assertThat(path).isNotEmptyFile());
    }

    @Test
    void carriesEveryExecutionInTheJsonAndNoneInTheCompactFormats(@TempDir Path directory)
            throws IOException {
        ReportWriter.write(ReportFixture.report(), directory, List.of("json", "md"));

        // 3 + 6 executions, only in the JSON - the compact formats show the aggregation.
        assertThat(Files.readString(directory.resolve("report.json")))
                .contains("\"rawResults\"")
                .contains("\"timeToValidResultMs\" : 300000");
        assertThat(Files.readString(directory.resolve("report.md")))
                .doesNotContain("rawResults");
    }

    @Test
    void rendersTheCaseMatrixAndTheSkipReasonInEveryCompactFormat(@TempDir Path directory)
            throws IOException {
        ReportWriter.write(ReportFixture.report(), directory, List.of("html", "md", "txt"));

        for (String format : List.of("html", "md", "txt")) {
            assertThat(Files.readString(directory.resolve("report." + format)))
                    .as(format)
                    .contains("qwen3:8b")
                    .contains("negate flag")
                    .contains("2/3");
        }
    }

    @Test
    void writesSelfContainedHtml(@TempDir Path directory) throws IOException {
        ReportWriter.write(ReportFixture.report(), directory, List.of("html"));
        String html = Files.readString(directory.resolve("report.html"));

        assertThat(html).startsWith("<!doctype html>").contains("</html>");
        assertThat(html).doesNotContain("src=\"http").doesNotContain("href=\"http");
        assertThat(html).contains("class=\"pass\"").contains("class=\"skip\"");
    }

    @Test
    void rejectsAnUnknownFormatByName(@TempDir Path directory) {
        assertThatThrownBy(() ->
                ReportWriter.write(ReportFixture.report(), directory, List.of("pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdf");
    }
}
