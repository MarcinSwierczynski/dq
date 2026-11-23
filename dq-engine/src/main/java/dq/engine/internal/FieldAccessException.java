package dq.engine.internal;

import dq.engine.api.ErrorKind;

/** Thrown by {@link TrackingFieldReader} to fail one evaluation with a specific {@link ErrorKind}. */
final class FieldAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorKind kind;

    FieldAccessException(ErrorKind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    ErrorKind kind() {
        return kind;
    }
}
