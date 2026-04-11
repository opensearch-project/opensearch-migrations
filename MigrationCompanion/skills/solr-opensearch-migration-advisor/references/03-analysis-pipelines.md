# Solr to OpenSearch Analysis Pipeline Migration

This document provides guidance on migrating Apache Solr analysis pipelines (tokenizers, token filters, and char filters) to OpenSearch. It covers the `analysis` section of Solr's `schema.xml` and how to reproduce equivalent behavior in OpenSearch index settings.

See also `03b-synonyms-and-language.md` for synonyms migration, language analyzers, legacy considerations, and a full worked example.

## 1. Overview of Analysis Pipeline Structure

In Solr, analysis is defined inside `<fieldType>` elements in `schema.xml`:

```xml
<fieldType name="text_en" class="solr.TextField" positionIncrementGap="100">
  <analyzer type="index">
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.StopFilterFactory" ignoreCase="true" words="stopwords.txt"/>
    <filter class="solr.SynonymGraphFilterFactory" synonyms="synonyms.txt" ignoreCase="true" expand="true"/>
    <filter class="solr.SnowballPorterFilterFactory" language="English"/>
  </analyzer>
  <analyzer type="query">
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.StopFilterFactory" ignoreCase="true" words="stopwords.txt"/>
  </analyzer>
</fieldType>
```

In OpenSearch, the equivalent is defined in the index `settings` under `analysis`, and referenced by name in the `mappings`:

```json
{
  "settings": {
    "analysis": {
      "analyzer": {
        "text_en_index": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "english_stop", "english_synonyms", "english_stemmer"]
        },
        "text_en_query": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "english_stop"]
        }
      },
      "filter": {
        "english_stop": { "type": "stop", "stopwords": "_english_" },
        "english_synonyms": { "type": "synonym_graph", "synonyms_path": "analysis/synonyms.txt" },
        "english_stemmer": { "type": "stemmer", "language": "english" }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "text_en_index",
        "search_analyzer": "text_en_query"
      }
    }
  }
}
```

Key structural differences:
- Solr uses `type="index"` and `type="query"` analyzer blocks; OpenSearch uses `analyzer` and `search_analyzer` field parameters.
- Solr's `positionIncrementGap` maps to OpenSearch's `position_increment_gap` field parameter (default 100 in both).
- Named filters and tokenizers must be declared in `settings.analysis` before being referenced.

## 2. Tokenizer Mapping

| Solr Tokenizer Factory | OpenSearch Tokenizer | Notes |
| :--- | :--- | :--- |
| `solr.StandardTokenizerFactory` | `standard` | Grammar-based tokenizer; handles Unicode text. |
| `solr.ClassicTokenizerFactory` | `classic` | Pre-Solr 3.1 standard tokenizer behavior. |
| `solr.WhitespaceTokenizerFactory` | `whitespace` | Splits on whitespace only. |
| `solr.KeywordTokenizerFactory` | `keyword` | Treats entire input as a single token. |
| `solr.LetterTokenizerFactory` | `letter` | Splits on non-letter characters. |
| `solr.LowerCaseTokenizerFactory` | `lowercase` | Splits on non-letters and lowercases. |
| `solr.UAX29URLEmailTokenizerFactory` | `uax_url_email` | Handles URLs and email addresses as tokens. |
| `solr.PathHierarchyTokenizerFactory` | `path_hierarchy` | Tokenizes path-like strings hierarchically. |
| `solr.NGramTokenizerFactory` | `ngram` | Generates n-grams from the entire input. |
| `solr.EdgeNGramTokenizerFactory` | `edge_ngram` | Generates n-grams from the beginning of input. |
| `solr.PatternTokenizerFactory` | `pattern` | Splits on a regex pattern. |
| `solr.ICUTokenizerFactory` | `icu_tokenizer` | Unicode-aware tokenizer (requires ICU Analysis plugin). |

### Tokenizer Parameter Mapping

**NGram / EdgeNGram:** `minGramSize` → `min_gram`, `maxGramSize` → `max_gram`, `preserveOriginal` → `preserve_original`.

**Pattern:** `pattern` → `pattern`, `group` → `group`.

**Path Hierarchy:** `delimiter` → `delimiter`, `replacement` → `replacement`, `skip` → `skip`.

## 3. Token Filter Mapping

| Solr Filter Factory | OpenSearch Filter Type | Notes |
| :--- | :--- | :--- |
| `solr.LowerCaseFilterFactory` | `lowercase` | Lowercases all tokens. |
| `solr.StopFilterFactory` | `stop` | Removes stop words. |
| `solr.SynonymFilterFactory` | `synonym` | Solr 4/5 synonym filter. Prefer `synonym_graph`. |
| `solr.SynonymGraphFilterFactory` | `synonym_graph` | Solr 6+ synonym filter; use with `flatten_graph` at index time. |
| `solr.SnowballPorterFilterFactory` | `snowball` | `language` attribute maps directly. |
| `solr.PorterStemFilterFactory` | `porter_stem` | English Porter stemmer. |
| `solr.EnglishMinimalStemFilterFactory` | `stemmer` with `language: minimal_english` | |
| `solr.KStemFilterFactory` | `kstem` | KStem English stemmer. |
| `solr.TrimFilterFactory` | `trim` | Trims leading/trailing whitespace. |
| `solr.ASCIIFoldingFilterFactory` | `asciifolding` | Converts accented characters to ASCII. |
| `solr.WordDelimiterFilterFactory` | `word_delimiter` | Solr 4/5; prefer `word_delimiter_graph`. |
| `solr.WordDelimiterGraphFilterFactory` | `word_delimiter_graph` | Graph-aware version (Solr 6+). |
| `solr.NGramFilterFactory` | `ngram` | Generates n-grams from tokens. |
| `solr.EdgeNGramFilterFactory` | `edge_ngram` | Generates edge n-grams from tokens. |
| `solr.ShingleFilterFactory` | `shingle` | Generates shingles (multi-word tokens). |
| `solr.RemoveDuplicatesTokenFilterFactory` | `unique` | Removes duplicate tokens at the same position. |
| `solr.LengthFilterFactory` | `length` | Removes tokens outside a min/max length range. |
| `solr.TruncateTokenFilterFactory` | `truncate` | Truncates tokens to a maximum length. |
| `solr.PatternReplaceFilterFactory` | `pattern_replace` | Applies a regex replacement to tokens. |
| `solr.ElisionFilterFactory` | `elision` | Removes elisions (e.g., French `l'`). |
| `solr.FlattenGraphFilterFactory` | `flatten_graph` | Required after `synonym_graph` at index time. |
| `solr.ICUFoldingFilterFactory` | `icu_folding` | Unicode folding (requires ICU Analysis plugin). |
| `solr.CJKWidthFilterFactory` | `cjk_width` | Normalizes CJK width variants. |
| `solr.CJKBigramFilterFactory` | `cjk_bigram` | Generates CJK bigrams. |

### Token Filter Parameter Mapping

**StopFilter:** `words` → `stopwords_path`, `ignoreCase` → `ignore_case`. Note: Solr `snowball` format must be converted to a plain list for OpenSearch.

**WordDelimiter / WordDelimiterGraph:** `generateWordParts` → `generate_word_parts`, `generateNumberParts` → `generate_number_parts`, `catenateWords` → `catenate_words`, `catenateNumbers` → `catenate_numbers`, `catenateAll` → `catenate_all`, `splitOnCaseChange` → `split_on_case_change`, `splitOnNumerics` → `split_on_numerics`, `preserveOriginal` → `preserve_original`, `stemEnglishPossessive` → `stem_english_possessive`.

**NGram / EdgeNGram filters:** `minGramSize` → `min_gram`, `maxGramSize` → `max_gram`, `preserveOriginal` → `preserve_original`.

**Shingle:** `minShingleSize` → `min_shingle_size`, `maxShingleSize` → `max_shingle_size`, `outputUnigrams` → `output_unigrams`, `outputUnigramsIfNoShingles` → `output_unigrams_if_no_shingles`, `tokenSeparator` → `token_separator`.

## 4. Char Filter Mapping

Char filters run before tokenization and operate on the raw character stream:

| Solr Char Filter Factory | OpenSearch Char Filter Type | Notes |
| :--- | :--- | :--- |
| `solr.HTMLStripCharFilterFactory` | `html_strip` | Strips HTML tags from input. |
| `solr.MappingCharFilterFactory` | `mapping` | Applies character-level substitutions from a mapping file. |
| `solr.PatternReplaceCharFilterFactory` | `pattern_replace` | Applies a regex replacement to the character stream. |
| `solr.ICUNormalizeCharFilterFactory` | `icu_normalizer` | ICU normalization (requires ICU Analysis plugin). |

**MappingCharFilter:** The Solr `mapping` attribute points to a file with lines like `"ä" => "ae"`. In OpenSearch, use the `mappings` parameter as an inline array (`["ä => ae", "ö => oe"]`) or `mappings_path` to point to a file.
