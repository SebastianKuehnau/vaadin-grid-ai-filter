package dev.demo.vaadin.aigridfilter.benchmark.run;

import java.util.List;

/** What the orchestrator asks one worker JVM to measure; handed over as a JSON file. */
public record WorkerRequest(String approachId, String model, List<String> caseIds, int runs,
                            boolean warmup, String ollamaBaseUrl, int queryTimeoutSeconds,
                            ChatSettings chat) {

    /** The Ollama chat options, mirroring each module's {@code application-ollama.properties}. */
    public record ChatSettings(double temperature, int numCtx, int numPredict, boolean think,
                               String keepAlive) {
    }
}
