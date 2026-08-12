#!/usr/bin/env bash
#
# deployWorkflowComponents.sh — bring up the capture-and-replay DATA PLANE for k6 load testing.
#
# Run AFTER kindTesting.sh (which installs the control plane: Argo, migration
# console, kube-prometheus, otel-collector, …). This deploys the traffic components DIRECTLY as
# plain Deployments/Services (the docker-compose stack, in k8s) — NO migration workflow, no CRs,
# no reconcilers — so everything comes up running and wired together:
#
#     k6 → capture-proxy → opensearch-source        (capture path)
#          capture-proxy → kafka → replayer → opensearch-target   (replay path)
#
# A load test is then just:
#     workflow k6 run --scenario ingest --config ingest-steady --target https://capture-proxy:9200
# Or the respective shell command picking the TestRun configmap matching the respective scenario and
# overwriting env file start values and env value overrides (see TrafficCapture/trafficLoadTest/scripts)
#
#
# Relationship to existing setups (keep in sync — this script intentionally duplicates
# the topology as plain Deployments rather than reusing either path):
#   - Topology / env / flags (Kafka KRaft listeners, CaptureProxy + TrafficReplayer args,
#     'logging-traffic-topic' / 'logging-group-default') mirror the canonical compose stack:
#       TrafficCapture/dockerSolution/src/main/docker/docker-compose.yml
#   - The CRD/reconciler-driven equivalent (Argo WorkflowTemplates + Strimzi-managed Kafka,
#     installed by the migrationAssistantWithArgo umbrella chart) lives in:
#       orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/
#         {captureProxy,setupCapture,replayer,setupKafka}.ts
#     This script deliberately bypasses all of that (no CRs, no Argo, no Strimzi) so the
#     data plane comes up as bare running Deployments for k6 load testing.
#
# Usage:
#   ./deployWorkflowComponents.sh up        # deploy + wait + print the k6 command (default)
#   ./deployWorkflowComponents.sh status    # show component state + the proxy URL
#   ./deployWorkflowComponents.sh down      # delete the data plane (control plane left intact)
#
# Env overrides:
#   CONTEXT=<kube-context>   NAMESPACE=ma
#   OS_IMAGE=mirror.gcr.io/opensearchproject/opensearch:2.19.1
#   KAFKA_IMAGE=mirror.gcr.io/apache/kafka:3.9.1
set -euo pipefail

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
OS_IMAGE="${OS_IMAGE:-mirror.gcr.io/opensearchproject/opensearch:2.19.1}"
KAFKA_IMAGE="${KAFKA_IMAGE:-mirror.gcr.io/apache/kafka:3.9.1}"

SOURCE_SVC="opensearch-source"
TARGET_SVC="opensearch-target"
PROXY_SVC="capture-proxy"
PROXY_URL="https://capture-proxy:9200"
KAFKA_TOPIC="logging-traffic-topic"
DP_LABEL="part-of=k6-dataplane"
CONSOLE_POD="migration-console-0"

# k6 load-test chart (operator + example TestRuns + RBAC). Installed here — NOT by the migration
# deploy.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
K6_CHART="${SCRIPT_DIR}/charts/components/k6LoadTest"
K6_RELEASE="k6-load-test"
# A run pulls two images. The runner is stock grafana/k6, through GCR's Docker Hub mirror (same
# pattern as OS_IMAGE/KAFKA_IMAGE).
K6_IMAGE="${K6_IMAGE:-mirror.gcr.io/grafana/k6:latest}"
# The scenarios and presets ride in migrations/k6_scripts (built from TrafficCapture/trafficLoadTest
# by buildImages) and are mounted at /scripts. Being a migrations/* image it lives in the same
# registry as the migration's own images, so the default is derived from captureProxyImage in
# install_k6_chart rather than hardcoded. Set K6_SCRIPTS_IMAGE to point somewhere else.
K6_SCRIPTS_IMAGE="${K6_SCRIPTS_IMAGE:-}"

K() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
img() { K get cm migration-image-config -o jsonpath="{.data.$1}" 2>/dev/null; }

# ── OpenSearch source/target (bare, no-auth, http single-node) ──────────────────
deploy_opensearch() {
  local name="$1"
  K apply -f - <<YAML >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: ${name}, labels: {app: ${name}, ${DP_LABEL/=/: }}}
spec:
  replicas: 1
  selector: {matchLabels: {app: ${name}}}
  template:
    metadata: {labels: {app: ${name}, ${DP_LABEL/=/: }}}
    spec:
      containers:
        - name: opensearch
          image: ${OS_IMAGE}
          env:
            - {name: discovery.type,              value: single-node}
            - {name: DISABLE_INSTALL_DEMO_CONFIG, value: "true"}
            - {name: plugins.security.disabled,   value: "true"}
            - {name: bootstrap.memory_lock,       value: "false"}
            - {name: OPENSEARCH_JAVA_OPTS,        value: "-Xms512m -Xmx512m"}
          ports: [{containerPort: 9200}]
          readinessProbe: {httpGet: {path: /_cluster/health, port: 9200}, initialDelaySeconds: 20, periodSeconds: 5}
          resources: {requests: {cpu: 250m, memory: 1Gi}, limits: {cpu: "1", memory: 2Gi}}
---
apiVersion: v1
kind: Service
metadata: {name: ${name}, labels: {app: ${name}, ${DP_LABEL/=/: }}}
spec: {selector: {app: ${name}}, ports: [{port: 9200, targetPort: 9200}]}
YAML
}

# ── Kafka (KRaft single-node) — the working trafficLoadTest compose config ──────
deploy_kafka() {
  K apply -f - <<YAML >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: kafka, labels: {app: kafka, ${DP_LABEL/=/: }}}
spec:
  replicas: 1
  strategy: {type: Recreate}
  selector: {matchLabels: {app: kafka}}
  template:
    metadata: {labels: {app: kafka, ${DP_LABEL/=/: }}}
    spec:
      containers:
        - name: kafka
          image: ${KAFKA_IMAGE}
          ports: [{containerPort: 9092}, {containerPort: 19092}, {containerPort: 29093}]
          env:
            - {name: KAFKA_NODE_ID, value: "1"}
            - {name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP, value: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT'}
            # Single-node KRaft in k8s: the controller + inter-broker must reach the broker via
            # localhost, NOT the kafka Service — a ClusterIP only routes to Ready endpoints, so
            # using the Service here deadlocks startup (pod can't become Ready until the quorum forms,
            # and the quorum can't form until the pod is Ready). Only the client listener uses kafka.
            - {name: KAFKA_ADVERTISED_LISTENERS, value: 'PLAINTEXT_HOST://kafka:9092,PLAINTEXT://localhost:19092'}
            - {name: KAFKA_PROCESS_ROLES, value: 'broker,controller'}
            - {name: KAFKA_CONTROLLER_QUORUM_VOTERS, value: '1@localhost:29093'}
            - {name: KAFKA_LISTENERS, value: 'CONTROLLER://:29093,PLAINTEXT_HOST://:9092,PLAINTEXT://:19092'}
            - {name: KAFKA_INTER_BROKER_LISTENER_NAME, value: 'PLAINTEXT'}
            - {name: KAFKA_CONTROLLER_LISTENER_NAMES, value: 'CONTROLLER'}
            - {name: CLUSTER_ID, value: 'load-test-cluster-00001'}
            - {name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR, value: "1"}
            - {name: KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS, value: "0"}
            - {name: KAFKA_TRANSACTION_STATE_LOG_MIN_ISR, value: "1"}
            - {name: KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR, value: "1"}
            - {name: KAFKA_LOG_DIRS, value: '/tmp/kraft-combined-logs'}
          readinessProbe: {tcpSocket: {port: 9092}, initialDelaySeconds: 15, periodSeconds: 5}
          resources: {requests: {cpu: 250m, memory: 512Mi}, limits: {cpu: "1", memory: 1Gi}}
---
apiVersion: v1
kind: Service
metadata: {name: kafka, labels: {app: kafka, ${DP_LABEL/=/: }}}
spec:
  selector: {app: kafka}
  ports:
    - {name: client, port: 9092, targetPort: 9092}
    - {name: internal, port: 19092, targetPort: 19092}
    - {name: controller, port: 29093, targetPort: 29093}
YAML
}

# ── Capture proxy (migrations/capture_proxy image; certs baked in) ──────────────
deploy_capture_proxy() {
  local image="$1" pull="$2"
  K apply -f - <<YAML >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: capture-proxy, labels: {app: capture-proxy, ${DP_LABEL/=/: }}}
spec:
  replicas: 1
  selector: {matchLabels: {app: capture-proxy}}
  template:
    metadata: {labels: {app: capture-proxy, ${DP_LABEL/=/: }}}
    spec:
      containers:
        - name: capture-proxy
          image: ${image}
          imagePullPolicy: ${pull:-IfNotPresent}
          # Jib bakes "java -cp @file CaptureProxy" as ENTRYPOINT; override so the classpath
          # script is exec'd and receives the main class + flags as args (matches docker-compose).
          command: ["/runJavaWithClasspath.sh"]
          args:
            - org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy
            - --kafkaConnection
            - kafka:9092
            - --destinationUri
            - http://${SOURCE_SVC}:9200
            - --listenPort
            - "9200"
            - --sslCertChainFile
            - /usr/share/captureProxy/config/pub.pem
            - --sslKeyFile
            - /usr/share/captureProxy/config/key.pem
            - --otelTraceCollectorEndpoint
            - http://otel-collector:4317
            - --otelMetricsCollectorEndpoint
            - http://otel-collector:4317
          ports: [{containerPort: 9200}]
          readinessProbe: {tcpSocket: {port: 9200}, initialDelaySeconds: 15, periodSeconds: 10}
          resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {cpu: "2", memory: 2Gi}}
---
apiVersion: v1
kind: Service
metadata: {name: capture-proxy, labels: {app: capture-proxy, ${DP_LABEL/=/: }}}
spec: {selector: {app: capture-proxy}, ports: [{port: 9200, targetPort: 9200}]}
YAML
}

# ── Traffic replayer (migrations/traffic_replayer image) ────────────────────────
deploy_replayer() {
  local image="$1" pull="$2"
  K apply -f - <<YAML >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: replayer, labels: {app: replayer, ${DP_LABEL/=/: }}}
spec:
  replicas: 1
  selector: {matchLabels: {app: replayer}}
  template:
    metadata: {labels: {app: replayer, ${DP_LABEL/=/: }}}
    spec:
      containers:
        - name: replayer
          image: ${image}
          imagePullPolicy: ${pull:-IfNotPresent}
          # No command override: the image entrypoint runs TrafficReplayer; args are its flags.
          args:
            - --speedup-factor
            - "2"
            - --target-uri
            - http://${TARGET_SVC}:9200
            - --insecure
            - --kafka-traffic-brokers
            - kafka:9092
            - --kafka-traffic-topic
            - ${KAFKA_TOPIC}
            - --kafka-traffic-group-id
            - logging-group-default
          env:
            - {name: SHARED_LOGS_DIR_PATH, value: /shared-logs-output/traffic-replayer-default}
          volumeMounts: [{name: shared-logs, mountPath: /shared-logs-output}]
          resources: {requests: {cpu: 250m, memory: 512Mi}, limits: {cpu: "1", memory: 1Gi}}
      volumes: [{name: shared-logs, emptyDir: {}}]
YAML
}

install_k6_chart() {
  say "Install k6 load-test chart (operator + example TestRuns + RBAC)"
  command -v helm >/dev/null || die "helm not found (needed to install the k6LoadTest chart)"
  if [ -z "$K6_SCRIPTS_IMAGE" ]; then
    # Reuse the registry the migration's own images came from: strip everything from "migrations/"
    # onward off captureProxyImage (cmd_up already verified it is present) and append the scripts
    # image. A registry that flattens images into one repo (ECR:
    # <repo>:migrations_capture_proxy_latest) has no such prefix to reuse — pass
    # K6_SCRIPTS_IMAGE explicitly there.
    local proxy_img prefix; proxy_img=$(img captureProxyImage)
    case "$proxy_img" in
      *migrations/*) prefix="${proxy_img%migrations/*}" ;;
      *)             prefix="" ;;
    esac
    K6_SCRIPTS_IMAGE="${prefix}migrations/k6_scripts:latest"
  fi
  helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
  # Vendor the k6-operator subchart: offline from Chart.lock if already vendored, else fetch it.
  helm dependency build "$K6_CHART" >/dev/null 2>&1 \
    || helm dependency update "$K6_CHART" >/dev/null 2>&1 \
    || die "helm dependency build failed for $K6_CHART"
  local repo="${K6_IMAGE%:*}" tag="${K6_IMAGE##*:}"
  local s_repo="${K6_SCRIPTS_IMAGE%:*}" s_tag="${K6_SCRIPTS_IMAGE##*:}"
  # Always re-pull the scripts image: it is rebuilt in place under a moving tag while iterating on
  # scenarios, so IfNotPresent would pin runner pods to whatever the node cached first.
  helm --kube-context "$CONTEXT" upgrade --install "$K6_RELEASE" "$K6_CHART" -n "$NAMESPACE" \
    --set image.repository="$repo" --set image.tag="$tag" --set image.pullPolicy=IfNotPresent \
    --set scriptsImage.repository="$s_repo" --set scriptsImage.tag="$s_tag" \
    --set scriptsImage.pullPolicy=Always \
    --timeout 300s 2>&1 | sed 's/^/  /'
  K rollout status deploy -l app.kubernetes.io/name=k6-operator --timeout=180s 2>&1 | tail -1 | sed 's/^/  /' || true
  ok "k6 operator + example TestRuns installed (runner: $K6_IMAGE, scripts: $K6_SCRIPTS_IMAGE)"
}

cmd_up() {
  say "Preflight (context=$CONTEXT namespace=$NAMESPACE)"
  kubectl --context "$CONTEXT" get ns "$NAMESPACE" >/dev/null 2>&1 || die "namespace $NAMESPACE not found"
  K get cm migration-image-config >/dev/null 2>&1 || die "migration-image-config missing (control plane not up? run startMinikubeAndDeployCharts.sh)"
  K get svc otel-collector >/dev/null 2>&1 || echo "  warn: otel-collector Service not found — proxy metrics won't be collected"
  local proxy_img proxy_pull rep_img rep_pull
  proxy_img=$(img captureProxyImage);  proxy_pull=$(img captureProxyPullPolicy)
  rep_img=$(img trafficReplayerImage); rep_pull=$(img trafficReplayerPullPolicy)
  [ -n "$proxy_img" ] || die "captureProxyImage not found in migration-image-config"
  [ -n "$rep_img" ]   || die "trafficReplayerImage not found in migration-image-config"
  ok "images: proxy=$proxy_img replayer=$rep_img"

  say "Deploy source + target OpenSearch"
  deploy_opensearch "$SOURCE_SVC"; deploy_opensearch "$TARGET_SVC"
  K rollout status deploy/"$SOURCE_SVC" --timeout=300s
  K rollout status deploy/"$TARGET_SVC" --timeout=300s
  ok "source + target ready"

  say "Deploy Kafka + create topic '$KAFKA_TOPIC'"
  deploy_kafka
  K rollout status deploy/kafka --timeout=300s
  K exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "$KAFKA_TOPIC" --partitions 1 --replication-factor 1 2>&1 | sed 's/^/  /'
  ok "kafka ready, topic present"

  say "Deploy Capture Proxy + Replayer"
  deploy_capture_proxy "$proxy_img" "$proxy_pull"
  deploy_replayer "$rep_img" "$rep_pull"
  K rollout status deploy/capture-proxy --timeout=300s
  K rollout status deploy/replayer --timeout=300s
  ok "capture proxy + replayer ready"

  install_k6_chart

  cmd_status
  say "Ready — run a load test"
  cat <<EOF
  Capture proxy is up at:  ${PROXY_URL}

  Run k6 against it (from the migration console pod, or anywhere with cluster context):
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- workflow k6 run \\
      --scenario ingest --config ingest-steady --target ${PROXY_URL}
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- workflow k6 list

  Traffic: k6 → capture-proxy → ${SOURCE_SVC}, and capture-proxy → kafka → replayer → ${TARGET_SVC}
  Tear down with:  $0 down
EOF
}

cmd_status() {
  say "Data-plane status (namespace=$NAMESPACE)"
  K get pods -l "$DP_LABEL" 2>/dev/null | sed 's/^/  /' || true
  echo "  proxy URL for k6:  ${PROXY_URL}"
}

cmd_down() {
  say "Uninstall k6 load-test chart (operator + example TestRuns + RBAC)"
  if command -v helm >/dev/null; then
    helm --kube-context "$CONTEXT" uninstall "$K6_RELEASE" -n "$NAMESPACE" 2>&1 | sed 's/^/  /' || true
  fi
  say "Delete the data plane (Deployments + Services)"
  K delete deploy,svc -l "$DP_LABEL" --ignore-not-found 2>&1 | sed 's/^/  /' || true
  ok "data plane + k6 chart torn down (control plane left intact)"
}

case "${1:-up}" in
  up)     cmd_up ;;
  status) cmd_status ;;
  down)   cmd_down ;;
  -h|--help) sed -n '2,37p' "$0" ;;
  *) die "unknown command '${1}' (use up | status | down)" ;;
esac
