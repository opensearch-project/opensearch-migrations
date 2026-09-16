package org.opensearch.migrations.bulkload.pipeline.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps a source kind to the provider that serves it.
 *
 * <p>Providers are discovered with {@link ServiceLoader}, as {@code IJsonTransformerProvider}
 * already is. The map is built and checked once — a blank or duplicate kind fails there, naming the
 * provider class — while source construction stays lazy.
 */
@Slf4j
public final class DocumentSourceRegistry {

    private final Map<String, DocumentSourceProvider<?>> providersByKind;

    private DocumentSourceRegistry(Map<String, DocumentSourceProvider<?>> providersByKind) {
        this.providersByKind = providersByKind;
    }

    /** Holder so the default registry is built on first use, not at class-load time. */
    private static class DefaultHolder {
        private static final DocumentSourceRegistry INSTANCE = fromServiceLoader();
    }

    /** The registry of providers on the application classpath. */
    public static DocumentSourceRegistry getDefault() {
        return DefaultHolder.INSTANCE;
    }

    /** Discover providers via {@link ServiceLoader} on the current thread's context classloader. */
    public static DocumentSourceRegistry fromServiceLoader() {
        var loader = ServiceLoader.load(
            DocumentSourceProvider.class,
            Thread.currentThread().getContextClassLoader());
        List<DocumentSourceProvider<?>> discovered = loader.stream()
            .map(ServiceLoader.Provider::get)
            .map(p -> (DocumentSourceProvider<?>) p)
            .collect(Collectors.toList());
        return of(discovered);
    }

    /** Build a registry from an explicit provider list. Used by tests and by {@link #fromServiceLoader()}. */
    public static DocumentSourceRegistry of(List<? extends DocumentSourceProvider<?>> providers) {
        Map<String, DocumentSourceProvider<?>> byKind = new LinkedHashMap<>();
        for (var provider : providers) {
            var kind = provider.kind();
            if (kind == null || kind.isBlank()) {
                throw new IllegalStateException(
                    "Document source provider " + provider.getClass().getName() + " declares a blank kind");
            }
            var normalized = normalize(kind);
            var existing = byKind.putIfAbsent(normalized, provider);
            if (existing != null) {
                throw new IllegalStateException("Document source kind '" + normalized + "' is claimed by both "
                    + existing.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
        log.atInfo().setMessage("Discovered {} document source provider(s): {}")
            .addArgument(byKind::size)
            .addArgument(byKind::keySet)
            .log();
        return new DocumentSourceRegistry(byKind);
    }

    /** Resolve a provider by kind, or fail naming what is available. */
    public DocumentSourceProvider<?> resolve(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("No document source kind was requested; available kinds: " + kinds());
        }
        var provider = providersByKind.get(normalize(kind));
        if (provider == null) {
            throw new IllegalArgumentException(
                "Unknown document source kind '" + kind + "'; available kinds: " + kinds());
        }
        return provider;
    }

    /** The kinds this registry can serve, in discovery order. */
    public List<String> kinds() {
        return List.copyOf(providersByKind.keySet());
    }

    private static String normalize(String kind) {
        return kind.trim().toLowerCase(Locale.ROOT);
    }
}
