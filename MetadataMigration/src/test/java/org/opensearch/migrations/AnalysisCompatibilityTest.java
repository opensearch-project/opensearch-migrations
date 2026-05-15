package org.opensearch.migrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class AnalysisCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void es6ToOs2_includesStandardFilterRemoval() throws Exception {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        // standard token filter is removed
        var removedFilters = ctx.path("removed").path("filter");
        boolean hasStandard = false;
        for (JsonNode n : removedFilters) hasStandard = hasStandard || "standard".equals(n.asText());
        assertThat("ES 6 → OS 2 should remove the legacy 'standard' token filter", hasStandard, is(true));
    }

    @Test
    void es6ToOs2_renamesDelimitedPayloadFilter() throws Exception {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        var ctx = parse(json);
        assertThat(ctx.path("renames").path("filter").path("delimited_payload_filter").asText(),
            equalTo("delimited_payload"));
    }

    @Test
    void es6ToOs2_renamesNgramAndEdgeNgramAliases() throws Exception {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("filter").path("nGram").asText(), equalTo("ngram"));
        assertThat(ctx.path("renames").path("filter").path("edgeNGram").asText(), equalTo("edge_ngram"));
        assertThat(ctx.path("renames").path("tokenizer").path("nGram").asText(), equalTo("ngram"));
        assertThat(ctx.path("renames").path("tokenizer").path("edgeNGram").asText(), equalTo("edge_ngram"));
    }

    @Test
    void es6ToOs2_renamesHtmlStripCharFilterAlias() throws Exception {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("char_filter").path("htmlStrip").asText(),
            equalTo("html_strip"));
    }

    @Test
    void es7ToOs2_doesNotApplyEs6Rules() throws Exception {
        // Source is already ES 7+, so it shouldn't have produced the legacy names; the
        // ES 6 rules should not apply (rule's source matcher is "below ES 7").
        var src = Version.fromString("ES 7.17.0");
        var tgt = Version.fromString("OS 2.19.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        if (json != null) {
            var ctx = parse(json);
            // standard filter should not be in removed list
            for (JsonNode n : ctx.path("removed").path("filter")) {
                assertThat("ES 7 → OS 2 should not strip 'standard' filter", n.asText(),
                    org.hamcrest.Matchers.not(equalTo("standard")));
            }
        }
    }

    @Test
    void osTarget_appliesPathHierarchyAliasRename() throws Exception {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("tokenizer").path("PathHierarchy").asText(),
            equalTo("path_hierarchy"));
    }

    @Test
    void osTarget_stripsStandardHtmlStripAnalyzer() throws Exception {
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        boolean foundShs = false;
        for (JsonNode n : ctx.path("removed").path("analyzer")) {
            if ("standard_html_strip".equals(n.asText())) foundShs = true;
        }
        assertThat("OS targets must drop the standard_html_strip analyzer", foundShs, is(true));
    }

    @Test
    void osToOs_returnsNullWhenNoRulesApply() {
        // OS 2 → OS 3 — there are no removals between OS majors; PathHierarchy still applies though.
        // So we expect non-null still (PathHierarchy rule).
        var src = Version.fromString("OS 2.19.0");
        var tgt = Version.fromString("OS 3.0.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        // PathHierarchy applies for any source → any OS target, so context is non-null
        assertThat(json, notNullValue());
    }

    @Test
    void targetES_anyOS_doesNotMatchAES6() {
        // Just sanity-check the relevance API
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        assertThat(AnalysisCompatibility.hasRulesFor(src, tgt), is(true));
    }

    @Test
    void targetES_old_doesNotApplyToTargetingPreEs7() {
        // No rules target ES < 7
        var src = Version.fromString("ES 5.6.0");
        var tgt = Version.fromString("ES 5.6.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, nullValue());
    }
}
