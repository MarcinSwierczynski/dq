package dq.http;

import dq.engine.api.BatchProgress;
import dq.engine.api.EvaluationError;
import dq.engine.api.Result;
import dq.engine.api.ValidationListener;
import dq.http.Views.ErrorView;
import dq.http.Views.RecordView;
import dq.http.Views.RuleResultView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Groups the engine's per-outcome callbacks into one {@link RecordView} per input record.
 *
 * <p>The engine delivers outcomes in input order and, within a record, in catalog order, so a record is
 * complete as soon as an outcome for another record (or the end of a batch) arrives. Only the current
 * record's outcomes are held, so memory stays bounded by one record. Records are told apart by the
 * engine's record index, because ids may repeat; the index itself is not part of the reply.
 * Call {@link #flush()} once the run ends, whatever its status.
 */
final class RecordGrouping implements ValidationListener {
    private final Consumer<RecordView> sink;

    private long index = -1;
    private String recordId;
    private Map<String, RuleResultView> results = new LinkedHashMap<>();
    private List<ErrorView> errors = new ArrayList<>();

    RecordGrouping(Consumer<RecordView> sink) {
        this.sink = sink;
    }

    @Override
    public void onResult(Result result) {
        startRecord(result.provenance().recordIndex(), result.recordId());
        results.put(result.ruleId(), RuleResultView.of(result));
    }

    @Override
    public void onError(EvaluationError error) {
        startRecord(error.recordIndex(), error.recordId());
        errors.add(ErrorView.of(error));
    }

    /** A record never spans batches, so the end of a batch also ends the current record. */
    @Override
    public void onBatchComplete(BatchProgress progress) {
        flush();
    }

    /** Emits the record collected so far, if any. */
    void flush() {
        if (index < 0) {
            return;
        }
        RecordView view = new RecordView(recordId, results, errors);
        index = -1;
        recordId = null;
        results = new LinkedHashMap<>();
        errors = new ArrayList<>();
        sink.accept(view);
    }

    private void startRecord(long recordIndex, String id) {
        if (recordIndex != index) {
            flush();
            index = recordIndex;
            recordId = id;
        }
    }
}
