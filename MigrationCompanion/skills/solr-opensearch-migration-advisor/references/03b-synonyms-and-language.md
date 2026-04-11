# Solr to OpenSearch: Synonyms, Language Analyzers, and Legacy Considerations

This document is a continuation of `03-analysis-pipelines.md`, covering synonyms migration, language-specific analyzers, Solr 4/5 legacy considerations, and a full worked example.

## 5. Synonyms Migration (`synonyms.txt`)

### 5.1 Solr Synonym File Formats

**Equivalent synonyms (comma-separated):**
```
ipod, i-pod, i pod
universe, cosmos
```

**Explicit mappings (arrow notation):**
```
i-pod, i pod => ipod
universe => cosmos, world
```

### 5.2 Solr Synonym Filter Versions

| Solr Version | Filter Factory | Behavior |
| :--- | :--- | :--- |
| Solr 4/5 | `solr.SynonymFilterFactory` | Graph-unaware; multi-word synonyms may be handled incorrectly. |
| Solr 6+ | `solr.SynonymGraphFilterFactory` | Graph-aware; handles multi-word synonyms correctly. |

### 5.3 OpenSearch Synonym Filter Configuration

- **`synonym`**: Graph-unaware (equivalent to Solr 4/5 `SynonymFilterFactory`). Use only for backward compatibility.
- **`synonym_graph`**: Graph-aware (equivalent to Solr 6+ `SynonymGraphFilterFactory`). **Preferred.**

**Inline synonyms:**
```json
{
  "filter": {
    "my_synonyms": {
      "type": "synonym_graph",
      "synonyms": ["ipod, i-pod, i pod", "universe => cosmos, world"]
    }
  }
}
```

**File-based synonyms (recommended for large lists):**
```json
{
  "filter": {
    "my_synonyms": {
      "type": "synonym_graph",
      "synonyms_path": "analysis/synonyms.txt"
    }
  }
}
```

The `synonyms_path` is relative to the OpenSearch config directory. Place the file at `<config_dir>/analysis/synonyms.txt`.

### 5.4 Index-Time vs. Query-Time Synonyms

| Approach | Solr | OpenSearch |
| :--- | :--- | :--- |
| Index-time synonyms | `SynonymFilterFactory` in `<analyzer type="index">` | Use `synonym_graph` + `flatten_graph` in index analyzer. |
| Query-time synonyms | `SynonymFilterFactory` in `<analyzer type="query">` | Use `synonym_graph` in search analyzer (no `flatten_graph`). |

**Important:** When using `synonym_graph` at **index time**, add `flatten_graph` as the next filter:

```json
{
  "analyzer": {
    "text_index": {
      "type": "custom",
      "tokenizer": "standard",
      "filter": ["lowercase", "my_synonyms", "flatten_graph"]
    }
  }
}
```

### 5.5 Synonym File Migration Steps

1. Copy `synonyms.txt` from Solr `conf/` to OpenSearch config `analysis/` directory.
2. Verify UTF-8 encoding (both require it).
3. `#` comment lines are supported in both Solr and OpenSearch.
4. If using `expand="true"` (Solr default), this is the default behavior of `synonym_graph`.
5. If using `expand="false"` in Solr, set `"expand": false` in the OpenSearch filter definition.
6. For `ignoreCase="true"`, place `lowercase` before `synonym_graph` in the filter chain.

### 5.6 `ManagedSynonymFilterFactory` (Solr 5+)

Export managed synonyms via the Solr REST API: `GET /solr/<collection>/schema/analysis/synonyms/english`, convert the JSON response to a flat `synonyms.txt` file, then use file-based `synonym_graph` in OpenSearch.

## 6. Language-Specific Analyzers

| Solr `fieldType` name (common) | OpenSearch Built-in Analyzer |
| :--- | :--- |
| `text_en` (English) | `english` |
| `text_de` (German) | `german` |
| `text_fr` (French) | `french` |
| `text_es` (Spanish) | `spanish` |
| `text_it` (Italian) | `italian` |
| `text_pt` (Portuguese) | `portuguese` |
| `text_nl` (Dutch) | `dutch` |
| `text_ru` (Russian) | `russian` |
| `text_ar` (Arabic) | `arabic` |
| `text_zh` (Chinese) | `cjk` or `smartcn` (with plugin) |
| `text_ja` (Japanese) | `kuromoji` (with plugin) |
| `text_ko` (Korean) | `nori` (with plugin) |

For languages with a built-in OpenSearch analyzer, set `"analyzer": "english"` (etc.) directly on the field mapping instead of defining a custom analyzer.

## 7. Solr 4 and 5 Legacy Considerations

- **`SynonymFilterFactory`**: Solr 4/5 only. Does not correctly handle multi-word synonyms. Use `synonym_graph` in OpenSearch and review `synonyms.txt` for multi-word entries.
- **`WordDelimiterFilterFactory`**: Solr 4/5. Map to `word_delimiter_graph` (preferred) or `word_delimiter` in OpenSearch.
- **Trie field analyzers**: Numeric Trie fields use internal encoding, not text analysis. No analysis config needed when migrating to OpenSearch numeric types.
- **`StandardTokenizerFactory` versions**: Solr 4 (Lucene 4.x), Solr 5 (Lucene 5.x), Solr 6 (Lucene 6.x, Unicode 8.0). OpenSearch uses a recent Lucene version; behavior is equivalent for most text but edge cases with emoji or unusual Unicode may differ.
- **`ClassicTokenizerFactory`**: Maps directly to OpenSearch `classic` tokenizer.
- **`ManagedStopFilterFactory`** (Solr 5+): Export via `GET /solr/<collection>/schema/analysis/stopwords/english` and convert to a plain stopwords file for OpenSearch.

## 8. Full Example: Migrating a Solr `text_en` Field Type

**Solr `schema.xml` (Solr 6 style):**
```xml
<fieldType name="text_en" class="solr.TextField" positionIncrementGap="100">
  <analyzer type="index">
    <charFilter class="solr.HTMLStripCharFilterFactory"/>
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.StopFilterFactory" ignoreCase="true" words="lang/stopwords_en.txt"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.EnglishPossessiveFilterFactory"/>
    <filter class="solr.KeywordMarkerFilterFactory" protected="protwords.txt"/>
    <filter class="solr.PorterStemFilterFactory"/>
  </analyzer>
  <analyzer type="query">
    <charFilter class="solr.HTMLStripCharFilterFactory"/>
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.SynonymGraphFilterFactory" synonyms="synonyms.txt" ignoreCase="true" expand="true"/>
    <filter class="solr.StopFilterFactory" ignoreCase="true" words="lang/stopwords_en.txt"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.EnglishPossessiveFilterFactory"/>
    <filter class="solr.KeywordMarkerFilterFactory" protected="protwords.txt"/>
    <filter class="solr.PorterStemFilterFactory"/>
  </analyzer>
</fieldType>
```

**OpenSearch equivalent index settings:**
```json
{
  "settings": {
    "analysis": {
      "char_filter": {
        "html_strip_filter": { "type": "html_strip" }
      },
      "filter": {
        "english_stop":       { "type": "stop", "stopwords": "_english_" },
        "english_possessive": { "type": "stemmer", "language": "possessive_english" },
        "english_keywords":   { "type": "keyword_marker", "keywords_path": "analysis/protwords.txt" },
        "english_stemmer":    { "type": "stemmer", "language": "english" },
        "english_synonyms":   { "type": "synonym_graph", "synonyms_path": "analysis/synonyms.txt", "expand": true }
      },
      "analyzer": {
        "text_en_index": {
          "type": "custom",
          "char_filter": ["html_strip_filter"],
          "tokenizer": "standard",
          "filter": ["english_stop", "lowercase", "english_possessive", "english_keywords", "english_stemmer"]
        },
        "text_en_query": {
          "type": "custom",
          "char_filter": ["html_strip_filter"],
          "tokenizer": "standard",
          "filter": ["english_synonyms", "english_stop", "lowercase", "english_possessive", "english_keywords", "english_stemmer"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "text_en_index",
        "search_analyzer": "text_en_query",
        "position_increment_gap": 100
      }
    }
  }
}
```

Notes:
- `EnglishPossessiveFilterFactory` → `stemmer` with `language: possessive_english`.
- `KeywordMarkerFilterFactory` → `keyword_marker` with `keywords_path`.
- `PorterStemFilterFactory` → `stemmer` with `language: english`.
- Synonyms are applied at query time only (recommended); `flatten_graph` is not needed.
- Stop words use the built-in `_english_` list; replace with `stopwords_path` for a custom file.
