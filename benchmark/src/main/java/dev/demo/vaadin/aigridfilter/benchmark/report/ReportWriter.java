package dev.demo.vaadin.aigridfilter.benchmark.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the finished report in the requested formats.
 *
 * <p>HTML, Markdown and text carry the same compact tables; JSON adds every single execution, so a
 * measurement can be re-analysed without running the models again.
 */
public final class ReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportWriter() {
    }

    public static List<Path> write(BenchmarkReport report, Path directory, List<String> formats) {
        try {
            Files.createDirectories(directory);
            List<Path> written = new ArrayList<>();
            for (String format : formats) {
                Path file = directory.resolve("report." + format.toLowerCase());
                switch (format.toLowerCase()) {
                    case "json" -> JSON.writeValue(file.toFile(), report);
                    case "md" -> text(file, MarkdownReport.render(report));
                    case "html" -> text(file, HtmlReport.render(report));
                    case "txt" -> text(file, TextReport.render(report));
                    default -> throw new IllegalArgumentException(
                            "Unknown report format '" + format + "'; known: html, md, json, txt");
                }
                written.add(file);
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the report to " + directory, e);
        }
    }

    /** The plain-text report, so the CLI can print the same summary it just wrote to disk. */
    public static String plainText(BenchmarkReport report) {
        return TextReport.render(report);
    }

    private static void text(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
