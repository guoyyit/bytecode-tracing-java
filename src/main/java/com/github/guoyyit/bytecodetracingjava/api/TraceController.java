package com.github.guoyyit.bytecodetracingjava.api;

import com.github.guoyyit.bytecodetracingjava.service.TraceService;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodDescriptor;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodStats;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceAggregator;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceEvent;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilterSpec;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceSnapshot;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST endpoints for snapshots, deltas, sampling and runtime instrumentation. */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceService service;

    public TraceController(TraceService service) {
        this.service = service;
    }

    /** Consistent point-in-time snapshot; optionally filtered by method name fragment. */
    @GetMapping("/snapshot")
    public SnapshotView snapshot(@RequestParam(required = false) String method) {
        TraceSnapshot snapshot = service.snapshot();
        List<MethodStatsView> methods = new ArrayList<>();
        for (Map.Entry<MethodDescriptor, MethodStats> entry : snapshot.methodStats().entrySet()) {
            if (method != null && !entry.getKey().displayName().contains(method)) {
                continue;
            }
            methods.add(MethodStatsView.of(entry.getKey(), entry.getValue()));
        }
        Map<Long, TraceSnapshot.ThreadStatsView> threads = snapshot.threadStats();
        return new SnapshotView(snapshot.sequence(), snapshot.timestampMillis(), methods,
                threads, snapshot.droppedEvents());
    }

    /** Incremental read: events with sequence greater than {@code since}. */
    @GetMapping("/delta")
    public TraceAggregator.Delta delta(@RequestParam long since) {
        return service.aggregator().delta(since);
    }

    /** Recent-event query by method, thread and time window. */
    @GetMapping("/events")
    public List<TraceEvent> events(@RequestParam(required = false) Integer methodId,
                                   @RequestParam(required = false) Long threadId,
                                   @RequestParam(defaultValue = "0") long from,
                                   @RequestParam(defaultValue = "9223372036854775807") long to,
                                   @RequestParam(defaultValue = "1000") int limit) {
        return service.aggregator().query(methodId, threadId, from, to, limit);
    }

    @GetMapping("/config")
    public ConfigView config() {
        return new ConfigView(service.config().isEnabled(),
                service.config().getSamplingRate(),
                service.config().getMaxDepth());
    }

    @PostMapping("/config")
    public ConfigView updateConfig(@RequestBody ConfigView update) {
        if (update.enabled() != null) {
            service.config().setEnabled(update.enabled());
        }
        if (update.samplingRate() != null) {
            service.config().setSamplingRate(update.samplingRate());
        }
        if (update.maxDepth() != null) {
            service.config().setMaxDepth(update.maxDepth());
        }
        return config();
    }

    /** Adds instrumentation to an already-loaded class. */
    @PostMapping("/instrument")
    public Map<String, Object> instrument(@RequestBody InstrumentRequest request) {
        TraceFilter filter = request.toFilter();
        service.instrumentClass(request.className(), filter);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("className", request.className());
        response.put("instrumented", true);
        return response;
    }

    /** Removes instrumentation, restoring the original class bytes. */
    @DeleteMapping("/instrument")
    public Map<String, Object> detach(@RequestParam String className) {
        service.detachClass(className);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("className", className);
        response.put("instrumented", false);
        return response;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        service.aggregator().reset();
        return Map.of("reset", true);
    }

    @GetMapping("/methods")
    public List<MethodDescriptor> methods() {
        return service.registry().all();
    }

    /** Bad input (unknown class, invalid rate, ...) maps to a 400 with a clear message. */
    @org.springframework.web.bind.annotation.ExceptionHandler({
            IllegalArgumentException.class,
            com.github.guoyyit.bytecodetracingjava.instrument.InstrumentationException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage()));
    }

    public record ConfigView(Boolean enabled, Double samplingRate, Integer maxDepth) {
    }

    public record InstrumentRequest(String className, String methodNamePattern,
                                    List<String> visibilities, List<String> requiredAnnotations) {
        TraceFilter toFilter() {
            TraceFilterSpec.Builder builder = TraceFilterSpec.builder();
            if (methodNamePattern != null) {
                builder.methodNamePattern(methodNamePattern);
            }
            if (visibilities != null && !visibilities.isEmpty()) {
                builder.visibilities(visibilities.stream()
                        .map(v -> TraceFilterSpec.Visibility.valueOf(v.toUpperCase()))
                        .toArray(TraceFilterSpec.Visibility[]::new));
            }
            if (requiredAnnotations != null && !requiredAnnotations.isEmpty()) {
                builder.requiredAnnotations(requiredAnnotations.toArray(String[]::new));
            }
            return builder.build();
        }
    }

    public record MethodStatsView(String method, long count, long errorCount, long totalNanos,
                                  long maxNanos, long minNanos, double avgNanos,
                                  long maxDepthObserved) {
        static MethodStatsView of(MethodDescriptor descriptor, MethodStats stats) {
            long count = stats.count();
            return new MethodStatsView(descriptor.displayName(), count, stats.errorCount(),
                    stats.totalNanos(), stats.maxNanos(), stats.minNanos(),
                    count == 0 ? 0.0 : stats.totalNanos() / (double) count,
                    stats.maxDepthObserved());
        }
    }

    public record SnapshotView(long sequence, long timestampMillis, List<MethodStatsView> methods,
                               Map<Long, TraceSnapshot.ThreadStatsView> threadStats,
                               long droppedEvents) {
    }
}
