package org.opensearch.migrations.transform.jinjava;

import java.nio.charset.Charset;
import java.util.Map;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.loader.ResourceLocator;
import com.hubspot.jinjava.loader.ResourceNotFoundException;

public class InlineTemplateResourceLocator implements ResourceLocator {

    private final Map<String, String> templates;

    public InlineTemplateResourceLocator(Map<String, String> templates) {
        this.templates = templates;
    }

    @Override
    public String getString(String fullName, Charset encoding, JinjavaInterpreter interpreter)
        throws ResourceNotFoundException {
        if (templates.containsKey(fullName)) {
            return templates.get(fullName);
        }
        throw new ResourceNotFoundException("Template not found: " + fullName);
    }
}
