package dq.engine.internal;

import dq.engine.api.RecordSource;
import dq.engine.api.RecordSourceException;
import dq.engine.api.SourceElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Streams a JSON array of objects: holds one element at a time, never the whole array. Created lazily —
 * nothing is read before the first {@link #next()}.
 */
public final class JsonArrayRecordSource implements RecordSource {
    // Elements are read one by one from a shared parser, so the rest of the array is not "trailing".
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {};

    private final InputStream in;
    private JsonParser parser;
    private boolean finished;

    public JsonArrayRecordSource(InputStream in) {
        this.in = in;
    }

    @Override
    public SourceElement next() {
        if (finished) {
            return null;
        }
        try {
            if (parser == null) {
                parser = MAPPER.createParser(in);
                JsonToken first = parser.nextToken();
                if (first != JsonToken.START_ARRAY) {
                    throw new RecordSourceException("input must be a JSON array, found "
                            + (first == null ? "no content" : "a JSON " + describe(first))
                            + location());
                }
            }
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new RecordSourceException("unexpected end of input inside the JSON array" + location());
            }
            return switch (token) {
                case END_ARRAY -> {
                    finished = true;
                    if (parser.nextToken() != null) {
                        throw new RecordSourceException("unexpected content after the JSON array" + location());
                    }
                    yield null;
                }
                case START_OBJECT -> SourceElement.of(MAPPER.readValue(parser, FIELDS));
                case START_ARRAY -> {
                    parser.skipChildren();
                    yield SourceElement.unreadable("element is a JSON array, expected an object");
                }
                default -> SourceElement.unreadable("element is a JSON " + describe(token) + ", expected an object");
            };
        } catch (JacksonIOException e) {
            // Must precede JacksonException, its supertype: an I/O failure is not a syntax error.
            finished = true;
            throw new RecordSourceException("could not read input: " + ioMessage(e), e);
        } catch (JacksonException e) {
            finished = true;
            throw new RecordSourceException("malformed JSON input: " + e.getOriginalMessage() + location(), e);
        } catch (RecordSourceException e) {
            finished = true;
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            if (parser != null) {
                parser.close();
            }
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static String ioMessage(JacksonIOException e) {
        return e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage()
                : e.getOriginalMessage();
    }

    private String location() {
        if (parser == null) {
            return "";
        }
        var loc = parser.currentLocation();
        return " (line " + loc.getLineNr() + ", column " + loc.getColumnNr() + ")";
    }

    private static String describe(JsonToken token) {
        return switch (token) {
            case VALUE_STRING -> "string";
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "number";
            case VALUE_TRUE, VALUE_FALSE -> "boolean";
            case VALUE_NULL -> "null";
            case START_OBJECT -> "object";
            default -> token.name();
        };
    }
}
