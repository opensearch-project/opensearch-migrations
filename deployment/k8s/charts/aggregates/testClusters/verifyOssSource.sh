#!/usr/bin/env bash
#
# verifyOssSource.sh — check a testClusters source deployed with valuesEksOss.yaml end to end:
# nginx TLS and basic auth, Elasticsearch reachable only through nginx, and S3 snapshots written
# with Pod Identity credentials (via the keystore sidecar), restored, and repeated after a refresh.
#
# Usage:
#   verifyOssSource.sh --bucket <s3-bucket> [--region us-east-2] [--namespace ma] [--context CTX]
#                      [--user admin --password admin] [--base-path es-oss-verify/<timestamp>]
#                      [--skip-refresh-wait]
#
# The checks run from the pod's refresh-s3-keystore container, which has curl and reaches the
# source through its Service, so they exercise the same path a migration does. The S3 listing
# runs with your local AWS credentials. Exits non-zero on the first failed check.
set -euo pipefail

NAMESPACE=ma
CONTEXT="$(kubectl config current-context)"
REGION="${AWS_REGION:-us-east-2}"
BUCKET=""
ES_USER=admin
ES_PASSWORD=admin
BASE_PATH="es-oss-verify/$(date -u +%Y%m%dT%H%M%SZ)"
POD=elasticsearch-master-0
SERVICE_URL="https://elasticsearch-master:9200"
REFRESH_WAIT=true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bucket) BUCKET="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --context) CONTEXT="$2"; shift 2 ;;
    --user) ES_USER="$2"; shift 2 ;;
    --password) ES_PASSWORD="$2"; shift 2 ;;
    --base-path) BASE_PATH="$2"; shift 2 ;;
    --skip-refresh-wait) REFRESH_WAIT=false; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$BUCKET" ]] || { echo "--bucket is required" >&2; exit 2; }

K=(kubectl --context "$CONTEXT" -n "$NAMESPACE")
REPO=verify-repo
SNAP="verify-$(date -u +%s)"
INDEX=verify-docs

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }

# Run curl inside the pod. Prints the HTTP status on the last line.
pod_curl() {
  "${K[@]}" exec "$POD" -c refresh-s3-keystore -- curl -sS --cacert /tmp/verify-ca.crt \
    -o /tmp/verify-body -w '%{http_code}' "$@"
}
pod_body() { "${K[@]}" exec "$POD" -c refresh-s3-keystore -- cat /tmp/verify-body; }
auth=(-u "${ES_USER}:${ES_PASSWORD}")
json=(-H 'Content-Type: application/json')

echo "== pod"
"${K[@]}" wait --for=condition=Ready "pod/$POD" --timeout=600s >/dev/null || fail "pod not Ready"
ready=$("${K[@]}" get pod "$POD" -o jsonpath='{range .status.containerStatuses[*]}{.name}={.ready} {end}')
[[ "$ready" != *false* ]] && pass "all containers ready: $ready" || fail "containers not ready: $ready"

echo "== TLS and basic auth through the Service"
"${K[@]}" get secret source-nginx-tls -o jsonpath='{.data.ca\.crt}' | base64 -d \
  | "${K[@]}" exec -i "$POD" -c refresh-s3-keystore -- sh -c 'cat > /tmp/verify-ca.crt'
code=$(pod_curl "$SERVICE_URL/") ; [[ "$code" == 401 ]] && pass "no credentials -> 401" || fail "no credentials -> $code"
code=$(pod_curl -u "${ES_USER}:wrong" "$SERVICE_URL/"); [[ "$code" == 401 ]] && pass "wrong password -> 401" || fail "wrong password -> $code"
code=$(pod_curl "${auth[@]}" "$SERVICE_URL/"); [[ "$code" == 200 ]] && pass "valid credentials, CA-verified TLS -> 200" || fail "valid credentials -> $code"
pod_body | grep -q '"number" : "7.10.2"' && pass "Elasticsearch 7.10.2 answered" || fail "unexpected version: $(pod_body)"
pod_body | grep -q '"build_flavor" : "oss"' && pass "build_flavor oss" || fail "not the OSS build: $(pod_body)"

echo "== Elasticsearch HTTP is loopback-only"
pod_ip=$("${K[@]}" get pod "$POD" -o jsonpath='{.status.podIP}')
if "${K[@]}" exec "$POD" -c refresh-s3-keystore -- curl -s -m 5 -o /dev/null "http://${pod_ip}:9201/" ; then
  fail "Elasticsearch answered on ${pod_ip}:9201"
else
  pass "nothing answers on ${pod_ip}:9201"
fi

echo "== Pod Identity variables"
es_env=$("${K[@]}" exec "$POD" -c source -- bash -c '
  for p in /proc/[0-9]*; do
    if tr "\0" " " < "$p/cmdline" 2>/dev/null | grep -q org.elasticsearch.bootstrap.Elasticsearch; then
      tr "\0" "\n" < "$p/environ"; exit 0
    fi
  done; echo NO_ES_PROCESS')
[[ "$es_env" == NO_ES_PROCESS ]] && fail "could not find the Elasticsearch JVM"
# Informational: repository-s3 ignores these while the keystore holds credentials.
if grep -q '^AWS_CONTAINER_' <<<"$es_env"; then
  echo "INFO  Elasticsearch JVM has the Pod Identity variables (unused while the keystore has credentials)"
else
  echo "INFO  Elasticsearch JVM has no Pod Identity variables (post-renderer applied)"
fi
"${K[@]}" exec "$POD" -c refresh-s3-keystore -- sh -c 'test -n "$AWS_CONTAINER_CREDENTIALS_FULL_URI"' \
  && pass "refresh-s3-keystore still has the Pod Identity variables" || fail "refresh-s3-keystore lost the Pod Identity variables"

echo "== repository-s3 and keystore"
code=$(pod_curl "${auth[@]}" "$SERVICE_URL/_cat/plugins?h=component"); pod_body | grep -q repository-s3 \
  && pass "repository-s3 plugin loaded" || fail "repository-s3 not loaded ($code): $(pod_body)"
"${K[@]}" logs "$POD" -c refresh-s3-keystore --tail=50 | grep -q "Reloaded S3 credentials" \
  && pass "keystore refreshed at least once" || fail "keystore has not been refreshed yet"

snapshot_round_trip() {
  local snap="$1"
  code=$(pod_curl "${auth[@]}" "${json[@]}" -X PUT "$SERVICE_URL/_snapshot/$REPO" \
    -d "{\"type\":\"s3\",\"settings\":{\"bucket\":\"$BUCKET\",\"base_path\":\"$BASE_PATH\"}}")
  [[ "$code" == 200 ]] && pass "registered S3 repository s3://$BUCKET/$BASE_PATH" \
    || fail "repository registration -> $code: $(pod_body)"
  code=$(pod_curl "${auth[@]}" -X POST "$SERVICE_URL/_snapshot/$REPO/_verify")
  [[ "$code" == 200 ]] && pass "repository verified (node wrote and read S3)" || fail "verify -> $code: $(pod_body)"
  code=$(pod_curl "${auth[@]}" -X PUT "$SERVICE_URL/_snapshot/$REPO/$snap?wait_for_completion=true" \
    "${json[@]}" -d "{\"indices\":\"$INDEX\"}")
  [[ "$code" == 200 ]] && pod_body | grep -q '"state":"SUCCESS"' && pass "snapshot $snap SUCCESS" \
    || fail "snapshot -> $code: $(pod_body)"
}

echo "== snapshot round trip"
for i in $(seq 1 25); do
  pod_curl "${auth[@]}" "${json[@]}" -X POST "$SERVICE_URL/$INDEX/_doc/$i" -d "{\"n\":$i}" >/dev/null
done
pod_curl "${auth[@]}" -X POST "$SERVICE_URL/$INDEX/_refresh" >/dev/null
snapshot_round_trip "$SNAP"
objects=$(aws s3 ls --region "$REGION" --recursive "s3://$BUCKET/$BASE_PATH/" | wc -l | tr -d ' ')
[[ "$objects" -gt 0 ]] && pass "$objects objects under s3://$BUCKET/$BASE_PATH/" || fail "no objects in S3"
code=$(pod_curl "${auth[@]}" "${json[@]}" -X POST "$SERVICE_URL/_snapshot/$REPO/$SNAP/_restore?wait_for_completion=true" \
  -d "{\"indices\":\"$INDEX\",\"rename_pattern\":\"(.+)\",\"rename_replacement\":\"restored-\$1\"}")
[[ "$code" == 200 ]] && pass "restored as restored-$INDEX" || fail "restore -> $code: $(pod_body)"
pod_curl "${auth[@]}" -X POST "$SERVICE_URL/restored-$INDEX/_refresh" >/dev/null
pod_curl "${auth[@]}" "$SERVICE_URL/restored-$INDEX/_count" >/dev/null
pod_body | grep -q '"count":25' && pass "restored index has 25 documents" || fail "restored count: $(pod_body)"

if [[ "$REFRESH_WAIT" == true ]]; then
  echo "== after a credential refresh"
  before=$("${K[@]}" logs "$POD" -c refresh-s3-keystore | grep -c "Reloaded S3 credentials" || true)
  for _ in $(seq 1 30); do
    sleep 5
    now=$("${K[@]}" logs "$POD" -c refresh-s3-keystore | grep -c "Reloaded S3 credentials" || true)
    [[ "$now" -gt "$before" ]] && break
  done
  [[ "$now" -gt "$before" ]] && pass "keystore reloaded again" || fail "no keystore reload within 150 s"
  snapshot_round_trip "${SNAP}-after-refresh"
fi

echo "== clean up"
pod_curl "${auth[@]}" -X DELETE "$SERVICE_URL/restored-$INDEX" >/dev/null || true
pod_curl "${auth[@]}" -X DELETE "$SERVICE_URL/_snapshot/$REPO/$SNAP" >/dev/null || true
pod_curl "${auth[@]}" -X DELETE "$SERVICE_URL/_snapshot/$REPO/${SNAP}-after-refresh" >/dev/null || true
pod_curl "${auth[@]}" -X DELETE "$SERVICE_URL/$INDEX" >/dev/null || true
pod_curl "${auth[@]}" -X DELETE "$SERVICE_URL/_snapshot/$REPO" >/dev/null || true
echo "ALL CHECKS PASSED"
