# Workflow CLI Reference

This file is a command reference and safety checklist. The default end-to-end workflow is defined in:

- `.kiro/steering/opensearch-migration-assistant-eks.sop.md`

## ⛔ NEVER Modify Without User Confirmation
**STOP and ASK the user before running ANY of these operations:**

- `aws opensearch update-domain-config` (ANY parameter)
- `aws opensearch update-domain-config --advanced-security-options` (master user, FGAC)
- `aws opensearch update-domain-config --access-policies` (domain access policy)
- `aws opensearch update-domain-config --ebs-options` (storage)
- `aws opensearch update-domain-config --cluster-config` (instance type/count)
- `aws opensearch update-domain-config --vpc-options` (networking)
- `aws ec2 authorize-security-group-*` / `revoke-security-group-*`
- `aws iam` role/policy modifications
- Any S3 bucket policy changes

**Instead:** Tell the user what needs to be changed and provide the exact command for them to run or approve.

Example response:
```
The target cluster has FGAC enabled. The migration role needs master user access.

**Action required:** Run this command to add the migration role as master user:
aws opensearch update-domain-config --domain-name <domain> --region <region> \
  --advanced-security-options '{"MasterUserOptions":{"MasterUserARN":"<role-arn>"}}'

Should I proceed with this change? (yes/no)
```

## Run Commands in Migration Console
```bash
# For workflow/console commands (need venv)
kubectl exec migration-console-0 -n ma -- bash -c "source /.venv/bin/activate && <command>"

# For simple file reads (no venv needed)
kubectl exec migration-console-0 -n ma -- cat <file>
```

## Configuration Commands
```bash
workflow configure sample        # Show sample config
workflow configure sample --load # Load sample as starting point
workflow configure edit          # Edit config in editor
workflow configure edit --stdin  # Load config from stdin (overwrites existing)
workflow configure view          # View current config
workflow configure clear         # Reset config

# View full config schema (JSON Schema)
kubectl exec migration-console-0 -n ma -- cat /root/.workflowUser.schema.json
```

## Load Config Programmatically
```bash
# Write config to pod, then load via stdin
cat << 'EOF' | kubectl exec -i migration-console-0 -n ma -- bash -c "cat > /tmp/config.yaml"
{ "sourceClusters": { ... }, "targetClusters": { ... }, "migrationConfigs": [...] }
EOF
kubectl exec migration-console-0 -n ma -- bash -c "source /.venv/bin/activate && cat /tmp/config.yaml | workflow configure edit --stdin"
```

## Execution Commands
```bash
workflow submit                  # Submit workflow (returns immediately)
workflow submit --wait           # Submit and wait for completion
workflow submit --wait --timeout 300  # With custom timeout (seconds)
workflow status                  # List running workflows
workflow status --all            # Include completed workflows
workflow status <workflow-name>  # Specific workflow status
workflow output <workflow-name>  # View logs (interactive step selection)
workflow output -f               # Stream logs in real-time
workflow output --tail-lines 100 # Last N lines
workflow approve                 # Approve manual gates (interactive)
workflow approve --acknowledge   # Approve without confirmation
workflow stop                    # Stop running workflow
```

### Before Approving Any Step
**ALWAYS check the output before approving:**
```bash
workflow output <workflow-name>
```
Review the step output for errors, warnings, or unexpected results before proceeding.

**Non-interactive alternative (for automation):**
```bash
# Get logs from workflow pods directly
kubectl logs -n ma -l workflows.argoproj.io/workflow=migration-workflow --tail=100

# Filter for specific step output (e.g., metadata evaluation)
kubectl logs -n ma -l workflows.argoproj.io/workflow=migration-workflow --tail=200 | grep -A 50 "Starting Metadata"
```

### Monitoring Long-Running Migrations

**Token-efficient monitoring strategy:**

1. **For metadata evaluate/migrate steps** - Get full output (important to review):
   ```bash
   kubectl logs -n ma -l workflows.argoproj.io/workflow=migration-workflow --tail=100 | grep -A 50 "Starting Metadata"
   ```

2. **For RFS backfill** - Use minimal tail (progress only):
   ```bash
   kubectl logs -n ma -l app.kubernetes.io/name=reindex-from-snapshot --tail=5 | grep -o "Doc Number [0-9]*"
   ```

3. **Status checks with exponential backoff:**
   - First check: 15 seconds
   - Then: 30s → 60s → 120s → 300s (cap at 5 minutes)
   ```bash
   workflow status 2>&1 | grep -E "Phase:|WAITING|Succeeded|Failed"
   ```

4. **After ~5 minutes of active backfill**, ask user:
   - "Migration is progressing. Should I continue monitoring, or would you prefer to check back later?"

5. If user wants async: provide command to check status later
   ```bash
   kubectl exec migration-console-0 -n ma -- bash -c "source /.venv/bin/activate && workflow status"
   ```

## Pre-Migration Estimation (REQUIRED)

**ALWAYS calculate and present estimates BEFORE starting migration:**

### 1. Get Source Data Size
```bash
console clusters curl source_cluster /_cluster/stats | jq '.indices.store.size_in_bytes / 1073741824' # GB with replicas
console clusters curl source_cluster /_cat/nodes?v  # Check node count for snapshot parallelism
```

### 2. Check Snapshot Speed Settings (for fresh repos)
```bash
# Check cluster-level recovery speed limit
console clusters curl source_cluster '/_cluster/settings?include_defaults=true' | jq '.defaults.indices.recovery.max_bytes_per_sec'
# Default is often 40mb - consider increasing for large datasets
```

### 3. Calculate Migration Time Estimate
```
Snapshot creation: data_size_gb / (40 MB/s × source_data_nodes) 
Transfer rate: xlarge_equivalents × 15.1 MB/s
Backfill time: primary_data_size / transfer_rate
Total: snapshot_time + metadata (~2 min) + backfill_time
```

### 4. Present to User and Confirm
```
**Migration Estimate:**
- Source data: X GB (Y GB primary)
- Target: N × instance_type = Z xlarge equivalents
- Estimated snapshot time: ~X minutes
- Estimated backfill time: ~Y minutes  
- Total estimated time: ~Z minutes

Does this timeline work, or should we scale up the target cluster first?
```

## Pre-Migration Checklist

**ALWAYS show target indices and ask user before starting migration:**
```bash
console clusters cat-indices
```

If target has user indices from previous runs, prompt user:
- **Clear target indices**: `echo y | console clusters clear-indices --cluster target`
- Note: `clear-indices` only removes indices, NOT templates

To clear templates manually:
```bash
console clusters curl target_cluster '/_index_template/<template>' -X DELETE
```

### ⚠️ Cleaning Up Snapshot Repos
**NEVER delete S3 files directly without first deleting snapshots via the cluster API.**

Correct order:
1. Delete snapshots from cluster first:
```bash
console clusters curl source_cluster '/_snapshot/<repo>/<snapshot>' -X DELETE
# Or delete all snapshots:
console clusters curl source_cluster '/_snapshot/<repo>/*' -X DELETE
```
2. Then optionally delete repo registration:
```bash
console clusters curl source_cluster '/_snapshot/<repo>' -X DELETE
```
3. Only THEN can you safely clear S3 bucket if needed

**If you delete S3 files directly, the cluster's snapshot metadata becomes corrupted and the repo is broken.**

## Cluster Check
```bash
# DON'T curl clusters directly - use console commands instead (handles auth properly)
console clusters connection-check              # Test connectivity
console -v clusters connection-check           # Verbose - shows full request/response
console clusters cat-indices                   # List indices on both clusters
console clusters curl source_cluster <path>    # Curl source (e.g., /, _cat/indices)
console clusters curl target_cluster <path>    # Curl target

# Pipe JSON output to jq for parsing - avoids multiple retries
console clusters curl source_cluster /_cluster/stats | jq '.indices.store.size_in_bytes'
console clusters curl target_cluster /_cat/indices?format=json | jq '.[].index'
```

## Load Test Data on Source
```bash
# Run OpenSearch Benchmark workloads (geonames, http_logs, nested, nyc_taxis)
console clusters run-test-benchmarks

# NOTE: Only works with basic auth clusters, NOT SigV4/AWS managed
# Uses --test-mode for quick simulation with small data

# For basic auth clusters, can also run script directly:
/root/runTestBenchmarks.sh --endpoint https://<host>:9200 --auth-user admin --auth-pass <pass>
```

## Pre-Migration: Check Target Indices
Before migrating, check if target has existing indices:
```bash
console clusters cat-indices
```
If target has user indices, prompt user:
- **Clear target indices**: `echo y | console clusters clear-indices --cluster target` (DESTRUCTIVE)
- **Continue without clearing**: May cause conflicts if same index names exist

```bash
# Clear all user indices on target (system indices like .plugins-* remain)
echo y | console clusters clear-indices --cluster target
```

## Debugging Connection Issues

### 1. Run verbose connection check first
```bash
console -v clusters connection-check
```

### 2. For AWS Managed domains - check access policy
```bash
# Get domain access policy
aws opensearch describe-domain --domain-name <domain> --region <region> \
  --query 'DomainStatus.AccessPolicies' --output text | jq .

# Migration role must be allowed (for SigV4 auth):
# - MIGRATIONS_EKS_CLUSTER_NAME + exported role ARNs (preferred)
```

### 3. List migration roles
```bash
aws iam list-roles --query "Roles[?contains(RoleName,'migration')].{Name:RoleName,Arn:Arn}" --output table
```

### 4. For VPC domains - check network path
```bash
# Check if domain is in VPC
aws opensearch describe-domain --domain-name <domain> --region <region> \
  --query 'DomainStatus.VPCOptions' --output json

# If VPC, use Reachability Analyzer in AWS Console:
# VPC > Reachability Analyzer > Create path
# Source: EKS cluster security group
# Destination: OpenSearch domain security group
```

### 5. For VPC domains - check network path
```bash
# Check if domain is in VPC
aws opensearch describe-domain --domain-name <domain> --region <region> \
  --query 'DomainStatus.VPCOptions' --output json

# If VPC, use Reachability Analyzer in AWS Console:
# VPC > Reachability Analyzer > Create path
# Source: EKS cluster security group
# Destination: OpenSearch domain security group
```

## Find OpenSearch Service Domains (All Regions)
```bash
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do (s=$(aws opensearch list-domain-names --region "$r" --query 'DomainNames[].DomainName' --output text 2>/dev/null) && [ -n "$s" ] && printf "=== %s ===\n%s\n" "$r" "$s") & done; wait
```

## Authentication Requirements

### Source Cluster
- **AWS Managed (OpenSearch Service)**: Use SigV4 auth. Must allow the exported migration snapshot role ARN access to the domain (obtain from CloudFormation exports / bootstrap output; do not guess role names).
- **Self-managed**: Use basic auth. Provide endpoint URL and create K8s secret with credentials.

### Target Cluster  
- Can use either SigV4 (AWS managed) or basic auth (self-managed)

### Creating K8s Secret for Basic Auth
```bash
kubectl create secret generic <secret-name> -n ma \
  --from-literal=username=<user> \
  --from-literal=password=<pass>
```

## Sample Config - AWS SigV4 (Cross-Region)
```json
{
  "sourceClusters": {
    "source": {
      "endpoint": "https://search-<domain>.<region>.es.amazonaws.com",
      "version": "ES 7.10",
      "authConfig": {
        "sigv4": { "region": "us-east-1", "service": "es" }
      }
    }
  },
  "targetClusters": {
    "target": {
      "endpoint": "https://search-<domain>.<region>.es.amazonaws.com",
      "version": "OS 3.3",
      "authConfig": {
        "sigv4": { "region": "us-east-2", "service": "es" }
      }
    }
  },
  "migrationConfigs": [
    { "fromSource": "source", "toTarget": "target" }
  ]
}
```

## Workflow Config Schema

### Structure Overview
```
skipApprovals                    # Global: skip all approval steps
sourceClusters.<name>            # Source cluster with snapshotRepo config
targetClusters.<name>            # Target cluster config
migrationConfigs[]               # Array of migration definitions
  └─ snapshotExtractAndLoadConfigs[]  # Snapshot-based migration
       ├─ createSnapshotConfig        # Create new snapshots (needs s3RoleArn)
       ├─ snapshotConfig              # Snapshot naming
       └─ migrations[]                # Migration steps
            ├─ metadataMigrationConfig   # Index templates, mappings
            └─ documentBackfillConfig    # Reindex from snapshot
```

### Key Points
- **snapshotRepo** goes inside source cluster (not top-level)
- **s3RepoPathUri** must be `s3://bucket-name` only - NO path suffix (schema validation fails)
- **Empty `{}` enables a feature with defaults** - without it, feature is disabled
- **replayerConfig** is for live traffic replay - NOT supported for AWS managed domains
- Version format: `"ES 7.10"` or `"OS 2.11"` (major.minor only)

### podReplicas Sizing (RFS Workers)
Calculate based on target cluster data node "xlarge equivalents":
```
podReplicas = (instance_count) × (xlarge_multiplier)

xlarge_multiplier:
  large    = 0.5
  xlarge   = 1
  2xlarge  = 2
  4xlarge  = 4
  8xlarge  = 8
  16xlarge = 16
```
Examples:
- 4 × large.search → 4 × 0.5 = **2 podReplicas**
- 3 × 16xlarge.search → 3 × 16 = **48 podReplicas**
- 1 × r8g.large.search → 1 × 0.5 = **1 podReplicas** (minimum)

Check target instance type:
```bash
aws opensearch describe-domain --domain-name <domain> --region <region> \
  --query 'DomainStatus.ClusterConfig.{InstanceType:InstanceType,InstanceCount:InstanceCount}'
```

### Migration Time Estimation
Estimate transfer time based on xlarge equivalents:
```
transfer_rate = xlarge_equivalents × 15.1 MB/s
estimated_time = total_data_size / transfer_rate
```

Get total data size (including replicas):
```bash
console clusters curl source_cluster /_cluster/stats | jq '.indices.store.size_in_bytes / 1073741824'
```

**⚠️ This is a rough estimate only.** Real-world transfer can be multiple times faster or slower depending on:
- Network conditions
- Index complexity and mappings
- Document sizes
- Cluster load

**If estimated time is an order of magnitude off from requirements, consider scaling up the target cluster before migration.**

Example calculation:
- Source data: 100 GB (with replicas)
- Target: 3 × 2xlarge = 6 xlarge equivalents
- Transfer rate: 6 × 15.1 = 90.6 MB/s
- Estimated time: 100,000 MB ÷ 90.6 MB/s ≈ 1,100 sec ≈ **~18 minutes**

### Get S3/Snapshot Config from EKS ConfigMaps
```bash
kubectl get configmap migrations-default-s3-config -n ma -o yaml
# Returns: BUCKET_NAME, AWS_REGION, SNAPSHOT_ROLE_ARN
```

## Sample Config - Full Snapshot Migration (AWS SigV4)
```json
{
  "skipApprovals": false,
  "sourceClusters": {
    "source": {
      "endpoint": "https://search-<domain>.<region>.es.amazonaws.com",
      "version": "ES 7.10",
      "authConfig": {"sigv4": {"region": "us-east-1", "service": "es"}},
      "snapshotRepo": {
        "awsRegion": "us-east-1",
        "s3RepoPathUri": "s3://<bucket-name>",
        "repoName": "migration_assistant_repo"
      }
    }
  },
  "targetClusters": {
    "target": {
      "endpoint": "https://search-<domain>.<region>.es.amazonaws.com",
      "version": "OS 3.3",
      "authConfig": {"sigv4": {"region": "us-east-2", "service": "es"}}
    }
  },
  "migrationConfigs": [
    {
      "fromSource": "source",
      "toTarget": "target",
      "skipApprovals": false,
      "snapshotExtractAndLoadConfigs": [
        {
          "name": "default-snapshot-migration",
          "createSnapshotConfig": {
            "s3RoleArn": "<snapshot role ARN from CloudFormation exports / bootstrap output>"
          },
          "snapshotConfig": {
            "snapshotNameConfig": {"snapshotNamePrefix": "migration-snapshot"}
          },
          "migrations": [
            {
              "name": "full-migration",
              "metadataMigrationConfig": {},
              "documentBackfillConfig": {"podReplicas": 1}
            }
          ]
        }
      ]
    }
  ]
}
```

## Sample Config - Basic Auth (Self-Managed)
```json
{
  "sourceClusters": {
    "source": {
      "endpoint": "https://source:9200",
      "version": "ES 7.10",
      "allowInsecure": false,
      "authConfig": {"basic": {"secretName": "source-creds"}},
      "snapshotRepo": {
        "awsRegion": "us-east-1",
        "s3RepoPathUri": "s3://bucket-name"
      }
    }
  },
  "targetClusters": {
    "target": {
      "endpoint": "https://target:9200",
      "version": "OS 2.11",
      "authConfig": {"basic": {"secretName": "target-creds"}}
    }
  },
  "migrationConfigs": [
    {
      "fromSource": "source",
      "toTarget": "target",
      "snapshotExtractAndLoadConfigs": [
        {
          "createSnapshotConfig": {},
          "snapshotConfig": {"snapshotNameConfig": {"snapshotNamePrefix": "migration"}},
          "migrations": [
            {
              "metadataMigrationConfig": {"indexAllowlist": ["*"]},
              "documentBackfillConfig": {"indexAllowlist": ["*"], "podReplicas": 2}
            }
          ]
        }
      ]
    }
  ]
}
```

## Troubleshooting Failed Migrations

### ALWAYS deep dive into failed mappings before retrying
When metadata migration fails, investigate the root cause before proceeding:

1. **Check the error message** - workflow status shows which index failed
2. **Compare source index mapping vs template**:
```bash
# Check source index mapping
console clusters curl source_cluster '/<index>/_mapping' | jq '.["<index>"].mappings.properties.<field>'

# Check if template exists and its mapping
console clusters curl source_cluster '/_index_template/<template>' | jq '.index_templates[0].index_template'

# Check target template (if migrated)
console clusters curl target_cluster '/_index_template/<template>' | jq '.index_templates[0].index_template.template.mappings.properties.<field>'
```

3. **Common causes**:
   - **Template/index mismatch**: Index was created with different mappings than its template defines
   - **Field type conflict**: Same field has different types (e.g., `keyword` vs `object`)
   - **Dynamic mapping drift**: Index evolved differently than template expected

4. **Resolution options**:
   - Delete conflicting template on target before index migration
   - Use `indexAllowlist` to skip problematic indices
   - Use transformer config to fix mappings during migration
   - Fix source data inconsistency before migration

### Example: Template vs Index Conflict
```
ERROR - logs-000001 failed: can't merge a non object mapping [service] with an object mapping
```
- Template defines: `"service": {"type": "keyword"}`
- Index has: `"service": {"properties": {"name": {...}, "version": {...}}}`
- Fix: Delete template on target, or exclude index, or fix source

## Post-Migration Verification

**Immediately after workflow succeeds, verify data:**
```bash
console clusters cat-indices --refresh
```

Compare doc counts between source and target - they should match exactly.

**Expected target state:**
- Indices may show `yellow` health if target has fewer nodes than replica count - this is normal
- System indices (`.plugins-*`, `.opendistro-*`) are target-specific, don't compare
- `.migrations_working_state_*` index is temporary migration state, can be ignored

## Lessons Learned

### Template/Index Conflicts
- **Index templates are migrated BEFORE indices**
- If source has inconsistent data (template defines field as `keyword` but index has it as `object`), migration will fail
- **Solution**: Delete conflicting template from source AND target before migration, or fix source data

### S3 Snapshot Repository Management
- **NEVER** delete S3 files directly - always use cluster API to delete snapshots first
- Deleting S3 files corrupts the repo metadata on the cluster
- If repo is corrupted: delete repo registration from cluster, clear S3, then workflow will recreate

### Workflow Retries
- `checkRfsCompletion` may fail multiple times while RFS is still running - this is normal retry behavior
- Workflow will eventually succeed once all shards are migrated
- Don't panic at failed checkRfsCompletion steps - check if RFS pod is still running

### Actual vs Estimated Performance
Real-world example (1M docs, 4.2 GB, 1 × m6g.large target):
- **Estimated**: 7.55 MB/s → ~9 min
- **Actual**: 5.8 MB/s → ~12 min
- Small instances may underperform estimates due to I/O constraints

### Common Pitfalls
1. **Forgetting to clear target** before retry → duplicate/conflicting data
2. **Not checking evaluate output** before approving → missing errors
3. **Deleting S3 directly** → corrupted snapshot repo
4. **Template conflicts** → metadata migration fails on indices
5. **Not verifying doc counts** after migration → silent data loss

## Full Documentation
```bash
cat opensearch-migrations.wiki/Workflow-CLI-Overview.md
cat opensearch-migrations.wiki/Workflow-CLI-Getting-Started.md
cat opensearch-migrations.wiki/Backfill-Workflow.md
```
