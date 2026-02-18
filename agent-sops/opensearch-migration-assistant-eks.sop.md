# OpenSearch Migration Assistant on EKS — Full-Stack Migration

## Overview

This SOP guides an agent through a complete migration from Elasticsearch or OpenSearch on Kubernetes to Amazon OpenSearch Service. It covers the full lifecycle: source discovery, target provisioning, Migration Assistant deployment, data migration, and optional parallel application stack deployment. It is runtime-discovery-first and uses a configurable interaction level so the user can be hands-on or hands-off.

## Parameters

- **hands_on_level** (required): How interactive the agent must be.
  - `guided`: Ask for confirmation before each phase and before any destructive or irreversible action.
  - `semi_auto`: Proceed automatically within a phase, but ask for confirmation at phase boundaries and before destructive/irreversible actions.
  - `auto`: Proceed automatically end-to-end, but still stop for destructive/irreversible actions unless explicitly permitted via `allow_destructive`.
- **migration_scope** (optional, default: ask user): Controls what the agent does.
  - `data_only`: Migrate data only (source must already exist, target must already exist or be provisioned).
  - `full_stack`: Full lifecycle — discover source, provision target, migrate data, deploy parallel application stack.
  - If omitted, the agent MUST ask the user: *"Do you want me to just migrate the data, or do you want the full stack — including provisioning the target domain and deploying a parallel copy of your application?"*
- **kube_context** (optional, default: current kubectl context): The kubectl context to use.
- **namespace** (optional, default: "ma"): Kubernetes namespace where MA is/will be deployed.
- **source_selection** (optional, default: "discover"): How to find the source cluster.
  - `discover`: Discover EKS clusters and Elasticsearch/OpenSearch deployments at runtime.
  - `aws_discover`: Discover Amazon OpenSearch Service / Serverless domains at runtime using AWS CLI.
  - `custom`: User provides endpoints and auth details.
- **source_cluster** (optional): Source cluster connection details, required when `source_selection=custom`.
- **target_provisioning** (optional, default: ask user): How to handle the target cluster.
  - `provision_new`: Provision a new Amazon OpenSearch Service domain based on source cluster sizing.
  - `use_existing`: Use an existing target cluster (discover or user-provided).
  - If omitted and `migration_scope=full_stack`, the agent MUST ask: *"Should I provision a new OpenSearch domain based on your source cluster, or do you have an existing target?"*
- **target_cluster** (optional): Target cluster connection details, required when `target_provisioning=use_existing` and `source_selection=custom`.
- **ma_deployment** (optional, default: "auto"): How to handle Migration Assistant deployment.
  - `auto`: Check if MA is already deployed; if not, deploy it via Helm.
  - `use_existing`: Assume MA is already deployed and healthy.
  - `deploy_new`: Always deploy a fresh MA instance via Helm.
- **app_deployment** (optional, default: ask user if `migration_scope=full_stack`): Whether to deploy a parallel application stack.
  - `parallel`: Deploy a parallel copy of the application pointing at the new target.
  - `skip`: Do not deploy any application stack.
  - If omitted and `migration_scope=full_stack`, the agent MUST ask: *"I can deploy a parallel copy of your application pointing at the new OpenSearch cluster. Want me to do that? If so, point me to your application manifests or describe your deployment."*
- **app_source** (optional): How to find the application to clone.
  - `discover`: Inspect K8s deployments in the source cluster's namespace for pods connecting to the source cluster.
  - `manifests`: User provides a path to K8s manifests, Helm chart, or deployment config.
  - `describe`: User describes the application and the agent builds manifests.
- **allow_insecure** (optional, default: false): If true, allow TLS without verification for cluster connections.
- **index_allowlist** (optional): List of index names to migrate (exact names). If omitted, migrate all non-system indices.
- **allow_destructive** (optional, default: false): If true, the agent may run destructive operations without additional confirmation when `hands_on_level=auto`.
- **aws_region** (optional): AWS region. Recommended to narrow discovery scope.
- **artifacts_dir** (optional, default: ".agents/migration/{run_id}"): Directory for run artifacts.
- **run_id** (optional): Identifier for this run. If omitted, generate `YYYY-MM-DD-HHMM-opensearch-migration`.

**Constraints for parameter acquisition:**
- You MUST ask for all required parameters upfront in a single prompt rather than one at a time because this reduces interruptions during execution.
- If the user's initial prompt implies scope (e.g., "migrate everything and spin up a parallel app"), you MUST infer parameters from context rather than asking redundant questions.
- You MUST normalize `hands_on_level` to one of: `guided`, `semi_auto`, `auto`.
- You MUST normalize boolean parameters (`allow_insecure`, `allow_destructive`) to true/false.
- You MUST confirm successful acquisition and normalization of parameters before proceeding.

## Mode Behavior

Apply these patterns throughout all steps based on the selected `hands_on_level`:

**guided**
- You MUST present a short plan before each phase.
- You MUST ask for confirmation before executing phase entry actions.
- You MUST ask for confirmation before any destructive or irreversible operation.

**semi_auto**
- You MUST ask for confirmation before entering each phase.
- You MUST ask for confirmation before any destructive or irreversible operation.
- You SHOULD proceed without further prompts inside a phase unless you encounter ambiguous inputs or unexpected risk.

**auto**
- You MUST proceed end-to-end without intermediate confirmations.
- You MUST still stop for destructive or irreversible operations unless `allow_destructive=true` because these operations can cause irreversible data loss.
- You MUST document all decisions, assumptions, and command outputs in the run log.

## Steps

### 0. Initialize Run Workspace

Create an artifacts directory and establish a consistent log/plan format for the run.

**Constraints:**
- You MUST create `{artifacts_dir}` and write these files:
  - `{artifacts_dir}/plan.md`
  - `{artifacts_dir}/discovery.md`
  - `{artifacts_dir}/run-log.md`
  - `{artifacts_dir}/summary.md` (may be empty initially)
- You MUST record the normalized parameters in `{artifacts_dir}/plan.md`.
- You MUST NOT overwrite an existing `{artifacts_dir}` that has contents because it could destroy previous run artifacts.

### 1. Source Environment Discovery

Discover the source Elasticsearch or OpenSearch cluster and its environment.

**Constraints:**
- You MUST support multiple discovery methods based on `source_selection`:
  - `discover`: List EKS clusters, inspect namespaces for ES/OS deployments, find service endpoints.
  - `aws_discover`: List Amazon OpenSearch Service domains across regions.
  - `custom`: Use user-provided endpoint and auth.
- You MUST NOT guess or hardcode cluster names, endpoints, or namespaces. All values MUST come from runtime discovery or explicit user input.

**EKS-based discovery procedure (`source_selection=discover`):**

```bash
# List EKS clusters in the region
aws eks list-clusters --region "${aws_region}" --output text

# Connect to the cluster
aws eks update-kubeconfig --region "${aws_region}" --name "<cluster-name>"

# Find Elasticsearch/OpenSearch deployments across namespaces
kubectl get pods --all-namespaces -l app=elasticsearch -o wide 2>/dev/null
kubectl get pods --all-namespaces -l app.kubernetes.io/name=elasticsearch -o wide 2>/dev/null
kubectl get pods --all-namespaces -l app=opensearch -o wide 2>/dev/null

# Find services exposing port 9200
kubectl get svc --all-namespaces --field-selector spec.type!=ExternalName \
  -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name} ports={.spec.ports[*].port}{"\n"}{end}' | grep 9200
```

- If multiple clusters or deployments are found, you MUST present the options and ask the user to choose (even in `auto` mode) because picking the wrong source is catastrophic.
- You MUST record the discovered cluster name, namespace, and service endpoint in `{artifacts_dir}/discovery.md`.

### 2. Source Cluster Inspection

Inspect the source cluster to understand its configuration, data, and compatibility requirements.

**Constraints:**
- You MUST connect to the source cluster and capture:
  - Cluster health (`GET _cluster/health`)
  - Node configuration (`GET _cat/nodes?v&h=name,ip,heap.percent,ram.percent,cpu,load_1m,node.role,master`)
  - Index list with sizes (`GET _cat/indices?v&h=index,health,status,pri,rep,docs.count,store.size&s=store.size:desc`)
  - Total data size (primary shards)
  - Shard count and sizing
  - Per-node vCPU count and memory (from node stats or K8s resource limits)
- You MUST capture the source cluster's network configuration for target provisioning:

```bash
# Get the EKS cluster's VPC, subnets, and security groups
EKS_CLUSTER="<cluster-name>"
VPC_ID=$(aws eks describe-cluster --name "${EKS_CLUSTER}" --region "${aws_region}" \
  --query 'cluster.resourcesVpcConfig.vpcId' --output text)
SUBNET_IDS=$(aws eks describe-cluster --name "${EKS_CLUSTER}" --region "${aws_region}" \
  --query 'cluster.resourcesVpcConfig.subnetIds' --output text)
SG_IDS=$(aws eks describe-cluster --name "${EKS_CLUSTER}" --region "${aws_region}" \
  --query 'cluster.resourcesVpcConfig.securityGroupIds' --output text)
CLUSTER_SG=$(aws eks describe-cluster --name "${EKS_CLUSTER}" --region "${aws_region}" \
  --query 'cluster.resourcesVpcConfig.clusterSecurityGroupId' --output text)
echo "VPC: $VPC_ID  Subnets: $SUBNET_IDS  SGs: $SG_IDS  ClusterSG: $CLUSTER_SG"
```

  - Record VPC ID, subnet IDs, and security group IDs in `{artifacts_dir}/discovery.md` — these are used as defaults for target provisioning in Step 4.
- You MUST inspect field types for compatibility issues:
  - `GET <index>/_mapping` for each index (or representative indices if many)
  - Flag known incompatible types: `sparse_vector`, `dense_vector` (may need `knn_vector`), ELSER-specific types, any ML-specific field types
  - Record compatibility issues in `{artifacts_dir}/discovery.md`
- You MUST present a summary of what was found:
  - Cluster version, node count, total data size
  - Number of indices, total doc count
  - Any compatibility issues detected
- In `guided` mode, you MUST ask the user to confirm the source looks correct before proceeding.

**Recommended connectivity pattern for EKS-based source:**

```bash
# Port-forward to the source cluster service
kubectl port-forward -n <namespace> svc/<es-service> 9200:9200 &

# Query the cluster
curl -s http://localhost:9200/_cluster/health?pretty
curl -s http://localhost:9200/_cat/indices?v
```

### 3. Application Discovery (if `migration_scope=full_stack`)

Discover the application stack connected to the source cluster so it can be cloned later.

**Skip condition:** Skip this step if `migration_scope=data_only` or `app_deployment=skip`.

**Constraints:**
- You MUST ask the user how to find their application if `app_source` is not set:
  *"I need to understand your application to deploy a parallel copy later. I can: (1) inspect your K8s cluster for deployments connecting to the source, (2) read your manifests/Helm charts if you point me to them, or (3) you can describe your setup and I'll build the config. Which works best?"*
- You MUST NOT assume any specific application architecture. The application could be:
  - A single pod with an env var pointing to ES
  - A multi-service stack (API, ingestion, dashboards)
  - A Helm-deployed application
  - Something entirely custom

**Discovery procedure (`app_source=discover`):**

```bash
# Find deployments in the same namespace or cluster that reference the source endpoint
kubectl get deployments --all-namespaces -o json | \
  jq -r '.items[] | select(.spec.template.spec.containers[].env[]?.value // "" | test("elasticsearch|<source-svc-name>|9200")) | "\(.metadata.namespace)/\(.metadata.name)"'

# For each discovered deployment, capture:
# - Full deployment spec
# - Service definitions
# - ConfigMaps/Secrets referenced
# - Environment variables that reference the source cluster
```

- You MUST record for each discovered application component:
  - Deployment name and namespace
  - Container image(s)
  - Environment variables or config that reference the source cluster endpoint
  - Associated services (ClusterIP, LoadBalancer, NodePort)
  - Replica count and resource limits
- You MUST present the discovered application topology to the user and confirm it's correct.
- You MUST save the original manifests to `{artifacts_dir}/app-original/` for reference.

**Manifest-based discovery (`app_source=manifests`):**
- You MUST read the provided manifests and identify all references to the source cluster endpoint.
- You MUST ask the user to confirm which environment variables or config values need to be updated for the target.

### 4. Target Provisioning

Provision or connect to the target OpenSearch cluster.

**Skip condition:** Skip provisioning if `target_provisioning=use_existing` and target is already configured.

**Constraints for `target_provisioning=provision_new`:**

The target domain MUST mirror the source cluster's shape as closely as possible. Use the source cluster inspection from Step 2 as the baseline.

**4a. Network — Default to source cluster's VPC/subnets/SGs:**
- You MUST default to the VPC, subnets, and security groups discovered from the source EKS cluster in Step 2.
- In `guided` or `semi_auto` mode, present the defaults and ask: *"I'll deploy the target in the same VPC ({vpc_id}), subnets ({subnet_ids}), and security groups ({sg_ids}) as your source EKS cluster. Change anything?"*
- In `auto` mode, use the source network config without asking.
- For multi-AZ deployments, select subnets across at least 2 AZs (3 preferred). Filter the source subnets to private subnets only:

```bash
# Filter to private subnets (no direct internet gateway route)
for SUBNET in ${SUBNET_IDS}; do
  RT=$(aws ec2 describe-route-tables --region "${aws_region}" \
    --filters "Name=association.subnet-id,Values=${SUBNET}" \
    --query 'RouteTables[0].Routes[?GatewayId!=`null` && starts_with(GatewayId,`igw-`)].GatewayId' --output text)
  [ -z "$RT" ] && PRIVATE_SUBNETS="${PRIVATE_SUBNETS} ${SUBNET}"
done
```

**4b. Data nodes — Match source count and size with modern instance types:**
- You MUST match the source data node count exactly.
- You MUST map source node vCPU and memory to the most modern generation of OpenSearch instance type with equivalent or better specs.
- Instance type modernization logic:
  1. Determine source per-node vCPU and memory (from Step 2 node stats or K8s resource limits).
  2. Find the latest generation OpenSearch instance type that matches or exceeds those specs.
  3. Prefer the latest generation available (e.g., `r7g` over `r6g` over `r5`, `m7g` over `m6g` over `m5`).
  4. Use `aws opensearch list-instance-type-details --engine-version OpenSearch_<version> --region "${aws_region}"` to verify the chosen type is available.
- Common mappings (use as guidance, always verify availability):
  | Source profile | Recommended OS instance type |
  |---|---|
  | ~2 vCPU, ~16 GB | `r7g.large.search` |
  | ~4 vCPU, ~32 GB | `r7g.xlarge.search` |
  | ~8 vCPU, ~64 GB | `r7g.2xlarge.search` |
  | ~16 vCPU, ~128 GB | `r7g.4xlarge.search` |
  | ~32 vCPU, ~256 GB | `r7g.8xlarge.search` |
  | ~4 vCPU, ~16 GB (balanced) | `m7g.xlarge.search` |
  | ~8 vCPU, ~32 GB (balanced) | `m7g.2xlarge.search` |

**4c. Dedicated master nodes — Always add for best practice:**
- You MUST add dedicated master nodes even if the source cluster does not have them.
- Master node sizing:
  | Data node count | Master instance type | Master count |
  |---|---|---|
  | 1–10 | `m7g.large.search` | 3 |
  | 11–30 | `m7g.xlarge.search` | 3 |
  | 31–75 | `c7g.2xlarge.search` | 3 |
  | 76+ | `c7g.4xlarge.search` | 3 |
- In `guided` mode, inform the user: *"I'm adding 3 dedicated master nodes ({master_type}) as best practice for cluster stability, even though your source doesn't have them. This is recommended for production workloads."*

**4d. Storage — Prefer NVME over EBS when available:**
- You MUST prefer instance types with local NVME storage (instance store) over EBS when:
  1. An NVME-backed instance type exists with similar vCPU/memory profile to the chosen EBS type.
  2. The local storage capacity is sufficient for the data (primary + replica + headroom).
  3. The cost is similar or lower than the EBS equivalent.
- NVME-backed instance families to consider:
  | Family | Profile | Local storage |
  |---|---|---|
  | `i3` | Storage-optimized | NVMe SSD (large) |
  | `im4gn` | Storage-optimized Graviton | NVMe SSD |
  | `or1` | OpenSearch-optimized | NVMe SSD + managed cache |
- If NVME is chosen, set `EBSEnabled=false` in the create-domain call.
- If NVME local storage is insufficient or no suitable instance type exists, fall back to EBS with `gp3` volumes sized at 1.5x–2x primary data size per node.
- In `guided` mode, present both options with cost estimates and let the user choose.

**4e. Present the provisioning plan:**
- You MUST present the full plan before creating the domain (even in `auto` mode) because provisioning creates billable AWS resources:
  *"Based on your source cluster ({source_node_count}× {source_vcpu}vCPU/{source_mem}GB nodes), I'm planning:*
  *- Data nodes: {data_type} × {data_count} (modern equivalent of your source)*
  *- Master nodes: {master_type} × 3 (dedicated, best practice)*
  *- Storage: {storage_description}*
  *- Network: VPC {vpc_id}, subnets {subnet_ids}, SGs {sg_ids} (same as source)*
  *- Version: OpenSearch {version}*
  *Estimated cost: ~$X/hr. Proceed?"*

**4f. Create the domain:**

```bash
aws opensearch create-domain \
  --domain-name "<domain-name>" \
  --engine-version "OpenSearch_<version>" \
  --cluster-config '{
    "InstanceType": "<data-type>",
    "InstanceCount": <data-count>,
    "DedicatedMasterEnabled": true,
    "DedicatedMasterType": "<master-type>",
    "DedicatedMasterCount": 3,
    "ZoneAwarenessEnabled": true,
    "ZoneAwarenessConfig": {"AvailabilityZoneCount": <az-count>}
  }' \
  --ebs-options '{"EBSEnabled": true, "VolumeType": "gp3", "VolumeSize": <size>}' \
  --vpc-options '{"SubnetIds": [<subnet-ids>], "SecurityGroupIds": [<sg-ids>]}' \
  --node-to-node-encryption-options '{"Enabled": true}' \
  --encryption-at-rest-options '{"Enabled": true}' \
  --domain-endpoint-options '{"EnforceHTTPS": true}' \
  --region "${aws_region}"
```

- If using NVME instance types, omit `--ebs-options` or set `EBSEnabled=false`.

- You MUST NOT wait for the domain to become active here. Domain provisioning takes 15–20 minutes. After submitting the `create-domain` call, you MUST immediately proceed to Step 5 (MA deployment) while the domain provisions in the background.
- You MUST record the domain name and expected configuration in `{artifacts_dir}/discovery.md` (endpoint and ARN will be captured after the domain becomes active).

**4g. Wait for domain activation (after MA deployment):**

After completing Step 5 (MA deployment), return here to poll domain status until it reaches `Active`:

```bash
while true; do
  STATUS=$(aws opensearch describe-domain --domain-name "<domain-name>" --region "${aws_region}" \
    --query 'DomainStatus.Processing' --output text)
  echo "Domain status: Processing=$STATUS"
  [ "$STATUS" = "False" ] && break
  sleep 30
done
```

- You MUST record the domain endpoint, ARN, and VPC configuration in `{artifacts_dir}/discovery.md` once the domain is active.

**Constraints for `target_provisioning=use_existing`:**
- You MUST discover or accept the target endpoint and validate connectivity.
- You MUST verify the target cluster version is compatible with the migration.

### 5. Migration Assistant Deployment

Deploy or connect to the Migration Assistant on the EKS cluster.

**Constraints:**
- If `ma_deployment=auto`, you MUST first check if MA is already deployed:

```bash
kubectl get namespace {namespace} 2>/dev/null
kubectl -n {namespace} get pods 2>/dev/null
```

- If MA is not deployed (or `ma_deployment=deploy_new`), deploy via Helm:

```bash
# Add the MA Helm repo (or use local chart)
helm repo add opensearch-migrations https://opensearch-project.github.io/opensearch-migrations/charts || true
helm repo update

# Install MA
helm install migration-assistant opensearch-migrations/opensearch-migrations \
  -n {namespace} --create-namespace \
  --set source.endpoint="<source-endpoint>" \
  --set target.endpoint="<target-endpoint>" \
  --set target.auth.type="sigv4" \
  --set target.auth.region="${aws_region}"
```

- You MUST wait for critical pods to be ready before proceeding:

```bash
kubectl -n {namespace} wait --for=condition=ready pod -l app=migration-console --timeout=300s
```

- You MUST verify you can exec into the migration console pod:

```bash
CONSOLE_POD=$(kubectl -n {namespace} get pod -l app=migration-console -o jsonpath='{.items[0].metadata.name}')
kubectl -n {namespace} exec "${CONSOLE_POD}" -- echo "Console accessible"
```

- If MA is already deployed (`ma_deployment=use_existing` or auto-detected), you MUST verify health:
  - All critical pods Running
  - Migration console accessible
  - Source and target connectivity from within the console
- If MA is not healthy (pods CrashLooping, Pending, or critical services absent), you MUST stop and propose remediation steps.
- You MUST record the MA deployment details in `{artifacts_dir}/discovery.md`.

### 6. Pre-Migration Validation

Validate connectivity and configuration from within the Migration Console before starting the workflow.

**Constraints:**
- You MUST run commands through the Migration Console pod and record them in `{artifacts_dir}/run-log.md`.
- You MUST validate connectivity to both source and target clusters from the console:
  - Reported cluster versions
  - Index list summary for source and target
  - Whether target has indices that would conflict with migration
- You MUST determine snapshot configuration:
  - Locate runtime config sources (ConfigMaps, mounted files, workflow schema)
  - Determine snapshot repo details (S3 bucket, region, IAM role)
  - Verify snapshot prerequisites are in place
- You MUST calculate and present a migration estimate (snapshot + metadata + backfill) using runtime measurements.
- You MUST classify the following as destructive: deleting/clearing indices, deleting templates, deleting snapshots.
- In `guided` and `semi_auto`, you MUST ask for explicit confirmation before any destructive action.
- In `auto`, you MUST NOT perform destructive actions unless `allow_destructive=true`.

**Recommended exec pattern:**

```bash
kubectl -n {namespace} exec "${CONSOLE_POD}" -- bash -lc \
  "source /.venv/bin/activate && <command>"
```

### 7. Configure and Run the Migration Workflow

Configure the MA workflow and execute it.

**Constraints:**
- You MUST retrieve the current workflow config and schema at runtime before editing.
- You MUST set the workflow configuration via supported mechanisms and record what you changed.
- You MUST calculate the RFS (Reindex From Snapshot) worker count using this formula:

  ```
  rfs_workers = min(target_data_node_total_vcpu / 6, number_of_source_shards)
  ```

  Where:
  - `target_data_node_total_vcpu` = (number of target data nodes) × (vCPU per target data node)
  - `number_of_source_shards` = total primary shard count across all indices being migrated

  This ensures workers don't overwhelm the target cluster and don't exceed the parallelism available from source shards.

- You MUST run the workflow using `workflow submit` and record workflow name/IDs.
- You MUST NOT use `--wait` or any other blocking wait. After submitting, immediately proceed to Step 8.
- If the workflow includes manual approval gates:
  - In `guided`, you MUST stop and request confirmation before approving any gate.
  - In `semi_auto`, you MUST stop and request confirmation at each gate.
  - In `auto`, you MAY approve non-destructive gates automatically, but MUST NOT approve gates that would trigger destructive actions unless `allow_destructive=true`.

### 8. Monitor Progress

Monitor the migration workflow and report progress.

**Constraints:**
- You MUST NOT use blocking waits (`--wait`, `sleep` without status checks, or any command that blocks until completion).
- You MUST use the following polling pattern immediately after any workflow submission:

```bash
while true; do
  workflow status
  sleep 30
done
```

- You MUST show the status output to the user on each polling iteration.
- You MUST report phase changes as they occur (e.g., snapshot → metadata → backfill).
- You MUST immediately diagnose on completion or failure — do not wait for the user to ask.
- You SHOULD use a token-efficient monitoring strategy (tail only progress indicators for long-running backfill).
- In `guided`, you MUST ask before changing worker scale.
- In `auto`, you MAY adjust scale automatically but MUST document the rationale.

### 9. Validate Data Migration

Verify the migration results are complete and consistent.

**Constraints:**
- You MUST compare source vs target using at least two independent signals (e.g., per-index doc counts and cluster stats).
- You MUST identify and report:
  - Missing indices
  - Mismatched document counts beyond an acceptable threshold
  - Mapping/template incompatibilities
- You MUST provide a short "go/no-go" assessment.
- If validation fails, you MUST propose remediation (re-run backfill, fix templates, etc.) before proceeding.

### 10. Parallel Application Stack Deployment (if `migration_scope=full_stack`)

Deploy a parallel copy of the application stack pointing at the new OpenSearch cluster.

**Skip condition:** Skip this step if `migration_scope=data_only` or `app_deployment=skip`.

**Constraints:**
- You MUST use the application information captured in Step 3 to create modified deployments.
- You MUST create modified copies of the original manifests with:
  - New deployment/service names (e.g., append `-migrated` or a user-chosen suffix)
  - Environment variables and config updated to point at the new OpenSearch endpoint
  - Same replica counts, resource limits, and other configuration preserved
  - New service endpoints so both stacks can run simultaneously
- You MUST present the modified manifests to the user for review before deploying (even in `auto` mode) because deploying incorrect application config could cause outages or data corruption.
- You MUST save the modified manifests to `{artifacts_dir}/app-migrated/`.

**Deployment procedure:**

```bash
# Apply the modified manifests
kubectl apply -f {artifacts_dir}/app-migrated/

# Wait for pods to be ready
kubectl -n <app-namespace> wait --for=condition=ready pod -l <migrated-app-selector> --timeout=300s

# Verify the new pods are running
kubectl -n <app-namespace> get pods -l <migrated-app-selector>
```

- You MUST verify the parallel stack is healthy:
  - All pods Running and Ready
  - Services have endpoints
  - Application can connect to the new OpenSearch cluster
- If the user wants to access the parallel stack (e.g., dashboards), you SHOULD set up port-forwarding or provide the service endpoint.
- You MUST NOT modify or disrupt the original application stack.

### 11. End-to-End Validation

Validate the complete migration including the application stack.

**Skip condition:** If `app_deployment=skip`, perform only data validation (same as Step 9).

**Constraints:**
- You MUST verify:
  - Data migration completeness (doc counts match)
  - Application stack health (all pods running)
  - Application connectivity to the new cluster (queries return data)
- You SHOULD present a side-by-side comparison if possible:
  - Source cluster stats vs target cluster stats
  - Original app endpoints vs migrated app endpoints
- You MUST provide a clear summary of what's running and how to access each stack.
- You MUST provide next steps for the user:
  - How to gradually shift traffic to the new stack
  - How to decommission the old stack when ready
  - Any remaining manual steps (DNS changes, load balancer updates, etc.)

### 12. Post-Run Summary

Produce a concise summary of everything that happened.

**Constraints:**
- You MUST write `{artifacts_dir}/summary.md` containing:
  - Source cluster details (version, size, index count)
  - Target cluster details (domain name, endpoint, version, sizing)
  - Migration Assistant deployment details
  - Migration workflow results (duration, doc counts, any issues)
  - Application stack deployment details (if applicable)
  - Validation results
  - Actions taken and destructive actions (if any)
  - Paths to logs and key outputs
- You MUST provide next steps based on observed gaps.
- You MUST present the summary to the user.

## Examples

### Example Input (Full Stack, Guided)

```text
hands_on_level: guided
migration_scope: full_stack
source_selection: discover
target_provisioning: provision_new
app_deployment: parallel
aws_region: us-east-1
```

### Example Input (Data Only, Auto)

```text
hands_on_level: auto
migration_scope: data_only
source_selection: custom
target_provisioning: use_existing
source_cluster: { endpoint: "https://source.example:9200", auth: "basic_auth" }
target_cluster: { endpoint: "https://target.example:9200", auth: "sigv4", region: "us-east-1" }
allow_destructive: true
```

### Example Input (Natural Language — Agent Infers Parameters)

```text
"I have an Elasticsearch cluster in EKS. Migrate everything to OpenSearch on Amazon OpenSearch Service.
Inspect my source, provision a matching target, deploy the Migration Assistant, run the migration,
then spin up a parallel application stack. Auto mode."
```

The agent should infer:
- `hands_on_level: auto`
- `migration_scope: full_stack`
- `source_selection: discover`
- `target_provisioning: provision_new`
- `app_deployment: parallel`
- `app_source: discover`

## Troubleshooting

### Helm Release Failed but Pods Are Running

If Helm shows FAILED but `kubectl -n {namespace} get pods` shows Running:
- You SHOULD explain that Helm is not transactional and some resources can exist despite a failed release.
- You SHOULD identify which resource failed and whether it blocks migration.

### Connectivity Check Fails

If the console cannot reach source/target:
- You SHOULD validate endpoint URLs, auth mode, TLS settings (`allow_insecure`), and network reachability.
- For EKS-based source: prefer NodePort + node IP or ClusterIP service over LoadBalancer DNS.
- You SHOULD stop before snapshot or destructive actions.

### Never Make Risky AWS Changes Automatically

If resolving connectivity would require AWS-side changes (access policies, security groups, IAM, domain config):
- You MUST NOT run `aws opensearch update-domain-config`, `aws ec2 authorize-security-group-*`, or IAM policy/role changes automatically.
- You SHOULD provide the exact commands and ask the user to run them or explicitly approve them.

### Workflow Retry Procedure

When a workflow fails and needs to be retried:

1. Run `workflow status` and capture the failure details.
2. Get logs from the failed pod (`kubectl logs -n {namespace} <pod-name>`).
3. Diagnose the root cause from the logs and status output.
4. Clean up before resubmitting:
   a. Delete the failed workflow.
   b. Delete the target index if metadata or transformer config needs to change.
   c. Verify the transformer config is correct before resubmitting.
5. Resubmit the workflow.
6. Immediately start the monitoring polling loop (see Step 8).

- You MUST follow this procedure in order — do not skip cleanup steps.
- You MUST NOT retry more than 3 times with the same approach. If the same error recurs, stop and report to the user with a summary of what was tried and propose alternative approaches.

### kubectl Command Patterns

- **Pod status:** `kubectl get pods -n {namespace} -l <selector>`
- **Pod logs:** `kubectl logs -n {namespace} <pod-name>` — always verify the pod exists first.
- You MUST NOT use `--timeout` with `kubectl get`.
- You MUST NOT pass empty or unresolved variables as pod names.

### Field Type Compatibility

Common field type transformations needed when migrating to OpenSearch:
- `sparse_vector` → `rank_features` (or disabled/removed if not supported in managed OpenSearch)
- `dense_vector` → `knn_vector`
- ELSER-specific types may need removal or transformation
- Always test creating a sample index with target field types on the target cluster before running metadata migration.

### Domain Provisioning Issues

- If domain creation fails due to VPC/subnet issues, verify the subnets are in the same AZs as the EKS cluster.
- If domain creation fails due to instance type availability, try a different instance type in the same family.
- Domain provisioning typically takes 15–20 minutes. Use this time to deploy MA (Step 5).

### Application Stack Deployment Issues

- If the parallel app can't connect to the new OpenSearch cluster, verify:
  - Security group rules allow traffic from the app pods to the OpenSearch domain
  - The endpoint URL is correct (use the VPC endpoint, not the public endpoint)
  - Auth credentials/IAM roles are configured for the new domain
- If pods fail to start, check for image pull issues, resource limits, or missing ConfigMaps/Secrets.
