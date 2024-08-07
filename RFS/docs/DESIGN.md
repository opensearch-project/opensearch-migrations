# RFS High Level Design

**LAST UPDATED: June 2024**

## Table of Contents
- [RFS High Level Design](#rfs-high-level-design)
    - [Terminology](#terminology)
    - [Background - General](#background---general)
    - [Background - Snapshots](#background---snapshots)
    - [Ultra-High Level Design](#ultra-high-level-design)
    - [Key RFS Worker concepts](#key-rfs-worker-concepts)
    - [How the RFS Worker works](#how-the-rfs-worker-works)
    - [Appendix: Assumptions](#appendix-assumptions)
    - [Appendix: Centralized or Decentralized Coordination?](#appendix-centralized-or-decentralized-coordination)
    - [Appendix: CMS Schema](#appendix-cms-schema)
    - [Appendix: RFS Worker State Machine](#appendix-rfs-worker-state-machine)

## Terminology
* **Lucene Document**: A single data record, as understood by Lucene
* **Lucene Index**: A collection of Lucene Documents
* **Elasticsearch**: A software stack that can be used to search large data sets (multiple petabytes) by combining many Lucene instances into a single distributed system
* **OpenSearch**: An open source fork of Elasticsearch primarily maintained by AWS
* **Elasticsearch Document**: A single data record, as understood by Elasticsearch
* **Elasticsearch Index**: A collection of Elasticsearch Documents all conforming to a shared specification; split across potentially many Elasticsearch Shards
* **Elasticsearch Shard (Shard)**: A subdivision of an Elasticsearch Index that corresponds to a unique Lucene Index and stores it’s portion of the Elasticsearch Index’s Elasticsearch Documents in that Lucene Index as Lucene Documents; provides an Elasticsearch-level abstraction around that Lucene Index.  The Shard may contain multiple copies of the Lucene Index (replicas) spread across multiple hosts.
* **Elasticsearch Cluster (Cluster)**: A collection of nodes, of various types, that store and provide access to some number of Elasticsearch Indexes
* **Elasticsearch Template (Template)**: A metadata setting on an Elasticsearch Cluster used to pre-populate, route, change, or otherwise enrich the Elasticsearch Documents stored in the Elasticsearch Cluster
* **Snapshot**: A copy of the Elasticsearch Indexes and Elasticsearch Templates in an Elasticsearch Cluster that can be stored and then restored to stand up a new Elasticsearch Cluster with the data/metadata of the original
* **Indexing/Ingestion**: The process of taking the raw Elasticsearch Document and transforming it into one or more Lucene Documents for storage in a Lucene Index within a single Shard of an Elastisearch Cluster

## Background - General

Elasticsearch and OpenSearch are distributed software systems that provide the capability to search across large data sets (multiple PBs).  Users need a way to easily move their data and metadata from one Elasticsearch/OpenSearch Cluster to another.  A major complicating factor is that Lucene only supports backwards compatibility for one major version; Lucene and Elasticsearch/OpenSearch major versions are linked for this reason.  There are a few ways users currently perform this data movement.

One approach is to take a snapshot of the source cluster, then restore it on a new target cluster.  This operation happens at the filesystem level and over the Elasticsearch/OpenSearch back channel, skipping the overhead of the HTTP layer of the distributed system.  Snapshot/restore works if the target cluster is the same major version (MV) or one higher than the source (MV + 1).  However, before the data can be moved to a target cluster of MV + 2, all the data in the cluster must be re-indexed at MV + 1 to convert it into the correct Lucene format.  Another approach is to use the bulk re-reindexing API on the source cluster to send all its Elasticsearch Documents to the target cluster.  This happens at the Elasticsearch Index level.  The target cluster converts the Elasticsearch Documents into Lucene Documents compatible with its Lucene major version.  The faster this process happens, the more load the source cluster experiences.  Additionally, the process is sensitive to individual source cluster nodes failing, and it carries the overhead of working via the distributed systems on both the source and target clusters.

Reindex-from-Snapshot (RFS) is a new, alternative approach proposed [in this RFC](https://github.com/opensearch-project/OpenSearch/issues/12667).  The premise is to take a snapshot of the source cluster, split it into its component Elasticsearch Shards, and have a fleet of workers each responsible for extracting the Elasticsearch Documents from a single Shard and re-indexing them on the target cluster.  This removes the strain on the source cluster while also bypassing the MV + 1 upgrade limit. While not enforced, there is a recommend best practice to limit Shards to 20-50 GB depending on use-case.  This means we can some confidence that our unit of work (the Shard) is self-limiting in size.  Because every Shard is a separate Lucene Index, the RFS design can fans out at the Shard-level.  The work of migrating each Shard is completely independent of the other Shards, except for available ingestion capacity on the target cluster.  For a 1 PB source cluster, assuming Shards averaging 50 GB, this means a full fan-out could leverage up to 20,000 workers in parallel (50 GB x 20,000 => 1 PB).

The ultimate goal of RFS is to enable the movement of the data in a large (multiple petabytes) source cluster to a new target cluster with a better user experience than either of the existing solutions - assuming the user can’t just use snapshot/restore.  Users whose target cluster is the same major version as the source, or just want to upgrade a single major version but have no intention of upgrading beyond that, should just use snapshot/restore.  The source cluster may have thousands of Elasticsearch Indices and Shards.  Achieving this ultimate goal there means distributing the work of reading the snapshot and re-indexing on the target cluster across many (potentially hundreds or thousands) of workers.

## Background - Snapshots

Elasticsearch Snapshots are a directory tree containing both data and metadata.  Each Elasticsearch Index has its own sub-directory, and each Elasticsearch Shard has its own sub-directory under the directory of its parent Elasticsearch Index.  The raw data for a given Elasticsearch Shard is store in its corresponding Shard sub-directory as a collection of Lucene files, which Elasticsearch obfuscates.  Metadata files exist in the snapshot to provide details about the snapshot as a whole, the source cluster’s global metadata and settings, each index in the snapshot, and each shard in the snapshot.

Below is an example for the structure of an Elasticsearch 7.10 snapshot, along with a breakdown of its contents

```
/filesystem/path/repo/snapshots/
├── index-0 <-------------------------------------------- [1]
├── index.latest
├── indices
│   ├── DG4Ys006RDGOkr3_8lfU7Q <------------------------- [2]
│   │   ├── 0 <------------------------------------------ [3]
│   │   │   ├── __iU-NaYifSrGoeo_12o_WaQ <--------------- [4]
│   │   │   ├── __mqHOLQUtToG23W5r2ZWaKA <--------------- [4]
│   │   │   ├── index-gvxJ-ifiRbGfhuZxmVj9Hg 
│   │   │   └── snap-eBHv508cS4aRon3VuqIzWg.dat <-------- [5]
│   │   └── meta-tDcs8Y0BelM_jrnfY7OE.dat <-------------- [6]
│   └── _iayRgRXQaaRNvtfVfRdvg
│       ├── 0
│       │   ├── __DNRvbH6tSxekhRUifs35CA
│       │   ├── __NRek2UuKTKSBOGczcwftng
│       │   ├── index-VvqHYPQaRcuz0T_vy_bMyw
│       │   └── snap-eBHv508cS4aRon3VuqIzWg.dat
│       └── meta-tTcs8Y0BelM_jrnfY7OE.dat
├── meta-eBHv508cS4aRon3VuqIzWg.dat <-------------------- [7]
└── snap-eBHv508cS4aRon3VuqIzWg.dat <-------------------- [8]
```

1. **Repository Metadata File**: JSON encoded; contains a mapping between the snapshots within the repo and the Elasticsearch Indices/Shards stored within it
2. **Index Directory**: Contains all the data/metadata for a specific Elasticsearch Index
3. **Shard Directory**: Contains all the data/metadata for a specific Shard of an Elasticsearch Index
4. **Lucene Files**: Lucene Index files, lightly-obfuscated by the snapshotting process; large files from the source filesystem are split in multiple parts
5. **Shard Metadata File**: SMILE encoded; contains details about all the Lucene files in the shard and a mapping between their in-Snapshot representation and their original representation on the source machine they were pulled from (e.g. original file name, etc)
6. **Index Metadata File**: SMILE encoded; contains things like the index aliases, settings, mappings, number of shards, etc
7. **Global Metadata File**: SMILE encoded; contains things like the legacy, index, and component templates
8. **Snapshot Metadata File**: SMILE encoded; contains things like whether the snapshot succeeded, the number of shards, how many shards succeeded, the ES/OS version, the indices in the snapshot, etc

## Ultra-High Level Design
The responsibility of performing an RFS operation is split into two groups of actors - the RFS Workers, and RFS Scaler (see Figure 1, below).

![RFS System Design](./RFS_Worker_HL_System.svg)

**Figure 1:** The Reindex-from-Snapshot high level system design 

The first group of actors are the RFS Workers, which this document focuses on.  RFS Workers perform the work of migrating the data and metadata from a source cluster to a target cluster.  They will coordinate amongst themselves in a decentralized manner using the target cluster as the source-of-truth for the state of the overall operation (see: Appendix: Centralized or Decentralized Coordination?).  Each RFS Worker is oblivious to the existence of any other RFS Worker working on the same operation, except as expressed in changes to overall operation’s metadata stored on the target cluster.  Each RFS Worker is solely concerned with answering the question, “given the operation metadata in the source-of-truth, what should I do right now?”  Any given RFS Worker may perform every step in the overall process, some of them, or none of them.  Any given RFS Worker should be able to die at any point in its work, and have a new RFS Worker resume that work gracefully.  The steps in an RFS operation are:

1. Take a snapshot of the source cluster and wait for its completion
2. Migrate the Elasticsearch Legacy Templates, Component Templates, and Index Templates
3. Migrate the Elasticsearch Index settings and configuration
4. Migrate the documents by retrieving each Elasticsearch Shard, unpacking it into a Lucene Index locally, and re-indexing its contents against the target cluster

The second group of actors are the RFS Scalers, which will receive a more detailed design in the future.  It is expected that RFS Scalers will not perform any steps of the RFS operation themselves; they will just manage the fleet of RFS Workers.  This includes regulating how many of them are running at a given time and reaping unhealthy RFS Workers.  The RFS Scalers will not assign work or help coordinate the RFS Workers.

## Key RFS Worker concepts

### Coordinating Metadata Store (CMS)

The Coordinating Metadata Store (CMS) is the source of truth for the status of the overall Reindex-from-Snapshot operation.  For the first iteration, the target Elasticsearch/Opensearch cluster is used for this purpose (see A1 in [Appendix: Assumptions](#appendix-assumptions)), but it could just as easily be a PostgreSQL instance, Dynamo DB, etc.  The schema for this metadata can be found in [Appendix: CMS Schema](#appendix-cms-schema).

RFS Workers query the CMS to infer what work they should next attempt, and update the CMS with any progress they have made.  RFS Workers interact with the CMS without making assumptions about how many other RFS Workers are doing the same thing.

Important CMS features in use:

* Atomic Creates: Used by the RFS Workers to ensure only one of them can successfully create a given record no matter how many make the attempt; the winner then assumes they can perform some associated work
* Optimistic Locking: Used by the RFS Workers to ensure only one of them can successfully update a given record no matter how many make the attempt; the winner then assumes they can perform some associated work
* Effective Search: Used by RFS Workers to find available work items registered in the CMS

### Work leases

An RFS Worker “acquires” a work item by either winning an atomic creation or an optimistic update on the CMS.  When it does so, it sets a maximum duration for it to complete work on the item as a part of the create/update operation. Ideally, it will use the CMS’s clock to do this.  The RFS Worker is assumed to have a lease on that work item until that duration expires.  If the work is not completed in that time, the RFS Worker will relinquish the work item and it or another RFS Worker will try again later.

As a specific example, an RFS Worker queries the CMS to find an Elasticsearch Shard to migrate to the target cluster.  The CMS returns a record corresponding to a specific Elasticsearch Shard’s progress that either has not been started or has an expired work lease, and the RFS Worker performs an optimistic update of its timestamp field, setting it (hypothetically) for 5 hours from the current time (according to the CMS’s clock).

RFS Workers regularly polls the CMS to see if their current work item’s lease has expired (according to the CMS’s clock); if they find it has expired, they kill themselves and allow an outside system to spin up a replacement RFS Worker.  Similarly, the RFS Scaler will check for expired work items and ensure that any RFS Workers associated with them have been reaped.

The process of finding the optimal initial work lease duration will be data driven based on actual usage statistics.  The CMS will contain the duration of each work item after a RFS operation finishes, which can be used to iteratively improve the initial “guess”.  Each RFS Worker is responsible for setting the duration for work items it attempts to acquire a lease for.

### One work lease at a time

An RFS Worker retains no more than a single work lease at a time.  If work item associated with that lease has multiple steps or components, the work lease covers the completion of all of them as a combined unit.  As a specific example, the RFS Worker that wins the lease to migrate the Elasticsearch Templates is responsible for migrating every Template of each type.  

Alternatively, if the work involved does not need coordination between RFS Workers to ensure only one is processing it at a given time, then a work lease is not used and the “one lease at a time” tenet does not apply.  As a specific example, multiple RFS Workers in the process of migrating Elasticsearch Indices will each receive a collection to migrate and could have the same Index in their collection.  They will each attempt to migrate the Index, and this is as expected.

### Work lease backoff

When an RFS Worker acquires a work item, it increments the number of attempts that have been made to finish it.  The RFS Worker increases its requested work lease duration based on the number of attempts.  If the number of attempts passes a specified threshold, the RFS Worker instead marks the work item as problematic so it won’t be picked up again.

The algorithm for backoff based on number of attempts and the maximum number of attempts to allow will both be data driven and expected to improve with experience.

### Don’t touch existing templates or indices

While performing its work, if an RFS Worker is tasked to create an Elasticsearch Template or Elasticsearch Index on the target cluster, but finds it already exists there, it will skip that creation.  The reasoning for this policy is as follows.

First, creation of Elasticsearch Template and Elasticsearch Index is atomic (see [Appendix: Assumptions](#appendix-assumptions)).  Second, it prevents re-work in the case that an RFS Worker is picking up the partial work of another RFS Worker that died.  Third, it provides space for users to customize/configure the target cluster differently than the source cluster.

### Overwrite documents by ID

While performing its work, if an RFS Worker is tasked to create an Elasticsearch Document on the target cluster, it will do so by using the same ID as on the source cluster, clobbering any existing Elasticsearch Document on the target cluster with that ID.  The reasoning for this policy is as follows.

The unit of work for an RFS Worker migrating Elasticsearch Documents is an Elasticsearch Shard, not an Elasticsearch Document.  If an RFS Worker dies unexpectedly while moving the Elasticsearch Documents in an Elasticsearch Shard, it would be hard to reliably track exactly which had been successfully moved such that another RFS Worker could resume at the correct position.  We will instead simplify the design by just starting the Elasticsearch Shard over from the beginning and overwriting any partial work.

## How the RFS Worker works

In this section, we describe in a high-level, narrative manner how the RFS Worker operates. Detailed state machine diagrams that outline the behavior more explicitly are below as well (see [Appendix: RFS Worker State Machine](#appendix-rfs-worker-state-machine)).  The state machine diagram is intended to be the source of truth, so favor its representation over the narrative description below if conflicts exist.

### RFS Worker threads

The RFS Worker’s running process has at least two running threads:

* Main Thread - performs the work of moving the data/metadata from the source cluster to the target cluster; starts the Metrics and Healthcheck Threads
* Healthcheck Thread - on a regular, scheduled basis will check the process’ shared state to determine which work item the RFS Worker currently has a lease on (if any) and confirm the lease is still valid; if the lease has expired, it immediately kills the process and all threads (see [Work leases](#work-leases)).

There are two pieces of state shared by the threads of the process, which the Main Thread is solely responsible for writing to.  The Healthcheck Thread treats this shared state as read-only.

* Container Phase - which phase the RFS Worker is operating in
* Work Item - which work item the RFS Worker currently has a lease on, if any

### Phase 0 - Container Start

On startup, the Main Thread initializes the process shared state and launches the Healthcheck Thread.

### Phase 1 - Snapshot Creation

The Main Thread queries the CMS to see if a Snapshot of the source cluster has already been created.  If so, we proceed to the next phase.

If not, we update the process shared state to indicate we’re creating a snapshot.  Based on the status of the CMS work entry for snapshot creation and the state of the source cluster, we will either attempt to create the Snapshot ourselves or wait for the in-progress Snapshot to complete.  When the Snapshot completes, we update the shared process state to indicate we've finished the phase, mark the CMS entry completed, and proceed to the next phase.  If the Snapshot fails, we update the CMS to indicate the snapshot failed, emit an event reporting the unrecoverable nature of the situation (from the RFS Worker’s perspective), and terminate the RFS Worker.

When creating the Snapshot on the source cluster, we use a consistent and deterministic name that all RFS Workers will be able to construct based upon the information available to them within their runtime environment.  This means that every RFS Worker will know which Snapshot to poll the status of on the source cluster regardless of whether it was the one to kick off its creation.

### Phase 2 - Cluster Metadata Migration

The Main Thread queries the CMS to see if a Cluster Metadata of the source cluster has already been migrated.  If so, we proceed to the next phase.

If not, we update the process shared state to indicate we’re migrating that metadata.  We enter a loop, where we check the status of the Metadata Migration entry in the CMS and then proceed based on what we find.  If it looks like no one is currently working on migrating the metadata, we attempt to acquire the work lease for it (see [Work leases](#work-leases), [Work lease backoff](#work-lease-backoff)).  The RFS Worker that wins the lease downloads the Cluster Metadata file from the Snapshot, then attempts to migrate (in order) the legacy, composite, and index Templates present in it to the target cluster - as long as the given Template matches the user whitelist and isn’t already present on the target cluster.  Once every Template has been processed, the RFS Worker updates the CMS’s status for the Cluster Metadata Migration to be complete.  RFS Workers that fail to win the lease or find that another RFS Worker has the lease wait a short, random time before checking again.  RFS Workers exit the loop when they discover the Cluster Metadata Migration has been completed or failed.

The work lease for this phase is on the entire Cluster Metadata migration (all Templates, of all types).  If something in that process fails enough times, we update the CMS to indicate the phase has failed, emit an event reporting the unrecoverable nature of the situation (from the RFS Worker’s perspective), and terminate the RFS Worker.

### Phase 3 - Index Migration

The Main Thread queries the CMS to see if the Elasticsearch Indices on the source cluster have already been migrated.  If so, we proceed to the next phase.

If not, we update the process shared state to indicate we’re migrating those Indices.  We then enter a loop to progress through two sub-phases.  At the beginning of each iteration, we check the CMS for the state of the Index Migration.

#### 3.1 - Setup

The goal of this sub-phase is to create records (Index Work Entries) in the CMS to track the migration of each Elasticsearch Index.  We exit the sub-phase when we find this has been completed, or that it has failed unrecoverably.

Each RFS Worker attempts to acquire a lease on the work to create these individual, Index-specific records (see: Work leases, Work lease backoff).  The RFS Worker that wins the lease downloads the Snapshot Metadata file from the Snapshot, then creates an Index Work Entry for each Index in the CMS (if it does not already exist).  The Index Work Entry contains Index’s name, migrations status, and the number of shards that Index has.  Once every Index Work Entry exists in the the CMS, it updates the CMS to indicate this sub-phase is completed.  RFS Workers that fail to win the lease or find that another RFS Worker has the lease wait a short, random time before returning the beginning of the loop.

The work lease for this phase is on the entire setup process (ensuring a record exists for every Index in the Snapshot).  If something in that process fails enough times, we update the CMS to indicate the phase has failed, emit an event reporting the unrecoverable nature of the situation (from the RFS Worker’s perspective), and terminate the RFS Worker.

#### 3.2 - Migrate the indices

The goal of this sub-phase is to migrate all the Elasticsearch Indices from the source cluster to the target cluster.  This means creating a corresponding Index on the target cluster for each we find on the source cluster.  We exit the sub-phase when we find every Index has been processed (successfully, or unsuccessfully).

Each RFS Worker asks the CMS for a random set of Index Work Entry that have not been processed yet.  For each Index Work Entry returned, we retrieve from the Snapshot the corresponding Index Metadata file, use the information in that file to attempt to create the Index on the target cluster if it’s not already there, and attempt to update the Index Work Entry to be completed if it hasn’t already been updated.  If we fail to create the Index on the target cluster, we attempt to increment the Index Work Entry’s number of attempts.  If we find that the number of attempts is over a specified threshold, we attempt to mark the Index Work Entry’s status to be failed and emit a notification event if we succeed.

If no Entries are returned, we know that this sub-phase is complete and attempt to update the CMS’s status for the Index Migration phase to be complete.

It’s important to point out that we don’t attempt to ensure that each Index is processed only once; we instead rely on our concept of [Don’t touch existing templates or indices](#dont-touch-existing-templates-or-indices) and optimistic locking on the Index Work Entry in the CMS to ensure that at least one RFS Workers will process each Index to completion without anything disastrous happening.  The work required for each Index is small, so there is little cost to having multiple RFS Workers attempt the same Index occasionally.

### Phase 4 - Document Migration

The Main Thread queries the CMS to see if the Elasticsearch Documents on the source cluster have already been migrated.  If so, we proceed to the next phase.

If not, we update the process shared state to indicate we’re migrating those Documents.  We then enter a loop to progress through two sub-phases.  At the beginning of each iteration, we check the CMS for the state of the Document Migration.

#### 4.1 - Setup

Similar to [3.1 - Setup](#31---setup) in the Index Migration phase, the goal of this sub-phase is to create records (Shard Work Entries) in the CMS to track the migration of each Shard.  We exit the sub-phase when we find this has been completed, or that it has failed unrecoverably.

The process is the same as 3.1 - Setup, with the exception that the RFS Worker that wins the lease to do the setup work will use the Index Work Entries to determine the number and details of the Shard Work Entries to be created.  For each Index Work Entry that was successfully migrated to target cluster, we check the number of shards set in the Index Work Entry and create a Shard Work Entry for each.  The Shard Work Entry will contain the Index name, shard number, and migration status.

#### 4.2 - Migrate the documents

The goal of this sub-phase is to migrate all the Elasticsearch Documents from the source cluster to the target cluster.  This means recreating all the Documents in the source cluster onto the target cluster.  We exit the sub-phase when we find every Shard has been processed (successfully, or unsuccessfully).

Each RFS Worker asks the CMS for a single, random Shard Work Entry that either has not been started or has an expired work lease (see [Work leases](#work-leases), [Work lease backoff](#work-lease-backoff)).  When then attempt to acquire the lease on that Entry.  If we fail, we ask the CMS for another Entry.  If we succeed, we check the number of times this Entry has been attempted.  If over the threshold, we mark it as failed and emit a notification event if not already done so then skip it.  If not over the threshold, we download the Shard’s files from the Snapshot, unpack them, read them as a Lucene Index, extract the original Elasticsearch Documents from them, and HTTP PUT those Documents against the target cluster.  When performing the PUT, we use the Document’s original ID from the source cluster and overwrite any Document on the target cluster’s corresponding Elasticsearch Index that has the same ID  (see [Overwrite documents by ID](#overwrite-documents-by-id)).  Once all Documents in the Shard have been processed, we mark the Shard Work Entry as completed and ask for another entry.

If no Entry is returned, we know that this sub-phase is complete and attempt to update the CMS’s status for the Documents Migration phase to be complete.

The work lease for this sub-phase is on the Shard (ensuring every Elasticsearch Document in that Shard has been processed).  We log/emit metrics to indicate how many Documents are successfully and unsuccessfully migrated but we don’t consider the Shard Work Entry to have failed if some (or even all) of the Documents in it are unsuccessfully migrated.  We only retry the Shard Work Entry when an RFS Worker fails to process every Document within the lease window.  These retries are relatively time consuming, but safe because we overwrite any partial work performed by a previous RFS Worker. 


## Appendix: Assumptions

We start with the following high-level assumptions about the structure of the solution.  Changes to these assumptions would likely have a substantial impact on the design.  

* (A1) - The RFS Workers cannot assume access to a data store other than the migration’s target cluster as a state-store for coordinating their work.
* (A2) - The RFS Worker will perform all the work required to complete a historical migration.
* (A3) - The RFS Scaler will only need to scale one type of Docker composition, which may be composed of multiple Containers, one of which is the RFS Worker

We have the following, additional assumptions about the process of performing a Reindex-from-Snapshot operation:

* (A4) - The creation of Elasticsearch Templates is atomic
* (A5) - The creation of Elasticsearch Indices is atomic
* (A6) - Re-doing portions of the overall RFS operation is fine, as long as every portion is completed at least once
* (A7) - Elasticsearch Templates must be migrated in the order: first Legacy, then Composite, then Index
* (A8) - All Elasticsearch Templates must be migrated before any Elasticsearch Indices can be migrated
* (A9) - Elasticsearch Indices can be migrated in parallel without ordering concerns
* (A10) - Elasticsearch Shards can only be migrated after their Elasticsearch Index has been migrated
* (A11) - Elasticsearch Shards can be migrated in parallel without ordering concerns

## Appendix: Centralized or Decentralized Coordination?

At a high level, the primary concerns for a Reindex-from-Snapshot operation are scalability and fault tolerance.  That is, the most important concern is that the system can handle even very large source clusters with a high degree of confidence that all work items will be processed at least once in a reasonable timeframe despite failures amongst the workers involved.  This naturally lends itself to a decentralized coordination approach over a centralized approach (whose primary benefits would be more consistent and efficient work allocation).  Additionally, our starting assumptions for the design fit a decentralized approach (see: Appendix: Assumptions). (A1) indicates we have a natural point of coordination amongst workers - the target cluster.  (A3) indicates that we need a homogenous fleet.  (A2) implies that every RFS Worker will already have a substantial set of responsibilities, and adding a work assignment layer to that would further complicate them.  (A6) allows us to avoid complex tracking/coordination by accepting that some work items might, occaisionally, be processed multiple times.

## Appendix: CMS Schema

Below is the schema for the coordinating metadata records to be stored in the CMS:

```
SNAPSHOT STATUS RECORD
ID: snapshot_status
FIELDS:
    * name (string): The snapshot name
    * status (string): NOT_STARTED, IN_PROGRESS, COMPLETED, FAILED

CLUSTER METADATA MIGRATION STATUS RECORD
ID: metadata_status
FIELDS:
    * status (string): IN_PROGRESS, COMPLETED, FAILED
    * leaseExpiry (timestamp): When the current work lease expires
    * numAttempts (integer): Times the task has been attempted

INDEX MIGRATION STATUS RECORD
ID: index_status
FIELDS:
    * status (string): SETUP, IN_PROGRESS, COMPLETED, FAILED
    * leaseExpiry (timestamp): When the current work lease expires
    * numAttempts (integer): Times the task has been attempted

INDEX WORK ENTRY RECORD
ID: <name of the index to be migrated>
FIELDS:
    * name (string): The index name
    * status (string): NOT_STARTED, COMPLETED, FAILED
    * numAttempts (integer): Times the task has been attempted
    * numShards (integer): Number of shards in the index

DOCUMENTS MIGRATION STATUS RECORD
ID: docs_status
FIELDS:
    * status (string): SETUP, IN_PROGRESS, COMPLETED, FAILED
    * leaseExpiry (timestamp): When the current work lease expires
    * numAttempts (integer): Times the task has been attempted

SHARD WORK ENTRY RECORD
ID: <name of the index to be migrated>_<shard number>
FIELDS:
    * indexName (string): The index name
    * shardId (integer): The shard number
    * status (string): NOT_STARTED, COMPLETED, FAILED
    * leaseExpiry (timestamp): When the current work lease expires
    * numAttempts (integer): Times the task has been attempted
```

## Appendix: RFS Worker State Machine

Here is a state machine diagram for the RFS Worker:

![RFS Worker state machine](./RFS_Worker_State_Machine.svg)