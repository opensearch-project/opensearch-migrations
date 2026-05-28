# Scope — What This Skill Does and Does Not Do

> **Skill IP** — operational framing for this assessment skill, not from any upstream doc.

## In scope

- Profile a Solr / Elasticsearch / OpenSearch source cluster for migration assessment.
- Recommend a target: Amazon OpenSearch Service or Amazon OpenSearch Serverless, plus collection
  type (search / time-series / vector) when applicable.
- Recommend a migration mechanism: Migration Assistant for OpenSearch (Reindex-from-Snapshot
  / RFS + Capture & Replay, including the Solr backfill workflow), snapshot/restore, OpenSearch
  Ingestion, reindex from remote, Logstash/EMR, or in-place blue/green upgrade.
- Solr schema mapping and query translation guidance via the bundled `solr-*` references.
- Produce sizing inputs that the customer plugs into the AWS Pricing Calculator
  (<https://calculator.aws>); the skill itself does not estimate dollars.
- Produce a written assessment report with citations retrieved live; per-domain retrieval
  primitives are catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md)
  (Three retrieval primitives section).

## Out of scope

- **Executing the migration.** You MUST hand off to the Migration Assistant docs and the user's
  runbook when they are ready to act.
- **Application-code refactors** (e.g., SolrJ → opensearch-java). You MUST hand off to the
  `aws-transform` skill or the user's engineering team.
- **Non-search workloads.** For RDS, Redshift, DynamoDB, or other migrations, you MUST use the
  appropriate AWS skills.
- **Operating a live OpenSearch cluster.** For day-2 operations, you MUST use the AWS MCP server
  and the Amazon OpenSearch Service operational docs.
- **Dollar-cost estimation.** You MUST NOT estimate dollars because pricing changes monthly and
  account-specific RI / Savings Plan / EDP discount math is unverifiable by an LLM. You MUST plug
  the sizing inputs into the AWS Pricing Calculator instead.

## When the user crosses a scope boundary

You MUST NOT attempt the out-of-scope task because doing so degrades quality and produces unreliable output. You MUST recommend the right tool:

| User asks for... | Recommend |
|---|---|
| "Run the migration now" | Migration Assistant docs + the user's runbook |
| "Refactor my application code from SolrJ to opensearch-java" | `aws-transform` skill |
| "Tune my live OpenSearch cluster" | AWS MCP server + OpenSearch operational docs |
| "Quote me an EDP-discounted price" | AWS Pricing Calculator + AWS Account Manager / Solutions Architect |
