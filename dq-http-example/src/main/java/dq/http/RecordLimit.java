package dq.http;

import dq.engine.source.RecordSource;
import dq.engine.source.SourceElement;

/** Decorates a source so that a buffered (non-streaming) response cannot grow without bound. */
final class RecordLimit implements RecordSource {
    private final RecordSource delegate;
    private final int max;
    private int count;

    RecordLimit(RecordSource delegate, int max) {
        this.delegate = delegate;
        this.max = max;
    }

    @Override
    public SourceElement next() {
        SourceElement element = delegate.next();
        if (element != null && ++count > max) {
            throw new Exceeded(max);
        }
        return element;
    }

    @Override
    public void close() {
        delegate.close();
    }

    static final class Exceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exceeded(int max) {
            super("more than " + max + " records");
        }
    }
}
