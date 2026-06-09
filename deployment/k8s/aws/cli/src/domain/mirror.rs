//! Third-party image and chart mirror manifest + private-ECR values generation.
//!
//! Contains the version-locked list of all container images and helm charts
//! required by the Migration Assistant, and generates the helm values override
//! that repoints all sub-charts at the private ECR mirror.

/// One helm chart to mirror: `(name, version, repository_url)`.
pub struct ChartEntry {
    pub name: &'static str,
    pub version: &'static str,
    pub repo: &'static str,
}

/// All helm charts required for deployment.
pub const CHARTS: &[ChartEntry] = &[
    ChartEntry {
        name: "cert-manager",
        version: "1.17.2",
        repo: "https://charts.jetstack.io",
    },
    ChartEntry {
        name: "strimzi-kafka-operator",
        version: "0.50.1",
        repo: "https://strimzi.io/charts/",
    },
    ChartEntry {
        name: "argo-workflows",
        version: "1.0.5",
        repo: "https://argoproj.github.io/argo-helm",
    },
    ChartEntry {
        name: "fluent-bit",
        version: "0.51.0",
        repo: "https://fluent.github.io/helm-charts",
    },
    ChartEntry {
        name: "kube-prometheus-stack",
        version: "72.0.0",
        repo: "https://prometheus-community.github.io/helm-charts",
    },
    ChartEntry {
        name: "etcd-operator",
        version: "0.4.2",
        repo: "oci://ghcr.io/aenix-io/charts",
    },
    ChartEntry {
        name: "opentelemetry-operator",
        version: "0.86.4",
        repo: "https://open-telemetry.github.io/opentelemetry-helm-charts",
    },
    ChartEntry {
        name: "localstack",
        version: "0.6.23",
        repo: "https://localstack.github.io/helm-charts",
    },
    ChartEntry {
        name: "grafana",
        version: "8.15.0",
        repo: "https://grafana.github.io/helm-charts",
    },
    ChartEntry {
        name: "jaeger",
        version: "3.2.0",
        repo: "https://jaegertracing.github.io/helm-charts",
    },
    ChartEntry {
        name: "kyverno",
        version: "3.7.1",
        repo: "https://kyverno.github.io/kyverno/",
    },
    ChartEntry {
        name: "aws-privateca-issuer",
        version: "v1.4.0",
        repo: "https://cert-manager.github.io/aws-privateca-issuer",
    },
    ChartEntry {
        name: "aws-mountpoint-s3-csi-driver",
        version: "2.5.0",
        repo: "https://awslabs.github.io/mountpoint-s3-csi-driver",
    },
    ChartEntry {
        name: "acmpca-chart",
        version: "1.2.2",
        repo: "oci://public.ecr.aws/aws-controllers-k8s",
    },
    ChartEntry {
        name: "cloudwatch-chart",
        version: "1.4.2",
        repo: "oci://public.ecr.aws/aws-controllers-k8s",
    },
];

/// All container images required for deployment.
pub const IMAGES: &[&str] = &[
    // cert-manager
    "quay.io/jetstack/cert-manager-controller:v1.17.2",
    "quay.io/jetstack/cert-manager-webhook:v1.17.2",
    "quay.io/jetstack/cert-manager-cainjector:v1.17.2",
    "quay.io/jetstack/cert-manager-startupapicheck:v1.17.2",
    // aws-privateca-issuer
    "public.ecr.aws/k1n1h4h4/cert-manager-aws-privateca-issuer:v1.4.0",
    // ack-acmpca-controller
    "public.ecr.aws/aws-controllers-k8s/acmpca-controller:1.2.2",
    // ack-cloudwatch-controller
    "public.ecr.aws/aws-controllers-k8s/cloudwatch-controller:1.4.2",
    // strimzi
    "quay.io/strimzi/operator:0.50.1",
    "quay.io/strimzi/kafka:0.50.1-kafka-4.0.0",
    "quay.io/strimzi/kafka-bridge:0.33.1",
    "quay.io/strimzi/kaniko-executor:0.50.1",
    "quay.io/strimzi/maven-builder:0.50.1",
    "quay.io/strimzi/buildah:0.50.1",
    // argo-workflows
    "quay.io/argoproj/workflow-controller:v4.0.3",
    "quay.io/argoproj/argocli:v4.0.3",
    "quay.io/argoproj/argoexec:v4.0.3",
    // fluent-bit
    "cr.fluentbit.io/fluent/fluent-bit:4.0.7",
    "docker.io/library/busybox:latest",
    // kube-prometheus-stack
    "quay.io/prometheus/prometheus:v3.3.1",
    "quay.io/prometheus-operator/prometheus-operator:v0.82.0",
    "quay.io/prometheus-operator/prometheus-config-reloader:v0.82.0",
    "quay.io/prometheus/node-exporter:v1.9.1",
    "quay.io/prometheus/alertmanager:v0.28.1",
    "quay.io/kiwigrid/k8s-sidecar:1.30.11",
    "registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0",
    "registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.5.3",
    "docker.io/bats/bats:v1.4.1",
    "quay.io/thanos/thanos:v0.38.0",
    // otel collector
    "public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3",
    // opentelemetry-operator
    "ghcr.io/open-telemetry/opentelemetry-operator/opentelemetry-operator:0.122.0",
    "quay.io/brancz/kube-rbac-proxy:v0.18.1",
    // grafana
    "docker.io/grafana/grafana:11.6.13",
    // jaeger
    "docker.io/jaegertracing/jaeger-agent:1.53.0",
    "docker.io/jaegertracing/jaeger-collector:1.53.0",
    "docker.io/jaegertracing/jaeger-query:1.53.0",
    "docker.io/jaegertracing/jaeger-cassandra-schema:1.53.0",
    "docker.io/library/cassandra:3.11.6",
    // kyverno
    "reg.kyverno.io/kyverno/kyverno:v1.17.1",
    "reg.kyverno.io/kyverno/kyvernopre:v1.17.1",
    "reg.kyverno.io/kyverno/background-controller:v1.17.1",
    "reg.kyverno.io/kyverno/cleanup-controller:v1.17.1",
    "reg.kyverno.io/kyverno/reports-controller:v1.17.1",
    "reg.kyverno.io/kyverno/kyverno-cli:v1.17.1",
    "ghcr.io/kyverno/readiness-checker:v0.1.0",
    "registry.k8s.io/kubectl:v1.34.3",
    // argo-workflows CRD upgrade job
    "registry.k8s.io/kubectl:v1.35.3",
    // localstack
    "docker.io/localstack/localstack:4.3.0",
    "docker.io/amazon/aws-cli:latest",
    // direct template references
    "docker.io/amazon/aws-cli:2.25.11",
    // coordinator cluster (RFS workflow)
    "docker.io/opensearchproject/opensearch:3.1.0",
    // mountpoint-s3 CSI driver
    "public.ecr.aws/mountpoint-s3-csi-driver/aws-mountpoint-s3-csi-driver:v2.5.0",
    "public.ecr.aws/csi-components/csi-node-driver-registrar:v2.16.0-eksbuild.3",
    "public.ecr.aws/csi-components/livenessprobe:v2.18.0-eksbuild.3",
    // buildkit + jib base images (--build lane)
    "docker.io/moby/buildkit:buildx-stable-1",
    "docker.io/library/amazoncorretto:21-al2023-headless",
    "docker.io/library/amazoncorretto:11-al2023-headless",
    "docker.io/library/amazoncorretto:8-alpine",
    "docker.io/library/amazonlinux:2023",
];

/// The ECR destination for a source image: `<ecr_host>/mirrored/<image_no_tag>:<tag>`.
pub fn ecr_dest(src: &str, ecr_host: &str) -> String {
    let (no_tag, tag) = match src.rsplit_once(':') {
        Some((h, t)) if !t.contains('/') => (h, t),
        _ => (src, "latest"),
    };
    format!("{ecr_host}/mirrored/{no_tag}:{tag}")
}

/// The ECR repository name for a source image (no host, no tag).
pub fn ecr_repo_name(src: &str) -> String {
    let no_tag = match src.rsplit_once(':') {
        Some((h, t)) if !t.contains('/') => h,
        _ => src,
    };
    format!("mirrored/{no_tag}")
}

/// Generate the private-ECR helm values YAML that repoints all sub-charts at
/// the mirrored registry. `ecr` is the full registry (e.g.
/// `123.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-stage`), `ecr_host` is
/// just the host portion.
pub fn generate_private_ecr_values(ecr_host: &str) -> String {
    let m = format!("{ecr_host}/mirrored");
    format!(
        r#"# Generated by migration-assistant CLI
# ECR Registry: {ecr_host}

ecrAuth:
  registry: "{ecr_host}"

charts:
  cert-manager:
    repository: "oci://{ecr_host}/charts/cert-manager"
    version: "v1.17.2"
    values:
      image:
        repository: "{m}/quay.io/jetstack/cert-manager-controller"
      webhook:
        image:
          repository: "{m}/quay.io/jetstack/cert-manager-webhook"
      cainjector:
        image:
          repository: "{m}/quay.io/jetstack/cert-manager-cainjector"
      startupapicheck:
        image:
          repository: "{m}/quay.io/jetstack/cert-manager-startupapicheck"

  strimzi-kafka-operator:
    repository: "oci://{ecr_host}/charts/strimzi-kafka-operator"
    values:
      image:
        registry: "{m}/quay.io"
      defaultImageRegistry: "{m}/quay.io"
      kafka:
        image:
          registry: "{m}/quay.io"
      buildah:
        image:
          registry: "{m}/quay.io"

  argo-workflows:
    repository: "oci://{ecr_host}/charts/argo-workflows"
    values:
      images:
        tag: v4.0.3
      crds:
        upgradeJob:
          image:
            repository: "{m}/registry.k8s.io/kubectl"
      controller:
        image:
          registry: "{m}/quay.io"
          repository: argoproj/workflow-controller
      executor:
        image:
          registry: "{m}/quay.io"
          repository: argoproj/argoexec
      server:
        image:
          registry: "{m}/quay.io"
          repository: argoproj/argocli

  fluent-bit:
    repository: "oci://{ecr_host}/charts/fluent-bit"
    values:
      image:
        repository: "{m}/cr.fluentbit.io/fluent/fluent-bit"
      testFramework:
        image:
          repository: "{m}/docker.io/library/busybox"
          tag: latest

  kube-prometheus-stack:
    repository: "oci://{ecr_host}/charts/kube-prometheus-stack"
    values:
      prometheusOperator:
        image:
          registry: "{m}/quay.io"
        admissionWebhooks:
          patch:
            image:
              registry: "{m}/registry.k8s.io"
        prometheusConfigReloader:
          image:
            registry: "{m}/quay.io"
      prometheus:
        prometheusSpec:
          image:
            registry: "{m}/quay.io"
          thanos:
            image: "{m}/quay.io/thanos/thanos:v0.38.0"
      alertmanager:
        alertmanagerSpec:
          image:
            registry: "{m}/quay.io"
      nodeExporter:
        enabled: true
      prometheus-node-exporter:
        image:
          registry: "{m}/quay.io"
      kube-state-metrics:
        image:
          registry: "{m}/registry.k8s.io"
      thanosRuler:
        thanosRulerSpec:
          image:
            registry: "{m}/quay.io"
      grafana:
        image:
          registry: "{ecr_host}"
          repository: "mirrored/docker.io/grafana/grafana"
        sidecar:
          image:
            registry: "{m}/quay.io"
        testFramework:
          image:
            repository: "{m}/docker.io/bats/bats"
            tag: "v1.4.1"

  opentelemetry-operator:
    repository: "oci://{ecr_host}/charts/opentelemetry-operator"
    values:
      manager:
        image:
          repository: "{m}/ghcr.io/open-telemetry/opentelemetry-operator/opentelemetry-operator"
        collectorImage:
          repository: "{m}/public.ecr.aws/aws-observability/aws-otel-collector"
      kubeRBACProxy:
        image:
          repository: "{m}/quay.io/brancz/kube-rbac-proxy"

  localstack:
    repository: "oci://{ecr_host}/charts/localstack"
    values:
      image:
        repository: "{m}/docker.io/localstack/localstack"

  grafana:
    repository: "oci://{ecr_host}/charts/grafana"
    values:
      image:
        registry: "{ecr_host}"
        repository: "mirrored/docker.io/grafana/grafana"
      sidecar:
        image:
          registry: "{m}/quay.io"
      testFramework:
        image:
          repository: "{m}/docker.io/bats/bats"
          tag: "v1.4.1"

  jaeger:
    repository: "oci://{ecr_host}/charts/jaeger"
    values:
      agent:
        image: "{m}/docker.io/jaegertracing/jaeger-agent"
      collector:
        image: "{m}/docker.io/jaegertracing/jaeger-collector"
      query:
        image: "{m}/docker.io/jaegertracing/jaeger-query"
      storage:
        cassandra:
          image: "{m}/docker.io/library/cassandra"

  kyverno:
    repository: "oci://{ecr_host}/charts/kyverno"
    values:
      image:
        registry: "{m}/reg.kyverno.io"
      initImage:
        registry: "{m}/reg.kyverno.io"
      webhooksCleanup:
        image:
          registry: "{m}/registry.k8s.io"
      test:
        image:
          registry: "{m}/ghcr.io"

  aws-privateca-issuer:
    repository: "oci://{ecr_host}/charts/aws-privateca-issuer"
    version: "v1.4.0"
    values:
      image:
        repository: "{m}/public.ecr.aws/k1n1h4h4/cert-manager-aws-privateca-issuer"

  aws-mountpoint-s3-csi-driver:
    repository: "oci://{ecr_host}/charts/aws-mountpoint-s3-csi-driver"
    values:
      image:
        repository: "{m}/public.ecr.aws/mountpoint-s3-csi-driver/aws-mountpoint-s3-csi-driver"
      sidecars:
        nodeDriverRegistrar:
          image:
            repository: "{m}/public.ecr.aws/csi-components/csi-node-driver-registrar"
        livenessProbe:
          image:
            repository: "{m}/public.ecr.aws/csi-components/livenessprobe"

  ack-acmpca-controller:
    repository: "oci://{ecr_host}/charts/acmpca-chart"
    version: "1.2.2"
    values:
      image:
        repository: "{m}/public.ecr.aws/aws-controllers-k8s/acmpca-controller"
        tag: "1.2.2"

  ack-cloudwatch-controller:
    repository: "oci://{ecr_host}/charts/cloudwatch-chart"
    version: "1.4.2"
    values:
      image:
        repository: "{m}/public.ecr.aws/aws-controllers-k8s/cloudwatch-controller"
        tag: "1.4.2"

defaultBucketConfiguration:
  bucketOperationImage: "{m}/docker.io/amazon/aws-cli:2.25.11"

otelCollectorImage: "{m}/public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3"

images:
  coordinatorCluster:
    repository: "{m}/docker.io/opensearchproject/opensearch"
    tag: "3.1.0"
"#
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ecr_dest_formats_correctly() {
        assert_eq!(
            ecr_dest(
                "quay.io/strimzi/kafka:0.50.1-kafka-4.0.0",
                "123.dkr.ecr.us-east-1.amazonaws.com"
            ),
            "123.dkr.ecr.us-east-1.amazonaws.com/mirrored/quay.io/strimzi/kafka:0.50.1-kafka-4.0.0"
        );
    }

    #[test]
    fn ecr_repo_name_strips_tag() {
        assert_eq!(
            ecr_repo_name("quay.io/strimzi/kafka:0.50.1"),
            "mirrored/quay.io/strimzi/kafka"
        );
    }

    #[test]
    fn values_yaml_contains_ecr_host() {
        let yaml = generate_private_ecr_values("123.dkr.ecr.us-east-1.amazonaws.com");
        assert!(yaml.contains("123.dkr.ecr.us-east-1.amazonaws.com"));
        assert!(yaml.contains("oci://123.dkr.ecr.us-east-1.amazonaws.com/charts/cert-manager"));
        assert!(yaml.contains("mirrored/quay.io/jetstack/cert-manager-controller"));
    }

    #[test]
    fn manifest_has_buildkit_image() {
        assert!(IMAGES.iter().any(|i| i.contains("moby/buildkit")));
    }

    #[test]
    fn manifest_chart_count() {
        assert_eq!(CHARTS.len(), 15);
    }
}
