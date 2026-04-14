#!/bin/sh
# =============================================================================
# privateEcrManifest.sh
#
# Version-locked list of ALL container images and helm charts required by the
# Migration Assistant. Includes production AND development/optional charts.
# Used by mirrorToEcr.sh and generatePrivateEcrValues.sh.
#
# Discovered via: helm template on each chart + runtime image analysis.
# Update this file when chart versions change.
# =============================================================================

# ALL helm charts: "name|version|repository"
CHARTS="
cert-manager|1.17.2|https://charts.jetstack.io
strimzi-kafka-operator|0.50.0|https://strimzi.io/charts/
argo-workflows|1.0.5|https://argoproj.github.io/argo-helm
fluent-bit|0.49.0|https://fluent.github.io/helm-charts
kube-prometheus-stack|72.0.0|https://prometheus-community.github.io/helm-charts
etcd-operator|0.4.2|oci://ghcr.io/aenix-io/charts
opentelemetry-operator|0.86.4|https://open-telemetry.github.io/opentelemetry-helm-charts
localstack|0.6.23|https://localstack.github.io/helm-charts
grafana|8.15.0|https://grafana.github.io/helm-charts
jaeger|3.2.0|https://jaegertracing.github.io/helm-charts
kyverno|3.7.1|https://kyverno.github.io/kyverno/
aws-privateca-issuer|v1.4.0|https://cert-manager.github.io/aws-privateca-issuer
aws-mountpoint-s3-csi-driver|2.5.0|https://awslabs.github.io/mountpoint-s3-csi-driver
acmpca-chart|1.2.2|oci://public.ecr.aws/aws-controllers-k8s
cloudwatch-chart|1.4.2|oci://public.ecr.aws/aws-controllers-k8s
"

# ALL container images required for deployment.
IMAGES="
# --- cert-manager ---
quay.io/jetstack/cert-manager-controller:v1.17.2
quay.io/jetstack/cert-manager-webhook:v1.17.2
quay.io/jetstack/cert-manager-cainjector:v1.17.2
quay.io/jetstack/cert-manager-startupapicheck:v1.17.2

# --- aws-privateca-issuer ---
public.ecr.aws/k1n1h4h4/cert-manager-aws-privateca-issuer:v1.4.0

# --- ack-acmpca-controller ---
public.ecr.aws/aws-controllers-k8s/acmpca-controller:1.2.2

# --- ack-cloudwatch-controller ---
public.ecr.aws/aws-controllers-k8s/cloudwatch-controller:1.4.2

# --- strimzi (operator + runtime images) ---
quay.io/strimzi/operator:0.50.0
quay.io/strimzi/kafka:0.50.0-kafka-4.0.0
quay.io/strimzi/kafka:0.50.0-kafka-4.0.1
quay.io/strimzi/kafka-bridge:0.33.1
quay.io/strimzi/kaniko-executor:0.50.0
quay.io/strimzi/maven-builder:0.50.0
quay.io/strimzi/buildah:0.50.0

# --- argo-workflows ---
# Keep in sync with values.yaml charts.argo-workflows and
# orchestrationSpecs/packages/argo-workflow-builders/tests/integ/infra/argoCluster.ts
quay.io/argoproj/workflow-controller:v4.0.3
quay.io/argoproj/argocli:v4.0.3
quay.io/argoproj/argoexec:v4.0.3

# --- fluent-bit ---
cr.fluentbit.io/fluent/fluent-bit:4.0.1
mirror.gcr.io/library/busybox:latest

# --- kube-prometheus-stack ---
quay.io/prometheus/prometheus:v3.3.1
quay.io/prometheus-operator/prometheus-operator:v0.82.0
quay.io/prometheus-operator/prometheus-config-reloader:v0.82.0
quay.io/prometheus/node-exporter:v1.9.1
quay.io/prometheus/alertmanager:v0.28.1
quay.io/kiwigrid/k8s-sidecar:1.30.0
registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0
registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.5.3
mirror.gcr.io/bats/bats:v1.4.1
quay.io/thanos/thanos:v0.38.0

# --- otel collector ---
public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3

# --- opentelemetry-operator ---
ghcr.io/open-telemetry/opentelemetry-operator/opentelemetry-operator:0.122.0
quay.io/brancz/kube-rbac-proxy:v0.18.1

# --- grafana ---
mirror.gcr.io/grafana/grafana:11.6.1

# --- jaeger ---
mirror.gcr.io/jaegertracing/jaeger-agent:1.53.0
mirror.gcr.io/jaegertracing/jaeger-collector:1.53.0
mirror.gcr.io/jaegertracing/jaeger-query:1.53.0
mirror.gcr.io/jaegertracing/jaeger-cassandra-schema:1.53.0
mirror.gcr.io/library/cassandra:3.11.6

# --- kyverno ---
reg.kyverno.io/kyverno/kyverno:v1.17.1
reg.kyverno.io/kyverno/kyvernopre:v1.17.1
reg.kyverno.io/kyverno/background-controller:v1.17.1
reg.kyverno.io/kyverno/cleanup-controller:v1.17.1
reg.kyverno.io/kyverno/reports-controller:v1.17.1
reg.kyverno.io/kyverno/kyverno-cli:v1.17.1
ghcr.io/kyverno/readiness-checker:v0.1.0
registry.k8s.io/kubectl:v1.34.3

# --- argo-workflows CRD upgrade job ---
registry.k8s.io/kubectl:v1.35.3

# --- localstack ---
mirror.gcr.io/localstack/localstack:4.3.0
mirror.gcr.io/amazon/aws-cli:latest

# --- direct template references ---
mirror.gcr.io/amazon/aws-cli:2.25.11

# --- coordinator cluster (used by RFS workflow) ---
mirror.gcr.io/opensearchproject/opensearch:3.1.0

# --- mountpoint-s3 CSI driver ---
public.ecr.aws/mountpoint-s3/aws-mountpoint-s3-csi-driver:v2.5.0
public.ecr.aws/csi-components/csi-node-driver-registrar:v2.16.0-eksbuild.3
public.ecr.aws/csi-components/livenessprobe:v2.18.0-eksbuild.3

# --- jib base images (used when building from source with --build-images) ---
mirror.gcr.io/library/amazoncorretto:17-al2023-headless

# --- buildkit base images (used by Dockerfiles when building from source) ---
mirror.gcr.io/library/amazoncorretto:11-al2023-headless
mirror.gcr.io/library/amazonlinux:2023
"
