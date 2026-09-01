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
import java.util.ArrayList;
import java.util.List;

/**
 * Starts one worker JVM per approach and model, and reads back what it measured.
 *
 * <p>The worker's classpath is this JVM's classpath plus the approach module's {@code target/classes};
 * only that one module is added, so its services are the only ones the worker can see.
 */
public class WorkerLauncher {

    private static final Logger logger = LoggerFactory.getLogger(WorkerLauncher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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

        try {
            Files.createDirectories(workDirectory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(requestFile.toFile(), request);

            int exitCode = run(command(approach, requestFile, resultFile), logFile);
            if (!Files.exists(resultFile)) {
                return WorkerResult.failed(approach.id(), request.model(),
                        "worker exited with " + exitCode + " and wrote no result; see " + logFile);
            }
            return JSON.readValue(resultFile.toFile(), WorkerResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + tag, e);
        }
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

    private int run(List<String> command, Path logFile) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        return process.waitFor();
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
