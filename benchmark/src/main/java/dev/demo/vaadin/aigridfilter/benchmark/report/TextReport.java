package dev.demo.vaadin.aigridfilter.benchmark.report;

import java.util.ArrayList;
import java.util.List;

/** The compact report as plain text, for a terminal or a paste into a chat. */
final class TextReport {

    private static final int MAX_CELL_WIDTH = 100;

    private TextReport() {
    }

    static String render(BenchmarkReport report) {
        StringBuilder out = new StringBuilder();
        out.append("MODEL BENCHMARK\n")
                .append("===============\n\n")
                .append("Natural-language filtering measured against the four approaches of this\n")
                .append("project, with the 22 queries of their service-level IT classes.\n\n")
                .append("Started  ").append(report.startedAt()).append('\n')
                .append("Finished ").append(report.finishedAt()).append("\n\n");

        List.of(ReportNotes.configuration(report), ReportTables.models(report),
                        ReportTables.correctness(report), ReportTables.speed(report),
                        ReportTables.caseMatrix(report), ReportNotes.skipped(report))
                .forEach(table -> append(out, table));

        out.append("NOTES\n").append("-".repeat(5)).append('\n');
        ReportNotes.footnotes(report).forEach(note -> out.append(wrap(note)).append('\n'));
        return out.toString();
    }

    private static void append(StringBuilder out, Table table) {
        out.append(table.title().toUpperCase()).append('\n')
                .append("-".repeat(table.title().length())).append('\n');
        if (table.rows().isEmpty()) {
            out.append("(none)\n\n");
            return;
        }
        List<Integer> widths = widths(table);
        row(out, table.headers(), widths);
        out.append(widths.stream().map("-"::repeat).reduce((a, b) -> a + "-+-" + b).orElse(""))
                .append('\n');
        table.rows().forEach(row -> row(out, row, widths));
        out.append('\n');
    }

    private static List<Integer> widths(Table table) {
        List<Integer> widths = new ArrayList<>();
        for (int column = 0; column < table.headers().size(); column++) {
            int width = clip(table.headers().get(column)).length();
            for (List<String> row : table.rows()) {
                width = Math.max(width, clip(row.get(column)).length());
            }
            widths.add(width);
        }
        return widths;
    }

    private static void row(StringBuilder out, List<String> cells, List<Integer> widths) {
        List<String> padded = new ArrayList<>();
        for (int column = 0; column < cells.size(); column++) {
            padded.add(pad(clip(cells.get(column)), widths.get(column)));
        }
        out.append(String.join(" | ", padded).stripTrailing()).append('\n');
    }

    /** Long queries would push every other column off the screen. */
    private static String clip(String cell) {
        return cell.length() <= MAX_CELL_WIDTH ? cell
                : cell.substring(0, MAX_CELL_WIDTH - 3) + "...";
    }

    private static String pad(String cell, int width) {
        return cell + " ".repeat(Math.max(0, width - cell.length()));
    }

    /** Footnotes as a bullet with a hanging indent, so a long note stays readable. */
    private static String wrap(String note) {
        StringBuilder wrapped = new StringBuilder("* ");
        int lineLength = 2;
        for (String word : note.split(" ")) {
            if (lineLength + word.length() > 96) {
                wrapped.append("\n  ");
                lineLength = 2;
            }
            wrapped.append(word).append(' ');
            lineLength += word.length() + 1;
        }
        return wrapped.toString().stripTrailing();
    }
}
