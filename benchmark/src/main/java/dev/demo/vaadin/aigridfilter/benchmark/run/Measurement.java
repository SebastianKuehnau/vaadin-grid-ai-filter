package dev.demo.vaadin.aigridfilter.benchmark.run;

import java.util.List;

/**
 * One execution of one case: what came back, how correct it was, and what it cost.
 *
 * @param timeToValidResultMs the whole {@code resolveFilter(...)} call plus the database query — what
 *                            a user waits for
 * @param llmLatenciesMs      one entry per model call; a tool approach adds a call per tool round trip
 * @param timeToFirstToolMs   the first model call's duration — when the model emitted its tool call;
 *                            {@code null} for 03, which calls no tool
 * @param tokensPerSecond     generation rate from Ollama's own {@code eval-count} / {@code eval-duration}
 */
public record Measurement(String caseId, int run, Status status, double precision, double recall,
                          int returnedCount, int expectedCount, long timeToValidResultMs,
                          List<Long> llmLatenciesMs, Long timeToFirstToolMs, long promptTokens,
                          long completionTokens, Double tokensPerSecond, String error) {

    public enum Status {
        /** The returned set is a correct answer to the query. */
        PASS,
        /** The model answered, but with the wrong set of customers. */
        FAIL,
        /** The query did not finish within the configured timeout. */
        TIMEOUT,
        /** The call threw before a result existed. */
        ERROR
    }

    public boolean counted() {
        return status == Status.PASS || status == Status.FAIL;
    }
}
