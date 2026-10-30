package org.opensearch.migrations.bulkload.pipeline.spi;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentSourceRegistryTest {

    private record StubSpec(String kind) implements DocumentSourceSpec {}

    private static class StubProvider implements DocumentSourceProvider<StubSpec> {
        private final String kind;

        StubProvider(String kind) {
            this.kind = kind;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public StubSpec parseSpec(JsonNode config) {
            return new StubSpec(kind);
        }

        @Override
        public DocumentSource create(StubSpec spec, SourceRuntime runtime) {
            throw new UnsupportedOperationException("not needed for registry tests");
        }
    }

    @Test
    void resolve_findsProviderByKind() {
        var provider = new StubProvider("es-snapshot");
        var registry = DocumentSourceRegistry.of(List.of(provider));

        assertThat(registry.resolve("es-snapshot"), sameInstance(provider));
        assertThat(registry.kinds(), contains("es-snapshot"));
    }

    @Test
    void resolve_normalizesCaseAndWhitespace() {
        var provider = new StubProvider("Es-Snapshot");
        var registry = DocumentSourceRegistry.of(List.of(provider));

        assertThat(registry.resolve("  ES-SNAPSHOT "), sameInstance(provider));
    }

    @Test
    void resolve_unknownKindNamesTheAvailableOnes() {
        var registry = DocumentSourceRegistry.of(List.of(new StubProvider("es-snapshot")));

        var thrown = assertThrows(IllegalArgumentException.class, () -> registry.resolve("nope"));
        assertThat(thrown.getMessage(), containsString("es-snapshot"));
    }

    @Test
    void resolve_blankKindIsRejected() {
        var registry = DocumentSourceRegistry.of(List.of(new StubProvider("es-snapshot")));

        assertThrows(IllegalArgumentException.class, () -> registry.resolve(" "));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(null));
    }

    /** Two providers claiming one kind is a build-time mistake; fail at registry construction. */
    @Test
    void duplicateKind_failsAtConstructionAndNamesBothProviders() {
        var thrown = assertThrows(IllegalStateException.class,
            () -> DocumentSourceRegistry.of(List.of(new StubProvider("dup"), new StubProvider("DUP"))));

        assertThat(thrown.getMessage(), containsString("dup"));
        assertThat(thrown.getMessage(), containsString(StubProvider.class.getName()));
    }

    @Test
    void blankProviderKind_failsAtConstruction() {
        var thrown = assertThrows(IllegalStateException.class,
            () -> DocumentSourceRegistry.of(List.of(new StubProvider("  "))));

        assertThat(thrown.getMessage(), containsString(StubProvider.class.getName()));
    }
}
