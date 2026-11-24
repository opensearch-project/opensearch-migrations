# Building Images on a Cloud Kubernetes Cluster (EKS / GKE / AKS)

This guide describes the cloud-Kubernetes BuildKit path used by `deployment/k8s/aws/aws-bootstrap.sh --build`. The backend lives in [backends/eksKubernetesBuildkit.sh](backends/eksKubernetesBuildkit.sh) and uses `docker buildx` with the `kubernetes` driver to spin up amd64 + arm64 buildkit Pods on the cluster's `build-nodepool`. Images are pushed directly to the cluster's ECR registry.

For the Docker-hosted local flow used by kind, see [README.md](README.md). The local flow runs the registry and BuildKit as Docker containers on the host and joins kind to the same Docker network — no in-cluster registry, no `kubectl port-forward`, no `--insecure-registry`.

## Prerequisites

- An EKS / GKE / AKS cluster reachable via `kubectl` (the EKS bootstrap script will create one for you).
- Docker CLI with `buildx` available.
- Helm 3.

## How the EKS path works

`aws-bootstrap.sh --build` does the following:
1. Sources `buildImages/backends/eksKubernetesBuildkit.sh` and calls `setup_build_backend`.
2. `setup_build_backend` installs the `buildImages` helm chart in the `buildkit` namespace (creates the `build-nodepool` Karpenter NodePool, RBAC, and the build-images Job template) with `awsEKSEnabled=true`, `multiArchNative=true`, and `deployBuildkitPods=false` — buildkit Pods are managed by the `kubernetes` buildx driver, not the chart.
3. `ensure_eks_buildx_builder` creates a `docker buildx` builder named `builder-${KUBE_CONTEXT}` with one node per architecture (`builder-amd64` / `builder-arm64`). Each node is a `--driver=kubernetes` instance pinned to its arch via `nodeselector` and tolerating the `build-nodepool` taint. The builder is pinned to `KUBE_CONTEXT` (see [Kube context and builder selection](#kube-context-and-builder-selection)) and selected through `BUILDX_BUILDER` for the calling shell only. Bootstrap allows 900s overall by default (`BUILDKIT_BOOTSTRAP_TIMEOUT`) and requires both nodes to report `running`. Unsuccessful inspect commands, including those that exit successfully with inactive nodes, are retried within the same deadline. Each inspect command is bounded by the remaining time when `timeout` or `gtimeout` is available. This works with older Buildx versions that do not support a Kubernetes driver timeout option. On exhaustion, pod and node-provisioning diagnostics are printed.
4. `gradlew :buildImages:${BUILD_TARGET} -PregistryEndpoint=$MIGRATIONS_ECR_REGISTRY -Pbuilder=builder-${KUBE_CONTEXT}` builds and pushes each image directly to ECR.
5. After the build, the bootstrap script removes the buildx builder with `remove_eks_buildx_builder`, which terminates the buildkit Pods so the `build-nodepool` can scale back to zero.

If the current `KUBE_CONTEXT` does not match `eks:|gke_|aks-|migration-eks-`, `eksKubernetesBuildkit.sh` aborts and points you at `dockerHostedBuildkit.sh` — the cloud backend is not a fallback for local clusters.

## Kube context and builder selection

The buildx `kubernetes` driver has no context option and rejects endpoint arguments. When a builder is created, it records `$KUBECONFIG` in each node's endpoint and later loads that file without a context override. Without pinning, the builder would follow the file's current-context, which can belong to a different cluster.

When `KUBE_CONTEXT` is set, which `aws-bootstrap.sh` always does (from `--kubectl-context` or the cluster name), the backend writes a single-context kubeconfig for it to `${BUILDKIT_KUBECONFIG_DIR:-~/.kube/buildx}/<builder>.kubeconfig` (mode 0600). The kubeconfig is resolved from your `KUBECONFIG`, which may list several files, or from `~/.kube/config`. Both nodes are created with `KUBECONFIG` set to that file. As a result, BuildKit Pods land on `KUBE_CONTEXT` even when the current-context points at another cluster, as with parallel `--skip-setting-k8s-context` deployments. Without `KUBE_CONTEXT`, the builder uses the current-context and your `KUBECONFIG`, as before.

An existing builder is reused only if all of the following hold:
- its nodes were created with this pinned file;
- the file still matches what `KUBE_CONTEXT` resolves to;
- both nodes are running.

`remove_eks_buildx_builder` uses `docker buildx rm` only for a pinned builder. Any other builder, such as one created before this change, would resolve the ambient context. For that kind of builder, the backend drops only the local entry (`--keep-daemon`) and deletes the `builder-amd64` / `builder-arm64` Deployments with `kubectl --context="${KUBE_CONTEXT}"`.

The backend never runs `docker buildx use`, so the machine-wide current builder is unchanged. `ensure_eks_buildx_builder` exports `BUILDX_BUILDER` for the calling shell and its children, and the Gradle tasks pass `--builder` from `-Pbuilder`.

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

Remove the buildx builder when done. This terminates the buildkit Pods so `build-nodepool` can scale to zero, deletes the builder's pinned kubeconfig, and unsets `BUILDX_BUILDER`. Run it from the same shell where you sourced the backend:

```bash
remove_eks_buildx_builder "builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
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
