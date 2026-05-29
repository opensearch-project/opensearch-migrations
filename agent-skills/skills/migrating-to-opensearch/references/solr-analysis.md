# Solr → OpenSearch analysis pipelines, synonyms, and language analyzers

This file owns the **operational rules** for migrating Solr analysis pipelines (tokenizers, token filters, char filters), synonyms, and language analyzers — these plus the high-leverage class-name mappings below are stable-core, so draft directly from them. Only the long-tail per-class mappings and parameter renames are version-volatile: tag those `[verify]` and confirm in the Step 8 batch rather than blocking the draft.

## Structural mapping

In Solr, analysis is defined inside `<fieldType>` elements in `schema.xml` with `<analyzer type="index">` and `<analyzer type="query">` blocks. In OpenSearch, the equivalent lives in `settings.analysis` and is referenced from `mappings` via `analyzer` and `search_analyzer`:

```json
{
  "settings": {
    "analysis": {
      "analyzer": {
        "text_en_index": {"type": "custom", "tokenizer": "standard",
          "filter": ["lowercase", "english_stop", "english_synonyms", "flatten_graph", "english_stemmer"]},
        "text_en_query": {"type": "custom", "tokenizer": "standard",
          "filter": ["lowercase", "english_stop"]}
      },
      "filter": {
        "english_stop":     {"type": "stop", "stopwords": "_english_"},
        "english_synonyms": {"type": "synonym_graph", "synonyms_path": "analysis/synonyms.txt"},
        "english_stemmer":  {"type": "stemmer", "language": "english"}
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {"type": "text", "analyzer": "text_en_index", "search_analyzer": "text_en_query"}
    }
  }
}
```

Key structural differences:
- Solr `type="index"` / `type="query"` analyzer blocks → OpenSearch `analyzer` / `search_analyzer` field parameters.
- Solr `positionIncrementGap` → OpenSearch `position_increment_gap` (default 100 in both).
- You MUST declare named filters/tokenizers in `settings.analysis` before reference.

## Class-name mapping (high-leverage set is stable-core; `[verify]` the long tail in the batch)

Draft from the high-leverage class names below — they are stable and cover the common case. For the full Solr-factory ↔ OpenSearch-class mapping (the long tail of tokenizers, token filters, char filters, including parameter renames), tag the specific mapping `[verify]` and confirm in the Step 8 batch — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section) for the OpenSearch tokenizer / token-filter / char-filter pages and the Apache Solr tokenizer / filter pages.

The high-leverage class names you will encounter most often: `solr.StandardTokenizerFactory` → `standard`; `solr.WhitespaceTokenizerFactory` → `whitespace`; `solr.LowerCaseFilterFactory` → `lowercase`; `solr.StopFilterFactory` → `stop`; `solr.SynonymGraphFilterFactory` → `synonym_graph`; `solr.WordDelimiterGraphFilterFactory` → `word_delimiter_graph`; `solr.HTMLStripCharFilterFactory` → `html_strip`; `solr.MappingCharFilterFactory` → `mapping`. Parameter names go camelCase → snake_case (e.g. `minGramSize` → `min_gram`, `generateWordParts` → `generate_word_parts`). All other mappings come from the live docs.

## Operational rules (skill IP — you MUST NOT omit)

You MUST NOT omit these rules because they bite in production and are not always obvious from the docs:

1. **Filter order.** You MUST place `lowercase` BEFORE `synonym_graph` and `stop`. The OpenSearch/Elasticsearch `synonym_graph` filter's `ignore_case` parameter is deprecated (it works only with the deprecated `tokenizer` parameter, per the [Elasticsearch synonym graph docs](https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-synonym-graph-tokenfilter.html)); case-folding MUST happen first so mixed-case input (`IPod`) matches lowercase dictionary entries (`ipod`). This mirrors Solr's `ignoreCase="true"` on `SynonymGraphFilterFactory` (per the [Solr filters docs](https://solr.apache.org/guide/solr/latest/indexing-guide/filters.html)).
2. **`flatten_graph` rule.** When `synonym_graph` (or `word_delimiter_graph`) appears in an **index-time** analyzer, the next filter MUST be `flatten_graph` because indexing cannot consume multi-position graph tokens directly (per the [Elasticsearch flatten_graph docs](https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-flatten-graph-tokenfilter.html): "Indexing does not support token graphs containing multi-position tokens"). At query time, you MUST NOT add `flatten_graph` because the query path consumes graphs natively. Preferred practice (per the same doc): "use graph token filters in search analyzers only" so `flatten_graph` is unnecessary.
3. **`SynonymFilterFactory` (Solr 4/5)** is graph-unaware and mishandles multi-word synonyms. The Solr docs explicitly mark it deprecated: "Synonym Filter has been deprecated in favor of Synonym Graph Filter, which is required for multi-term synonym support" (per the [Solr 6.6 filter descriptions](https://solr.apache.org/guide/6_6/filter-descriptions.html); `SynonymGraphFilterFactory` was available from Solr 6.6+). You MUST migrate to `synonym_graph`. You MUST review `synonyms.txt` for multi-word entries and re-test.
4. **`WordDelimiterFilterFactory`** → you SHOULD prefer `word_delimiter_graph` in OpenSearch for the same graph-correctness reasons.
5. **Trie-field analyzers** carry no analysis config because Trie types use internal numeric encoding. When migrating Trie types to OpenSearch native numeric types (`integer`/`long`/`float`/`double`/`date`), no analyzer migration is needed.

## Synonyms (`synonyms.txt`) migration

Solr supports two formats: equivalent (`ipod, i-pod, i pod`) and explicit (`i-pod, i pod => ipod`). Both work in OpenSearch unchanged.

OpenSearch filter types: `synonym` (graph-unaware, Solr 4/5 equivalent — backward compat only) and `synonym_graph` (preferred). You SHOULD use `synonyms` for inline lists or `synonyms_path` (relative to `<config_dir>/analysis/`) for files.

Migration steps:
1. You MUST copy `synonyms.txt` from Solr `conf/` to `<opensearch_config>/analysis/`.
2. You MUST verify UTF-8 encoding; `#` comment lines are supported in both.
3. `expand="true"` (Solr default) is the default in `synonym_graph`. For `expand="false"`, you MUST set `"expand": false`.
4. For `ignoreCase="true"`, you MUST place `lowercase` before `synonym_graph` (rule 1 above).

`ManagedSynonymFilterFactory` (Solr 5+): you MUST export with `GET /solr/<collection>/schema/analysis/synonyms/english`, convert the JSON to a flat `synonyms.txt`, then use file-based `synonym_graph`.

## Language analyzers

For languages with a built-in OpenSearch analyzer, you SHOULD set `"analyzer": "<name>"` directly on the field instead of building a custom analyzer:

| Solr `text_*` | OpenSearch | Notes |
|---|---|---|
| `text_en` | `english` | |
| `text_de`, `text_fr`, `text_es`, `text_it`, `text_pt`, `text_nl`, `text_ru`, `text_ar` | matching language analyzer | |
| `text_zh` | `cjk` or `smartcn` | `smartcn` requires the `analysis-smartcn` plugin (pre-installed on Amazon OpenSearch Service per [AWS supported plugins](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/supported-plugins.html)) |
| `text_ja` | `kuromoji` | requires the `analysis-kuromoji` plugin (included on all Amazon OpenSearch Service domains per [AWS supported plugins](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/supported-plugins.html)) |
| `text_ko` | `nori` | requires the `analysis-nori` plugin (pre-installed on Amazon OpenSearch Service 1.3+ per [AWS supported plugins](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/supported-plugins.html)) |

For the full set of built-in analyzers, retrieve the OpenSearch supported-analyzers page per [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).
