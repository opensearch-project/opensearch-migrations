package org.opensearch.migrations.transform;

import java.io.Reader;
import java.util.Map;

/**
 * This is a simple interface to convert a JSON object (String, Map, or Array) into another
 * JSON object.  Any changes to datastructures, nesting, order, etc should be intentional.
 */
public interface ITextTransformer {
    Reader transformJson(Reader incomingText);
}
