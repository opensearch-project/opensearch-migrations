package org.opensearch.migrations.transform.jinjava;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.google.common.io.Resources;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.loader.ClasspathResourceLocator;
import com.hubspot.jinjava.loader.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NameMappingClasspathResourceLocator extends ClasspathResourceLocator {

    private String getDefaultVersion(final String fullName) throws IOException {
        try {
            var versionFile = fullName + "/defaultVersion";
            var versionLines = Resources.readLines(Resources.getResource(versionFile), StandardCharsets.UTF_8).stream()
                .filter(s->!s.isEmpty())
                .collect(Collectors.toList());
            if (versionLines.size() == 1) {
                return fullName + "/" + versionLines.get(0).trim();
            }
            throw new IllegalStateException("Expected defaultVersion resource to contain a single line with a name");
        } catch (ResourceNotFoundException e) {
            log.atTrace().setCause(e).setMessage("Caught ResourceNotFoundException, but this is expected").log();
        }
        return fullName;
    }

    @Override
    public String getString(String fullName, Charset encoding, JinjavaInterpreter interpreter) throws IOException {
        return super.getString(getDefaultVersion("jinjava/" + fullName), encoding, interpreter);
    }
}
