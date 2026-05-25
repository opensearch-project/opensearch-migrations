# OpenSearch Migration Assistant

**The most reliable, cost-effective way to migrate to OpenSearch — zero downtime, full rollback safety.**

[![Latest Release](https://img.shields.io/github/v/release/opensearch-project/opensearch-migrations)](https://github.com/opensearch-project/opensearch-migrations/releases)
[![codecov](https://codecov.io/gh/opensearch-project/opensearch-migrations/graph/badge.svg)](https://codecov.io/gh/opensearch-project/opensearch-migrations)
[![Weekly Releases](https://img.shields.io/badge/releases-weekly-blue)](https://github.com/opensearch-project/opensearch-migrations/releases)
[![Contributors](https://img.shields.io/github/contributors/opensearch-project/opensearch-migrations)](https://github.com/opensearch-project/opensearch-migrations/graphs/contributors)

---

## The Problem

Upgrading Elasticsearch or migrating to OpenSearch traditionally means:

| Manual Approach | What Goes Wrong |
|---|---|
| Upgrade one major version at a time (6→7→8→OS) | Weeks of maintenance windows per hop |
| Blue/green with reindex | Hours of downtime, risk of data drift |
| Snapshot-restore to a new cluster | No way to validate before cutting over |
| Hope your clients work with the new version | Breaking changes discovered in production |

## See It In Action

<p align="center">
  <img src="demo/migration-demo.svg" alt="Migration Assistant Demo — ES 7.10 to OpenSearch 3.6" width="900"/>
</p>

> *ES 7.10 → OpenSearch 3.6: snapshot creation, metadata migration, document backfill, and verification — all orchestrated by the workflow CLI on Kubernetes.*
>
> Run it yourself: `bash demo/run-demo.sh`

## The Solution

Migration Assistant handles the entire journey in **one hop** — from any supported source directly to your target version — while your production cluster stays online:

<p align="center">
  <img src="https://raw.githubusercontent.com/wiki/opensearch-project/opensearch-migrations/diagrams/eks-architecture.svg" alt="Migration Assistant Architecture" width="900"/>
</p>

### How it works

1. **Deploy target cluster** — automated provisioning via AWS CDK or local Docker
2. **Backfill historical data** — snapshot-based, upgrades Lucene format automatically
3. **Capture & replay live traffic** — real requests validated against target before cutover
4. **Compare responses** — automated diff between source and target responses
5. **Switch over** — route traffic to target with instant rollback if anything looks wrong
6. **Redirect client traffic** — point application traffic to the target cluster or enable it directly

---

## Performance

Each Reindex-From-Snapshot worker (2 vCPU, 4 GB) sustains **23.5 MB/s** — that's **~2 TB/day per worker**. Add workers to scale linearly:

| Workers | Throughput | Data Moved per Day |
|---|---|---|
| 1 | 23.5 MB/s | ~2 TB |
| 10 | 235 MB/s | ~20 TB |
| 50 | 1.18 GB/s | ~100 TB |

Workers run on AWS Fargate — scale out on demand, pay only for migration duration, scale back to zero when done.

| Component | Per-Worker Throughput | Docs/Minute |
|---|---|---|
| Reindex-From-Snapshot | 23.5 MB/s | 590,000 |
| Traffic Replay | 67.5 MB/s | 1,694,000 |

[Full benchmark methodology →](#performance-benchmarks)

---

## Supported Migration Paths

<table>
<tr>
  <th rowspan="2">Source</th>
  <th colspan="3">Target</th>
</tr>
<tr>
  <th>OpenSearch 1.x</th>
  <th>OpenSearch 2.x</th>
  <th>OpenSearch 3.x</th>
</tr>
<tr><td>Elasticsearch 1.x–2.x*</td><td>&#10003;</td><td>&#10003;</td><td>&#10003;</td></tr>
<tr><td>Elasticsearch 5.x–7.x</td><td>&#10003;</td><td>&#10003;</td><td>&#10003;</td></tr>
<tr><td>Elasticsearch 8.x</td><td></td><td>&#10003;</td><td>&#10003;</td></tr>
<tr><td>OpenSearch 1.x–2.x</td><td></td><td>&#10003;</td><td>&#10003;</td></tr>
<tr><td>Apache Solr 6.x–9.x*</td><td></td><td></td><td>&#10003;</td></tr>
</table>

\* Backfill only — Capture and Replay is not supported for these source versions.

**Platforms:** Self-managed (cloud or on-premises), Amazon OpenSearch Service, Amazon OpenSearch Serverless

> **No other tool supports post-fork migrations.** Migration Assistant is the only solution that migrates data from Elasticsearch 8.x or Apache Solr to OpenSearch. Standard approaches (snapshot-restore, reindex-from-remote, rolling upgrades) break at the ES 7.10 fork boundary and require upgrading one major version at a time — a process that doesn't scale. For Solr, no other tool reads backup data directly — alternatives require re-indexing from the application layer.

---

## Quick Start

### Local (Docker)

```bash
git clone https://github.com/opensearch-project/opensearch-migrations.git
cd opensearch-migrations
./gradlew -p TrafficCapture/dockerSolution build
```

See the [Development Guide](DEVELOPER_GUIDE.md) for running the full local stack.

### AWS Deployment

Deploy the production-ready solution in your AWS account:

```bash
# Full automated deployment via CDK
```

Follow the [Deployment Guide](https://docs.aws.amazon.com/solutions/migration-assistant-for-amazon-opensearch-service/) for step-by-step instructions.

### AI-Guided Migration

The [Kiro CLI](kiro-cli/README.md) provides an AI agent that walks you through the entire process — from source discovery to provisioning to data migration.

---

## Key Capabilities

| Capability | Description |
|---|---|
| [**Metadata Migration**](MetadataMigration/README.md) | Migrate indices, templates, aliases, and cluster settings |
| [**Reindex-from-Snapshot**](RFS/docs/DESIGN.md) | Backfill historical data with automatic Lucene version upgrade |
| [**Capture & Replay**](docs/TrafficCaptureAndReplayDesign.md) | Validate your target handles real production traffic correctly |
| [**Traffic Routing**](docs/ClientTrafficSwinging.md) | Zero-downtime client switchover with rollback |
| [**Migration Console**](docs/migration-console.md) | CLI that guides you step-by-step |
| [**Response Comparison**](docs/TrafficCaptureAndReplayDesign.md) | Automated diff of source vs. target responses |

**Migration Rollback:** Your source cluster stays synchronized throughout. Monitor the target's behavior under real load, then commit the switch — or revert instantly.

---

## Performance Benchmarks

Tested on 03/10/25 ([PR #1337](https://github.com/opensearch-project/opensearch-migrations/pull/1337)). Average doc size: 2.39 KB uncompressed (source doc + index command), 1.54 KB primary shard size per doc on OS 2.17 with defaults.

| Service | vCPU | Memory (GB) | Type Mapping Sanitization | Peak Docs/min | Primary Shard Rate (MB/s) | Source Data Rate (MB/s) |
|---|---|---|---|---|---|---|
| Reindex-From-Snapshot | 2 | 4 | Disabled | 590,000 | 15.1 | 23.5 |
| Reindex-From-Snapshot | 2 | 4 | Enabled | 546,000 | 14.0 | 21.7 |
| Traffic Replay | 8 | 48 | Disabled | 1,694,000 | 43.5 | 67.5 |
| Traffic Replay | 8 | 48 | Enabled | 1,645,000 | 42.2 | 65.5 |

Throughput scales with additional Fargate workers (horizontal) or larger instances (vertical). All tests CPU-bound; results may vary with client compression.

---

## Documentation

- **[User Guide](https://docs.opensearch.org/latest/migration-assistant/)** — Full walkthrough for production migrations
- **[Development Guide](DEVELOPER_GUIDE.md)** — Build, test, and deploy locally

---

## Community

This project ships weekly releases and is actively maintained by 50+ contributors.

**Found a bug or need a feature?**

- [Search existing issues](https://github.com/opensearch-project/opensearch-migrations/issues) — upvote and comment if it's already reported
- [Open a new issue](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose) — we triage within the week

**Want to contribute?** See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## CI/CD

Weekly releases published via GitHub Actions + [Jenkins](https://migrations.ci.opensearch.org/). Artifacts include attestation for supply-chain verification.

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

Apache-2.0 — see [LICENSE](LICENSE).
