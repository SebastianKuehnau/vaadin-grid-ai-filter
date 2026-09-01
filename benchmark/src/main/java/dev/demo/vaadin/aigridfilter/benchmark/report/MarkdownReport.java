package dev.demo.vaadin.aigridfilter.benchmark.report;

import java.util.List;

/** The compact report as Markdown. */
final class MarkdownReport {

    private MarkdownReport() {
    }

    static String render(BenchmarkReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Model benchmark\n\n")
                .append("Natural-language filtering measured against the four approaches of this ")
                .append("project, with the 22 queries of their service-level IT classes.\n\n")
                .append("Started `").append(report.startedAt()).append("`, finished `")
                .append(report.finishedAt()).append("`.\n\n");

        List.of(ReportNotes.configuration(report), ReportTables.models(report),
                        ReportTables.correctness(report), ReportTables.speed(report),
                        ReportTables.caseMatrix(report), ReportNotes.skipped(report))
                .forEach(table -> append(out, table));

        out.append("## Notes\n\n");
        ReportNotes.footnotes(report).forEach(note -> out.append("- ").append(note).append('\n'));
        return out.toString();
    }

    private static void append(StringBuilder out, Table table) {
        out.append("## ").append(table.title()).append("\n\n");
        if (table.rows().isEmpty()) {
            out.append("_none_\n\n");
            return;
        }
        row(out, table.headers());
        out.append('|').append("---|".repeat(table.headers().size())).append('\n');
        table.rows().forEach(row -> row(out, row));
        out.append('\n');
    }

    private static void row(StringBuilder out, List<String> cells) {
        out.append("| ");
        out.append(String.join(" | ", cells.stream().map(MarkdownReport::escape).toList()));
        out.append(" |\n");
    }

    private static String escape(String cell) {
        return cell.replace("|", "\\|");
    }
}
