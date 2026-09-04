package dev.demo.vaadin.aigridfilter.benchmark.run;

import java.util.List;

/**
 * What one worker JVM measured for one approach against one model.
 *
 * @param modelSizeBytes     the model's resident size in Ollama after the warmup, from {@code /api/ps}
 * @param modelVramBytes     how much of that size sits in VRAM; 0 on a CPU-only machine
 * @param workerPeakHeapBytes the worker JVM's peak heap — the footnote, not the headline
 * @param fatalError         set when the worker could not measure at all; the run is then unusable
 */
public record WorkerResult(String approachId, String model, List<Measurement> measurements,
                           Long modelSizeBytes, Long modelVramBytes, long workerPeakHeapBytes,
                           String fatalError) {

    public static WorkerResult failed(String approachId, String model, String fatalError) {
        return new WorkerResult(approachId, model, List.of(), null, null, 0L, fatalError);
    }
}
