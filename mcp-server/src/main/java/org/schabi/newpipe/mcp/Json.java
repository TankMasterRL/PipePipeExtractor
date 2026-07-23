package org.schabi.newpipe.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small helper for building ordered JSON object maps that the MCP SDK (Jackson) serializes.
 */
final class Json {

    private Json() {
    }

    /**
     * Build an ordered map from alternating key/value arguments, skipping any entry whose value
     * is {@code null} so absent fields are simply omitted from the resulting JSON.
     *
     * @param keyValues alternating {@code String} keys and their values
     * @return a {@link LinkedHashMap} preserving insertion order
     */
    static Map<String, Object> obj(final Object... keyValues) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            final Object value = keyValues[i + 1];
            if (value != null) {
                map.put((String) keyValues[i], value);
            }
        }
        return map;
    }
}
