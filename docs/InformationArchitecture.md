# Migration Assistant Information Architecture

## Purpose

This document outlines the design principles for how the Migration Assistant (MA) project will manage communication between its different elements with the introduction of a frontend and backend system. It establishes how these new systems will integrate with existing components while aligning with the team’s strategic shift toward API-driven, web-first deliverables.

## Context

The Migration Assistant currently supports two categories of tools:

1. **Heavy tools** – Java-based processes such as **Reindex-from-Snapshot (RFS)**, **Capture Proxy**, and **Replayer**. These typically run in dedicated containers, require lifecycle management, and are orchestrated using systems like Argo.
2. **Light tools** – Smaller Python scripts or Java applications that run directly on the **Migration Console instance**.

With the introduction of the **frontend** and its reliance on the **API layer** provided by the Migration Console, we now have a unified system of control with structured verbs and endpoints. This transition shifts how we view ownership of state and orchestration of processes.

## Strategic Shift

* **Old model**: Customers were expected to script logic into the Migration Console via CLI commands. These commands returned human-readable responses.
* **New model**: Customers needing automation will use the **Migration Assistant API directly**. This establishes a **clear, structured interface boundary** for partner teams, removing ambiguity in command patterns.

Implications:

* Reduced priority for console-first features.
* All designs should be treated as **web-first**, ensuring customer-facing deliverables and structured APIs.
* The console remains available for ad-hoc usage, but feature investment will focus on the frontend and API.

## System of Record

Migrations must have a **single source of truth** for dynamic configuration and execution state:

* The **API layer (backed by a database, e.g., PostgreSQL)** will be the system of record for all migration configuration, actions, and results.
* **Direct database access will not be permitted**. All data will only be accessible through APIs.
* Tools that produce transient state (e.g., metadata migrate output) will register that state with the API, which persists it in the DB.
* Heavy tools that interact with clusters (source/target) will still do so directly, but their lifecycle, triggers, and state reporting will always flow through the API.

## Orchestration & Argo Workflows

* Argo workflows to provide orchestration for heavy tools.
* Workflow execution data will remain in Argo, but the Migration Console API will **query and expose workflow states** as structured API responses.
* Migration Assistant will not duplicate workflow execution data but will provide standardized access and metadata references.
* Principle: **Workflow data is stored in Argo, surfaced through API.**

```mermaid
flowchart LR
%% Groups
subgraph FE[Frontend]
UI[Web UI]
end

subgraph API[Migration Console]
APIv[API Endpoints]
Obs[Observability]
   subgraph DB[Operational DB]
   CFG[(Config Tables)]
   STATE[(State & Results)]
   end
end

subgraph Argo[Argo Workflows]
WQ[Workflows]
ES[Execution State]
end

subgraph Heavy[Heavy Tools]
RFS[Reindex from Snapshot]
CAP[Capture Proxy]
REP[Replayer]
end

subgraph Light[Light Tools]
L1[Metadata Migrate]
L2[Validators]
end

subgraph Clusters[Source/Target Clusters]
SRC[(Source Cluster)]
TGT[(Target Cluster)]
end

UI -->|HTTP| APIv
APIv --> CFG
APIv --> STATE
APIv --> Obs

APIv --> WQ
WQ --> RFS
WQ --> CAP
WQ --> REP
WQ --> ES
APIv --> ES

APIv --> L1
APIv --> L2

RFS --> SRC
RFS --> TGT
CAP --> SRC
REP --> TGT
L1 --> SRC
L1 --> TGT
L2 --> SRC
L2 --> TGT


APIv --> UI
```

## Design Principles

1. **API-first** – All new control plane features must be defined as structured APIs. CLI/console extensions are secondary.
2. **Single Source of Truth** – Migration state is always stored in one system (DB for config/results, Argo for workflow execution).
3. **Web-first customer experience** – Design for frontend consumption, then adapt for CLI if necessary.
4. **Encapsulation** – Tools continue direct cluster interactions but must always report state and results through the API.
5. **Observability & Traceability** – APIs should provide consistent tracking, logging, and error reporting across workflows.
6. **Extensibility** – APIs should allow integration by partner teams without requiring console-level scripting.

## Example Flows

* **Metadata Migration**:

  * Runs as a light tool.
  * Triggered via API call.
  * Results persisted in API-managed DB.
  * Accessible via frontend through standardized API.

```mermaid
sequenceDiagram
  participant FE as Frontend
  participant API as MA API
  participant DB as DB (Config/State)
  participant LT as Metadata Migrate
  participant SRC as Source Cluster
  participant TGT as Target Cluster

  FE->>API: POST /sessions/{sessionId}/metadata/start {params}
  API->>LT: Invoke task
  LT->>SRC: Read metadata
  LT->>TGT: Update target
  LT -->>API: Return execution results
  API->>DB: Insert results
  FE->>API: GET /sessions/{sessionId}/metadata/latest_status
  API->>DB: Read state/results
  API-->>FE: {status, progress, artifacts}
```

* **Reindex-from-Snapshot**:

  * Runs as a heavy tool, orchestrated via Argo.
  * Triggered via API.
  * Workflow execution data stored in Argo.
  * API provides normalized status endpoint for frontend.

```mermaid
sequenceDiagram
  participant FE as Frontend
  participant API as MA API
  participant DB as DB
  participant Argo as Argo
  participant RFS as RFS (Container)
  participant SRC as Source
  participant TGT as Target

  FE->>API: POST /sessions/{sessionName}/backfill/start {snapshotRef, plan}
  API->>Argo: Submit workflow
  Argo-->>API: Workflow ID
  API->>DB: Insert action + Workflow ID
  Argo->>RFS: Create workers
  RFS->>SRC: Read snapshot
  RFS->>TGT: Write indexes/docs
  loop Polling
  FE->>API: GET /sessions/{sessionName}/backfill/status
  API->>DB: Lookup Workflow ID
  API->>Argo: Check workflow status
  API->>TGT: Enrich status (cat indices...)
  API-->>FE: {phase, eta, issues}
  end
```