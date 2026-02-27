This updated plan includes the **Deep-Path Nesting** strategy for ECR and the **Helm OCI** mirroring workflow. This ensures your air-gapped EKS cluster can pull images and charts using their original names without modifying any Helm `values.yaml` files.

---

# 🚀 Air-Gap Readiness: Transparent K8s Mirroring Plan

This guide provides an automated strategy to mirror public container images and Helm charts into a private, air-gapped ECR registry.

## 🛠 Strategy Summary

1. **Deep-Path Mirroring:** ECR repositories will be created to exactly match the upstream structure (e.g., `mirrors/bitnami/nginx`).
2. **Transparent Redirection:** A `DaemonSet` configures nodes to redirect `docker.io` requests to your ECR "mirrors" folder.
3. **Helm OCI:** Helm charts are stored in ECR as OCI artifacts, allowing for a single source of truth for all deployment assets.

---

## Part 1: The Sync Script (Images & Helm Charts)

This script handles the "A Priori" loading. It creates the nested repository structure in ECR so that no manifest changes are needed later.

### `sync-to-ecr.sh`

```bash
#!/bin/bash
set -e

# --- CONFIGURATION ---
REGION="us-east-1"
ACCOUNT_ID="123456789012"
ECR_BASE="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
MIRROR_PREFIX="mirrors"
CHART_PREFIX="charts"

# List of images to mirror
IMAGES=(
  "bitnami/nginx:1.25.0"
  "library/ubuntu:22.04"
  "library/redis:7.0"
)

# List of Helm charts to mirror (Format: repo/chart:version)
CHARTS=(
  "bitnami/nginx:15.0.0"
)

# 1. Login to ECR for both Crane and Helm
aws ecr get-login-password --region $REGION | crane auth login --username AWS --password-stdin $ECR_BASE
aws ecr get-login-password --region $REGION | helm registry login --username AWS --password-stdin $ECR_BASE

# 2. Sync Images with Deep-Path Nesting
for IMG in "${IMAGES[@]}"; do
    # Extract path and tag
    PATH_TAG=(${IMG//:/ })
    IMAGE_PATH=${PATH_TAG[0]}
    
    # Ensure ECR repo exists with nesting
    REPO_NAME="${MIRROR_PREFIX}/${IMAGE_PATH}"
    echo "Ensuring ECR Repo: $REPO_NAME"
    aws ecr describe-repositories --repository-name "$REPO_NAME" --region $REGION || \
    aws ecr create-repository --repository-name "$REPO_NAME" --region $REGION

    # Copy directly from public registry to ECR
    echo "Syncing image $IMG..."
    crane copy "docker.io/$IMG" "${ECR_BASE}/${REPO_NAME}:${PATH_TAG[1]}"
done

# 3. Sync Helm Charts as OCI Artifacts
for CHART_DATA in "${CHARTS[@]}"; do
    # Format: bitnami/nginx:15.0.0
    CHART_FULL=$(echo $CHART_DATA | cut -d: -f1)
    CHART_NAME=$(echo $CHART_FULL | cut -d/ -f2)
    CHART_VER=$(echo $CHART_DATA | cut -d: -f2)
    
    echo "Processing Chart: $CHART_NAME..."
    
    # Create ECR repo for the chart
    aws ecr create-repository --repository-name "${CHART_PREFIX}/${CHART_NAME}" --region $REGION || true
    
    # Pull, Package, and Push to ECR
    helm pull "$CHART_FULL" --version "$CHART_VER"
    helm push "${CHART_NAME}-${CHART_VER}.tgz" "oci://${ECR_BASE}/${CHART_PREFIX}"
    rm "${CHART_NAME}-${CHART_VER}.tgz"
done

```

---

## Part 2: The Node Configurator (DaemonSet)

This `DaemonSet` must be applied to the cluster. It maps the `docker.io` namespace to your nested ECR mirror path.

### `containerd-configurator.yaml`

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: containerd-mirror-setup
  namespace: kube-system
spec:
  selector:
    matchLabels:
      name: containerd-mirror-setup
  template:
    metadata:
      labels:
        name: containerd-mirror-setup
    spec:
      hostPID: true
      containers:
      - name: configurator
        image: alpine:latest
        securityContext:
          privileged: true
        command:
        - sh
        - -c
        - |
          # Important: The endpoint includes the /v1/ suffix for ECR
          # and the /mirrors/ prefix to match our sync script's nesting.
          MIRROR_ENDPOINT="https://<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/v1/mirrors"
          CONFIG_FILE="/etc/containerd/config.toml"

          if grep -q "$MIRROR_ENDPOINT" "$CONFIG_FILE"; then
            echo "Config already exists. Sleeping..."
            sleep infinity
          fi

          echo "Configuring containerd mirror..."
          cat <<EOF >> "$CONFIG_FILE"

[plugins."io.containerd.grpc.v1.cri".registry.mirrors."docker.io"]
  endpoint = ["$MIRROR_ENDPOINT"]
EOF

          # Restart containerd via the host's systemd
          nsenter -t 1 -m -u -n -i systemctl restart containerd
          sleep infinity
        volumeMounts:
        - name: host-etc-containerd
          mountPath: /etc/containerd
      volumes:
      - name: host-etc-containerd
        hostPath:
          path: /etc/containerd

```

---

## Part 3: Deploying in the Air-Gap

When it comes time to deploy, your commands look like this:

### 1. Install the Chart

Since the chart is in ECR, use the OCI reference:

```bash
helm install my-release oci://<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/charts/nginx --version 15.0.0

```

### 2. Zero-Change Images

Because of the **DaemonSet** and **Deep-Path Nesting**, the chart will attempt to pull `docker.io/bitnami/nginx`. The node will translate this to:
`https://<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/v1/mirrors/bitnami/nginx`

This matches the ECR repository created by the script in Part 1. **No `values.yaml` overrides are required.**

---

## ⚠️ Final Checklist for your Agent

* **IAM Roles:** Ensure the EKS Node Instance Profile has `AmazonEC2ContainerRegistryReadOnly` permissions.
* **Public Images:** Remind the user that "Official" Docker images (like `nginx`, `ubuntu`) actually live at `library/nginx`. The sync script handles this automatically.
* **Helm Versions:** Ensure `helm` version is 3.8.0 or higher to support the `oci://` protocol properly.

Would you like me to include a cleanup script that removes old image tags from ECR to save on storage costs?