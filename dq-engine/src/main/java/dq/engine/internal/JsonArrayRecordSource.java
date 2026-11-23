package dq.engine.internal;

import dq.engine.api.RecordSource;
import dq.engine.api.RecordSourceException;
import dq.engine.api.SourceElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a JSON array of objects and hands out its elements one by one. The whole array is parsed on the
 * first {@link #next()} — nothing is read before that.
 */
public final class JsonArrayRecordSource implements RecordSource {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {};

    private final InputStream in;
    private Iterator<JsonNode> elements;

    public JsonArrayRecordSource(InputStream in) {
        this.in = in;
    }

    @Override
    public SourceElement next() {
        if (elements == null) {
            elements = Collections.emptyIterator(); // a failed parse is not retried
            elements = parse().iterator();
        }
        if (!elements.hasNext()) {
            return null;
        }
        JsonNode element = elements.next();
        if (element.isObject()) {
            return SourceElement.of(MAPPER.convertValue(element, FIELDS));
        }
        return SourceElement.unreadable("element is a JSON " + describe(element) + ", expected an object");
    }

    private JsonNode parse() {
        JsonNode root;
        try {
            root = MAPPER.readTree(in);
        } catch (JacksonIOException e) {
            // Must precede JacksonException, its supertype: an I/O failure is not a syntax error.
            throw new RecordSourceException("could not read input: " + ioMessage(e), e);
        } catch (JacksonException e) {
            throw new RecordSourceException("malformed JSON input: " + e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new RecordSourceException("input must be a JSON array, found no content");
        }
        if (!root.isArray()) {
            throw new RecordSourceException("input must be a JSON array, found a JSON " + describe(root));
        }
        return root;
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String ioMessage(JacksonIOException e) {
        return e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage()
                : e.getOriginalMessage();
    }

    private static String describe(JsonNode node) {
        return node.getNodeType().name().toLowerCase(Locale.ROOT);
    }
}
