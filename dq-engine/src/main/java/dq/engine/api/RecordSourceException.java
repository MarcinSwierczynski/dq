package dq.engine.api;

/** The input stream behind a {@link RecordSource} is broken (I/O failure, malformed JSON, …). */
public class RecordSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RecordSourceException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecordSourceException(String message) {
        super(message);
    }
}
