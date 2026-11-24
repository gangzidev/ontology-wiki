package com.ontologywiki.ontology.service.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 当前分析执行器实例自启动以来的统计，只保存固定数量的计数器。 */
final class AnalysisRuntimeMetrics {
    final Timing queueWait = new Timing();
    final Timing scan = new Timing();
    final Timing wikiDocuments = new Timing();
    final Timing businessRules = new Timing();
    final Timing generation = new Timing();
    final Timing modelEnrichment = new Timing();
    final Timing nameCompletion = new Timing();
    final Timing model = new Timing();
    final AtomicLong dispatchFailures = new AtomicLong();
    private final long since = System.currentTimeMillis();

    Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("since", since);
        values.put("dispatchFailures", dispatchFailures.get());
        values.put("queueWait", queueWait.snapshot());
        values.put("scan", scan.snapshot());
        values.put("wikiDocuments", wikiDocuments.snapshot());
        values.put("businessRules", businessRules.snapshot());
        values.put("generation", generation.snapshot());
        values.put("modelEnrichment", modelEnrichment.snapshot());
        values.put("nameCompletion", nameCompletion.snapshot());
        values.put("model", model.snapshot());
        return values;
    }

    static final class Timing {
        private long count;
        private long failures;
        private long totalNanos;
        private long maxNanos;

        <T> T measure(Supplier<T> operation) {
            long start = System.nanoTime();
            boolean success = false;
            try {
                T result = operation.get();
                success = true;
                return result;
            } finally {
                record(System.nanoTime() - start, success);
            }
        }

        synchronized void record(long nanos, boolean success) {
            count++;
            if (!success) failures++;
            totalNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
        }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("count", count);
            values.put("failures", failures);
            values.put("meanMs", count == 0 ? null : totalNanos / (double) count / 1_000_000);
            values.put("maxMs", count == 0 ? null : maxNanos / 1_000_000.0);
            return values;
        }
    }
}
