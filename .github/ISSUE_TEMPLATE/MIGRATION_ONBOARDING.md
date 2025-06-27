---
name:  🔧 Migration Onboarding
about: Create an onboarding issue
title: '[Onboarding]'
labels: 'migrations and upgrades, untriaged'
assignees: ''
---

### Issue Description
_Please provide a detailed description of the problem or question. If you have any relevant logs, screenshots, or supporting data, include them inline or as attachments to help us assist you more effectively._

### High-Level / Project Management Questions

- **Source version:** `<Elasticsearch version | OpenSearch version>`  
  *Please specify the full version (e.g., Elasticsearch 7.10.2).*

- **Source environment:** `<source environment details>`
  *Please specify details about your source environment, including whether it is self-managed or managed, and whether it is in the cloud or on-premises.*

- **Target version:** `<Elasticsearch | OpenSearch>`  
  *Please specify the full version (e.g., OpenSearch 1.3).*

- **Target environment:** `<target environment details>`
  *Please specify details about your target environment, including whether it is self-managed or managed, and whether it is in the cloud or on-premises.*

- **Migration Assistant Version:** `<Specify release version used>`

- **Do you have a maintenance window during which requests will not be serviced?** `<Yes | No>`  
  *Determines the migration strategy—zero downtime vs. planned outage—and helps prevent unexpected service disruptions. If no maintenance window is available, or if the window is insufficient for a full backfill, a live capture mechanism must be included.*

- **Do you perform multiple updates or deletes to the same document within 10 seconds?** `<Yes | No>`  
  *If updates occur faster than 10 seconds, additional mechanisms may be required to preserve update order and document consistency.*

- **Is a 30ms impact for indexing requests acceptable?** `<Yes | No>`  
  *Typically, there’s a 10–30 ms delay on source cluster index requests, and sub-second delays have been observed between source and target. If this additional latency is not acceptable, live capture cannot be performed.*

- **What is your primary use case?** `<Search | Logging>`  
  *Migration strategies may vary depending on the use case.*

### Platform & Architecture

- **Number of nodes:** `<count>`

- **Have you identified all application dependencies on Elasticsearch/OpenSearch and the required client changes?** `<Yes | No>`  
  *Ensures the migration won’t break dependent applications or integrations.*

- **Source authentication mechanism:** `<SigV4 | Basic Auth | No Auth>`  
- **Target authentication mechanism:** `<SigV4 | Basic Auth | No Auth>`

- **[Live Capture Limitation] Do your applications generate document IDs, or do you rely on Elasticsearch/OpenSearch to generate them?** `<Yes | No>`  
  *If document IDs are not specified by the application, source and target states may diverge during live migration.*

- **Do source indices contain a `_source` field?** `<Yes | No>`  
  *The `_source` field is required for reindexing and recovery. Most migration tools, including Migration Assistant, depend on it.*  
  _Note: You can verify if `_source` has been disabled by checking the index mappings. If `_source` is not explicitly set to `false`, it has not been disabled._

- **Are you able to redirect client traffic for your migration?** `<Yes | No | N/A if not using live capture>`  
  *To capture live traffic, source traffic must be redirected at the start of the migration and again when it completes.*

### Data & Cluster Metrics

- **How much data is stored in the cluster?** `<Specify with units, e.g., 2PB>`

- **Do you have any indices created more than one major version ago that have not been reindexed?** `<Yes | No>`  
  *Upgrades require contiguous major versions. For example, if an index was created in OpenSearch 1 and you're migrating to OpenSearch 3, it must first be reindexed into OpenSearch 2.*

- **What is the average and peak throughput of the cluster (in MBps)?**  
  *Ensures the cluster operates within expected Migration Assistant limits.*

- **What is your indexing rate (writes per second)?**  
- **How many queries per second?**  
- **How many shards exist, and what is the maximum shard size?**  
  *Shard count and size impact performance and migration scalability. This also helps identify potential shard size limitations.*  
  _Tip: Use the following request to retrieve shard data: `GET _cat/shards?v=true&s=index`_

### Plugins

- **Have you reviewed plugin compatibility between your source and target environments?** `<Yes | No>`  
  *Not all plugins are compatible across versions. Some may require alternatives or custom integrations.*

- **Are you using Kibana or any other visualization tools?** `<Yes | No>`  
  *Ensures dashboards and visualizations are preserved or rebuilt post-migration.*
