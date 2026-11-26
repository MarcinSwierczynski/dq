package dq.engine.json;

import static org.assertj.core.api.Assertions.assertThat;

import dq.engine.fixtures.Fixture;
import dq.engine.run.CollectingListener;
import dq.engine.run.CountingListener;
import dq.engine.run.DqEngine;
import dq.engine.run.RunOptions;
import dq.engine.run.RunStatus;
import dq.engine.run.RunSummary;
import java.io.InputStream;
import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * F5: one million records through the real streaming path ({@code JsonRecordSources.jsonArray}). Runs in
 * the {@code scaleTest} Gradle task with {@code -Xmx64m}; a run that accumulated outcomes or records
 * (3M results ≈ hundreds of MB) could not finish under that cap. Beyond finishing, it measures the heap
 * left after every garbage collection during the run, and requires that peak to stay within
 * {@link #MAX_LIVE_GROWTH_BYTES} of the heap before the run: memory does not grow with the input.
 */
@Tag("scale")
class ScaleTest {

    private static final int RECORDS = 1_000_000;
    /** A quarter of the 64 MB cap. Measured: about 1-2 MB, sequential and parallel. */
    private static final long MAX_LIVE_GROWTH_BYTES = 16L << 20;
    /** Few enough that keeping all their outcomes fits under the cap, enough to exceed the bound. */
    private static final int LEAK_RECORDS = 20_000;

    @Test
    void oneMillionRecordsInBoundedMemory() {
        runAndCheck("sequential", RunOptions.builder().batchSize(1000).build());
    }

    @Test
    void oneMillionRecordsInParallelInBoundedMemory() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            runAndCheck("parallel x" + threads, RunOptions.builder().batchSize(1000).executor(pool).build());
        }
    }

    /** Control: the same measurement does see a listener that keeps its outcomes, on a far smaller input. */
    @Test
    void theMeasurementDetectsALeakingListener() {
        DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();
        var keepsEverything = new CollectingListener();
        HeapAfterGc heap;
        try (var watch = HeapAfterGc.watch()) {
            engine.validate(JsonRecordSources.jsonArray(new GeneratedRecords(LEAK_RECORDS)), keepsEverything);
            System.gc(); // one collection after the run, so the retained outcomes are measured
            heap = watch;
            awaitNotification(heap);
        }
        assertThat(keepsEverything.results()).hasSize(3 * LEAK_RECORDS);
        assertThat(heap.peakBytes() - heap.baselineBytes()).as("live heap growth with retained outcomes, bytes")
                .isGreaterThan(MAX_LIVE_GROWTH_BYTES);
        System.out.printf(java.util.Locale.ROOT, "scale (control, %,d records kept): heap after GC: peak %.1f MB, "
                + "baseline %.1f MB%n", LEAK_RECORDS, heap.peakBytes() / 1048576.0, heap.baselineBytes() / 1048576.0);
    }

    /** GC notifications arrive asynchronously; waits briefly for the one after an explicit {@code System.gc()}. */
    private static void awaitNotification(HeapAfterGc heap) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        int seen = heap.collections();
        while (heap.collections() == seen && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void runAndCheck(String mode, RunOptions options) {
        DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();
        var listener = new CountingListener();
        long maxHeap = Runtime.getRuntime().maxMemory();

        RunSummary summary;
        HeapAfterGc heap;
        try (var watch = HeapAfterGc.watch()) {
            summary = engine.validate(JsonRecordSources.jsonArray(new GeneratedRecords(RECORDS)), listener, options);
            heap = watch;
        }

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.recordsRead()).isEqualTo(RECORDS);
        assertThat(summary.resultCount() + summary.errorCount()).isEqualTo(3L * RECORDS);
        assertThat(listener.results() + listener.errors()).isEqualTo(3L * RECORDS);
        assertThat(listener.batches()).isEqualTo(RECORDS / 1000);
        // The run must have been measured, and what survives collections must not grow with the input:
        // one batch of 1,000 records and its outcomes is well under a megabyte.
        assertThat(heap.collections()).as("GCs during the run").isPositive();
        assertThat(heap.peakBytes() - heap.baselineBytes()).as("live heap growth over the run, bytes")
                .isLessThan(MAX_LIVE_GROWTH_BYTES);
        System.out.printf(java.util.Locale.ROOT,
                "scale (%s): %,d records, %,d outcomes in %d ms (%,.0f records/s), max heap %d MB; "
                        + "heap after GC: peak %.1f MB, baseline %.1f MB, over %d GCs%n", mode,
                summary.recordsRead(), summary.resultCount() + summary.errorCount(), summary.duration().toMillis(),
                summary.recordsRead() / Math.max(0.001, summary.duration().toNanos() / 1e9), maxHeap >> 20,
                heap.peakBytes() / 1048576.0, heap.baselineBytes() / 1048576.0, heap.collections());
    }

    /**
     * Heap occupancy right after each garbage collection during a run, from the JVM's GC notifications.
     * What survives a collection is live data plus not-yet-collected old garbage, so the peak is an
     * upper bound on the run's live set. Raw "used" heap is no measure: it climbs to the cap before every
     * collection whatever the live set is.
     */
    static final class HeapAfterGc implements NotificationListener, AutoCloseable {
        private final List<NotificationEmitter> emitters = new ArrayList<>();
        private final Set<String> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP).map(MemoryPoolMXBean::getName)
                .collect(Collectors.toSet());
        private final long baselineBytes;
        private long peakBytes;
        private int collections;

        private HeapAfterGc() {
            System.gc();
            baselineBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener(this, null, null);
                    emitters.add(emitter);
                }
            }
        }

        static HeapAfterGc watch() {
            return new HeapAfterGc();
        }

        @Override
        public synchronized void handleNotification(Notification notification, Object handback) {
            if (!notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                return;
            }
            var info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            long after = info.getGcInfo().getMemoryUsageAfterGc().entrySet().stream()
                    .filter(pool -> heapPools.contains(pool.getKey()))
                    .mapToLong(pool -> pool.getValue().getUsed()).sum();
            peakBytes = Math.max(peakBytes, after);
            collections++;
        }

        synchronized long peakBytes() {
            return peakBytes;
        }

        long baselineBytes() {
            return baselineBytes;
        }

        synchronized int collections() {
            return collections;
        }

        @Override
        public void close() {
            for (NotificationEmitter emitter : emitters) {
                try {
                    emitter.removeNotificationListener(this);
                } catch (ListenerNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** A JSON array of {@code count} records produced lazily, one record's bytes at a time. */
    static final class GeneratedRecords extends InputStream {
        private static final String[] COUNTRIES = {"DE", "FR", "ZZ", "PL"};
        private final int count;
        private int next;
        private byte[] chunk = "[".getBytes(StandardCharsets.US_ASCII);
        private int pos;
        private boolean done;

        GeneratedRecords(int count) {
            this.count = count;
        }

        @Override
        public int read() {
            if (!fill()) {
                return -1;
            }
            return chunk[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            return n;
        }

        private boolean fill() {
            while (pos == chunk.length) {
                if (done) {
                    return false;
                }
                if (next == count) {
                    chunk = "]".getBytes(StandardCharsets.US_ASCII);
                    done = true;
                } else {
                    String country = COUNTRIES[next % COUNTRIES.length];
                    String vat = next % 3 == 0 ? "" : ",\"vatId\":\"" + country + "123456789\"";
                    chunk = ((next == 0 ? "" : ",") + "{\"id\":\"g" + next + "\",\"country\":\"" + country + "\""
                            + vat + ",\"legalName\":\"Partner " + next + "\",\"iban\":\"" + country + "00" + next + "\"}")
                            .getBytes(StandardCharsets.US_ASCII);
                    next++;
                }
                pos = 0;
            }
            return true;
        }
    }
}
