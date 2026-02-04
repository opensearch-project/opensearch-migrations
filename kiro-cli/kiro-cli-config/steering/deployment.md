# Deployment Guide

This document is a reference. The default, end-to-end workflow is defined in:

- `.kiro/steering/opensearch-migration-assistant-eks.sop.md`

## Best Practices
- Use `--wait` flags to block until operations complete (reduces polling):
  - `helm install/uninstall --wait --timeout 60s`
  - `kubectl delete namespace <ns> --wait=true`
  - `kubectl wait --for=condition=Ready pod/name --timeout=60s`
- Use `--skip-console-exec` on bootstrap to avoid interactive kubectl exec at end
- `workflow submit --wait` blocks until workflow completes
- For cleanup: `helm uninstall -n ma ma --wait --timeout 60s` then `kubectl delete namespace ma --wait=true`
- Run commands in migration console with venv:
  ```bash
  kubectl exec migration-console-0 -n ma -- bash -c "source /.venv/bin/activate && <command>"
  ```
- Use `-v` flag for verbose output when debugging:
  ```bash
  console -v clusters connection-check
  workflow -v status
  ```

## Prerequisites
- EKS cluster deployed via CloudFormation (migration-assistant-eks stack)
- AWS CLI configured with appropriate credentials
- kubectl installed

## Pick An MA Environment (Stage)

You have two supported paths:

1) **Use an existing stage** by reading CloudFormation exports (`MigrationsExportString*`) and connecting to the exported EKS cluster.
2) **Deploy a new stage** via CloudFormation (from synthesized templates) or CDK (high risk; requires explicit confirmation in interactive workflows).

### Use Existing Stage (Exports)

The EKS solution exports an `export ...; export ...;` string via CloudFormation output `MigrationsExportString`.

Reference (no stage filter):

```bash
eval $(aws cloudformation list-exports --query "Exports[?starts_with(Name, \`MigrationsExportString\`)].[Value]" --output text)
aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"
kubectl get pods -n ma
```

If multiple exports exist, prefer using `deployment/k8s/aws/aws-bootstrap.sh --stage <stage>` to select the right one.

### Deploy New Stage (CloudFormation)

For developers, you can synthesize templates and deploy a new EKS stage:

```bash
./gradlew :deployment:migration-assistant-solution:cdkSynthMinified
aws cloudformation deploy \
  --template-file deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json \
  --stack-name "MA-EKS-<STAGE>-<REGION>" \
  --parameter-overrides Stage=<stage> \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM
```

## Find All Migration Stacks (All Regions)
```bash
# POSIX-compatible, parallel (~6 seconds)
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do (s=$(aws cloudformation list-stacks --region "$r" --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE --query "StackSummaries[?contains(StackName,'migration')].[StackName,StackStatus]" --output text 2>/dev/null) && [ -n "$s" ] && printf "=== %s ===\n%s\n" "$r" "$s") & done; wait
```

## Connect to EKS Cluster
```bash
aws eks update-kubeconfig --region <REGION> --name migration-eks-cluster-<STAGE>-<REGION>
kubectl get pods -n ma
```

## Install Migration Assistant (Bootstrap)
```bash
# Download bootstrap script
cd /tmp && rm -f aws-bootstrap.sh && rm -rf opensearch-migrations
curl -s -o aws-bootstrap.sh https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/main/deployment/k8s/aws/aws-bootstrap.sh
chmod +x aws-bootstrap.sh

# Run with --skip-console-exec to avoid interactive session
./aws-bootstrap.sh --skip-console-exec
```

### Bootstrap Script Parameters
| Parameter | Default | Description |
|-----------|---------|-------------|
| `--org-name` | opensearch-project | GitHub org |
| `--branch` | main | Git branch |
| `--tag` | (auto) | Specific version tag |
| `--skip-console-exec` | false | Skip kubectl exec into console |
| `--build-images` | false | Build images from source |
| `--namespace` | ma | Kubernetes namespace |

## Access Migration Console
```bash
kubectl exec -it migration-console-0 -n ma -- /bin/bash
```

## Cleanup EKS Cluster Resources
```bash
# Connect to cluster
aws eks update-kubeconfig --region <REGION> --name migration-eks-cluster-<STAGE>-<REGION>

# Uninstall helm release and delete namespace
helm uninstall -n ma ma
kubectl delete namespace ma
```

## Full Documentation
```bash
cat opensearch-migrations.wiki/Deploying-to-EKS.md
cat opensearch-migrations.wiki/Deploying-to-Kubernetes.md
```
