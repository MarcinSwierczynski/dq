package dq.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dq.engine.api.CountingListener;
import dq.engine.api.DqEngine;
import dq.engine.api.RecordSources;
import dq.engine.api.RunOptions;
import dq.engine.api.RunStatus;
import dq.engine.api.RunSummary;
import dq.engine.fixtures.Fixture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * F5: one million records through the real streaming path ({@code RecordSources.jsonArray}). Runs in
 * the {@code scaleTest} Gradle task with {@code -Xmx64m}; a run that accumulated outcomes or records
 * (3M results ≈ hundreds of MB) could not finish under that cap.
 */
@Tag("scale")
class ScaleTest {

    private static final int RECORDS = 1_000_000;

    @Test
    void oneMillionRecordsInBoundedMemory() {
        DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();
        var listener = new CountingListener();
        long maxHeap = Runtime.getRuntime().maxMemory();

        RunSummary summary = engine.validate(RecordSources.jsonArray(new GeneratedRecords(RECORDS)), listener,
                RunOptions.builder().batchSize(1000).build());

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.recordsRead()).isEqualTo(RECORDS);
        assertThat(summary.resultCount() + summary.errorCount()).isEqualTo(3L * RECORDS);
        assertThat(listener.results() + listener.errors()).isEqualTo(3L * RECORDS);
        assertThat(listener.batches()).isEqualTo(RECORDS / 1000);
        System.out.printf(java.util.Locale.ROOT, "scale: %,d records, %,d outcomes in %d ms (%,.0f records/s), max heap %d MB%n",
                summary.recordsRead(), summary.resultCount() + summary.errorCount(), summary.duration().toMillis(),
                summary.recordsRead() / Math.max(0.001, summary.duration().toNanos() / 1e9), maxHeap >> 20);
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
