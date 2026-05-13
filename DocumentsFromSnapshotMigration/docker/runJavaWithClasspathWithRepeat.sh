#!/bin/sh

# Re-runs the RFS Java entrypoint, translating well-known exit codes from
# RfsMigrateDocuments into orchestrator-friendly behavior:
#
#   0 (success)              -> work was migrated; restart immediately to
#                                acquire the next shard.
#   3 (NO_WORK_LEFT_EXIT_CODE) -> all work for this run is done. Propagate
#                                the non-zero code so the orchestrator
#                                (k8s Deployment / kubelet) applies its
#                                CrashLoopBackOff backoff between restarts
#                                while the control plane (Argo / operator)
#                                observes completion and scales replicas
#                                to zero. Exiting 0 here would cause the
#                                Pod's container to be restarted with no
#                                backoff at all, immediately re-burning a
#                                ~2.5 s cold-start cycle.
#   4 (NO_WORK_AVAILABLE_EXIT_CODE) -> work still exists but none is currently
#                                available to this worker (e.g. all leases
#                                held by peers). Sleep with jittered
#                                exponential backoff before re-execing the
#                                JVM, so we don't burn ~2.5s of cold-start
#                                per attempt while peers finish.
#   anything else (real failure) -> propagate so the orchestrator (k8s, Argo,
#                                ECS, ...) sees the failure and retries
#                                according to its own policy.
#
# These codes are defined in RfsMigrateDocuments.java; keep both in sync.

OUR_PID=$$

# Backoff configuration for the "no work available right now" case. Override
# with environment variables if a particular orchestrator wants different
# behavior (e.g. very small clusters with chatty coordinators).
NO_WORK_BACKOFF_INITIAL_SECONDS="${NO_WORK_BACKOFF_INITIAL_SECONDS:-5}"
NO_WORK_BACKOFF_MAX_SECONDS="${NO_WORK_BACKOFF_MAX_SECONDS:-60}"

cleanup() {
    echo "Received termination signal. Forwarding to child processes..."
    JAVA_PID=$(pgrep -P $OUR_PID -f "java")
    if [ -n "$JAVA_PID" ]; then
        echo "Sending SIGTERM to Java process $JAVA_PID"
        kill -TERM "$JAVA_PID"
        TIMEOUT=30
        while kill -0 "$JAVA_PID" 2>/dev/null && [ $TIMEOUT -gt 0 ]; do
            sleep 1
            TIMEOUT=$((TIMEOUT-1))
        done
        if kill -0 "$JAVA_PID" 2>/dev/null; then
            echo "Java process didn't terminate gracefully, forcing..."
            kill -9 "$JAVA_PID"
        fi
    else
        echo "No Java process found to terminate"
    fi
    exit 0
}

trap cleanup TERM INT QUIT

backoff_seconds=$NO_WORK_BACKOFF_INITIAL_SECONDS
while true; do
    /rfs-app/runJavaWithClasspath.sh "$@" &
    JAVA_PID=$!
    wait $JAVA_PID
    EXIT_CODE=$?
    case "$EXIT_CODE" in
        0)
            echo "Process exited with code 0, restarting..."
            backoff_seconds=$NO_WORK_BACKOFF_INITIAL_SECONDS
            ;;
        3)
            # Propagate non-zero so the orchestrator's restart policy
            # applies a backoff (k8s CrashLoopBackOff) between attempts.
            # The control plane will scale us to zero once it sees the
            # work is complete; until then, backoff is what we want.
            echo "Process exited with code 3 (NO_WORK_LEFT). Propagating to orchestrator for backoff."
            exit 3
            ;;
        4)
            # Add small jitter (0-1s) so 50+ workers don't re-storm the
            # coordinator in lockstep after they all stalled together.
            jitter=$(awk 'BEGIN{srand(); print int(rand()*2)}')
            sleep_for=$((backoff_seconds + jitter))
            echo "Process exited with code 4 (NO_WORK_AVAILABLE). Sleeping ${sleep_for}s before retry."
            sleep "$sleep_for"
            # Exponential backoff capped at NO_WORK_BACKOFF_MAX_SECONDS.
            backoff_seconds=$((backoff_seconds * 2))
            if [ "$backoff_seconds" -gt "$NO_WORK_BACKOFF_MAX_SECONDS" ]; then
                backoff_seconds=$NO_WORK_BACKOFF_MAX_SECONDS
            fi
            ;;
        *)
            exit $EXIT_CODE
            ;;
    esac
done
