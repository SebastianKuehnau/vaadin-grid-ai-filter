package dev.demo.vaadin.aigridfilter.benchmark.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
import dev.demo.vaadin.aigridfilter.benchmark.Approach;
import dev.demo.vaadin.aigridfilter.benchmark.cases.BenchmarkCase;
import dev.demo.vaadin.aigridfilter.benchmark.cases.CaseCatalog;
import dev.demo.vaadin.aigridfilter.benchmark.ollama.OllamaAdmin;
import dev.demo.vaadin.aigridfilter.benchmark.ollama.OllamaCallMetrics;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Measures one approach against one model, in its own JVM: reads a {@link WorkerRequest} file and
 * writes a {@link WorkerResult} file.
 *
 * <p>One process per approach is not a detail but the mechanism: modules 03 and 04 ship five classes
 * under identical fully qualified names, so they can never share a classpath. Each worker gets its
 * own module's {@code target/classes} and therefore its own, unambiguous services.
 */
public final class BenchmarkWorker {

    private static final Logger logger = LoggerFactory.getLogger(BenchmarkWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Approach approach;
    private final ConfigurableApplicationContext context;
    private final CustomerRepository repository;
    private final TokenUsageAdvisor advisor;
    private final CallBudgetChatModel budget;
    private final List<Customer> allCustomers;
    private final int queryTimeoutSeconds;

    private BenchmarkWorker(Approach approach, ConfigurableApplicationContext context,
                            int queryTimeoutSeconds) {
        this.approach = approach;
        this.context = context;
        this.repository = context.getBean(CustomerRepository.class);
        this.advisor = context.getBean(TokenUsageAdvisor.class);
        this.budget = context.getBean(CallBudgetChatModel.class);
        this.allCustomers = repository.findAll();
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public static void main(String[] args) throws Exception {
        Path requestFile = Path.of(args[0]);
        Path resultFile = Path.of(args[1]);

        WorkerRequest request = JSON.readValue(requestFile.toFile(), WorkerRequest.class);
        WorkerResult result;
        try {
            result = measure(request);
        } catch (Throwable failure) {
            logger.error("Worker failed for {} / {}", request.approachId(), request.model(), failure);
            result = WorkerResult.failed(request.approachId(), request.model(), describe(failure));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);

        // Hibernate's and Ollama's client pools keep non-daemon threads around; the work is done.
        System.exit(0);
    }

    private static WorkerResult measure(WorkerRequest request) {
        Approach approach = Approach.byId(request.approachId());
        // The modules put an application.properties of their own on this classpath; ignore all of them
        // and let the request be the single source of configuration.
        System.setProperty("spring.config.name", "benchmark-worker");

        try (ConfigurableApplicationContext context = start(request)) {
            return new BenchmarkWorker(approach, context, request.queryTimeoutSeconds())
                    .measureAll(request);
        }
    }

    private WorkerResult measureAll(WorkerRequest request) {
        if (request.warmup()) {
            warmUp();
        }
        OllamaAdmin.LoadedModel loaded = new OllamaAdmin(request.ollamaBaseUrl())
                .loaded(request.model()).orElse(null);

        List<Measurement> measurements = new ArrayList<>();
        for (int run = 1; run <= request.runs(); run++) {
            // A full pass over every case per run, like an IT class run - the model's answers depend
            // on Ollama's cached prefix, so the order the cases arrive in matters.
            for (String caseId : request.caseIds()) {
                measurements.add(measureOnce(CaseCatalog.byId(caseId), run));
            }
        }
        return new WorkerResult(approach.id(), request.model(), measurements,
                loaded == null ? null : loaded.sizeBytes(),
                loaded == null ? null : loaded.vramBytes(),
                peakHeapBytes(), null);
    }

    /** One model call that is not measured, so the first case does not pay the model's load time. */
    private void warmUp() {
        logger.info("Warming up {}", approach.id());
        budget.reset();
        try {
            // Under the query timeout like every measured case: a warm-up that hangs used to hang the
            // whole run, because nothing above it is bounded either.
            resolveWithTimeout("show me all customers in Berlin");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Warm-up query interrupted; measuring anyway");
        } catch (Exception e) {
            logger.warn("Warm-up query failed; measuring anyway", e);
        }
    }

    /**
     * Runs one query, bounded by the query timeout.
     *
     * <p>A fresh executor per query: a timed-out query leaves its thread blocked in the model call,
     * and that thread must never be reused for the next one.
     */
    private List<Customer> resolveWithTimeout(String query) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<Customer>> answer = executor.submit(
                    () -> repository.findAll(agent().resolveFilter(query)));
            return answer.get(queryTimeoutSeconds, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private Measurement measureOnce(BenchmarkCase benchmarkCase, int run) {
        logger.info("--> run {} {} '{}'", run, benchmarkCase.id(), benchmarkCase.displayQuery());
        advisor.reset();
        budget.reset();

        List<Customer> returned = List.of();
        Measurement.Status status = null;
        String error = null;

        long start = System.nanoTime();
        try {
            returned = resolveWithTimeout(benchmarkCase.query());
        } catch (TimeoutException e) {
            status = Measurement.Status.TIMEOUT;
            error = "no result within " + queryTimeoutSeconds + " s";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Measurement.Status.ERROR;
            error = "interrupted";
        } catch (Exception e) {
            status = Measurement.Status.ERROR;
            error = describe(e);
        }
        long timeToValidResultMs = (System.nanoTime() - start) / 1_000_000;

        Set<Long> expected = expectedIds(benchmarkCase);
        long correctlyReturned = returned.stream()
                .filter(customer -> expected.contains(customer.getId())).count();
        long acceptablyReturned = returned.stream().filter(benchmarkCase.mayMatch()).count();

        boolean answered = status == null;
        if (answered) {
            // Correct means: nothing expected is missing, and nothing returned is out of bounds.
            status = correctlyReturned == expected.size() && acceptablyReturned == returned.size()
                    ? Measurement.Status.PASS : Measurement.Status.FAIL;
        }
        // A timeout or an exception left no answer to score, so it scores zero rather than "empty
        // set, nothing expected, perfect" - the aggregation leaves those executions out anyway.
        double precision = !answered ? 0.0
                : returned.isEmpty() ? (expected.isEmpty() ? 1.0 : 0.0)
                : (double) acceptablyReturned / returned.size();
        double recall = !answered ? 0.0
                : expected.isEmpty() ? 1.0 : (double) correctlyReturned / expected.size();

        List<TokenUsageAdvisor.Call> calls = advisor.calls();
        logger.info("{} {} run {}: {} ({} returned, {} expected) in {} ms",
                approach.id(), benchmarkCase.id(), run, status, returned.size(), expected.size(),
                timeToValidResultMs);

        return new Measurement(benchmarkCase.id(), run, status, precision, recall,
                returned.size(), expected.size(), timeToValidResultMs,
                calls.stream().map(TokenUsageAdvisor.Call::durationMillis).toList(),
                timeToFirstToolMs(calls),
                calls.stream().mapToLong(TokenUsageAdvisor.Call::promptTokens).sum(),
                calls.stream().mapToLong(TokenUsageAdvisor.Call::completionTokens).sum(),
                OllamaCallMetrics.tokensPerSecond(calls), error);
    }

    /**
     * The first model call's duration — the point at which the model has emitted its first tool call.
     * 03 calls no tool and gets no value. Where the model answered without calling a tool at all
     * (small talk, say), this is simply that one call's duration; whether a tool ran is not
     * observable from outside the module's service.
     */
    private Long timeToFirstToolMs(List<TokenUsageAdvisor.Call> calls) {
        return approach.toolBased() && !calls.isEmpty() ? calls.getFirst().durationMillis() : null;
    }

    /** The ids a correct answer must contain, computed from the seeded data - never a hard-coded list. */
    private Set<Long> expectedIds(BenchmarkCase benchmarkCase) {
        Set<Long> ids = new LinkedHashSet<>();
        allCustomers.stream().filter(benchmarkCase.mustMatch())
                .forEach(customer -> ids.add(customer.getId()));
        return ids;
    }

    /** A fresh agent per query: three of the four services are prototypes and hold per-query state. */
    private CustomerSearchAgent agent() {
        return approach.beanName() == null
                ? context.getBean(CustomerSearchAgent.class)
                : context.getBean(approach.beanName(), CustomerSearchAgent.class);
    }

    private static ConfigurableApplicationContext start(WorkerRequest request) {
        return new SpringApplicationBuilder(WorkerConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .registerShutdownHook(false)
                .properties(springProperties(request))
                .run();
    }

    private static Map<String, Object> springProperties(WorkerRequest request) {
        WorkerRequest.ChatSettings chat = request.chat();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", "jdbc:h2:mem:benchmark;DB_CLOSE_DELAY=-1");
        properties.put("spring.jpa.hibernate.ddl-auto", "create-drop");
        properties.put("spring.jpa.defer-datasource-initialization", true);
        properties.put("spring.sql.init.mode", "always");
        properties.put("spring.jpa.open-in-view", false);
        properties.put("spring.ai.model.chat", "ollama");
        // The orchestrator has already made sure the model is there.
        properties.put("spring.ai.ollama.init.pull-model-strategy", "never");
        properties.put("spring.ai.ollama.base-url", request.ollamaBaseUrl());
        properties.put("spring.ai.ollama.chat.model", request.model());
        properties.put("spring.ai.ollama.chat.temperature", chat.temperature());
        properties.put("spring.ai.ollama.chat.num-ctx", chat.numCtx());
        properties.put("spring.ai.ollama.chat.num-predict", chat.numPredict());
        properties.put("spring.ai.ollama.chat.think", chat.think());
        properties.put("spring.ai.ollama.chat.keep-alive", chat.keepAlive());
        // Read by the CallBudgetChatModel bean, which every service gets instead of the raw one.
        properties.put("benchmark.worker.max-model-calls-per-query", request.maxModelCallsPerQuery());
        properties.put("logging.level.root", "WARN");
        properties.put("logging.level.dev.demo.vaadin.aigridfilter", "INFO");
        return properties;
    }

    private static long peakHeapBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
}
