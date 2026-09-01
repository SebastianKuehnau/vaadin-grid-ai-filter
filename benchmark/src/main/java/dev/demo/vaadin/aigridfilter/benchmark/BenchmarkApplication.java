package dev.demo.vaadin.aigridfilter.benchmark;

import dev.demo.vaadin.aigridfilter.benchmark.cases.CaseCatalog;
import dev.demo.vaadin.aigridfilter.benchmark.ollama.OllamaAdmin;
import dev.demo.vaadin.aigridfilter.benchmark.report.Aggregator;
import dev.demo.vaadin.aigridfilter.benchmark.report.BenchmarkReport;
import dev.demo.vaadin.aigridfilter.benchmark.report.ReportWriter;
import dev.demo.vaadin.aigridfilter.benchmark.run.WorkerLauncher;
import dev.demo.vaadin.aigridfilter.benchmark.run.WorkerRequest;
import dev.demo.vaadin.aigridfilter.benchmark.run.WorkerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The benchmark's entry point: decides what to measure, runs one worker JVM per approach and model,
 * and writes the report.
 *
 * <p>Deliberately started by hand only — no build ever runs it. There is no autoconfiguration here on
 * purpose: this process needs neither a database nor a chat model, only the configuration binding.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BenchmarkProperties.class)
public class BenchmarkApplication implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(BenchmarkApplication.class);
    private static final DateTimeFormatter RUN_DIRECTORY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final BenchmarkProperties properties;

    public BenchmarkApplication(BenchmarkProperties properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(BenchmarkApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant startedAt = Instant.now();
        Path projectRoot = ProjectLayout.projectRoot(properties.projectRoot());
        List<Approach> approaches = properties.approaches().stream().map(Approach::byId).toList();
        List<String> caseIds = selectedCaseIds();
        // Fail before the first model call if a module was never compiled.
        approaches.forEach(approach -> ProjectLayout.classesDirectory(projectRoot, approach));

        OllamaAdmin ollama = new OllamaAdmin(properties.ollama().baseUrl());
        String ollamaVersion = ollama.version().orElseThrow(() -> new IllegalStateException(
                "No Ollama answers at " + properties.ollama().baseUrl()
                        + " - start it, or point benchmark.ollama.base-url somewhere else"));

        Path outputDirectory = outputDirectory(projectRoot);
        logger.info("Ollama {} at {}; measuring {} approaches x {} models x {} cases x {} runs",
                ollamaVersion, properties.ollama().baseUrl(), approaches.size(),
                properties.models().size(), caseIds.size(), properties.runs());
        logger.info("Results go to {}", outputDirectory);

        WorkerLauncher launcher =
                new WorkerLauncher(projectRoot, properties.workerClasspath(), outputDirectory);
        List<WorkerResult> results = new ArrayList<>();
        for (String model : properties.models()) {
            ensureAvailable(ollama, model);
            for (Approach approach : approaches) {
                WorkerResult result = measure(launcher, model, approach, caseIds);
                if (result != null) {
                    results.add(result);
                }
            }
        }
        if (results.isEmpty()) {
            throw new IllegalStateException("Nothing was measured - every approach skipped every case");
        }

        BenchmarkReport report = Aggregator.aggregate(
                configuration(ollamaVersion, caseIds), results, caseIds, startedAt, Instant.now());
        List<Path> written =
                ReportWriter.write(report, outputDirectory, properties.report().formats());

        System.out.println();
        System.out.println(ReportWriter.plainText(report));
        logger.info("Report written to {}", outputDirectory);
        written.forEach(path -> logger.info("  {}", path.getFileName()));
    }

    /** Runs one approach against one model; {@code null} when nothing is left to measure for it. */
    private WorkerResult measure(WorkerLauncher launcher, String model, Approach approach,
                                 List<String> caseIds) {
        List<String> selected = caseIds.stream().filter(caseId -> runnable(approach, caseId)).toList();
        if (selected.isEmpty()) {
            logger.info("{} / {}: every selected case is inexpressible here - skipped",
                    model, approach.id());
            return null;
        }
        logger.info("Measuring {} / {} - {} of {} cases, {} runs",
                model, approach.id(), selected.size(), caseIds.size(), properties.runs());

        Instant start = Instant.now();
        WorkerResult result = launcher.launch(new WorkerRequest(approach.id(), model, selected,
                properties.runs(), properties.warmup(), properties.ollama().baseUrl(),
                properties.queryTimeoutSeconds(),
                new WorkerRequest.ChatSettings(properties.chat().temperature(),
                        properties.chat().numCtx(), properties.chat().numPredict(),
                        properties.chat().think(), properties.chat().keepAlive())));

        long seconds = Duration.between(start, Instant.now()).toSeconds();
        if (result.fatalError() != null) {
            logger.error("  {} / {} failed after {} s: {}",
                    model, approach.id(), seconds, result.fatalError());
        } else {
            logger.info("  {} / {} done in {} s", model, approach.id(), seconds);
        }
        return result;
    }

    /** Unsupported means the filter type has no slot for it — not measured unless asked for. */
    private boolean runnable(Approach approach, String caseId) {
        return properties.runUnsupported() || !approach.unsupportedCases().containsKey(caseId);
    }

    private List<String> selectedCaseIds() {
        if (properties.cases() == null || properties.cases().isEmpty()) {
            return CaseCatalog.allIds();
        }
        // byId throws with the list of known ids, which is the error message we want here.
        return properties.cases().stream()
                .map(caseId -> CaseCatalog.byId(caseId.trim()).id())
                .toList();
    }

    private void ensureAvailable(OllamaAdmin ollama, String model) {
        if (ollama.hasModel(model)) {
            return;
        }
        if (!properties.ollama().autoPull()) {
            throw new IllegalStateException("Ollama has no '" + model
                    + "' and benchmark.ollama.auto-pull is false; available: " + ollama.models());
        }
        logger.info("Pulling {} - this downloads several GB and can take a while", model);
        ollama.pull(model);
        if (!ollama.hasModel(model)) {
            throw new IllegalStateException("Pulling '" + model + "' did not make it available");
        }
    }

    private Path outputDirectory(Path projectRoot) {
        Path configured = Path.of(properties.report().directory());
        Path base = configured.isAbsolute() ? configured : projectRoot.resolve(configured);
        return base.resolve(LocalDateTime.now().format(RUN_DIRECTORY));
    }

    private BenchmarkReport.Configuration configuration(String ollamaVersion, List<String> caseIds) {
        return new BenchmarkReport.Configuration(properties.ollama().baseUrl(), ollamaVersion,
                properties.models(), properties.approaches(), caseIds, properties.runs(),
                properties.warmup(), properties.runUnsupported(), properties.queryTimeoutSeconds(),
                properties.chat().temperature(), properties.chat().numCtx(),
                properties.chat().numPredict(), properties.chat().think(),
                properties.chat().keepAlive());
    }
}
