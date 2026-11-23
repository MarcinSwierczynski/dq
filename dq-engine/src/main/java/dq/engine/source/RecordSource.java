package dq.engine.source;

/**
 * Supplies records to a run, one at a time. Hosts implement it over a Kafka consumer, a JDBC cursor,
 * an object store, …; {@link RecordSources} has the built-in ones. {@code validate} pulls elements on
 * the calling thread and closes the source when the run ends, whatever the outcome.
 *
 * <p>Report an element that is not a record as {@link SourceElement#unreadable}; the run continues.
 * Throw (ideally {@link RecordSourceException}) only when the stream itself is broken; that aborts the run.
 */
public interface RecordSource extends AutoCloseable {

    /** The next element, or {@code null} when the source is exhausted. */
    SourceElement next();

    /**
     * Releases resources. Called exactly once by {@code validate}, after the last outcome was delivered.
     * An exception thrown here is logged and does not change the run's result. Does nothing by default.
     */
    @Override
    default void close() {}
}
