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

/** Writes one JSON line per outcome and flushes the socket after every batch. */
final class NdjsonListener implements ValidationListener {
    private final OutputStream out;
    private final JsonMapper json;

    NdjsonListener(OutputStream out, JsonMapper json) {
        this.out = new BufferedOutputStream(out, 64 * 1024);
        this.json = json;
    }

    @Override
    public void onResult(Result result) {
        line("result", Views.ResultView.of(result));
    }

    @Override
    public void onError(EvaluationError error) {
        line("error", Views.ErrorView.of(error));
    }

    @Override
    public void onBatchComplete(BatchProgress progress) {
        flush();
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
