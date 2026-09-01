package dev.demo.vaadin.aigridfilter.benchmark.report;

import java.util.List;

/** The compact report as one self-contained HTML file. */
final class HtmlReport {

    private static final String STYLE = """
            :root { color-scheme: light dark; --line: #d0d7de; --muted: #57606a; --bg: #ffffff;
                    --fg: #1f2328; --head: #f6f8fa; --pass: #dafbe1; --partial: #fff8c5;
                    --fail: #ffebe9; }
            @media (prefers-color-scheme: dark) {
              :root { --line: #30363d; --muted: #9198a1; --bg: #0d1117; --fg: #e6edf3;
                      --head: #161b22; --pass: #1b3b26; --partial: #3d3418; --fail: #3d1d1d; }
            }
            body { margin: 0 auto; padding: 2rem 1.5rem; max-width: 78rem; background: var(--bg);
                   color: var(--fg); font: 15px/1.55 system-ui, -apple-system, sans-serif; }
            h1 { margin: 0 0 .25rem; font-size: 1.6rem; }
            h2 { margin: 2.25rem 0 .75rem; font-size: 1.1rem; text-transform: uppercase;
                 letter-spacing: .06em; color: var(--muted); }
            p.lede { margin: 0 0 1.5rem; color: var(--muted); }
            div.scroll { overflow-x: auto; }
            table { border-collapse: collapse; width: 100%; font-size: .88rem; }
            th, td { border: 1px solid var(--line); padding: .35rem .6rem; text-align: left;
                     white-space: nowrap; }
            th { background: var(--head); font-weight: 600; }
            td.query { white-space: normal; min-width: 18rem; }
            td.pass { background: var(--pass); } td.partial { background: var(--partial); }
            td.fail { background: var(--fail); } td.skip { color: var(--muted); text-align: center; }
            ul.notes { color: var(--muted); font-size: .85rem; padding-left: 1.1rem; }
            ul.notes li { margin-bottom: .4rem; }
            """;

    private HtmlReport() {
    }

    static String render(BenchmarkReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Model benchmark</title>\n<style>\n").append(STYLE)
                .append("</style>\n</head>\n<body>\n")
                .append("<h1>Model benchmark</h1>\n")
                .append("<p class=\"lede\">Natural-language filtering measured against the four ")
                .append("approaches of this project, with the 22 queries of their service-level IT ")
                .append("classes.<br>Started ").append(escape(report.startedAt()))
                .append(", finished ").append(escape(report.finishedAt())).append(".</p>\n");

        List.of(ReportNotes.configuration(report), ReportTables.models(report),
                        ReportTables.correctness(report), ReportTables.speed(report),
                        ReportTables.caseMatrix(report), ReportNotes.skipped(report))
                .forEach(table -> append(out, table));

        out.append("<h2>Notes</h2>\n<ul class=\"notes\">\n");
        ReportNotes.footnotes(report).forEach(note ->
                out.append("<li>").append(escape(note)).append("</li>\n"));
        out.append("</ul>\n</body>\n</html>\n");
        return out.toString();
    }

    private static void append(StringBuilder out, Table table) {
        out.append("<h2>").append(escape(table.title())).append("</h2>\n");
        if (table.rows().isEmpty()) {
            out.append("<p class=\"lede\">none</p>\n");
            return;
        }
        out.append("<div class=\"scroll\">\n<table>\n<thead>\n<tr>");
        table.headers().forEach(header ->
                out.append("<th>").append(escape(header)).append("</th>"));
        out.append("</tr>\n</thead>\n<tbody>\n");
        for (List<String> row : table.rows()) {
            out.append("<tr>");
            row.forEach(cell -> out.append("<td").append(cellClass(cell)).append('>')
                    .append(escape(cell)).append("</td>"));
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n</div>\n");
    }

    /** Colours the case matrix by how many runs of a cell passed; other cells stay plain. */
    private static String cellClass(String cell) {
        if (cell.equals("-")) {
            return " class=\"skip\"";
        }
        if (!cell.matches("\\d+/\\d+")) {
            return cell.length() > 40 ? " class=\"query\"" : "";
        }
        String[] parts = cell.split("/");
        int passed = Integer.parseInt(parts[0]);
        int total = Integer.parseInt(parts[1]);
        if (passed == total) {
            return " class=\"pass\"";
        }
        return passed == 0 ? " class=\"fail\"" : " class=\"partial\"";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
