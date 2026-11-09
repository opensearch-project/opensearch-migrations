#!/usr/bin/env bash
# Creates the source/target cluster configmaps Migration Assistant reads, from the cluster-details JSON that
# deploy-clusters.sh wrote.
# Usage: cluster-configmaps.sh --kube-context C --stage S --source-version V --target-version V
set -euo pipefail
source "$(dirname "$0")/lib.sh"

kube_context="" stage="" source_version="" target_version=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --kube-context) kube_context="$2"; shift 2 ;;
        --stage) stage="$2"; shift 2 ;;
        --source-version) source_version="$2"; shift 2 ;;
        --target-version) target_version="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
[[ -n "$kube_context" && -n "$stage" && -n "$source_version" && -n "$target_version" ]] \
    || die "--kube-context, --stage, --source-version and --target-version are required"

details="$REPO_ROOT/test/tmp/cluster-details-$stage.json"
[[ -f "$details" ]] || die "Cluster details not found: $details"

write_configmap() {
    local role="$1" version="$2" endpoint name expanded
    endpoint=$(jq -r --arg r "$role" '.[$r].endpoint' "$details")
    if [[ "$role" == source ]]; then
        jq -n --arg e "$endpoint" --arg region "$REGION" --arg v "$version" \
            '{endpoint: $e, allow_insecure: true, sigv4: {region: $region, service: "es"}, version: $v}' \
            > "/tmp/$role-cluster-config.json"
    else
        jq -n --arg e "$endpoint" --arg region "$REGION" \
            '{endpoint: $e, allow_insecure: true, sigv4: {region: $region, service: "es"}}' \
            > "/tmp/$role-cluster-config.json"
    fi
    expanded=$(expand_version "$version")
    name="$role-$expanded-migration-config"
    kubectl --context="$kube_context" create configmap "$name" --from-file=cluster-config="/tmp/$role-cluster-config.json" \
        --namespace ma --dry-run=client -o yaml | kubectl --context="$kube_context" apply -f -
    kubectl --context="$kube_context" -n ma get configmap "$name" -o yaml
}
write_configmap source "$source_version"
write_configmap target "$target_version"
