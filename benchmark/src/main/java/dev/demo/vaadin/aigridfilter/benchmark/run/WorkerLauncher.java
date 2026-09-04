package dev.demo.vaadin.aigridfilter.benchmark.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.vaadin.aigridfilter.benchmark.Approach;
import dev.demo.vaadin.aigridfilter.benchmark.ProjectLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts one worker JVM per approach and model, and reads back what it measured.
 *
 * <p>The worker's classpath is this JVM's classpath plus the approach module's {@code target/classes};
 * only that one module is added, so its services are the only ones the worker can see.
 */
public class WorkerLauncher {

    private static final Logger logger = LoggerFactory.getLogger(WorkerLauncher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** On top of the queries' own budget: JVM start, Spring context, and Ollama loading the model. */
    private static final Duration STARTUP_ALLOWANCE = Duration.ofMinutes(5);

    private final Path projectRoot;
    private final String ownClasspath;
    private final Path workDirectory;

    /** {@code reportDirectory} is where the report lands; the workers get a subdirectory of their own. */
    public WorkerLauncher(Path projectRoot, String configuredClasspath, Path reportDirectory) {
        this.projectRoot = projectRoot;
        this.ownClasspath = configuredClasspath == null || configuredClasspath.isBlank()
                ? verifiedOwnClasspath() : configuredClasspath;
        this.workDirectory = reportDirectory.resolve("workers");
    }

    /** Runs one approach against one model; a worker that dies returns a result with a fatal error. */
    public WorkerResult launch(WorkerRequest request) {
        Approach approach = Approach.byId(request.approachId());
        String tag = approach.id() + "_" + request.model().replace(':', '-');
        Path requestFile = workDirectory.resolve("request-" + tag + ".json");
        Path resultFile = workDirectory.resolve("result-" + tag + ".json");
        Path logFile = workDirectory.resolve("worker-" + tag + ".log");
        Duration timeout = timeoutFor(request);

        try {
            Files.createDirectories(workDirectory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(requestFile.toFile(), request);

            Exit exit = run(command(approach, requestFile, resultFile), logFile, timeout);
            if (!Files.exists(resultFile)) {
                return WorkerResult.failed(approach.id(), request.model(),
                        exit.describe(timeout, logFile));
            }
            return JSON.readValue(resultFile.toFile(), WorkerResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + tag, e);
        }
    }

    /**
     * The longest a worker may plausibly take: every query using its full timeout, plus room to start.
     *
     * <p>The last backstop of the run. Each query is bounded inside the worker, but a worker can also
     * wedge where no query timeout reaches — and {@code waitFor()} without a bound would then hang
     * every remaining model and approach behind it.
     */
    private Duration timeoutFor(WorkerRequest request) {
        long queries = (request.warmup() ? 1 : 0) + (long) request.caseIds().size() * request.runs();
        return Duration.ofSeconds(queries * request.queryTimeoutSeconds()).plus(STARTUP_ALLOWANCE);
    }

    private List<String> command(Approach approach, Path requestFile, Path resultFile) {
        String classpath = ownClasspath + File.pathSeparator
                + ProjectLayout.classesDirectory(projectRoot, approach);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath);
        command.add(BenchmarkWorker.class.getName());
        command.add(requestFile.toString());
        command.add(resultFile.toString());
        return command;
    }

    private Exit run(List<String> command, Path logFile, Duration timeout)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        if (process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            return new Exit(process.exitValue(), false);
        }
        logger.error("Worker exceeded {} min - killing it so the run can go on; see {}",
                timeout.toMinutes(), logFile);
        process.destroyForcibly().waitFor();
        return new Exit(process.exitValue(), true);
    }

    /** How a worker ended: normally with an exit code, or killed because it ran out of time. */
    private record Exit(int code, boolean killed) {

        String describe(Duration timeout, Path logFile) {
            return killed
                    ? "worker did not finish within " + timeout.toMinutes() + " min and was killed; see "
                            + logFile
                    : "worker exited with " + code + " and wrote no result; see " + logFile;
        }
    }

    /** A fat jar has no listable classpath to hand on, so that launch mode is rejected up front. */
    private static String verifiedOwnClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        boolean singleJar = !classpath.contains(File.pathSeparator) && classpath.endsWith(".jar");
        if (classpath.isBlank() || singleJar) {
            throw new IllegalStateException("""
                    The benchmark needs a listable classpath to build the worker JVMs' classpath, \
                    but it was started from a single jar. Run it with \
                    './mvnw spring-boot:run -pl benchmark', or set benchmark.worker-classpath.""");
        }
        logger.debug("Worker classpath base has {} entries",
                classpath.split(File.pathSeparator).length);
        return classpath;
    }
}
