package dq.engine.catalog;

/**
 * A rule or catalog definition is invalid (duplicate or blank id, blank label, empty mapping, …).
 * Thrown while building rules, mappings or the engine — never while records are evaluated.
 */
public final class InvalidCatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidCatalogException(String message) {
        super(message);
    }

    public InvalidCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
