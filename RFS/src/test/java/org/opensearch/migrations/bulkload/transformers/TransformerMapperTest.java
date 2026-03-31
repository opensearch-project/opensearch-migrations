package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionStrictness;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;


public class TransformerMapperTest {

    @Test
    void testGetTransformer_strict_found() {
        var params = mock(MetadataTransformerParams.class);
        var mapper = new TransformerMapper(Version.fromString("ES 5.0"), Version.fromString("OS 3"));

        var transformer = mapper.getTransformer(0, params, false);
        assertThat(transformer, notNullValue());
        assertThat(transformer, instanceOf(CanonicalTransformer.class));
    }

    @Test
    void testGetTransformer_strict_notFound() {
        var mapper = new TransformerMapper(Version.fromString("ES 1.0"), Version.fromString("OS 999"));

        var exception = assertThrows(IllegalArgumentException.class, () -> mapper.getTransformer(0, null, false));
        assertThat(exception.getMessage(), containsString(VersionStrictness.REMEDIATION_MESSAGE));
    }

    @Test
    void testGetTransformer_loose_found() {
        var params = mock(MetadataTransformerParams.class);
        var mapper = new TransformerMapper(Version.fromString("ES 1.0"), Version.fromString("OS 999"));

        var transformer = mapper.getTransformer(0, params, true);
        assertThat(transformer, notNullValue());
        assertThat(transformer, instanceOf(CanonicalTransformer.class));
    }

    @Test
    void testGetTransformer_loose_notFound() {
        var mapper = new TransformerMapper(Version.fromString("OS 1.0"), Version.fromString("ES 2"));

        var exception = assertThrows(IllegalArgumentException.class, () -> mapper.getTransformer(0, null, true));

        assertThat(exception.getMessage(), not(containsString(VersionStrictness.REMEDIATION_MESSAGE)));
    }
}
