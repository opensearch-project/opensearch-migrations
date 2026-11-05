#!/usr/bin/env bash
# Prints diagnostics from a test's deployment into the job log, one collapsed group per section. Runs after the test
# and before cleanup, so the EKS cluster and stacks still exist. Reads what was deployed from the CI state file the test
# script wrote. Best effort: it never fails the job.
#
# What it prints: Kubernetes pods, nodes, events, container logs (the last LOG_TAIL_LINES lines of each), Argo
# workflows and migration resources from the 'ma' namespace; the migration console's pytest logs; failed
# CloudFormation stack events; the testAutomation reports. Never Secrets or ConfigMaps. The job log is public, so every
# section is masked (account IDs, AWS access key IDs, credentials in URLs, password fields, Authorization headers), and
# GitHub ignores workflow commands (::...::) in the printed output.
#
# Usage: collect-logs.sh
set -uo pipefail
source "$(dirname "$0")/lib.sh"
state_load

NAMESPACE=ma
LOG_TAIL_LINES=500
YAML_MAX_LINES=3000

mask() {
    sed -E \
        -e 's/[0-9]{12}/[MASKED]/g' \
        -e 's/(AKIA|ASIA)[A-Z0-9]{16}/[MASKED-KEY-ID]/g' \
        -e 's#://[^/:@[:space:]]+:[^@[:space:]]+@#://[MASKED]@#g' \
        -e 's/("?[Pp]assword"?[[:space:]]*[:=][[:space:]]*)("[^"]*"|[^[:space:],}]+)/\1[MASKED]/g' \
        -e 's/([Aa]uthorization"?[[:space:]]*:[[:space:]]*"?(Basic|Bearer|basic|bearer)[[:space:]]+)[^[:space:]"]+/\1[MASKED]/g'
}

# section TITLE COMMAND...: runs COMMAND and prints its masked output as one collapsed group. Workflow commands are off
# inside the group, so a pod log line can't end the group, add masks or set outputs.
section() {
    local title="$1" token output; shift
    output=$("$@" 2>&1 | mask) || true
    [[ -n "${output//[[:space:]]/}" ]] || return 0
    # Letters only, so the job's account-ID masking (12 digits) can't change it.
    # shellcheck disable=SC2018 # ASCII letters on purpose (LC_ALL=C)
    token=$(LC_ALL=C tr -dc 'a-z' < /dev/urandom | head -c 24)
    echo "::group::$title"
    echo "::stop-commands::$token"
    printf '%s\n' "$output"
    echo "::$token::"
    echo "::endgroup::"
}

dump_kubernetes() {
    local ctx="$KUBE_CONTEXT" kc pod
    if ! kubectl config get-contexts "$ctx" >/dev/null 2>&1; then
        log "collect-logs: no kube context '$ctx'; skipping Kubernetes"
        return 0
    fi
    kc=(kubectl --context="$ctx" --request-timeout=60s)
    section "Kubernetes: pods" "${kc[@]}" get pods -A -o wide
    section "Kubernetes: nodes" "${kc[@]}" get nodes -o wide
    section "Kubernetes: events" "${kc[@]}" get events -A --sort-by=.lastTimestamp
    section "Kubernetes: Argo workflows" sh -c \
        "$(printf '%q ' "${kc[@]}") -n $NAMESPACE get workflows -o yaml | head -n $YAML_MAX_LINES"
    section "Kubernetes: migration resources" sh -c \
        "$(printf '%q ' "${kc[@]}") -n $NAMESPACE get kafkaclusters,capturedtraffics,captureproxies,datasnapshots,snapshotmigrations,trafficreplays,approvalgates,migrationruns -o yaml | head -n $YAML_MAX_LINES"
    for pod in $("${kc[@]}" -n "$NAMESPACE" get pods --no-headers 2>/dev/null |
                 awk '$3 != "Running" && $3 != "Completed" {print $1}'); do
        section "Kubernetes: describe $pod (not running)" "${kc[@]}" -n "$NAMESPACE" describe pod "$pod"
    done

    # testAutomation runs pytest in the background in the console pod and writes its log under /tmp.
    if "${kc[@]}" -n "$NAMESPACE" get pod migration-console-0 >/dev/null 2>&1; then
        # shellcheck disable=SC2016  # expands in the pod
        section "Migration console: pytest log" "${kc[@]}" -n "$NAMESPACE" exec migration-console-0 -- \
            sh -c 'for f in /tmp/*pytest*.log; do [ -f "$f" ] && echo "===== $f" && cat "$f"; done'
    fi

    for pod in $("${kc[@]}" -n "$NAMESPACE" get pods -o name 2>/dev/null); do
        pod="${pod#pod/}"
        section "Logs: $pod (last $LOG_TAIL_LINES lines per container)" "${kc[@]}" -n "$NAMESPACE" logs "$pod" \
            --all-containers --prefix --tail="$LOG_TAIL_LINES"
        # Only pods whose containers restarted have previous logs.
        if "${kc[@]}" -n "$NAMESPACE" get pod "$pod" -o jsonpath='{.status.containerStatuses[*].restartCount}' 2>/dev/null |
                grep -q '[1-9]'; then
            section "Logs: $pod, before its last restart" "${kc[@]}" -n "$NAMESPACE" logs "$pod" \
                --all-containers --prefix --previous --tail="$LOG_TAIL_LINES"
        fi
    done
}

dump_stack_events() {
    local stack
    for stack in "${MA_STACK_NAME:-}" "${CLUSTER_STACK_NAME:-}"; do
        [[ -n "$stack" ]] || continue
        # shellcheck disable=SC2016  # backticks are JMESPath literals
        section "CloudFormation: failed events for $stack" aws cloudformation describe-stack-events --stack-name "$stack" \
            --query 'StackEvents[?contains(ResourceStatus, `FAILED`)].[Timestamp,LogicalResourceId,ResourceType,ResourceStatus,ResourceStatusReason]' \
            --output text
    done
}

[[ -n "${KUBE_CONTEXT:-}" ]] && dump_kubernetes
dump_stack_events
for report in "$REPO_ROOT"/libraries/testAutomation/reports/*.json; do
    [[ -f "$report" ]] && section "Test report: $(basename "$report")" cat "$report"
done
[[ -f "$CI_STATE_FILE" ]] && section "CI state" cat "$CI_STATE_FILE"
exit 0
