# Migration Assistant Reference

## Overview

Migration Assistant (MA) is the execution engine for OpenSearch migrations.
It is deployed on Kubernetes (EKS or generic K8s) and provides:

- Migration Console — CLI control plane for all migration operations
- Reindex-From-Snapshot (RFS) — Snapshot-based document backfill
- Capture Proxy — Live traffic capture to Kafka
- Replayer — Replay captured traffic against target
- Metadata Migration — Templates, settings, aliases

## Migration Console CLI

All cluster API calls go through the Migration Console:

```bash
CONSOLE_POD=$(kubectl -n ma get pod -l app=migration-console -o jsonpath='{.items[0].metadata.name}')
kubectl -n ma exec "${CONSOLE_POD}" -- bash -lc "<command>"
```

### Cluster Commands
```bash
console clusters connection-check          # Validate source/target connectivity
console clusters curl --path '/' --cluster source   # Query source cluster
console clusters curl --path '/' --cluster target   # Query target cluster
console clusters cat-indices               # List indices on both clusters
console clusters clear-indices --cluster target     # Clear target indices (DESTRUCTIVE)
```

### Workflow Commands
```bash
workflow configure sample                  # Show sample config
workflow configure sample --load           # Load sample as starting point
workflow configure edit --stdin             # Load config from stdin
workflow configure view                    # View current config
workflow submit                            # Submit workflow (returns immediately)
workflow status                            # Check workflow status
workflow status --all                      # Include completed workflows
workflow output                            # View step logs
workflow output -f                         # Stream logs in real-time
workflow approve                           # Approve manual gates
workflow stop                              # Stop running workflow
```

### Configuration Schema
```bash
# View full config schema from running console pod
kubectl exec migration-console-0 -n ma -- cat /root/.workflowUser.schema.json

# Or download versioned schema from GitHub
curl -sLO https://github.com/opensearch-project/opensearch-migrations/releases/download/<version>/workflowMigration.schema.json
```

## Deployment Methods

### EKS Deployment (Recommended)
1. Deploy CloudFormation stack for IAM, S3, security groups
2. Run `aws-bootstrap.sh` to install Helm chart
3. Verify pods: `kubectl -n ma get pods`

### Docker Deployment (Development/Testing)
1. Run docker-compose from `TrafficCapture/dockerSolution/`
2. Verify containers are running
3. Access Migration Console container directly

## Key Configuration Settings

- `max_snapshot_rate_mb_per_node: 1000` — Maximize snapshot throughput (default ~40 MB/s is too slow)
- RFS worker count — Calculate from shard count and target vCPU (see sizing.md)
- Index allowlist — Migrate specific indices or all non-system indices
