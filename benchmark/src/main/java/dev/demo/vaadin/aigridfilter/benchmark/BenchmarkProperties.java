package dev.demo.vaadin.aigridfilter.benchmark;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Everything the benchmark run is steered by — from {@code application.yaml}, a {@code config/} file
 * next to the working directory, or {@code --benchmark.*} on the command line.
 *
 * @param cases                  the case ids to measure; empty means all 22
 * @param runUnsupported         also measure the cases an approach architecturally cannot express
 * @param maxModelCallsPerQuery  how many model calls one query may make before it is given up on
 * @param projectRoot            where the module directories live; auto-detected when not set
 * @param workerClasspath        overrides the classpath handed to the worker JVMs; unusual launches only
 */
@ConfigurationProperties("benchmark")
public record BenchmarkProperties(
        @DefaultValue Ollama ollama,
        @DefaultValue({"qwen3:8b"}) List<String> models,
        @DefaultValue({"02a", "02b", "03", "04"}) List<String> approaches,
        @DefaultValue List<String> cases,
        @DefaultValue("3") int runs,
        @DefaultValue("true") boolean warmup,
        @DefaultValue("false") boolean runUnsupported,
        @DefaultValue("300") int queryTimeoutSeconds,
        @DefaultValue("12") int maxModelCallsPerQuery,
        @DefaultValue Chat chat,
        @DefaultValue Report report,
        String projectRoot,
        String workerClasspath) {

    /** Which Ollama answers, and whether a missing model may be downloaded. */
    public record Ollama(@DefaultValue("http://localhost:11434") String baseUrl,
                         @DefaultValue("true") boolean autoPull) {
    }

    /** The chat options, mirroring every module's {@code application-ollama.properties}. */
    public record Chat(@DefaultValue("0.0") double temperature,
                       @DefaultValue("4096") int numCtx,
                       @DefaultValue("512") int numPredict,
                       @DefaultValue("false") boolean think,
                       @DefaultValue("1h") String keepAlive) {
    }

    /** Where the report lands and in which formats; each run gets its own timestamped directory. */
    public record Report(@DefaultValue("benchmark/results") String directory,
                         @DefaultValue({"html", "md", "json", "txt"}) List<String> formats) {
    }
}
