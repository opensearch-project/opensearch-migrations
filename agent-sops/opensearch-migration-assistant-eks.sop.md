# OpenSearch Migration Assistant on EKS

## Overview

This SOP guides an agent through running an OpenSearch Migration Assistant (MA) migration on Kubernetes (EKS by default) using the Migration Console. It is runtime-discovery-first (find the right cluster/context/namespace/configmaps at execution time) and uses a configurable interaction level so the user can be hands-on or hands-off.

## Parameters

- **hands_on_level** (required): How interactive the agent must be.
  - `guided`: Ask for confirmation before each phase and before any destructive or irreversible action.
  - `semi_auto`: Proceed automatically within a phase, but ask for confirmation at phase boundaries and before destructive/irreversible actions.
  - `auto`: Proceed automatically end-to-end, but still stop for destructive/irreversible actions unless explicitly permitted via `allow_destructive`.
- **kube_context** (optional, default: current kubectl context): The kubectl context to use.
- **namespace** (optional, default: "ma"): Kubernetes namespace where MA is deployed.
- **source_selection** (optional, default: "aws_discover"): How to choose source/target clusters.
  - `aws_discover`: Discover Amazon OpenSearch Service / Serverless domains at runtime using AWS CLI.
  - `custom`: User provides endpoints and auth details.
- **source_cluster** (optional): Source cluster connection details, required when `source_selection=custom`.
- **target_cluster** (optional): Target cluster connection details, required when `source_selection=custom`.
- **allow_insecure** (optional, default: false): If true, allow TLS without verification for cluster connections.
- **index_allowlist** (optional): List of index names to migrate (exact names). If omitted, migrate all non-system indices.
- **allow_destructive** (optional, default: false): If true, the agent may run destructive operations without additional confirmation when `hands_on_level=auto`.
- **ma_environment_mode** (optional, default: "use_existing_stage"): How to acquire the MA EKS environment.
  - `use_existing_stage`: Read CloudFormation exports (`MigrationsExportString*`) and connect to the exported EKS cluster.
  - `deploy_new_stage`: Deploy a new stage via CloudFormation/CDK (creates/updates AWS resources; high risk).
- **stage** (optional): Stage identifier used by the EKS solution (e.g., `dev`, `test`, `prod`). Required when `ma_environment_mode=use_existing_stage` or when deploying a stage.
- **aws_region** (optional): AWS region for the MA EKS environment. Required when deploying a stage; recommended for `use_existing_stage` to narrow discovery.
- **cfn_stack_name** (optional): If deploying a stage with CloudFormation, the explicit stack name to create/update.
- **vpc_mode** (optional, default: "create_vpc"): Only used for `deploy_new_stage`.
  - `create_vpc`: Deploy the "Create VPC" EKS template.
  - `import_vpc`: Deploy the "Import VPC" EKS template.
- **cfn_template_path** (optional): Template file path to deploy (for `deploy_new_stage`), e.g. `deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json`.
- **artifacts_dir** (optional, default: ".agents/migration/{run_id}"): Directory for run artifacts.
- **run_id** (optional): Identifier for this run. If omitted, generate `YYYY-MM-DD-HHMM-opensearch-migration`.

**Constraints for parameter acquisition:**
- You MUST ask for all required parameters upfront in a single prompt rather than one at a time because this reduces interruptions during execution.
- You MUST normalize `hands_on_level` to one of: `guided`, `semi_auto`, `auto`.
- You MUST normalize boolean parameters (`allow_insecure`, `allow_destructive`) to true/false.
- You MUST support multiple input methods for `source_cluster` and `target_cluster` when `source_selection=custom` (direct input, file path, URL).
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

### 0. Acquire/Select MA EKS Environment (Stage)

Ensure there is a reachable EKS cluster that can host MA.

**Constraints:**
- You MUST support both `use_existing_stage` and `deploy_new_stage` paths.
- You MUST treat `deploy_new_stage` as high risk because it creates/updates AWS infrastructure.
- In `guided` and `semi_auto`, you MUST ask for explicit confirmation before starting any deployment that creates/updates AWS resources.
- In `auto`, you MUST NOT deploy a new stage unless the user explicitly provided `ma_environment_mode=deploy_new_stage`.

**Non-negotiable (runtime-discovery-first):**
- You MUST NOT construct or guess the EKS cluster name (for example, do not use patterns like `migration-eks-cluster-<STAGE>-<REGION>`). You MUST obtain the cluster name from CloudFormation exports (preferred), from the bootstrap script output, or from an explicit user-provided value.
- You MUST treat any example values as placeholders only and require runtime discovery before execution.

**use_existing_stage procedure (recommended):**

Run these commands on the operator machine (local shell), not inside the Migration Console.

1) Prefer the bootstrap script for stage selection:

```bash
deployment/k8s/aws/aws-bootstrap.sh --stage <stage> --skip-console-exec
```

2) If the bootstrap script is not available, select the export by export *Name* (not by `Value` content):

```bash
# If aws_region is known, use it. Otherwise, iterate regions and stop once found.

# List candidate exports (Name only)
aws cloudformation list-exports --region "<region>" \
  --query "Exports[?starts_with(Name, 'MigrationsExportString')].Name" --output text

# Choose the correct export NAME (often stage-specific). If multiple match, stop and ask the user to pick one.
EXPORT_NAME="<one export name from the list>"

# Fetch and apply the export Value (this is typically a shell 'export ...; export ...' string)
EXPORT_VALUE=$(aws cloudformation list-exports --region "<region>" \
  --query "Exports[?Name=='${EXPORT_NAME}'].Value" --output text)
eval "${EXPORT_VALUE}"

# Sanity-check required variables exist before continuing
test -n "${AWS_CFN_REGION}" && test -n "${MIGRATIONS_EKS_CLUSTER_NAME}"
```

3) After exports are applied, connect kubectl to the exported EKS cluster:

```bash
aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"
kubectl config current-context
```

**deploy_new_stage procedure (developer approach):**
- You MUST prefer deploying from an explicit CloudFormation template path (synthesized from source) and record it.
- You MUST document the selected `vpc_mode`, `stage`, `aws_region`, and resulting stack name.
- After the stack is CREATE/UPDATE complete, you MUST fetch exports and connect kubectl as in the existing-stage path.

### 1. Initialize Run Workspace

Create an artifacts directory and establish a consistent log/plan format for the run.

**Constraints:**
- You MUST create `{artifacts_dir}` and write these files:
  - `{artifacts_dir}/plan.md`
  - `{artifacts_dir}/discovery.md`
  - `{artifacts_dir}/run-log.md`
  - `{artifacts_dir}/summary.md` (may be empty initially)
- You MUST record the normalized parameters in `{artifacts_dir}/plan.md`.
- You MUST NOT overwrite an existing `{artifacts_dir}` that has contents because it could destroy previous run artifacts.

### 2. Runtime Discovery (Kubernetes + MA Deployment)

Identify the active Kubernetes context, confirm MA is deployed, and locate the Migration Console entry point.

**Constraints:**
- You MUST determine and record:
  - current context (`kubectl config current-context`)
  - current namespace setting (if any)
  - the target namespace (`{namespace}`)
- You MUST gather MA health at runtime using commands like:
  - `kubectl -n {namespace} get pods`
  - `kubectl -n {namespace} get deployments,statefulsets,svc`
- You MUST find the Migration Console pod (commonly `migration-console-0`) and record the exact pod name.
- You MUST verify you can exec into the console pod (or provide remediation if you cannot).
- If MA is not healthy (pods CrashLooping, Pending, or critical services absent), you MUST stop and propose remediation steps rather than attempting migration actions.

**Command context separation (MUST follow):**
- `kubectl`, `helm`, `aws` commands run on the operator machine (local shell).
- `workflow` and `console` commands typically run inside the Migration Console pod via `kubectl exec`.

### 3. Source and Target Cluster Selection

Determine source/target endpoints and auth strategy with minimal hardcoding.

**Constraints:**
- If `source_selection=aws_discover`, you MUST attempt runtime discovery of domains (region scan) and present the discovered choices.
  - Recommended discovery command:
    - `for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do (s=$(aws opensearch list-domain-names --region "$r" --query 'DomainNames[].DomainName' --output text 2>/dev/null) && [ -n "$s" ] && printf "=== %s ===\n%s\n" "$r" "$s") & done; wait`
- If `source_selection=custom`, you MUST validate `source_cluster` and `target_cluster` include endpoint and auth mode.
- You MUST capture final chosen source/target details in `{artifacts_dir}/discovery.md` (redacting secrets).
- You MUST NOT print credentials or secret values in logs because that can leak sensitive information; record only references (e.g., secret name/ARN) and auth type.

### 4. Enter Migration Console and Validate Connectivity

Use the Migration Console to run connectivity checks and capture baseline state.

**Constraints:**
- You MUST run commands through the Migration Console pod and record the exact command forms you used in `{artifacts_dir}/run-log.md`.
- You SHOULD prefer MA-provided CLIs (typically `workflow` for orchestration, and `console` for cluster operations) because they centralize auth/config and reduce manual errors.
- You MUST validate connectivity to both clusters and record:
  - reported cluster versions
  - a concise index list summary for source and target
  - whether target has indices that would conflict with migration

**Recommended exec pattern (reference):**

```bash
# Local shell
kubectl -n {namespace} exec <migration-console-pod> -- bash -lc \
  "source /.venv/bin/activate && <command>"
```

### 5. Acquire Snapshot and Migration Configuration at Runtime

Pull snapshot configuration from the runtime environment instead of embedding values.

**Constraints:**
- You MUST locate runtime configuration sources and record them in `{artifacts_dir}/discovery.md`, for example:
  - ConfigMaps in the MA namespace (e.g., `kubectl -n {namespace} get configmap`)
  - Mounted config files inside the Migration Console
  - Workflow config schema (`/root/.workflowUser.schema.json`)
- You MUST determine snapshot repo details (S3 or filesystem), including region/bucket/role if applicable.
- If required snapshot prerequisites are missing (bucket/role/repo registration), you MUST stop and document the minimal remediation steps.

### 6. Pre-Migration Estimate and Safety Gate

Estimate duration and confirm target cleanup behavior before starting the workflow.

**Constraints:**
- You MUST calculate and present an estimate (snapshot + metadata + backfill) using runtime measurements (cluster stats) because migration windows are capacity-sensitive.
- You MUST classify the following as destructive: deleting/clearing indices, deleting templates, deleting snapshots, deleting snapshot repo registrations.
- In `guided` and `semi_auto`, you MUST ask the user for explicit confirmation before any destructive action.
- In `auto`, you MUST NOT perform destructive actions unless `allow_destructive=true` because these operations can cause irreversible data loss.
- If destructive actions are approved, you MUST record exactly what will be deleted and why in `{artifacts_dir}/plan.md`.

### 7. Configure and Run the Workflow

Configure the MA workflow and execute it while capturing evidence.

**Constraints:**
- You MUST retrieve the current workflow config and schema at runtime (e.g., `workflow configure view`, `/root/.workflowUser.schema.json`) before editing.
- You MUST set the workflow configuration via supported mechanisms (interactive editor or `--stdin`) and record what you changed.
- You MUST run the workflow using `workflow submit` (optionally `--wait`) and record workflow name/IDs.
- If the workflow includes manual approval gates:
  - In `guided`, you MUST stop and request confirmation before approving any gate.
  - In `semi_auto`, you MUST stop and request confirmation at each gate.
  - In `auto`, you MAY approve non-destructive gates automatically, but MUST NOT approve gates that would trigger destructive actions unless `allow_destructive=true`.

### 8. Monitor Progress and Adjust Scale

Monitor progress and adjust backfill worker scale carefully.

**Constraints:**
- You MUST use MA-provided status/log mechanisms where possible (workflow status/output and component logs).
- You SHOULD use a token-efficient monitoring strategy (tail only progress indicators for long-running backfill).
- In `guided`, you MUST ask before changing scale.
- In `semi_auto`, you SHOULD ask before changing scale unless you previously recorded an approved scaling policy in `{artifacts_dir}/plan.md`.
- In `auto`, you MAY adjust scale automatically but MUST document the rationale and before/after values.

### 9. Validate Completion

Verify the migration results are complete and consistent for the chosen scope.

**Constraints:**
- You MUST compare source vs target using at least two independent signals (e.g., per-index doc counts and independent cluster stats trends) because a single metric can be misleading.
- You MUST identify and report:
  - missing indices
  - mismatched document counts beyond an acceptable threshold
  - mapping/template incompatibilities discovered
- You SHOULD provide a short “go/no-go” checklist for cutover readiness.

### 10. Post-Run Summary

Produce a concise summary of what happened, what changed, and where to look for evidence.

**Constraints:**
- You MUST write `{artifacts_dir}/summary.md` containing:
  - final source/target selection (redacted)
  - actions taken
  - destructive actions taken (if any)
  - final status and validation results
  - paths to logs and key outputs
- You MUST provide next steps based on observed gaps (re-run backfill, fix templates, adjust allowlist, etc.).

## Examples

### Example Input (Guided)

```text
hands_on_level: guided
namespace: ma
source_selection: aws_discover
index_allowlist: ["logs-2026.01.01", "logs-2026.01.02"]
```

### Example Input (Auto with Destructive Allowed)

```text
hands_on_level: auto
allow_destructive: true
namespace: ma
source_selection: custom
source_cluster: { endpoint: "https://source.example:9200", auth: "basic_auth", secret_ref: "aws-secretsmanager:..." }
target_cluster: { endpoint: "https://target.example:9200", auth: "sigv4", region: "us-east-1" }
```

## Troubleshooting

### Helm Release Failed but Pods Are Running

If Helm shows FAILED but `kubectl -n {namespace} get pods` shows Running:
- You SHOULD explain that Helm is not transactional and some resources can exist despite a failed release.
- You SHOULD identify which resource failed and whether it blocks migration.

### Connectivity Check Fails

If the console cannot reach source/target:
- You SHOULD validate endpoint URLs, auth mode, TLS settings (`allow_insecure`), and network reachability (VPC routing/security groups if applicable).
- You SHOULD stop before snapshot or destructive actions.

### Never Make Risky AWS Changes Automatically

If resolving connectivity would require AWS-side changes (access policies, security groups, IAM, domain config):
- You MUST NOT run `aws opensearch update-domain-config`, `aws ec2 authorize-security-group-*`, or IAM policy/role changes automatically because these can impact production access and security posture.
- You SHOULD provide the exact commands and ask the user to run them or explicitly approve them.
