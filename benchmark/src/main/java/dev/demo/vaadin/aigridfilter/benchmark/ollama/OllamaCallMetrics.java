package dev.demo.vaadin.aigridfilter.benchmark.ollama;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;

import java.time.Duration;
import java.util.List;

/** Reads Ollama's own generation counters out of the response metadata the advisor kept. */
public final class OllamaCallMetrics {

    private OllamaCallMetrics() {
    }

    /**
     * Generated tokens per second across all calls of one query, from Ollama's {@code eval-count} and
     * {@code eval-duration} — the pure generation rate, without prompt evaluation or transport.
     * Falls back to completion tokens over the calls' wall clock when the backend reports neither.
     */
    public static Double tokensPerSecond(List<TokenUsageAdvisor.Call> calls) {
        long tokens = 0;
        long nanos = 0;
        for (TokenUsageAdvisor.Call call : calls) {
            Long count = asLong(call.providerMetadata().get("eval-count"));
            Long duration = asNanos(call.providerMetadata().get("eval-duration"));
            if (count != null && duration != null && duration > 0) {
                tokens += count;
                nanos += duration;
            }
        }
        if (nanos > 0) {
            return tokens * 1_000_000_000.0 / nanos;
        }
        long millis = calls.stream().mapToLong(TokenUsageAdvisor.Call::durationMillis).sum();
        long completion = calls.stream().mapToLong(TokenUsageAdvisor.Call::completionTokens).sum();
        return millis > 0 && completion > 0 ? completion * 1000.0 / millis : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** Spring AI hands durations over either as a {@link Duration} or as raw nanoseconds. */
    private static Long asNanos(Object value) {
        if (value instanceof Duration duration) {
            return duration.toNanos();
        }
        return asLong(value);
    }
}
