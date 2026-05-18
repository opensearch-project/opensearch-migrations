package org.opensearch.migrations;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

/**
 * Compatibility table for analyzer/tokenizer/char_filter/token-filter component names
 * across Elasticsearch 1.x-9.x, OpenSearch 1.x-3.x, and Solr 6.x-9.x.
 *
 * Drives the preemptive metadata transform that strips or renames analysis components
 * in source-cluster index/template settings before they are sent to the target.
 *
 * The reactive InvalidResponse-based retry path remains as a safety net for cases not
 * covered here; this table simply prevents the round-trip failure when we already know
 * a name will be rejected.
 *
 * Source for the rules: ES/OS/Solr release notes and analysis-common source as of 2026-05.
 */
@UtilityClass
public class AnalysisCompatibility {

    public static final String FILTER = "filter";
    public static final String TOKENIZER = "tokenizer";
    public static final String CHAR_FILTER = "char_filter";
    public static final String ANALYZER = "analyzer";

    /**
     * One rule: when matchesSource(src) AND matchesTarget(tgt), apply the action to
     * the named component of the given kind.
     */
    private static final class Rule {
        final String kind;
        final String name;
        final String renameTo; // null = strip; non-null = rename
        final Predicate<Version> matchesSource;
        final Predicate<Version> matchesTarget;

        Rule(String kind, String name, String renameTo,
             Predicate<Version> matchesSource, Predicate<Version> matchesTarget) {
            this.kind = kind;
            this.name = name;
            this.renameTo = renameTo;
            this.matchesSource = matchesSource;
            this.matchesTarget = matchesTarget;
        }
    }

        private static final String DELIMITED_PAYLOAD = "delimited_payload";

    // Targets we care about: ES 7+, any OS. Sources can be anything below the target.
    private static final Predicate<Version> TARGET_ES_7_OR_OS =
            UnboundVersionMatchers.isGreaterOrEqualES_7_X.or(UnboundVersionMatchers.anyOS);
    private static final Predicate<Version> TARGET_OS = UnboundVersionMatchers.anyOS;
    // Solr is a possible source. Rules that target Solr add it to the source matcher.
    // SOLR_OR_BELOW_ES8 covers ES 7 as a source because ES 7 clusters can contain
    // indices originally created under ES 6 (restored from snapshots) that still carry
    // legacy ES 6 analysis names.
    private static final Predicate<Version> SOLR_OR_BELOW_ES8 =
            UnboundVersionMatchers.anySolr.or(UnboundVersionMatchers.isBelowES_8_X);
    private static final Predicate<Version> SOLR_ANY = UnboundVersionMatchers.anySolr;
    // Only ES 8+ removes some legacy aliases for new indices; OS still tolerates them
    // (with a warning), but we still strip them defensively for OS targets.

    /**
     * Each entry encodes a known incompatibility. Order doesn't matter — the resulting
     * config aggregates by kind.
     *
     * Conventions:
     *  - renameTo == null : strip the reference (e.g. the no-op `standard` filter)
     *  - renameTo != null : rewrite occurrences to the new name
     *  - matchesSource specifies which source versions COULD produce the bad name.
     *  - matchesTarget specifies which target versions REJECT the bad name.
     */
    private static final List<Rule> RULES = List.of(
            // ──────────────────── Token filters ────────────────────
            // The legacy "standard" token filter was a no-op even in ES 5; deprecated 6.5; rejected on
            // new indices in ES 7.0; fully removed in ES 8.0. OS rejects from 1.0 onward.
            // Source includes ES 7 because ES 7 clusters can restore ES 6 snapshots that carry this name.
            new Rule(FILTER, "standard", null,
                    UnboundVersionMatchers.isBelowES_8_X, TARGET_ES_7_OR_OS),

            // delimited_payload_filter → delimited_payload  (renamed in ES 6.2; rejected on
            // new ES 7.0+ indices; OS 2.0 fully removed registration)
            // Source includes ES 7 because ES 7 clusters can restore ES 6 snapshots that carry this name.
            new Rule(FILTER, "delimited_payload_filter", DELIMITED_PAYLOAD,
                    UnboundVersionMatchers.isBelowES_8_X, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "analysis_delimited_payload_filter", DELIMITED_PAYLOAD,
                    UnboundVersionMatchers.isBelowES_8_X, TARGET_ES_7_OR_OS),

            // camelCase aliases for ngram/edge_ngram filters: deprecated ES 6.4; rejected on new
            // indices since ES 7.0 (alias still registered for read-compat through 7.x). OS rejects
            // them on new-index creation everywhere. Solr also emits the camelCase form.
            // Source includes ES 7 (SOLR_OR_BELOW_ES8) because ES 7 clusters can hold ES 6 restored indices.
            new Rule(FILTER, "nGram", "ngram", SOLR_OR_BELOW_ES8, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "edgeNGram", "edge_ngram", SOLR_OR_BELOW_ES8, TARGET_ES_7_OR_OS),

            // ─────────────────────── Tokenizers ─────────────────────
            // camelCase aliases for ngram/edge_ngram tokenizers: deprecated ES 7.6; rejected on new
            // indices in ES 8.0. OS treats them as deprecated and rejects on new indices everywhere.
            // Source includes ES 7 because ES 7 natively used nGram/edgeNGram before they were deprecated.
            new Rule(TOKENIZER, "nGram", "ngram", SOLR_OR_BELOW_ES8, TARGET_ES_7_OR_OS),
            new Rule(TOKENIZER, "edgeNGram", "edge_ngram", SOLR_OR_BELOW_ES8, TARGET_ES_7_OR_OS),

            // PathHierarchy (camelCase tokenizer): deprecated by OS in 2.12, slated for removal in
            // 4.0, but harmless to rewrite preemptively. Targets: any OS. Solr's
            // pathHierarchy short-name lower-cases the same way.
            new Rule(TOKENIZER, "PathHierarchy", "path_hierarchy", v -> true, TARGET_OS),
            new Rule(TOKENIZER, "pathHierarchy", "path_hierarchy", SOLR_ANY, TARGET_OS),

            // ───────────────────── Char filters ─────────────────────
            // htmlStrip (camelCase) was a pre-configured alias deprecated in ES 6.3; rejected on new
            // ES 7.x indices and gone in 8.0. Solr emits the same camelCase form.
            // Source includes ES 7 because ES 7 clusters can restore ES 6 snapshots that carry this name.
            new Rule(CHAR_FILTER, "htmlStrip", "html_strip", SOLR_OR_BELOW_ES8, TARGET_ES_7_OR_OS),
            new Rule(CHAR_FILTER, "patternReplace", "pattern_replace", SOLR_ANY, TARGET_ES_7_OR_OS),

            // ─────────────────────── Solr camelCase → OS snake_case ───────────────────────
            // Only includes filters/char_filters/tokenizers we have confirmed exist (with the
            // same semantics) under the snake_case short name in OpenSearch. Components that
            // would require a parameter rewrite (e.g. solr.GermanStemFilterFactory →
            // stemmer + language=german) are intentionally left alone — if a user has a
            // truly custom or non-equivalent component, the create-index call will fail with
            // an "unknown tokenizer/filter" error and the existing reactive InvalidResponse
            // retry will surface that to the user instead of silently misconfiguring.
            //
            // Sourced from the Solr 6-9 ↔ ES/OS analysis-component compatibility audit
            // (Solr indexing-guide + opensearch-project/OpenSearch analysis-common module).

            // Token filters with direct OS short-name equivalents.
            new Rule(FILTER, "synonymGraph", "synonym_graph", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "flattenGraph", "flatten_graph", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "wordDelimiter", "word_delimiter", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "wordDelimiterGraph", "word_delimiter_graph", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "removeDuplicates", "remove_duplicates", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "delimitedPayload", DELIMITED_PAYLOAD, SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "commonGrams", "common_grams", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "decimalDigit", "decimal_digit", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "patternReplace", "pattern_replace", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "patternCaptureGroup", "pattern_capture", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "keepWord", "keep", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "keepTypes", "keep_types", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "keywordMarker", "keyword_marker", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "keywordRepeat", "keyword_repeat", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "porterStem", "porter_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "kStem", "kstem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "stemmerOverride", "stemmer_override", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "snowballPorter", "snowball", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "hunspellStem", "hunspell", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "minHash", "min_hash", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "limitTokenCount", "limit", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "asciiFolding", "asciifolding", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "dictionaryCompoundWord", "dictionary_decompounder", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "hyphenationCompoundWord", "hyphenation_decompounder", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "cjkBigram", "cjk_bigram", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "cjkWidth", "cjk_width", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "reverseString", "reverse", SOLR_ANY, TARGET_ES_7_OR_OS),

            // Per-language NORMALIZATION filters: confirmed direct OS short-name equivalents.
            // These do NOT require parameter rewrites — they map name-for-name.
            new Rule(FILTER, "arabicNormalization", "arabic_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "germanNormalization", "german_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "hindiNormalization", "hindi_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "indicNormalization", "indic_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "persianNormalization", "persian_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "scandinavianFolding", "scandinavian_folding", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "scandinavianNormalization", "scandinavian_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "serbianNormalization", "serbian_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "soraniNormalization", "sorani_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "bengaliNormalization", "bengali_normalization", SOLR_ANY, TARGET_ES_7_OR_OS),

            // Per-language STEMMERS that have a direct OS short-name (only these — others
            // need stemmer+language= parameter rewrites which are out of scope for this
            // pass). Confirmed in opensearch-project/OpenSearch analysis-common.
            new Rule(FILTER, "germanStem", "german_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "frenchStem", "french_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "russianStem", "russian_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "brazilianStem", "brazilian_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "arabicStem", "arabic_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "czechStem", "czech_stem", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(FILTER, "dutchStem", "dutch_stem", SOLR_ANY, TARGET_ES_7_OR_OS),

            // Tokenizers with direct OS short-name equivalents (Solr-only camelCase).
            new Rule(TOKENIZER, "uax29URLEmail", "uax_url_email", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(TOKENIZER, "simplePattern", "simple_pattern", SOLR_ANY, TARGET_ES_7_OR_OS),
            new Rule(TOKENIZER, "simplePatternSplit", "simple_pattern_split", SOLR_ANY, TARGET_ES_7_OR_OS),

            // Char filters with direct OS short-name equivalents (Solr-only).
            new Rule(CHAR_FILTER, "mapping", "mapping", SOLR_ANY, TARGET_ES_7_OR_OS), // identity, harmless

            // ─────────────────────── Analyzers ──────────────────────
            // standard_html_strip: deprecated ES 6.5; rejected on ES 8+ new indices; removed in OS 2.0.
            // For OS targets we strip, leaving the analyzer block in place; the InvalidResponse retry
            // will then drop it as an unknown setting if the user references it elsewhere.
            new Rule(ANALYZER, "standard_html_strip", null,
                    v -> true, TARGET_OS)
    );

    /**
     * Build the JS-transformer context JSON describing what to strip/rename for the given
     * (source, target) pair. Returns {@code null} if no rules apply (caller should skip
     * the transformer entirely).
     */
    public static String buildContextJsonOrNull(Version sourceVersion, Version targetVersion) {
        var perKindRemoved = new LinkedHashMap<String, Set<String>>();
        var perKindRenames = new LinkedHashMap<String, Map<String, String>>();

        for (var rule : RULES) {
            if (!rule.matchesSource.test(sourceVersion) || !rule.matchesTarget.test(targetVersion)) {
                continue;
            }
            if (rule.renameTo == null) {
                perKindRemoved.computeIfAbsent(rule.kind, k -> new LinkedHashSet<>()).add(rule.name);
            } else {
                perKindRenames.computeIfAbsent(rule.kind, k -> new LinkedHashMap<>()).put(rule.name, rule.renameTo);
            }
        }

        if (perKindRemoved.isEmpty() && perKindRenames.isEmpty()) {
            return null;
        }

        var mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode removed = root.putObject("removed");
        for (var entry : perKindRemoved.entrySet()) {
            var arr = removed.putArray(entry.getKey());
            entry.getValue().forEach(arr::add);
        }
        ObjectNode renames = root.putObject("renames");
        for (var entry : perKindRenames.entrySet()) {
            var obj = renames.putObject(entry.getKey());
            entry.getValue().forEach(obj::put);
        }
        return root.toString();
    }

    /**
     * Returns true if the (source, target) pair triggers at least one analysis-component rule.
     */
    public static boolean hasRulesFor(Version sourceVersion, Version targetVersion) {
        for (var rule : RULES) {
            if (rule.matchesSource.test(sourceVersion) && rule.matchesTarget.test(targetVersion)) {
                return true;
            }
        }
        return false;
    }
}
