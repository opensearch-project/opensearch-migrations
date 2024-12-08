package org.opensearch.migrations.transform.jinjava;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.Resources;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.loader.ClasspathResourceLocator;
import com.hubspot.jinjava.loader.ResourceNotFoundException;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NameMappingClasspathResourceLocator extends ClasspathResourceLocator {
    final Map<String, String> overrideResourceMap;

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    private static class ResourceCacheKey {
        private String fullName;
        private Charset encoding;
    }

    private final LoadingCache<ResourceCacheKey, String> resourceCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<>() {
            @Override
            public String load(ResourceCacheKey key) throws IOException {
                try {
                    String versionedName = getDefaultVersion("jinjava/" + key.getFullName());
                    return Resources.toString(Resources.getResource(versionedName), key.getEncoding());
                } catch (IllegalArgumentException e) {
                    throw new ResourceNotFoundException("Couldn't find resource: " + key.getFullName());
                }
            }
        });

    public NameMappingClasspathResourceLocator(Map<String, String> overrideResourceMap) {
        this.overrideResourceMap = Optional.ofNullable(overrideResourceMap).orElse(Map.of());
    }

    private static String getDefaultVersion(final String fullName) throws IOException {
        try {
            var versionFile = fullName + "/defaultVersion";
            var versionLines = Resources.readLines(Resources.getResource(versionFile), StandardCharsets.UTF_8).stream()
                .filter(s->!s.isEmpty())
                .collect(Collectors.toList());
            if (versionLines.size() == 1) {
                return fullName + "/" + versionLines.get(0).trim();
            }
            throw new IllegalStateException("Expected defaultVersion resource to contain a single line with a name");
        } catch (IllegalArgumentException e) {
            log.atTrace().setCause(e).setMessage("Caught ResourceNotFoundException, but this is expected").log();
        }
        return fullName;
    }

    @Override
    public String getString(String fullName, Charset encoding, JinjavaInterpreter interpreter) throws IOException {
        var overrideResource = overrideResourceMap.get(fullName);
        if (overrideResource != null) {
            return overrideResource;
        }
        try {
            return resourceCache.get(new ResourceCacheKey(fullName, encoding));
        } catch (ExecutionException e) {
            throw new IOException("Failed to get resource content named `" + fullName + "`from cache", e);
        }
    }
}
