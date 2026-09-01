package dev.demo.vaadin.aigridfilter.benchmark.report;

import java.util.List;

/** A rendered-format-agnostic table, so Markdown, text and HTML share one source of content. */
public record Table(String title, List<String> headers, List<List<String>> rows) {
}
