# OpenSearch Dashboards Migration Design Document

## Table of Contents
1. [Key Terminology](#key-terminology)
2. [Problem Statement](#problem-statement)
3. [Current State Analysis](#current-state-analysis)
4. [Solution Approach](#solution-approach)
5. [User Experience Flow](#user-experience-flow)
6. [Appendix](#appendix)

## Key Terminology

* **.kibana_N indexes**
: The .kibana* indexes store runtime configurations and saved objects in Kibana and OpenSearch Dashboards. These indexes contain user-created content, dynamic settings, and are updated through UI interactions. In Elasticsearch 5.x and earlier, a single .kibana index was used. Elasticsearch 6.x and 7.x introduced multiple .kibana* indexes (e.g., .kibana_1, .kibana_2) with a .kibana alias pointing to the current active index. OpenSearch uses .opensearch_dashboards index, which replaces .kibana but maintains a similar structure to Elasticsearch 7.x.

* **Index Patterns**
: Index patterns in OpenSearch Dashboards define which OpenSearch indices you want to explore and analyze. They allow you to perform searches and create visualizations across one or more indices that match the specified pattern.

* **Visualizations**
: Visualizations in OpenSearch Dashboards are graphical representations of your data. They can include various chart types, metrics, maps, and other visual elements that help you understand and analyze your data. Visualizations can be combined to create comprehensive dashboards.
For a comprehensive list of available visualizations, refer to [Appendix [5]](#appendix).

* **Dashboard**
: Dashboards in OpenSearch Dashboards are collections of visualizations, searches, and other components arranged on a single page. They provide a comprehensive view of your data, allowing users to interact with and explore data insights in real-time. Dashboards can be shared, exported, and embedded into other applications.

* **Saved Objects**
: Saved objects in Kibana and OpenSearch Dashboards include dashboards, visualizations, index patterns, and other configurations that users can save and manage. They enable users to preserve their work and share it across different environments or with other users.

* **Export.json file**
: The export.json file is a JSON-formatted file that contains specific saved objects from Kibana and OpenSearch Dashboards, such as Dashboards, Visualizations, Index Patterns and other saved objects. This file represents a snapshot of selected content at the time of export and is commonly used for:
  - Backing up OpenSearch Dashboards configurations
  - Migrating content between different OpenSearch or Elasticsearch environments
  - Sharing specific dashboards or visualizations with other users or system

For a comprehensive list of exportable saved objects, refer to [Appendix [6]](#appendix).

---

## Problem Statement

Customers migrating from Kibana to OpenSearch Dashboards face significant operational challenges due to the lack of an automated solution for dashboard migration. This gap forces organizations to rely on manual, error-prone processes that are time-intensive and risk data loss, broken visualizations, and service disruptions.

To address this need, we propose introducing a new feature within the OpenSearch Migration Assistant: **Dashboards Migration**. This feature will streamline the transformation and migration of Kibana dashboards to OpenSearch Dashboards, preserving referential integrity between visualizations and their data sources. By eliminating manual intervention, this enhancement reduces business risks and ensures a seamless transition for organizations migrating from Elasticsearch to OpenSearch.

---

## Current State Analysis

### Background: How to migrate dashboards and related content from Kibana to OpenSearch Dashboards today?

Migrating dashboards and related content from Kibana to OpenSearch Dashboards (OSD) involves several methods, each with its own requirements and limitations. Below, we outline the primary approaches used today, focusing on what needs to be done to execute the migration and why these methods are not optimal for users.

#### 1. Self-Managed to Self-Managed Migration Using Configuration Files

This method involves exporting Kibana objects, migrating the Elasticsearch cluster to OpenSearch while preserving .kibana* indices, and manually translating configuration settings from kibana.yml to opensearch_dashboards.yml. However, this process is highly manual, error-prone, and lacks automated validation for configuration translations across different Elasticsearch versions (5.x, 6.x, 7.x).

#### 2. Using Export/Import Option

In this approach, saved objects (e.g., dashboards, visualizations, index patterns) are exported from Kibana into an export.json file using Kibana’s API or UI and then imported into OpenSearch Dashboards. The level of manual intervention depends on the source Elasticsearch version: older versions like 5.x require manual recreation of index patterns, while newer versions (6.8+) support more complete exports without manual intervention. While this method simplifies migrations for newer Elasticsearch versions, it fails to address critical gaps for legacy environments like 5.x.

#### 3. Dashboard Sanitizer Tool

The Dashboard Sanitizer tool processes exported Kibana dashboards in NDJSON format to transform them into OpenSearch-compatible structures using a provided transformation pipeline. Despite its functionality, this tool is limited to static exports and supports only Elasticsearch 7.10 or later versions. It excludes advanced visualization types (e.g., Lens visualizations, Maps, Canvas Workpads), making it unsuitable for complex or legacy environments requiring comprehensive migration support.

(view [Appendix](#appendix) [1], [2], [3] for more information on these methods)

---

### Background: Migration Assistant Overview

The Migration Assistant is primarily interacted with through its command-line console interface, which orchestrates each phase of the migration. The process begins with users selecting their preferred deployment method—local Docker deployment, Kubernetes, or AWS CDK—and configuring connection parameters for both the source Elasticsearch cluster and the target OpenSearch cluster. Before initiating migration tasks, the Migration Assistant performs health checks to validate connectivity and access permissions, ensuring that the system is ready for migration. The workflow involves several key phases designed to preserve data consistency and minimize risks during the transition.

1. Deploy a SourceProxy to intercept and record client traffic directed at the source Elasticsearch cluster. Persist this traffic to Kafka topics, enabling eventual replay to maintain synchronization between source and target clusters.

2. Initiate point-in-time snapshots of selected indices from the source cluster using `console snapshot create`. These snapshots establish a baseline for data migration.

3. Perform a non-destructive scan of the source cluster to identify all items eligible for migration. This step helps detect potential issues before transferring data.

4. Use `console metadata migrate` to transfer index configurations, mappings, and settings to the target OpenSearch cluster. The console provides options to review and approve modifications required for compatibility.

5. The `console backfill start` command transfers documents from source snapshots to target indices with configurable parallelism. Dynamically adjust worker counts (`console backfill scale`) based on system performance requirements.

6. Once initial data migration is complete, activate the replay mechanism (`console replay start`) to process captured traffic events against the target cluster. This progressively reduces synchronization lag between source and target systems.

7. When both clusters reach a synchronized state, redirect client applications to the target OpenSearch cluster according to the defined switchover strategy.

8. After verifying successful switchover, terminate replay processes (`console replay stop`) and clean up migration artifacts in accordance with retention policies.

Throughout this workflow, benefit from monitoring capabilities provided by integrated CloudWatch dashboards (deployed in the user's AWS account), performance metrics, and validation commands that ensure data consistency during critical transition points.

```mermaid
graph TD
    A[Deployment Method<br/>Docker/K8s/AWS CDK] --> B[Connection Validation]
    B[Connection Validation] --> C[Traffic Capture]
    
    subgraph MigrationFlow[Migration Process]
        D[Snapshot Creation] --> E[Metadata Evaluation]
        E --> F[Metadata Migration]
        F --> G[Backfill Process]
        G --> H[Traffic Replay]
        H --> I[Switchover & Cleanup]
    end
    
    C --> D
```

[4] refer to this for more details on the architecture of migration assistant.

### Solution Approach

The proposed user experience for dashboard migration aims to streamline the process by integrating it seamlessly into the Migration Assistant workflow. Here’s an overview of how users will interact with the system:

1. **Metadata Evaluation**: Perform console metadata evaluate, which now includes .kibana as a candidate for migration, providing detailed insights into dashboard components.

```bash
console metadata evaluate
...
(along with existing metadata evaluation output)

System indices identified:
- .kibana (contains 17 visualization components)
  - 8 visualizations
  - 1 index pattern
  - 1 config setting
  - 7 other dashboard-related objects
...
```

2. **Dashboard Migration**: Execute console dashboards migrate, which handles the transformation and preparation of dashboard objects for migration.

```bash
console dashboards migrate 
...
Estimated duration: 4 minutes (ES 5.6 → OS 2.19)
Proceed? [y/N]: y

Uploading to target cluster...
- Importing dashboard components
Success: Dashboards available after metadata migrate
```

3. **Dashboard Upload**: The `console dashboards upload` command imports the migrated dashboard content into the target OpenSearch cluster.

```bash
console dashboards upload --auth-type saml --role DashboardAdmin
...
Uploading to target cluster...
- Importing dashboard components
Success: Dashboards available after metadata migrate
...
```

4. **Standard Migration Flow**: Proceed with `console metadata migrate` and `console backfill start` to complete the data migration.

```bash
...
console metadata migrate
...
console backfill start
...
```

```mermaid
graph TD
    A[Start Migration] --> B{Source Version Detection}
    B -->|For example, ES 5.6| C["Create Custom Snapshot\n(.kibana + limited data indices)"]
    C --> D[Restore in ES 5.6 Cluster]
    D --> E[Reindex to ES 6.x Cluster]
    E --> F[Snapshot ES 6.x State]
    
    F --> G[Restore in ES 7.x Cluster]
    G --> H[Snapshot ES 7.x State]
    
    H --> I[Restore in OS 1.x Cluster]
    I --> J[Snapshot OS 1.x State]
    
    J --> K[Restore in OS 2.x Cluster]
    K --> L[Export .kibana as export.json]
    L --> M[Cleanup All Temporary Resources]
    
    subgraph DashboardMigration[Dashboard Migration Process]
        N[console metadata evaluate] --> O[console dashboards migrate]
        O --> P[console dashboards upload]
        P --> Q[console metadata migrate]
    end
    
    O -->|Orchestration| C
    M --> P
    
    classDef critical fill:#ffebee,stroke:#ff5252,stroke-width:2px;
    class E critical
```

Only the ES 5.x →ES 6.x transition requires reindexing, while subsequent upgrades use snapshot/restore. Refer to [Appendix [8]](#appendix) for more details.

---

## Requirements

To implement the dashboard migration feature effectively, the following requirements must be met:

With the introduction of `console dashboards migrate` and `console dashboards upload` to handle dashboard transformation, preparation, and upload. The `console dashboards migrate` command initiates the entire process of dashboards migration by orchestrating the upgrade functionality. Whereas, the `console dashboards upload` command uploads the NDJSON file to the user's production target OpenSearch cluster.

#### Upgrade Functionality

The upgrade functionality is a critical component of the `console dashboards migrate` command. There is a need for an orchestration strategy here to make this 'upgrade' functionality work on a user's deployment of Migration Assistant. It involves:

- Source Version Detection: Identify the source Elasticsearch version (e.g., ES 5.6).

- Custom Snapshot: The tool should create a custom snapshot of the user's production source cluster that contains all .kibana content, and minimum content from other data indices. This snapshot will be saved in a new S3 Repository of the user's AWS Account.

- Orchestration Strategy:
  - Intermediate Cluster Deployment in the background: Launching intermediate clusters for each major version upgrade after source version has been detected.
  - Snapshots Creation: Create full snapshots of these intermediate clusters.
  - Snapshot Restoration: Restore snapshots in intermediate clusters to ensure compatibility.
  - Only performs reindexing when absolutely required (ES 5→6 transition), uses direct snapshot/restore for subsequent version upgrades
  - Index Alias Update: Update index aliases to reflect new cluster configurations.

- Transformation Pipeline: Transform dashboard components to ensure compatibility with OpenSearch Dashboards.

- Verification: Validate the successful transformation and migration of dashboard components.

- Cleanup: Remove temporary resources and snapshots post-migration to maintain a clean environment.

#### Upload Functionality

- Upload NDJSON: The tool should upload the NDJSON file to the user's production target OpenSearch cluster without manual intervention.

- Include valid opensearch-dashboards Authentication to make a secure upload for a managed service cluster.

Refer to [Appendix [7]](#appendix) for more details on the OpenSearch Dashboards saved objects API.

---

## Appendix

[1] This method involves exporting Kibana objects, migrating the Elasticsearch cluster to OpenSearch, and manually translating configuration files from kibana.yml to opensearch_dashboards.yml. The process is manual, error-prone, and lacks automated validation for different Elasticsearch versions.
Documentation: OpenSearch Migration Guide [https://opensearch.org/docs/2.8/upgrade-to/dashboards-upgrade-to/]

[2] In this approach, administrators export saved objects (dashboards, visualizations, index patterns) via Kibana’s API or UI and then import them into OpenSearch Dashboards. Depending on the source version of Elasticsearch, this method may require manual recreation of index patterns (for versions 5.x) or offer a more complete export without manual intervention (for versions 6.8+).
Documentation: Kibana Export/Import API [https://aws.amazon.com/blogs/big-data/export-and-import-kibana-dashboards-with-amazon-opensearch/]

[3] For Elasticsearch 7.10+ environments, the Dashboard Sanitizer tool processes exported Kibana dashboards in NDJSON format, transforming them into OpenSearch-compatible structures. This tool, however, is limited to static exports and excludes advanced visualizations (e.g., Lens, Maps, Canvas Workpads), making it unsuitable for complex environments.
Documentation: OpenSearch Dashboard Sanitizer Tool [https://github.com/opensearch-project/opensearch-migrations/blob/main/dashboardsSanitizer/UserGuide.md]

[4] Refer to this for more details on the architecture of Migration Assistant.
Documentation: Migration Assistant Architecture Overview [https://opensearch.org/docs/latest/migration-assistant/#architecture-overview]

[5] Visualization Support Across Versions

| Visualization Type | ES 5.x | ES 6.3              | ES 6.8 | ES 7.10.2 | OS 2.x | Category |
|--------------------|--------|---------------------|--------|-----------|--------|----------|
| Area Chart         | ✓      | ✓                   | ✓      | ✓         | ✓      | Basic    |
| Horizontal Bar     | ✓      | ✓                   | ✓      | ✓         | ✓      | Basic    |
| Heat Map           | ✓      | ✓                   | ✓      | ✓         | ✓      | Basic    |
| Line Chart         | ✓      | ✓                   | ✓      | ✓         | ✓      | Basic    |
| Pie Chart          | ✓      | ✓                   | ✓      | ✓         | ✓      | Basic    |
| Vertical Bar       | ✓      | ✓                   | ✓      | ✓         | ✓      | Basic    |
| Gantt Chart        | x      | x                   | x      | ✓         | ✓      | Basic    |
| Metric             | ✓      | ✓                   | ✓      | ✓         | ✓      | Data     |
| Gauge              | ✓      | ✓                   | ✓      | ✓         | ✓      | Data     |
| Data Table         | ✓      | ✓                   | ✓      | ✓         | ✓      | Data     |
| Goal               | ✓      | ✓                   | ✓      | ✓         | ✓      | Data     |
| Coordinate Map     | ✓      | ✓                   | ✓      | ✓         | ✓      | Maps     |
| Region Map         | ✓      | ✓                   | ✓      | ✓         | ✓      | Maps     |
| Maps               | x      | x                   | x      | x         | ✓      | Maps     |
| Timelion           | ✓      | ✓                   | ✓      | ✓         | ✓ named Timeline     | Time Series |
| Visual Builder     | ✓ experimental    | ✓ experimental      | ✓      | x         | ✓ named VizBuilder     | Time Series    |
| TSVB               | x      | x                   | x      | ✓         | ✓      | Time Series    |
| Markdown           | ✓      | ✓                   | ✓      | ✓         | ✓      | Other |
| Tag Cloud          | ✓      | ✓                   | ✓      | ✓         | ✓      | Other |
| Controls           | x      | ✓ experimental      | ✓ experimental    | ✓ experimental       | ✓ experimental      | Other |
| Vega               | x      | ✓ experimental      | ✓ experimental    | ✓         | ✓      | Other |
| PPL                | x      | x                   | x      | x         | ✓      | Other |

[6] Export Functionality Across Versions

| Version | Export Path | Exportable Saved Object Types |
|---------|-------------|--------------|
| OS 2.x  | Dashboards Management > Saved objects > Export | config, index-pattern, visualization, url, query, homepage, dashboard, map, visualization-visbuilder, augment-vis, search, observability-panel, integration-instance, integration-template, observability-visualization, observability-search, observability-notebook |
| ES 7.10 | Stack Management > Saved Objects > Export | index-pattern, config, url, query, dashboard, visualization, search |
| ES 6.8  | Management > Saved Objects > Export | index-pattern, visualization, dashboard, search |
| ES 5.6  | Management > Saved Objects > Export | dashboards, visualizations, searches |

[7] OpenSearch Dashboards Saved Objects API
Documentation: OpenSearch Dashboards Saved Objects API [https://opensearch-project.github.io/OpenSearch-Dashboards/docs/openapi/saved_objects/]

[8] Elasticsearch 5.x to 6.x Reindexing Requirement
Documentation: Elasticsearch 5.x to 6.x Reindexing [https://www.elastic.co/guide/en/kibana/5.6/migrating-6.0-index.html]
