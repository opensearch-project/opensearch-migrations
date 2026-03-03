# Argo CI/CD — Jenkins & GHA Replacement

Replaces Jenkins CI/CD pipelines and GitHub Actions CI.yml fan-out with Argo Workflows + Argo Events + ReportPortal.

## Architecture

```
GitHub Events (push/PR/release/label)
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│  Argo Events (argo-events namespace)                     │
│  EventBus (NATS) ← EventSource (GitHub webhook)         │
│  Sensors: ci-pipeline, integ-tests, release              │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│  Argo Workflows (ma namespace)                           │
│                                                          │
│  CI Pipeline (replaces CI.yml):                          │
│    30x gradle stripes, 3x python, 3x node, lint, e2e    │
│                                                          │
│  Integration Tests (replaces Jenkins):                   │
│    k8s-local, k8s-matrix, full-es68, rfs, traffic-replay │
│    eks-integ, cleanup-deployment, release-pipeline        │
│                                                          │
│  Shared Templates: common-ci-steps, build-images         │
│  CronWorkflows: nightly-k8s-matrix                       │
│  Synchronization: semaphores via ConfigMap               │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│  ReportPortal (reportportal namespace)                   │
│  JUnit XML import, JSON report upload, dashboards        │
└─────────────────────────────────────────────────────────┘
```

## Directory Structure

```
argo/
├── setup-minikube-ci.sh              # P0: One-command minikube CI setup
├── deploy.sh                         # Unified deploy: --all, --p0..--p5, --test
├── Makefile                          # Build images, deploy, validate, test
├── ci-stage-locks-configmap.yaml     # Semaphore concurrency limits
│
├── workflows/                         # WorkflowTemplates
│   ├── common-ci-steps.yaml          # git-checkout, gradle-build, build-docker-images,
│   │                                 # cleanup-ma, get-registry-ip, notify-github-status
│   ├── build-images.yaml             # Standalone image build workflow
│   ├── argo-ci-pipeline.yaml         # Replaces CI.yml (30x gradle, python, node fan-out)
│   ├── k8s-local-test.yaml           # Replaces k8sLocalDeployment.groovy
│   ├── k8s-matrix-test.yaml          # Replaces k8sMatrixTest.groovy
│   ├── upload-results.yaml           # ReportPortal upload (JUnit XML, JSON, streaming)
│   └── aws-integ/                    # AWS integration test pipelines
│       ├── default-integ-pipeline.yaml   # Base: checkout → build → CDK deploy → pytest → cleanup
│       ├── full-es68-e2e.yaml            # ES 6.8 → OS 2.19 full migration
│       ├── rfs-and-traffic-replay.yaml   # RFS-only + traffic replay (thin wrappers)
│       ├── eks-integ-pipeline.yaml       # EKS-based with ordered CFN cleanup
│       ├── eks-byos-integ-pipeline.yaml  # Bring-Your-Own-Snapshot via EKS
│       ├── eks-solutions-cfn-test.yaml   # EKS CFN Create-VPC/Import-VPC validation
│       ├── eks-isolated-deploy.yaml      # Isolated network EKS deployment
│       ├── solutions-cfn-test.yaml       # EC2-based Solutions CFN validation
│       ├── cleanup-deployment.yaml       # Teardown a deployment stage
│       └── release-pipeline.yaml         # S3 publish, Maven, Docker image copy
│
├── events/                            # Argo Events config
│   ├── eventbus.yaml                 # NATS Jetstream backbone
│   ├── eventsource-github.yaml       # GitHub webhook receiver (PR + release)
│   ├── sensor-ci-pipeline.yaml       # CI pipeline trigger (replaces CI.yml)
│   ├── sensor-integ-tests.yaml       # Integration test triggers (replaces jenkins_tests.yml)
│   └── sensor-release.yaml           # Release trigger (draft release → workflow)
│
├── cron/
│   └── nightly-matrix.yaml           # CronWorkflow (0 22 * * * UTC)
│
├── config/                            # Production EKS configs
│   ├── production-eks.yaml           # IRSA SAs, secrets, Karpenter NodePool
│   └── s3-artifact-repo.yaml         # S3 artifact repo (replaces MinIO)
│
├── images/                            # Container images
│   ├── ci-runner/Dockerfile          # Python, AWS CLI, kubectl, helm, CDK, Java 17
│   └── release-runner/Dockerfile     # skopeo, AWS CLI, Maven, Java 17
│
├── reportportal/
│   ├── install.sh                    # Helm install with minikube values
│   └── values-minikube.yaml          # Lightweight resource requests
│
└── examples/
    ├── run-single-test.yaml          # Quick: ES_7.10 → OS_2.19
    ├── run-matrix.yaml               # Quick: matrix across OS_2.19, OS_3.1
    ├── run-ci-pipeline.yaml          # Quick: full CI pipeline (4 stripes for local)
    └── run-es-version-tests.yaml     # Quick: ES 5.6 and ES 8.x tests
```

## Quick Start

```bash
# Full setup from scratch
./argo/deploy.sh --all

# Or iteratively by priority
./argo/deploy.sh --p0                     # Infrastructure
./argo/deploy.sh --skip-infra --p1 --p2   # CI templates + integration test
./argo/deploy.sh --skip-infra --p3        # Matrix + cron
./argo/deploy.sh --skip-infra --p4        # Argo Events
./argo/deploy.sh --skip-infra --p5        # ReportPortal

# Run tests
argo submit -n ma argo/examples/run-single-test.yaml --watch
argo submit -n ma argo/examples/run-matrix.yaml --watch
```

## Jenkins → Argo Feature Mapping

| Jenkins Pipeline | Argo Replacement | File |
|---|---|---|
| `k8sLocalDeployment.groovy` | `k8s-local-test.yaml` | WorkflowTemplate + DAG + onExit |
| `k8sMatrixTest.groovy` | `k8s-matrix-test.yaml` | Workflow-of-Workflows + withParam |
| `defaultIntegPipeline.groovy` | `aws-integ/default-integ-pipeline.yaml` | WorkflowTemplate + IRSA |
| `eksIntegPipeline.groovy` | `aws-integ/eks-integ-pipeline.yaml` | Ordered CFN cleanup DAG |
| `eksBYOSIntegPipeline.groovy` | `aws-integ/eks-byos-integ-pipeline.yaml` | BYOS via EKS |
| `eksSolutionsCFNTest.groovy` | `aws-integ/eks-solutions-cfn-test.yaml` | Create-VPC + Import-VPC modes |
| `solutionsCFNTest.groovy` | `aws-integ/solutions-cfn-test.yaml` | CDK deploy + validate |
| `eksIsolatedDeploy.groovy` | `aws-integ/eks-isolated-deploy.yaml` | Build EKS + isolated EKS |
| `fullES68SourceE2ETest.groovy` | `aws-integ/full-es68-e2e.yaml` | WorkflowTemplate |
| `rfsDefaultE2ETest.groovy` | `aws-integ/rfs-and-traffic-replay.yaml` | Thin wrapper Workflow |
| `trafficReplayDefaultE2ETest.groovy` | `aws-integ/rfs-and-traffic-replay.yaml` | Thin wrapper Workflow |
| `cleanupDeployment.groovy` | `aws-integ/cleanup-deployment.yaml` | WorkflowTemplate + mutex |
| `release.jenkinsFile` | `aws-integ/release-pipeline.yaml` | DAG: S3+Maven+Docker+GitHub |
| `CI.yml` (GHA) | `argo-ci-pipeline.yaml` | 30x gradle, 3x python, 3x node |
| `jenkins_tests.yml` (GHA) | `events/sensor-integ-tests.yaml` | Argo Events Sensor |
| `cron('H 22 * * *')` | `cron/nightly-matrix.yaml` | CronWorkflow |
| `lock(label: STAGE)` | `ci-stage-locks-configmap.yaml` | synchronization.semaphores |
| `withAWS(role: ...)` | IRSA + STS AssumeRole | ServiceAccount annotations |
| `post { always }` | `onExit` handler | Exit handler DAG |
| `archiveArtifacts` | S3 artifact outputs | MinIO (local) / S3 (prod) |
| Console logs + Blue Ocean | Argo UI + ReportPortal | `upload-results.yaml` |

## GHA Workflows — What Stays vs Moves

| Workflow | Decision | Rationale |
|---|---|---|
| `CI.yml` | → Argo (`argo-ci-pipeline`) | 30x fan-out, cheaper on EKS |
| `jenkins_tests.yml` | → Eliminated | Argo Events receives GitHub events directly |
| `codeql.yml` | Stays in GHA | GitHub-native security scanning |
| `sonar-qube.yml` | Stays in GHA | Self-contained, no infra needed |
| `release-drafter.yml` | Stays in GHA | GitHub-native |
| `backport.yml` | Stays in GHA | Branch management |

## Production vs Minikube

| Component | Minikube | Production EKS |
|---|---|---|
| EventBus replicas | 1 | 3 |
| Artifact storage | MinIO | S3 (`config/s3-artifact-repo.yaml`) |
| Image registry | minikube local | ECR |
| Node scheduling | Single node | Karpenter (`config/production-eks.yaml`) |
| AWS auth | N/A | IRSA service accounts |
| Secrets | K8s secrets | External Secrets Operator |

## Production Setup

Before deploying to production EKS, update these placeholder values in `config/production-eks.yaml`:

| Placeholder | Description | Example |
|---|---|---|
| `ACCOUNT_ID` | AWS account ID for CI/CD | `123456789012` |
| `CLUSTER_NAME` | EKS cluster name | `migrations-ci-prod` |
| `REPLACE_WITH_TEST_ACCOUNT_ID` | AWS account ID for test deployments | `987654321098` |
| `REPLACE_WITH_GITHUB_TOKEN` | GitHub personal access token or app token | `ghp_...` |

```bash
# Replace placeholders
sed -i 's/ACCOUNT_ID/123456789012/g' config/production-eks.yaml
sed -i 's/CLUSTER_NAME/migrations-ci-prod/g' config/production-eks.yaml

# Apply production config
kubectl apply -f config/production-eks.yaml
kubectl apply -f config/s3-artifact-repo.yaml

# Build and push CI images
make push-all REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com

# Deploy all templates
make deploy-prod
```

## Troubleshooting

### Workflow stuck in Pending
```bash
# Check if semaphore is blocking
argo get -n ma @latest -o yaml | grep -A5 synchronization

# Check stage locks
kubectl get configmap ci-stage-locks -n ma -o yaml

# List all running workflows (may be holding locks)
argo list -n ma --running
```

### Artifact upload/download failures
```bash
# Verify MinIO is running (minikube)
kubectl get pods -n minio
kubectl -n minio port-forward svc/minio-console 9001:9001
# Browse http://localhost:9001 — check argo-artifacts bucket exists

# Verify artifact repo config
kubectl -n ma get configmap workflow-controller-configmap -o yaml | grep -A10 artifactRepository

# Check workflow controller logs
kubectl logs -n ma -l app.kubernetes.io/name=argo-workflows --tail=50
```

### Argo Events sensor not triggering
```bash
# Check EventSource pod
kubectl get pods -n argo-events
kubectl logs -n argo-events -l eventsource-name=github-migrations --tail=30

# Check Sensor pod
kubectl logs -n argo-events -l sensor-name=ci-pipeline-sensor --tail=30

# Verify EventBus is healthy
kubectl get eventbus -n argo-events
kubectl get pods -n argo-events -l eventbus-name=default

# Test webhook manually
kubectl port-forward -n argo-events svc/github-migrations-eventsrc-svc 12000:12000 &
curl -X POST http://localhost:12000/github/pr \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" \
  -d '{"action":"opened","pull_request":{"number":1,"head":{"ref":"test","sha":"abc","repo":{"clone_url":"https://github.com/test/test.git"}}}}'
```

### ReportPortal not receiving results
```bash
# Check ReportPortal pods
kubectl get pods -n reportportal

# Verify API is accessible
kubectl -n reportportal port-forward svc/reportportal-serviceapi 8585:8585 &
curl -s http://localhost:8585/health | jq .

# Check token secret exists
kubectl get secret reportportal-token -n ma

# Test upload manually
curl -X POST http://localhost:8585/api/v1/opensearch_migrations/launch/import \
  -H "Authorization: Bearer superadmin_personal" \
  -F "file=@test-results.xml"
```

### Cleanup handler not running
```bash
# Exit handlers run even on failure, but check:
argo get -n ma <workflow-name> -o yaml | grep -A5 onExit

# If cleanup is stuck, force-delete
argo terminate -n ma <workflow-name>
helm uninstall ma -n ma || true
kubectl delete namespace kyverno-ma --ignore-not-found
```

### Docker build failures (DinD sidecar)
```bash
# Check if DinD sidecar started
argo logs -n ma <workflow-name> --container dind

# Common issue: privileged containers not allowed
# Fix: ensure PodSecurityPolicy/PodSecurityStandard allows privileged
kubectl get psp  # or check namespace labels for PSS
```
