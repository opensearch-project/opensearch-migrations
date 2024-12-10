package org.opensearch.migrations.transform.jinjava;

import com.hubspot.jinjava.doc.annotations.JinjavaDoc;
import com.hubspot.jinjava.doc.annotations.JinjavaParam;
import com.hubspot.jinjava.doc.annotations.JinjavaSnippet;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.tag.Tag;
import com.hubspot.jinjava.tree.TagNode;

@JinjavaDoc(
    value = "Throws a runtime exception with the specified message",
    params = {
        @JinjavaParam(value = "message", type = "string", desc = "The error message to throw")
    },
    snippets = {
        @JinjavaSnippet(
            code = "{% throw 'Invalid input provided' %}"
        )
    }
)

public class ThrowTag implements Tag {
    private static final String TAG_NAME = "throw";

    @Override
    public String getName() {
        return TAG_NAME;
    }

    @Override
    public String interpret(TagNode tagNode, JinjavaInterpreter interpreter) {
        String message = interpreter.render(tagNode.getHelpers().trim());
        throw new JinjavaThrowTagException(message);
    }

    public static class JinjavaThrowTagException extends RuntimeException {
        public JinjavaThrowTagException(String message) {
            super(message);
        }
    }

    @Override
    public String getEndTagName() {
        return null;
    }
}
