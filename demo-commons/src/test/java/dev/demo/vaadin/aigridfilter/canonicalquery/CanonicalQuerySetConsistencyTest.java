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
 * Guards the canonical query set against drift. {@code docs/canonical-query-set.md} is the single source
 * of truth for the natural-language queries every AI module is tested with, and five places spell them
 * out verbatim: the four modules' canonical-query ITs, and the standalone benchmark script.
 * <p>
 * Those copies exist on purpose. Each IT carries its own table of queries, expected outcome and reference
 * predicates, so opening one class tells you what runs and what a correct answer looks like without
 * chasing a shared enum in another module. This test is the price of that legibility: the copies are
 * allowed, but the build fails the moment one of them differs from the document, in wording or order.
 * <p>
 * It lives in {@code demo-commons} because that is the module every build touches, and because the
 * invariant is repo-wide rather than any one module's business. Plain JUnit — no Spring context, no LLM,
 * no Ollama — so it runs in every {@code mvn test} and turns a one-word edit into a build failure instead
 * of something a reviewer has to notice.
 */
class CanonicalQuerySetConsistencyTest {

    private static final String DOCUMENT = "docs/canonical-query-set.md";

    private static final String BENCHMARK_SOURCE = "ollama-benchmark/BenchmarkLocalModels.java";

    private static final List<String> IT_SOURCES = List.of(
            "02-ai-agent-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/flat/FlatCanonicalQueryIT.java",
            "02-ai-agent-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/operator/OperatorCanonicalQueryIT.java",
            "03-ai-structured-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/StructuredCanonicalQueryIT.java",
            "04-ai-hybrid-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/HybridCanonicalQueryIT.java");

    /** A fenced {@code text} block in the document — one per canonical query, in order. */
    private static final Pattern DOCUMENTED_QUERY =
            Pattern.compile("```text\\R(.*?)\\R```", Pattern.DOTALL);

    /**
     * A case in an IT's table: {@code new Case("C1_SINGLE_VALUE", EXPRESSIBLE, "show me all …",}. The
     * query sits on the line after the outcome flag, so the pattern spans lines.
     */
    private static final Pattern IT_QUERY = Pattern.compile(
            "new Case\\(\"[A-Z0-9_]+\",\\s*(?:NOT_EXPRESSIBLE|EXPRESSIBLE),\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    /** A canonical case in the benchmark script: {@code EvalCase.canonical("C1_SINGLE_VALUE", "...", ...)}. */
    private static final Pattern BENCHMARK_QUERY =
            Pattern.compile("EvalCase\\.canonical\\(\"[A-Z0-9_]+\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyItMatchesTheDocument() throws IOException {
        List<String> documented = queriesIn(DOCUMENTED_QUERY, read(DOCUMENT));
        assertThat(documented).as("%s must document at least the seven required categories", DOCUMENT)
                .hasSizeGreaterThanOrEqualTo(7);

        for (String source : IT_SOURCES) {
            assertThat(queriesIn(IT_QUERY, read(source)))
                    .as("%s must carry every canonical query verbatim, in the order of %s "
                                    + "(update all copies together, never one of them)", source, DOCUMENT)
                    .containsExactlyElementsOf(documented);
        }
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
