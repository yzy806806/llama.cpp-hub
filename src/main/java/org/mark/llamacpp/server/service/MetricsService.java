package org.mark.llamacpp.server.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics service singleton for Prometheus metrics export.
 * Provides system and application metrics in Prometheus format.
 */
public class MetricsService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);

    private static final MetricsService INSTANCE = new MetricsService();

    private final PrometheusMeterRegistry registry;

    // Model metrics
    private final AtomicInteger loadedModels = new AtomicInteger(0);
    private final Counter modelLoadCounter;
    private final Counter modelUnloadCounter;
    private final Timer modelLoadDuration;

    // Request metrics
    private final Counter requestsTotal;
    private final Counter requestsErrors;
    private final Counter tokensTotal;
    private final Timer requestDuration;

    // System metrics
    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;

    // Config
    private volatile boolean enabled = true;

    private MetricsService() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Model metrics
        Gauge.builder("llama_hub_loaded_models", loadedModels, AtomicInteger::get)
            .description("Number of currently loaded models")
            .register(registry);

        this.modelLoadCounter = Counter.builder("llama_hub_model_load_total")
            .description("Total number of model loads")
            .register(registry);

        this.modelUnloadCounter = Counter.builder("llama_hub_model_unload_total")
            .description("Total number of model unloads")
            .register(registry);

        this.modelLoadDuration = Timer.builder("llama_hub_model_load_duration_seconds")
            .description("Model loading duration in seconds")
            .register(registry);

        // Request metrics
        this.requestsTotal = Counter.builder("llama_hub_requests_total")
            .description("Total number of requests")
            .register(registry);

        this.requestsErrors = Counter.builder("llama_hub_requests_errors_total")
            .description("Total number of request errors")
            .register(registry);

        this.tokensTotal = Counter.builder("llama_hub_tokens_total")
            .description("Total number of generated tokens")
            .register(registry);

        this.requestDuration = Timer.builder("llama_hub_request_duration_seconds")
            .description("Request processing duration in seconds")
            .register(registry);

        // System metrics - register JVM memory
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        Gauge.builder("llama_hub_jvm_memory_used_bytes", memoryBean, bean ->
            bean.getHeapMemoryUsage().getUsed() + bean.getNonHeapMemoryUsage().getUsed())
            .description("JVM memory usage in bytes")
            .register(registry);

        Gauge.builder("llama_hub_jvm_memory_max_bytes", memoryBean, bean ->
            bean.getHeapMemoryUsage().getMax())
            .description("JVM max memory in bytes")
            .register(registry);

        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        Gauge.builder("llama_hub_system_cpu_load", osBean, bean -> bean.getSystemLoadAverage())
            .description("System CPU load average")
            .register(registry);

        // Load config from system property
        this.enabled = !"false".equalsIgnoreCase(System.getProperty("metrics.enabled", "true"));

        logger.info("MetricsService initialized, enabled: {}", enabled);
    }

    public static MetricsService getInstance() {
        return INSTANCE;
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        logger.info("MetricsService enabled: {}", enabled);
    }

    // Model metrics methods
    public void incrementLoadedModels() {
        loadedModels.incrementAndGet();
    }

    public void decrementLoadedModels() {
        loadedModels.decrementAndGet();
    }

    public void setLoadedModels(int count) {
        loadedModels.set(count);
    }

    public void recordModelLoad() {
        if (enabled) {
            modelLoadCounter.increment();
        }
    }

    public void recordModelUnload() {
        if (enabled) {
            modelUnloadCounter.increment();
        }
    }

    public void recordModelLoadDuration(long durationMs) {
        if (enabled) {
            modelLoadDuration.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    // Request metrics methods
    public void recordRequest() {
        if (enabled) {
            requestsTotal.increment();
        }
    }

    public void recordRequestError() {
        if (enabled) {
            requestsErrors.increment();
        }
    }

    public void recordTokens(int count) {
        if (enabled) {
            tokensTotal.increment(count);
        }
    }

    public void recordRequestDuration(long durationMs) {
        if (enabled) {
            requestDuration.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Get Prometheus metrics output.
     */
    public String getMetrics() {
        return registry.scrape();
    }
}