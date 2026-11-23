package dq.http;

import dq.engine.api.BatchProgress;
import dq.engine.api.EvaluationError;
import dq.engine.api.Result;
import dq.engine.api.ValidationListener;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes one {@code {"type":"record",…}} line per input record (see {@link RecordGrouping}) and flushes
 * the socket after every batch. Call {@link #finish()} when the run ends, before the trailer line.
 */
final class NdjsonListener implements ValidationListener {
    private final OutputStream out;
    private final JsonMapper json;
    private final RecordGrouping grouping = new RecordGrouping(record -> line("record", record));

    NdjsonListener(OutputStream out, JsonMapper json) {
        this.out = new BufferedOutputStream(out, 64 * 1024);
        this.json = json;
    }

    @Override
    public void onResult(Result result) {
        grouping.onResult(result);
    }

    @Override
    public void onError(EvaluationError error) {
        grouping.onError(error);
    }

    @Override
    public void onBatchComplete(BatchProgress progress) {
        grouping.onBatchComplete(progress);
        flush();
    }

    /** Writes the last, possibly partial, record of the run. */
    void finish() {
        grouping.flush();
    }

    /** Writes {@code {"type": type, ...fields of payload}} followed by a newline. */
    void line(String type, Object payload) {
        ObjectNode node = json.createObjectNode().put("type", type);
        node.setAll(json.<ObjectNode>valueToTree(payload));
        try {
            out.write(json.writeValueAsBytes(node));
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
