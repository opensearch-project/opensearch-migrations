package org.opensearch.migrations;

import org.apache.logging.log4j.core.parser.ParseException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class VersionTest {

    @Test
    void fromString() throws ParseException {
        var expected = Version.builder().flavor(Flavor.OPENSEARCH).major(1).minor(3).patch(18).build();
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
        var expected = Version.builder().flavor(Flavor.OPENSEARCH).major(1).minor(3).patch(0).build();
        assertThat(Version.fromString("OpenSearch 1.3.0"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.3.x"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch  1.3"), equalTo(expected));
    }

    @Test
    void fromString_defaultMinor() throws ParseException {
        var expected = Version.builder().flavor(Flavor.OPENSEARCH).major(1).minor(0).patch(0).build();
        assertThat(Version.fromString("OpenSearch 1.0.0"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.0"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.x.x"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch 1.x"), equalTo(expected));
        assertThat(Version.fromString("OpenSearch  1"), equalTo(expected));
    }

    @Test
    void parseAllPossibleNames() throws ParseException {
        for (var testCase : Flavor.values()) {
            var expected = Version.builder().flavor(testCase).major(4).build();
            assertThat(Version.fromString(testCase.shorthand + " 4.0.0") , equalTo(expected));
            assertThat(Version.fromString(testCase.name() + " 4.0.0") , equalTo(expected));
        }
    }
}
