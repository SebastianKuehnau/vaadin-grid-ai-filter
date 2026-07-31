package dev.demo.vaadin.aigridfilter.canonicalquery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the canonical query set against drift. {@code docs/canonical-query-set.md} is the single source of
 * truth, and two places spell the queries out verbatim: {@link CanonicalQuery} — which every AI module's
 * integration tests share, through the service and through the UI — and the standalone benchmark script,
 * which stays dependency-free and therefore keeps its own text copy.
 * <p>
 * The enum is checked <em>reflectively</em> and the benchmark script by regex, and the asymmetry is the
 * point: the enum lives in this module, so its values can simply be read — no source parsing, no Java
 * escape handling, and declaration order gives the ordering check for free. The benchmark script is a
 * single-file program with no classes to reflect on, so its case list is matched as text.
 * <p>
 * Plain JUnit: no Spring context, no LLM, no Ollama. It runs in every {@code mvn test} and turns a one-word
 * edit in either copy into a build failure instead of something a reviewer has to notice.
 */
class CanonicalQuerySetConsistencyTest {

    private static final String DOCUMENT = "docs/canonical-query-set.md";

    private static final String BENCHMARK_SOURCE = "ollama-benchmark/BenchmarkLocalModels.java";

    /** A fenced {@code text} block in the document — one per canonical query, in order. */
    private static final Pattern DOCUMENTED_QUERY =
            Pattern.compile("```text\\R(.*?)\\R```", Pattern.DOTALL);

    /** A canonical case in the benchmark script: {@code EvalCase.canonical("C1_SINGLE_VALUE", "...", ...)}. */
    private static final Pattern BENCHMARK_QUERY =
            Pattern.compile("EvalCase\\.canonical\\(\"[A-Z0-9_]+\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void theSharedEnumMatchesTheDocument() throws IOException {
        List<String> documented = queriesIn(DOCUMENTED_QUERY, read(DOCUMENT));
        assertThat(documented).as("%s must document at least the seven required categories", DOCUMENT)
                .hasSizeGreaterThanOrEqualTo(7);

        assertThat(CanonicalQuery.values()).extracting(CanonicalQuery::query)
                .as("%s must contain every canonical query verbatim, in the order of %s "
                                + "(update both together, never one of them)",
                        CanonicalQuery.class.getSimpleName(), DOCUMENT)
                .containsExactlyElementsOf(documented);
    }

    @Test
    void theBenchmarkScriptMatchesTheDocument() throws IOException {
        List<String> documented = queriesIn(DOCUMENTED_QUERY, read(DOCUMENT));

        assertThat(queriesIn(BENCHMARK_QUERY, read(BENCHMARK_SOURCE)))
                .as("%s must run exactly the canonical queries of %s, so its token and latency figures "
                                + "line up query-for-query with the IT suites' pass/fail results",
                        BENCHMARK_SOURCE, DOCUMENT)
                .containsExactlyElementsOf(documented);
    }

    /** All group-1 matches of {@code pattern}, in order, with Java string escapes resolved. */
    private static List<String> queriesIn(Pattern pattern, String text) {
        List<String> queries = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            queries.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return queries;
    }

    /** Reads a repo-relative file, so the test works from the module directory and from the repo root. */
    private static String read(String repoRelativePath) throws IOException {
        return Files.readString(repoRoot().resolve(repoRelativePath), StandardCharsets.UTF_8);
    }

    /** Walks up from the working directory until the canonical query set document is found. */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(DOCUMENT))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + DOCUMENT + " in any parent of "
                + Path.of("").toAbsolutePath());
    }
}
