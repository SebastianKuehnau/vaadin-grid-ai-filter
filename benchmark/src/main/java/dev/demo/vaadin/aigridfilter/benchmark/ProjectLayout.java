package dev.demo.vaadin.aigridfilter.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;

/** Finds the module directories, so the benchmark can be started from anywhere in the project. */
public final class ProjectLayout {

    private ProjectLayout() {
    }

    /** The configured root, or the nearest ancestor of the working directory that holds the modules. */
    public static Path projectRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            return verified(Path.of(configured).toAbsolutePath().normalize());
        }
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (looksLikeProjectRoot(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("""
                Cannot find the project root from the working directory. Start the benchmark from \
                inside the project, or set benchmark.project-root to the directory that holds \
                00-commons and 02-ai-agent-filter.""");
    }

    /** The compiled classes of an approach's module — what its worker JVM gets on the classpath. */
    public static Path classesDirectory(Path projectRoot, Approach approach) {
        Path classes = projectRoot.resolve(approach.moduleDirectory()).resolve("target/classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException(("Approach %s needs compiled classes at %s. "
                    + "Build them first: ./mvnw compile").formatted(approach.id(), classes));
        }
        return classes;
    }

    private static Path verified(Path root) {
        if (!looksLikeProjectRoot(root)) {
            throw new IllegalStateException(
                    "benchmark.project-root=" + root + " does not hold the module directories");
        }
        return root;
    }

    private static boolean looksLikeProjectRoot(Path candidate) {
        return Files.isDirectory(candidate.resolve("00-commons"))
                && Files.isDirectory(candidate.resolve("02-ai-agent-filter"));
    }
}
