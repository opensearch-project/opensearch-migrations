package org.opensearch.migrations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.text.ParseException;

import org.junit.jupiter.api.Test;

public class VersionTest {

    @Test
    void fromString() throws ParseException {
        var expected = Version.builder().flavor(Flavor.OpenSearch).major(1).minor(3).patch(18).build();
        assertThat(Version.fromString("OpenSearch 1.3.18"), equalTo(expected));
        assertThat(Version.fromString("Opensearch 1.3.18"), equalTo(expected));
        assertThat(Version.fromString("Opensearch  1.3.18"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch_1_3_18"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch__1_3_18"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch______1_3_18"), equalTo(expected));
        assertThat(Version.fromString("OS 1.3.18"), equalTo(expected));
        assertThat(Version.fromString("OS_1_3_18"), equalTo(expected));
    }

    @Test
    void fromString_defaultPatch() throws ParseException {
        var expected = Version.builder().flavor(Flavor.OpenSearch).major(1).minor(3).patch(0).build();
        assertThat(Version.fromString("OpenSearch 1.3.0"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.3.x"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch  1.3"), equalTo(expected));
    }

    @Test
    void fromString_defaultMinor() throws ParseException {
        var expected = Version.builder().flavor(Flavor.OpenSearch).major(1).minor(0).patch(0).build();
        assertThat(Version.fromString("OpenSearch 1.0.0"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.0"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.x.x"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.x"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch  1"), equalTo(expected));
    }
}
