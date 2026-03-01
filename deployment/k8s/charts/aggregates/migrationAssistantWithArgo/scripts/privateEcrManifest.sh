#!/bin/sh
# =============================================================================
# privateEcrManifest.sh
#
# Version-locked list of ALL container images and helm charts required by the
# Migration Assistant. Includes production AND development/optional charts.
# Used by mirror-to-ecr.sh and generate-private-ecr-values.sh.
#
# Discovered via: helm template on each chart + runtime image analysis.
# Update this file when chart versions change.
# =============================================================================

# ALL helm charts: "name|version|repository"
CHARTS="
cert-manager|1.17.2|https://charts.jetstack.io
strimzi-kafka-operator|0.47.0|https://strimzi.io/charts/
argo-workflows|0.47.1|https://argoproj.github.io/argo-helm
fluent-bit|0.49.0|https://fluent.github.io/helm-charts
kube-prometheus-stack|72.0.0|https://prometheus-community.github.io/helm-charts
etcd-operator|0.4.2|oci://ghcr.io/aenix-io/charts
cloudnative-pg|0.27.1|https://cloudnative-pg.github.io/charts
opentelemetry-operator|0.86.4|https://open-telemetry.github.io/opentelemetry-helm-charts
localstack|0.6.23|https://localstack.github.io/helm-charts
grafana|8.15.0|https://grafana.github.io/helm-charts
jaeger|3.2.0|https://jaegertracing.github.io/helm-charts
kyverno|3.5.2|https://kyverno.github.io/kyverno/
"

# ALL container images required for deployment.
IMAGES="
# --- cert-manager ---
quay.io/jetstack/cert-manager-controller:v1.17.2
quay.io/jetstack/cert-manager-webhook:v1.17.2
quay.io/jetstack/cert-manager-cainjector:v1.17.2
quay.io/jetstack/cert-manager-startupapicheck:v1.17.2

# --- strimzi (operator + runtime images) ---
quay.io/strimzi/operator:0.47.0
quay.io/strimzi/kafka:0.47.0-kafka-3.9.0
quay.io/strimzi/kafka:0.47.0-kafka-4.0.0
quay.io/strimzi/kafka-bridge:0.32.0
quay.io/strimzi/kaniko-executor:0.47.0
quay.io/strimzi/maven-builder:0.47.0

# --- argo-workflows ---
quay.io/argoproj/workflow-controller:v3.7.9
quay.io/argoproj/argocli:v3.7.9
quay.io/argoproj/argoexec:v3.7.9

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

# --- etcd-operator ---
ghcr.io/aenix-io/etcd-operator:v0.4.2
gcr.io/kubebuilder/kube-rbac-proxy:v0.16.0
quay.io/coreos/etcd:v3.5.12

# --- cloudnative-pg operator + PostgreSQL runtime ---
ghcr.io/cloudnative-pg/cloudnative-pg:1.26.0
ghcr.io/cloudnative-pg/postgresql:17.2

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
reg.kyverno.io/kyverno/kyverno:v1.15.2
reg.kyverno.io/kyverno/kyvernopre:v1.15.2
reg.kyverno.io/kyverno/background-controller:v1.15.2
reg.kyverno.io/kyverno/cleanup-controller:v1.15.2
reg.kyverno.io/kyverno/reports-controller:v1.15.2
reg.kyverno.io/kyverno/kyverno-cli:v1.15.2
registry.k8s.io/kubectl:v1.32.7
mirror.gcr.io/library/busybox:1.35

# --- localstack ---
mirror.gcr.io/localstack/localstack:4.3.0
mirror.gcr.io/amazon/aws-cli:latest

# --- direct template references ---
mirror.gcr.io/amazon/aws-cli:2.25.11

# --- coordinator cluster (used by RFS workflow) ---
mirror.gcr.io/opensearchproject/opensearch:3.1.0
"
