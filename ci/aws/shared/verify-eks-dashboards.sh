#!/usr/bin/env bash
# Checks the CloudWatch dashboards the ACK controller manages.
# Usage: verify-eks-dashboards.sh create|delete --kube-context C --stage S
set -euo pipefail
source "$(dirname "$0")/lib.sh"

action="${1:-}"; shift || true
kube_context="" stage=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --kube-context) kube_context="$2"; shift 2 ;;
        --stage) stage="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
[[ "$action" == create || "$action" == delete ]] || die "action must be 'create' or 'delete'"
[[ -n "$kube_context" && -n "$stage" ]] || die "--kube-context and --stage are required"

resources=(
    "dashboards.cloudwatch.services.k8s.aws/ma-$stage-$REGION-capturereplay"
    "dashboards.cloudwatch.services.k8s.aws/ma-$stage-$REGION-reindexfromsnapshot"
)
names=("MA-$stage-$REGION-CaptureReplay" "MA-$stage-$REGION-ReindexFromSnapshot")

if [[ "$action" == create ]]; then
    kubectl --context="$kube_context" -n ma wait --for=condition=ACK.ResourceSynced=True "${resources[@]}" --timeout=180s
    for name in "${names[@]}"; do
        body=$(aws cloudwatch get-dashboard --dashboard-name "$name" --query DashboardBody --output text)
        jq -e 'type == "object" and ((.widgets | type) == "array") and (.widgets | length > 0)
               and any(.widgets[]; .type == "metric" and ((.properties.metrics | type) == "array")
                                   and (.properties.metrics | length > 0))' <<< "$body" >/dev/null \
            || die "CloudWatch dashboard '$name' has no populated metric widgets"
        log "Dashboard $name has metric widgets"
    done
    exit 0
fi

kubectl --context="$kube_context" -n ma delete "${resources[@]}" --wait=false
kubectl --context="$kube_context" -n ma wait --for=delete "${resources[@]}" --timeout=180s
for name in "${names[@]}"; do
    for _ in $(seq 1 24); do
        if output=$(aws cloudwatch get-dashboard --dashboard-name "$name" 2>&1); then
            sleep 5
            continue
        fi
        grep -q ResourceNotFound <<< "$output" && continue 2
        die "Unexpected error checking dashboard '$name': $output"
    done
    die "CloudWatch dashboard '$name' still exists after 120 seconds"
done
log "Dashboards deleted"
