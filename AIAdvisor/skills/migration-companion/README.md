# Migration Companion for OpenSearch

An **OpenSearch Agent Skill** that provides a unified, AI-guided migration
experience for migrating to OpenSearch from Elasticsearch, OpenSearch, or
Apache Solr.

## What It Does

Migration Companion is the conversational front door to
[Migration Assistant](https://github.com/opensearch-project/opensearch-migrations).
It uses progressive discovery to:

1. **Identify** the source platform and version from natural language
2. **Assess** the source environment with source-specific depth
   - Elasticsearch/OpenSearch: lightweight assessment (version, size, downtime tolerance)
   - Solr: deep assessment (schema conversion, query translation, sizing)
3. **Recommend** the right migration strategy (backfill-only vs. backfill + capture & replay)
4. **Deploy** Migration Assistant on EKS or Docker
5. **Execute** the migration workflow (metadata → snapshot → backfill)
6. **Validate** results (doc counts, cluster stats, sample queries)
7. **Guide** cutover and cleanup

## Example Prompts

* "Help me migrate from Elasticsearch 7.10 to OpenSearch."
* "I need to migrate from Solr 8 to OpenSearch and understand whether my queries will work."
* "Upgrade my OpenSearch 1.3 cluster to OpenSearch 2.17."
* "Full autonomous migration from my ES cluster in EKS to a new Amazon OpenSearch Service domain."

## Supported Sources

| Source | Backfill | Capture & Replay |
|---|---|---|
| Elasticsearch 1.x–8.x | ✅ | ✅ (5.x+) |
| OpenSearch 1.x–2.x | ✅ | ✅ |
| Apache Solr 8.x–9.x | ✅ (SolrReader) | ❌ |

## Skill Structure

```
migration-companion/
├── SKILL.md                          # Main skill definition and workflow
├── README.md                         # This file
├── steering/
│   ├── sizing.md                     # Cluster sizing rules (all sources)
│   ├── compatibility.md              # Version compatibility and known issues
│   └── safety.md                     # Destructive action rules
└── references/
    ├── elasticsearch-migration.md    # ES-specific migration details
    ├── solr-migration.md             # Solr schema, query, architecture mapping
    └── migration-assistant.md        # MA capabilities and CLI reference
```

## Relationship to Existing Components

| Component | Role | Relationship |
|---|---|---|
| **Migration Companion** (this skill) | Conversational front door | Orchestrates everything below |
| **Migration Assistant** | Execution engine | Deployed and driven by Companion |
| **Solr Migration Advisor** | Solr-specific assessment | Assessment logic incorporated into Companion's Phase 1B |
| **Kiro CLI agent** | ES/OS execution agent | Execution logic incorporated into Companion's Phases 4–6 |

## License

Apache-2.0. See [LICENSE](../../LICENSE).
