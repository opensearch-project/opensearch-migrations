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

    /** Helper: checks whether a JSON array node contains a given text value. */
    private static boolean arrayContains(JsonNode arrayNode, String value) {
        for (JsonNode n : arrayNode) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    // ───────────────────── Existing happy-path tests ─────────────────────

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

    // ───────────────────── Same-version pairs ─────────────────────

    @Test
    void sameVersionEs7_hasRulesFor_returnsFalse() {
        // ES 7.10 → ES 7.10: The PathHierarchy rule (source: v->true, target: TARGET_OS) does NOT
        // match an ES target. But standard_html_strip (source: v->true, target: TARGET_OS) also
        // requires an OS target. No rule has ES 7 as both valid source AND target, so hasRulesFor
        // should return false.
        var v = Version.fromString("ES 7.10.0");
        assertThat("ES 7.10 → ES 7.10 has no applicable rules", AnalysisCompatibility.hasRulesFor(v, v), is(false));
    }

    @Test
    void sameVersionEs7_buildContextJsonOrNull_returnsNull() {
        // ES 7.10 as both source and target. Source matchers requiring "below ES 7" fail because
        // ES 7.10 is not below ES 7. Source matchers for Solr fail because flavor is ES.
        // The only rules with v->true source are PathHierarchy and standard_html_strip, but
        // their target matcher is TARGET_OS, which does not match ES 7.10.
        var v = Version.fromString("ES 7.10.0");
        assertThat(AnalysisCompatibility.buildContextJsonOrNull(v, v), nullValue());
    }

    @Test
    void sameVersionEs6_hasRulesFor_returnsFalse() {
        // ES 6.8 → ES 6.8: source matchers for "below ES 7" pass (ES 6 < 7), but target
        // matchers require ES 7+ or OS. ES 6.8 is not ES 7+ and not OS, so no rules fire.
        var v = Version.fromString("ES 6.8.0");
        assertThat("ES 6.8 → ES 6.8 should have no rules", AnalysisCompatibility.hasRulesFor(v, v), is(false));
        assertThat(AnalysisCompatibility.buildContextJsonOrNull(v, v), nullValue());
    }

    @Test
    void sameVersionOs1_hasRules() throws Exception {
        // OS 1.0 → OS 1.0: PathHierarchy rule has source v->true, target TARGET_OS. OS 1.0
        // is an OS flavor, so target matches. Also standard_html_strip (source v->true, target TARGET_OS).
        var v = Version.fromString("OS 1.0.0");
        assertThat("OS 1 → OS 1 should have rules (PathHierarchy, standard_html_strip)",
            AnalysisCompatibility.hasRulesFor(v, v), is(true));
        var json = AnalysisCompatibility.buildContextJsonOrNull(v, v);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        assertThat(ctx.path("renames").path("tokenizer").path("PathHierarchy").asText(),
            equalTo("path_hierarchy"));
        assertThat("standard_html_strip should be removed for OS → OS",
            arrayContains(ctx.path("removed").path("analyzer"), "standard_html_strip"), is(true));
    }

    // ───────────────────── OS-to-OS: Solr-specific rules must NOT fire ─────────────────────

    @Test
    void osToOs_solrOnlyRenamesDoNotFire() throws Exception {
        // OS 1.0 → OS 2.0: rules with source matcher SOLR_ANY should not fire
        // because the source flavor is OS, not Solr.
        var src = Version.fromString("OS 1.0.0");
        var tgt = Version.fromString("OS 2.0.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        var filterRenames = ctx.path("renames").path("filter");
        // Solr-only filter renames must be absent
        assertThat("synonymGraph is Solr-only and must not appear in OS→OS",
            filterRenames.path("synonymGraph").isMissingNode(), is(true));
        assertThat("flattenGraph is Solr-only and must not appear in OS→OS",
            filterRenames.path("flattenGraph").isMissingNode(), is(true));
        assertThat("wordDelimiter is Solr-only and must not appear in OS→OS",
            filterRenames.path("wordDelimiter").isMissingNode(), is(true));
        assertThat("asciiFolding is Solr-only and must not appear in OS→OS",
            filterRenames.path("asciiFolding").isMissingNode(), is(true));

        // Solr-only tokenizer renames must be absent
        var tokenizerRenames = ctx.path("renames").path("tokenizer");
        assertThat("uax29URLEmail is Solr-only and must not appear in OS→OS",
            tokenizerRenames.path("uax29URLEmail").isMissingNode(), is(true));
        assertThat("pathHierarchy (lowercase) is Solr-only and must not appear in OS→OS",
            tokenizerRenames.path("pathHierarchy").isMissingNode(), is(true));

        // Solr-only char_filter renames must be absent
        var charFilterRenames = ctx.path("renames").path("char_filter");
        assertThat("patternReplace char_filter is Solr-only and must not appear in OS→OS",
            charFilterRenames.path("patternReplace").isMissingNode(), is(true));
    }

    @Test
    void osToOs_onlyPathHierarchyAndStandardHtmlStripApply() throws Exception {
        // OS 2 → OS 2: The only rules with source v->true and target TARGET_OS are
        // PathHierarchy and standard_html_strip. Verify that is exactly what we get.
        var src = Version.fromString("OS 2.0.0");
        var tgt = Version.fromString("OS 2.0.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);

        // Renames: only PathHierarchy tokenizer
        assertThat(ctx.path("renames").path("tokenizer").path("PathHierarchy").asText(),
            equalTo("path_hierarchy"));
        // No filter renames
        assertThat(ctx.path("renames").path("filter").isMissingNode()
            || ctx.path("renames").path("filter").isEmpty(), is(true));

        // Removals: only standard_html_strip analyzer
        assertThat(arrayContains(ctx.path("removed").path("analyzer"), "standard_html_strip"), is(true));
        // No filter removals
        assertThat(ctx.path("removed").path("filter").isMissingNode()
            || ctx.path("removed").path("filter").isEmpty(), is(true));
    }

    // ───────────────────── Edge version: ES 6.8 → ES 7.0 boundary ─────────────────────

    @Test
    void es6ToEs7_standardFilterRemoved() throws Exception {
        // ES 6.8 → ES 7.0: the exact boundary where "standard" filter became invalid.
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("ES 7.0.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        assertThat("standard filter must be removed at ES 6 → ES 7 boundary",
            arrayContains(ctx.path("removed").path("filter"), "standard"), is(true));
    }

    @Test
    void es6ToEs7_renamesDelimitedPayloadFilterAndAliases() throws Exception {
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("ES 7.0.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("filter").path("delimited_payload_filter").asText(),
            equalTo("delimited_payload"));
        assertThat(ctx.path("renames").path("filter").path("analysis_delimited_payload_filter").asText(),
            equalTo("delimited_payload"));
    }

    @Test
    void es6ToEs7_renamesNgramAliases() throws Exception {
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("ES 7.0.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("filter").path("nGram").asText(), equalTo("ngram"));
        assertThat(ctx.path("renames").path("filter").path("edgeNGram").asText(), equalTo("edge_ngram"));
        assertThat(ctx.path("renames").path("tokenizer").path("nGram").asText(), equalTo("ngram"));
        assertThat(ctx.path("renames").path("tokenizer").path("edgeNGram").asText(), equalTo("edge_ngram"));
    }

    @Test
    void es6ToEs7_renamesHtmlStripCharFilter() throws Exception {
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("ES 7.0.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("char_filter").path("htmlStrip").asText(),
            equalTo("html_strip"));
    }

    @Test
    void es6ToEs7_doesNotStripStandardHtmlStripAnalyzer() throws Exception {
        // standard_html_strip analyzer removal targets OS only, not ES 7
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("ES 7.0.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat("standard_html_strip should NOT be removed for ES 7 target",
            arrayContains(ctx.path("removed").path("analyzer"), "standard_html_strip"), is(false));
    }

    @Test
    void es6ToEs7_doesNotIncludePathHierarchyRename() throws Exception {
        // PathHierarchy rename targets OS only
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("ES 7.0.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat("PathHierarchy rename should not appear for ES 7 target",
            ctx.path("renames").path("tokenizer").path("PathHierarchy").isMissingNode(), is(true));
    }

    // ───────────────────── buildContextJsonOrNull returns null ─────────────────────

    @Test
    void es5ToEs6_returnsNull() {
        // ES 5 → ES 6: no rule targets ES 6 (all targets require ES 7+ or OS)
        var src = Version.fromString("ES 5.6.0");
        var tgt = Version.fromString("ES 6.8.0");
        assertThat(AnalysisCompatibility.buildContextJsonOrNull(src, tgt), nullValue());
    }

    @Test
    void es7ToEs7_returnsNull() {
        // ES 7.10 → ES 7.17: source is ES 7 (not below ES 7, not Solr), and target is ES not OS.
        // No rule's source matcher accepts ES 7 AND target matcher accepts ES 7 simultaneously,
        // except PathHierarchy (source v->true) and standard_html_strip (source v->true),
        // but both target OS only.
        var src = Version.fromString("ES 7.10.0");
        var tgt = Version.fromString("ES 7.17.0");
        assertThat(AnalysisCompatibility.buildContextJsonOrNull(src, tgt), nullValue());
    }

    @Test
    void hasRulesFor_es5ToEs6_returnsFalse() {
        var src = Version.fromString("ES 5.6.0");
        var tgt = Version.fromString("ES 6.8.0");
        assertThat(AnalysisCompatibility.hasRulesFor(src, tgt), is(false));
    }

    @Test
    void hasRulesFor_es7ToEs7_returnsFalse() {
        var src = Version.fromString("ES 7.10.0");
        var tgt = Version.fromString("ES 7.17.0");
        assertThat(AnalysisCompatibility.hasRulesFor(src, tgt), is(false));
    }

    // ───────────────────── Solr source scenarios ─────────────────────

    @Test
    void solrToOs_firesSolrSpecificFilterRenames() throws Exception {
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("OS 2.19.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        var filterRenames = ctx.path("renames").path("filter");

        // Solr camelCase → OS snake_case filter renames
        assertThat(filterRenames.path("synonymGraph").asText(), equalTo("synonym_graph"));
        assertThat(filterRenames.path("flattenGraph").asText(), equalTo("flatten_graph"));
        assertThat(filterRenames.path("wordDelimiter").asText(), equalTo("word_delimiter"));
        assertThat(filterRenames.path("wordDelimiterGraph").asText(), equalTo("word_delimiter_graph"));
        assertThat(filterRenames.path("removeDuplicates").asText(), equalTo("remove_duplicates"));
        assertThat(filterRenames.path("delimitedPayload").asText(), equalTo("delimited_payload"));
        assertThat(filterRenames.path("commonGrams").asText(), equalTo("common_grams"));
        assertThat(filterRenames.path("decimalDigit").asText(), equalTo("decimal_digit"));
        assertThat(filterRenames.path("patternReplace").asText(), equalTo("pattern_replace"));
        assertThat(filterRenames.path("patternCaptureGroup").asText(), equalTo("pattern_capture"));
        assertThat(filterRenames.path("keepWord").asText(), equalTo("keep"));
        assertThat(filterRenames.path("keepTypes").asText(), equalTo("keep_types"));
        assertThat(filterRenames.path("keywordMarker").asText(), equalTo("keyword_marker"));
        assertThat(filterRenames.path("keywordRepeat").asText(), equalTo("keyword_repeat"));
        assertThat(filterRenames.path("porterStem").asText(), equalTo("porter_stem"));
        assertThat(filterRenames.path("kStem").asText(), equalTo("kstem"));
        assertThat(filterRenames.path("stemmerOverride").asText(), equalTo("stemmer_override"));
        assertThat(filterRenames.path("snowballPorter").asText(), equalTo("snowball"));
        assertThat(filterRenames.path("hunspellStem").asText(), equalTo("hunspell"));
        assertThat(filterRenames.path("minHash").asText(), equalTo("min_hash"));
        assertThat(filterRenames.path("limitTokenCount").asText(), equalTo("limit"));
        assertThat(filterRenames.path("asciiFolding").asText(), equalTo("asciifolding"));
        assertThat(filterRenames.path("dictionaryCompoundWord").asText(), equalTo("dictionary_decompounder"));
        assertThat(filterRenames.path("hyphenationCompoundWord").asText(), equalTo("hyphenation_decompounder"));
        assertThat(filterRenames.path("cjkBigram").asText(), equalTo("cjk_bigram"));
        assertThat(filterRenames.path("cjkWidth").asText(), equalTo("cjk_width"));
        assertThat(filterRenames.path("reverseString").asText(), equalTo("reverse"));
    }

    @Test
    void solrToOs_firesSolrNormalizationFilterRenames() throws Exception {
        var src = Version.fromString("SOLR 8.0.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var filterRenames = ctx.path("renames").path("filter");

        assertThat(filterRenames.path("arabicNormalization").asText(), equalTo("arabic_normalization"));
        assertThat(filterRenames.path("germanNormalization").asText(), equalTo("german_normalization"));
        assertThat(filterRenames.path("hindiNormalization").asText(), equalTo("hindi_normalization"));
        assertThat(filterRenames.path("indicNormalization").asText(), equalTo("indic_normalization"));
        assertThat(filterRenames.path("persianNormalization").asText(), equalTo("persian_normalization"));
        assertThat(filterRenames.path("scandinavianFolding").asText(), equalTo("scandinavian_folding"));
        assertThat(filterRenames.path("scandinavianNormalization").asText(), equalTo("scandinavian_normalization"));
        assertThat(filterRenames.path("serbianNormalization").asText(), equalTo("serbian_normalization"));
        assertThat(filterRenames.path("soraniNormalization").asText(), equalTo("sorani_normalization"));
        assertThat(filterRenames.path("bengaliNormalization").asText(), equalTo("bengali_normalization"));
    }

    @Test
    void solrToOs_firesSolrStemmerFilterRenames() throws Exception {
        var src = Version.fromString("SOLR 7.0.0");
        var tgt = Version.fromString("OS 1.0.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var filterRenames = ctx.path("renames").path("filter");

        assertThat(filterRenames.path("germanStem").asText(), equalTo("german_stem"));
        assertThat(filterRenames.path("frenchStem").asText(), equalTo("french_stem"));
        assertThat(filterRenames.path("russianStem").asText(), equalTo("russian_stem"));
        assertThat(filterRenames.path("brazilianStem").asText(), equalTo("brazilian_stem"));
        assertThat(filterRenames.path("arabicStem").asText(), equalTo("arabic_stem"));
        assertThat(filterRenames.path("czechStem").asText(), equalTo("czech_stem"));
        assertThat(filterRenames.path("dutchStem").asText(), equalTo("dutch_stem"));
    }

    @Test
    void solrToOs_firesSolrTokenizerRenames() throws Exception {
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var tokenizerRenames = ctx.path("renames").path("tokenizer");

        assertThat(tokenizerRenames.path("uax29URLEmail").asText(), equalTo("uax_url_email"));
        assertThat(tokenizerRenames.path("simplePattern").asText(), equalTo("simple_pattern"));
        assertThat(tokenizerRenames.path("simplePatternSplit").asText(), equalTo("simple_pattern_split"));
        // nGram/edgeNGram also fire for Solr (SOLR_OR_BELOW_ES7 includes anySolr)
        assertThat(tokenizerRenames.path("nGram").asText(), equalTo("ngram"));
        assertThat(tokenizerRenames.path("edgeNGram").asText(), equalTo("edge_ngram"));
        // pathHierarchy (lowercase) is Solr-only
        assertThat(tokenizerRenames.path("pathHierarchy").asText(), equalTo("path_hierarchy"));
        // PathHierarchy (capitalized) is any source → OS
        assertThat(tokenizerRenames.path("PathHierarchy").asText(), equalTo("path_hierarchy"));
    }

    @Test
    void solrToOs_firesSolrCharFilterRenames() throws Exception {
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var charFilterRenames = ctx.path("renames").path("char_filter");

        assertThat(charFilterRenames.path("htmlStrip").asText(), equalTo("html_strip"));
        assertThat(charFilterRenames.path("patternReplace").asText(), equalTo("pattern_replace"));
        // The "mapping" char filter is an identity rename (Solr → OS)
        assertThat(charFilterRenames.path("mapping").asText(), equalTo("mapping"));
    }

    @Test
    void solrToOs_includesNgramFilterRenames() throws Exception {
        // Solr is in SOLR_OR_BELOW_ES7, so nGram/edgeNGram filter rules should also fire
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var filterRenames = ctx.path("renames").path("filter");
        assertThat(filterRenames.path("nGram").asText(), equalTo("ngram"));
        assertThat(filterRenames.path("edgeNGram").asText(), equalTo("edge_ngram"));
    }

    @Test
    void solrToEs7_firesSolrSpecificRules() throws Exception {
        // Solr → ES 7: TARGET_ES_7_OR_OS matches ES 7. Solr-specific rules should fire.
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("ES 7.17.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        // Solr filter renames
        assertThat(ctx.path("renames").path("filter").path("synonymGraph").asText(),
            equalTo("synonym_graph"));
        // But PathHierarchy (TARGET_OS only) should NOT be present
        assertThat("PathHierarchy requires OS target",
            ctx.path("renames").path("tokenizer").path("PathHierarchy").isMissingNode(), is(true));
        // standard_html_strip (TARGET_OS only) should NOT be in removed
        assertThat("standard_html_strip requires OS target",
            arrayContains(ctx.path("removed").path("analyzer"), "standard_html_strip"), is(false));
    }

    @Test
    void solrToEs6_returnsNull() {
        // Solr → ES 6: no rule targets ES 6 (all targets require ES 7+ or OS)
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("ES 6.8.0");
        assertThat(AnalysisCompatibility.buildContextJsonOrNull(src, tgt), nullValue());
    }

    @Test
    void hasRulesFor_solrToOs_returnsTrue() {
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("OS 2.0.0");
        assertThat(AnalysisCompatibility.hasRulesFor(src, tgt), is(true));
    }

    @Test
    void hasRulesFor_solrToEs6_returnsFalse() {
        var src = Version.fromString("SOLR 9.0.0");
        var tgt = Version.fromString("ES 6.8.0");
        assertThat(AnalysisCompatibility.hasRulesFor(src, tgt), is(false));
    }

    // ───────────────────── analysis_delimited_payload_filter alias ─────────────────────

    @Test
    void es6ToOs2_renamesAnalysisDelimitedPayloadFilter() throws Exception {
        // Exercises the second delimited_payload alias
        var src = Version.fromString("ES 6.8.23");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat(ctx.path("renames").path("filter").path("analysis_delimited_payload_filter").asText(),
            equalTo("delimited_payload"));
    }

    // ───────────────────── JSON structure validation ─────────────────────

    @Test
    void buildContextJsonOrNull_hasCorrectTopLevelKeys() throws Exception {
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        assertThat("Top-level 'removed' key must exist", ctx.has("removed"), is(true));
        assertThat("Top-level 'renames' key must exist", ctx.has("renames"), is(true));
        // These are the only two top-level keys
        assertThat("Exactly two top-level keys", ctx.size(), equalTo(2));
    }

    @Test
    void buildContextJsonOrNull_removedValuesAreArrays() throws Exception {
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var removed = ctx.path("removed");
        removed.fields().forEachRemaining(entry ->
            assertThat("removed." + entry.getKey() + " must be an array",
                entry.getValue().isArray(), is(true)));
    }

    @Test
    void buildContextJsonOrNull_renameValuesAreObjects() throws Exception {
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("OS 2.19.0");
        var ctx = parse(AnalysisCompatibility.buildContextJsonOrNull(src, tgt));
        var renames = ctx.path("renames");
        renames.fields().forEachRemaining(entry ->
            assertThat("renames." + entry.getKey() + " must be an object",
                entry.getValue().isObject(), is(true)));
    }

    // ───────────────────── ES 7 → OS (PathHierarchy only, no ES6 rules) ─────────────────────

    @Test
    void es7ToOs_onlyPathHierarchyAndStandardHtmlStripApply() throws Exception {
        // ES 7.17 → OS 2.19: source is ES 7 (not below 7, not Solr).
        // Only rules with source v->true fire: PathHierarchy and standard_html_strip.
        var src = Version.fromString("ES 7.17.0");
        var tgt = Version.fromString("OS 2.19.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);

        // PathHierarchy rename is present
        assertThat(ctx.path("renames").path("tokenizer").path("PathHierarchy").asText(),
            equalTo("path_hierarchy"));

        // standard_html_strip removal is present
        assertThat(arrayContains(ctx.path("removed").path("analyzer"), "standard_html_strip"), is(true));

        // No filter removals (standard filter not removed because source is ES 7, not below ES 7)
        assertThat(ctx.path("removed").path("filter").isMissingNode()
            || ctx.path("removed").path("filter").isEmpty(), is(true));

        // No filter renames (delimited_payload_filter, nGram, edgeNGram all require source below ES 7)
        assertThat(ctx.path("renames").path("filter").isMissingNode()
            || ctx.path("renames").path("filter").isEmpty(), is(true));
    }

    // ───────────────────── ES 5 → ES 7 (early source version) ─────────────────────

    @Test
    void es5ToEs7_appliesAllEs6Rules() throws Exception {
        // ES 5.6 → ES 7.0: ES 5 is below ES 7, so all "below ES 7" source rules fire.
        var src = Version.fromString("ES 5.6.0");
        var tgt = Version.fromString("ES 7.0.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        assertThat(arrayContains(ctx.path("removed").path("filter"), "standard"), is(true));
        assertThat(ctx.path("renames").path("filter").path("delimited_payload_filter").asText(),
            equalTo("delimited_payload"));
        assertThat(ctx.path("renames").path("filter").path("nGram").asText(), equalTo("ngram"));
        assertThat(ctx.path("renames").path("char_filter").path("htmlStrip").asText(),
            equalTo("html_strip"));
    }

    // ───────────────────── ES 6 → OS 1 (earliest OS target) ─────────────────────

    @Test
    void es6ToOs1_appliesAllRules() throws Exception {
        // OS 1.0 is the earliest OS version. All ES-below-7 rules should fire for OS targets.
        var src = Version.fromString("ES 6.8.0");
        var tgt = Version.fromString("OS 1.0.0");
        var json = AnalysisCompatibility.buildContextJsonOrNull(src, tgt);
        assertThat(json, notNullValue());
        var ctx = parse(json);
        assertThat(arrayContains(ctx.path("removed").path("filter"), "standard"), is(true));
        assertThat(ctx.path("renames").path("filter").path("delimited_payload_filter").asText(),
            equalTo("delimited_payload"));
        assertThat(ctx.path("renames").path("tokenizer").path("PathHierarchy").asText(),
            equalTo("path_hierarchy"));
        assertThat(arrayContains(ctx.path("removed").path("analyzer"), "standard_html_strip"), is(true));
    }
}
