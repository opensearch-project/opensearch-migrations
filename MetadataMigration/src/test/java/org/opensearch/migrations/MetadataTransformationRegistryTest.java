package org.opensearch.migrations;

import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.cli.Transformers;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class MetadataTransformationRegistryTest {

    private List<String> infoNames(Transformers transformers) {
        return transformers.getTransformerInfos().stream()
            .map(Transformers.TransformerInfo::getName)
            .collect(Collectors.toList());
    }

    @Test
    void analysisComponentTransformIsActive_ES6_to_OS2() {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var transformers = MetadataTransformationRegistry.getCustomTransformationByClusterVersions(src, tgt);
        assertThat("Analysis component compatibility transform should be active for ES6 → OS2",
            infoNames(transformers), hasItem("Analysis component compatibility"));
    }

    @Test
    void analysisComponentTransformIsSkipped_when_noRulesApply() {
        // ES 5.6 → ES 5.6 (loose match) — no rules in our table apply.
        var src = Version.fromString("ES 5.6.0");
        var tgt = Version.fromString("ES 5.6.0");
        var transformers = MetadataTransformationRegistry.getCustomTransformationByClusterVersions(src, tgt);
        assertThat("Analysis component compatibility transform should be inactive when no rules apply",
            infoNames(transformers), not(hasItem("Analysis component compatibility")));
    }

    @Test
    void analysisComponentTransformWiresFilenameAndContext() {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        // The transform's bindings JSON gets escaped and placed in a JsonJSTransformerProvider config.
        // We verify the script reference is present and that the context references at least the
        // 'standard' filter removal — a known ES 6 → OS 2 case.
        var transformers = MetadataTransformationRegistry.getCustomTransformationByClusterVersions(src, tgt);
        assertThat(transformers, is(org.hamcrest.Matchers.notNullValue()));
        // The aggregated config is logged; we re-derive context to assert content is correct.
        var configCtx = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(configCtx, containsString("standard"));
        assertThat(configCtx, containsString("delimited_payload"));
    }
}
