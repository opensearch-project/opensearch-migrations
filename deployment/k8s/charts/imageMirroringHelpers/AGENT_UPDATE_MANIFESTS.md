# Agent Prompt: Update ECR Mirror Manifests After Chart Changes

## Context

The Migration Assistant deploys on air-gapped EKS clusters by mirroring all
public container images and helm charts to a private ECR registry. The image
and chart lists are maintained in version-locked manifest files.

## Files You'll Be Working With

```
deployment/k8s/charts/aggregates/migrationAssistantWithArgo/
  scripts/
    privateEcrManifest.sh          # Production images + charts
    testClustersEcrManifest.sh     # Test cluster images + charts  
    buildImagesEcrManifest.sh      # Build tooling images
    generatePrivateEcrValues.sh    # Helm values overrides for ECR
    mirrorToEcr.sh                 # Copies everything to ECR
    verifyNoPublicImages.sh        # Validates no public refs remain
    updateEcrManifest.sh           # Discovery script (helper)
  values.yaml                     # Chart defaults (images section)
  templates/resources/
    imageConfigmap.yaml            # Configmap for workflow images
```

## When To Run This

After ANY of these changes:
- Chart version bumped in `values.yaml` (e.g., cert-manager 1.17.2 → 1.18.0)
- New chart added or removed from `conditionalPackageInstalls`
- Image tag changed in any template or values file
- New template added that references a container image

## Step-by-Step Process

### 1. Discover all images from helm charts

For each chart in `values.yaml` under `charts:`, pull it and extract images:

```bash
helm pull <chart> --repo <repo> --version <version> --untar
helm template test <chart>/ 2>/dev/null | grep 'image:' | sed 's/.*image:\s*//' | tr -d '"' | sort -u
rm -rf <chart>/
```

Do this for ALL charts, not just production ones. Check `conditionalPackageInstalls`
in both `values.yaml` and `valuesEks.yaml` to see the entire range of what can be enabled.

### 2. Check for runtime images not in helm template output

These operators pull images at runtime that don't appear in `helm template`:

| Operator | Runtime images | How to find |
|----------|---------------|-------------|
| strimzi | kafka, kafka-bridge, kaniko-executor, maven-builder | `helm template` → grep `STRIMZI_` env vars |
| etcd-operator | `quay.io/coreos/etcd:v3.5.12` | Hardcoded in operator, check etcd-operator release notes |
| prometheus-operator | thanos sidecar | Check `thanosRuler` in kube-prometheus-stack values |
| kube-prometheus-stack | grafana sidecar (`kiwigrid/k8s-sidecar`) | In grafana sub-chart values |

### 3. Check orchestrationSpecs for images

```bash
grep -rn 'image:' orchestrationSpecs/packages/migration-workflow-templates/src/ | grep -v test
```

Any hardcoded image should be made configurable via the `migration-image-config`
configmap (see `imageConfigmap.yaml` and `imageDefinitions.ts`).

### 4. Check the top-level chart templates

```bash
helm template test deployment/k8s/charts/aggregates/migrationAssistantWithArgo \
  -f .../values.yaml -f .../valuesEks.yaml \
  --set stageName=x --set aws.region=x --set aws.account=x \
  2>/dev/null | grep 'image:' | sort -u
```

### 5. Update the manifest files

**`privateEcrManifest.sh`**: All production images + charts. Format:
```
CHARTS="
name|version|repository
"
IMAGES="
# --- component ---
registry/path:tag
"
```

**`testClustersEcrManifest.sh`**: Only test cluster images (elasticsearch, opensearch).

**`buildImagesEcrManifest.sh`**: Only build tooling (moby/buildkit).

### 6. Update `generatePrivateEcrValues.sh`

For each chart, add image overrides that point to `${M}/<original-registry>/<path>`.
Follow the existing patterns in the file — each chart has a different values schema
for image configuration. Pull the chart and check its `values.yaml` to find the
correct keys.

### 7. Verify

```bash
./scripts/verifyNoPublicImages.sh <any-ecr-host>
```

This renders the helm template with the ECR values and checks that no `image:`
fields reference public registries (docker.io, quay.io, registry.k8s.io, etc.).

If it reports violations, add the missing overrides to `generatePrivateEcrValues.sh`.

### 8. Test on isolated cluster

Deploy to an isolated subnet (no NAT/IGW) with:
```bash
./aws-bootstrap.sh \
  --deploy-import-vpc-cfn --build-cfn --build-images \
  --build-chart-and-dashboards --push-all-images-to-private-ecr \
  --create-vpc-endpoints --stack-name ... --stage ... \
  --vpc-id ... --subnet-ids ... --region ...
```

Watch for `ImagePullBackOff` pods — each one means a missing image in the manifest.
Add it, re-mirror, and retry until all pods are Running.

## Common Mistakes

1. Forgetting runtime images (strimzi kafka, etcd data, thanos sidecar)
2. Using full URL in `repository` when chart expects `registry` + `repository` split
3. Not updating the OCI chart version override for cert-manager (`v` prefix)
4. Missing the `docker.io/` prefix — images like `busybox:latest` are actually `docker.io/library/busybox:latest`
5. Not checking the `testClusters` and `buildImages` manifests separately
