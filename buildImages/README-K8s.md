# Building Images on a Cloud Kubernetes Cluster (EKS / GKE / AKS)

This guide describes the cloud-Kubernetes BuildKit path used by `deployment/k8s/aws/aws-bootstrap.sh --build`. The backend lives in [backends/eksKubernetesBuildkit.sh](backends/eksKubernetesBuildkit.sh) and uses `docker buildx` with the `kubernetes` driver to spin up amd64 + arm64 buildkit Pods on the cluster's `build-nodepool`. Images are pushed directly to the cluster's ECR registry.

For the Docker-hosted local flow used by minikube and kind, see [README.md](README.md). The local flow runs the registry and BuildKit as Docker containers on the host and joins kind/minikube to the same Docker network — no in-cluster registry, no `kubectl port-forward`, no `--insecure-registry`.

## Prerequisites

- An EKS / GKE / AKS cluster reachable via `kubectl` (the EKS bootstrap script will create one for you).
- Docker CLI with `buildx` available.
- Helm 3.

## How the EKS path works

`aws-bootstrap.sh --build` does the following:
1. Sources `buildImages/backends/eksKubernetesBuildkit.sh` and calls `setup_build_backend`.
2. `setup_build_backend` installs the `buildImages` helm chart in the `buildkit` namespace (creates the `build-nodepool` Karpenter NodePool, RBAC, and the build-images Job template) with `awsEKSEnabled=true`, `multiArchNative=true`, and `deployBuildkitPods=false` — buildkit Pods are managed by the `kubernetes` buildx driver, not the chart.
3. `ensure_eks_buildx_builder` creates a `docker buildx` builder named `builder-${KUBE_CONTEXT}` with one node per architecture (`builder-amd64` / `builder-arm64`). Each node is a `--driver=kubernetes` instance pinned to its arch via `nodeselector` and tolerating the `build-nodepool` taint. The builder is bootstrapped with a kubectl-level pre-wait of 900s (default) to give Karpenter time to scale up cold nodes.
4. `gradlew :buildImages:${BUILD_TARGET} -PregistryEndpoint=$MIGRATIONS_ECR_REGISTRY -Pbuilder=builder-${KUBE_CONTEXT}` builds and pushes each image directly to ECR.
5. After the build, the bootstrap script removes the buildx builder, which terminates the buildkit Pods so the `build-nodepool` can scale back to zero.

If the current `KUBE_CONTEXT` does not match `eks:|gke_|aks-|migration-eks-`, `eksKubernetesBuildkit.sh` aborts and points you at `dockerHostedBuildkit.sh` — the cloud backend is not a fallback for local clusters.

## Manual usage

To run a manual build against an existing EKS cluster (assumes you've already created the cluster and have `kubectl` configured against it):

```bash
export KUBE_CONTEXT=$(kubectl config current-context)
. ./buildImages/backends/eksKubernetesBuildkit.sh
setup_build_backend

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${MIGRATIONS_ECR_REGISTRY%%/*}"

./gradlew buildImagesToRegistry \
  -PregistryEndpoint="${MIGRATIONS_ECR_REGISTRY}" \
  -Pbuilder="builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
```

Verify images landed in ECR:

```bash
aws ecr list-images --repository-name "$(echo "${MIGRATIONS_ECR_REGISTRY}" | cut -d'/' -f2 | cut -d':' -f1)"
```

## Cleaning up

Remove the buildx builder when done — this terminates the buildkit Pods so `build-nodepool` can scale to zero:

```bash
docker buildx rm "builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
```

To remove the helm release entirely:

```bash
helm uninstall buildkit -n buildkit
kubectl delete namespace buildkit
```

To delete the cluster:

```bash
aws cloudformation delete-stack --stack-name "${CFN_STACK_NAME}"
```
