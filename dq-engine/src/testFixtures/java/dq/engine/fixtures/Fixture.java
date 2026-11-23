package dq.engine.fixtures;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared test data. */
public final class Fixture {
    private Fixture() {}

    /** An ordered record from alternating key/value pairs. */
    public static Map<String, Object> record(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
