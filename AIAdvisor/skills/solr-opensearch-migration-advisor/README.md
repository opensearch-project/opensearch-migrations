# Solr to OpenSearch Migration Skill

An **OpenSearch Agent Skill** that helps migrate from [Apache Solr](https://solr.apache.org/) to [OpenSearch](https://opensearch.org/).

## Example Prompts

* "Help me migrate from Solr to OpenSearch."
* "Convert this Solr schema to OpenSearch mapping: `<schema>...</schema>`"
* "Translate this Solr query to OpenSearch: `title:opensearch AND price:[10 TO 100]`"
* "Create a migration report for my Solr setup."

> [!TIP]
> In Kiro, use **I want to use the Solr to OpenSearch skill.** to ensure Kiro activates this skill.


# Developer Guide

## Running the Tests

```bash
uv pip install -e ".[dev]"
pytest
```

## How to Exercise the Skill
Use the below samples in your agent chat UI to interact with the skill.

### Example Solr IMDB queries

* q=title:Inception
* q=title:Batman AND genres:Action
* q=*:*&fq=startYear:[2010 TO 2020]
* q=genres:Sci-Fi&sort=startYear desc

```
defType=edismax
&q=Christopher Nolan Sci-Fi
&qf=title^10 directors^2 genres^1
&pf=title^20
&tie=0.1
&bq=averageRating:[8 TO 10]^5
```

### Example Solr Cluster Config

1. SolrCloud with 3 nodes, 2 shards, 2 replicas per shard
2. 40 million documents
3. 50 QPS
4. Indexing rate is 5,000 to 10,000 docs/second
5. Running on AWS m6.ilarge EC2 instances
6. Heap is 8 GB

### Example Solr Schema

```
<schema name="imdb-movies" version="1.6">
  <!-- Unique Identifier -->
  <field name="id" type="string" indexed="true" stored="true" required="true" />

  <!-- Movie Metadata -->
  <field name="title" type="text_general" indexed="true" stored="true" />
  <field name="original_title" type="text_general" indexed="true" stored="true" />
  <field name="year" type="pint" indexed="true" stored="true" />
  <field name="runtime_minutes" type="pint" indexed="true" stored="true" />
  <field name="is_adult" type="boolean" indexed="true" stored="true" />

  <!-- Content and People (Multi-valued) -->
  <field name="genres" type="string" indexed="true" stored="true" multiValued="true" />
  <field name="directors" type="string" indexed="true" stored="true" multiValued="true" />
  <field name="cast" type="string" indexed="true" stored="true" multiValued="true" />

  <!-- Ratings and Metrics -->
  <field name="average_rating" type="pfloat" indexed="true" stored="true" />
  <field name="num_votes" type="plong" indexed="true" stored="true" />

  <!-- Catch-all field for searching -->
  <field name="text" type="text_general" indexed="true" stored="false" multiValued="true" />

  <!-- Copy Fields for search optimization -->
  <copyField source="title" dest="text"/>
  <copyField source="directors" dest="text"/>
  <copyField source="cast" dest="text"/>
  <copyField source="genres" dest="text"/>

  <!-- Field Type Definitions -->
  <fieldType name="string" class="solr.StrField" sortMissingLast="true" />
  <fieldType name="text_general" class="solr.TextField" positionIncrementGap="100">
    <analyzer>
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.LowerCaseFilterFactory"/>
    </analyzer>
  </fieldType>
  <fieldType name="pint" class="solr.IntPointField" />
  <fieldType name="pfloat" class="solr.FloatPointField" />
  <fieldType name="plong" class="solr.LongPointField" />
  <fieldType name="boolean" class="solr.BoolField" />
</schema>
```
