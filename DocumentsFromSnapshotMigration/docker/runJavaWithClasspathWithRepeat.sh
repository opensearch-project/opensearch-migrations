#!/bin/sh
# Runs the RFS worker JVM (runJavaWithClasspath.sh execs java) and decides what happens when it
# exits.
#
# Memory-tier scaling off (RFS_WORKER_MEMORY_TIER unset): the original loop. Relaunch after exit 0;
# otherwise exit with the worker's code and let the kubelet restart the container.
#
# Memory-tier scaling on: this script never exits on its own, so the container keeps running and the
# pod stays Ready while a worker recovers from a crash. A ReplicaSet scale-down therefore ranks
# victims by the pod-deletion-cost the monitor sets from lease truth instead of preferring a pod
# that has just crashed. By worker exit code:
#   0 (item completed), 2 (lease timeout, successors created)       relaunch now
#   3 (heap OOM), or 137 when the cgroup's oom_kill count rose      record the crash as OOM, relaunch now
#   4 (no work for this tier), 7 (tier at its lease cap)            sleep 15-30s, then relaunch
#   6 (no work left anywhere)                                       sleep 60-90s, then relaunch
#   anything else (1, 5, a bare 137, ...)                           record the crash, relaunch now
# Each worker is launched with an instance id (--worker-instance-id; its worker id, and so every
# lease it holds, ends in that id) and the recent crashes as --predecessor-crashes
# ("<instanceId>=<exit code or OOMKilled>,..."). The worker's startup sweep escalates (OOM) or
# releases (anything else) any lease a crashed predecessor died holding. After three consecutive
# crashes of short-lived workers, relaunches back off (5s doubling to 5 minutes).
#
# A kernel (cgroup) OOM only reaches this script as a 137 when the kernel kills single processes.
# With cgroup v2's default memory.oom.group=1 the kernel kills every process in the container,
# this script included, and the kubelet restarts the container instead. Two optional environment
# hooks let evidence survive that:
#   RFS_STATE_DIR          a directory that outlives this script but not the pod (an emptyDir in
#                          k8s). Holds the crash list and an open-launch marker; a marker with no
#                          recorded outcome at startup means the previous container died abruptly.
#   RFS_CRASH_EVIDENCE_FILE a file some outside witness maintains with the same
#                          "<instanceId>=<outcome>" entries (in k8s, a downward-API projection of a
#                          pod annotation the fleet monitor writes when it sees an OOMKilled
#                          restart). Merged into the crash list before every launch; when startup
#                          finds an unexplained open launch, this script waits up to
#                          RFS_EVIDENCE_WAIT_SECONDS (default 120) for the file to name it before
#                          launching blind.
# Neither hook set: identical behavior, evidence just dies with the container.
#
# On SIGTERM a running worker is forwarded the signal; its shutdown hook also finishes a startup
# sweep that had not completed. If no worker is running but a crash has not been swept yet, one
# sweep-only worker runs before this script exits.

OUR_PID=$$

if [ -z "${RFS_WORKER_MEMORY_TIER+x}" ]; then
    # Setup signal handling
    cleanup() {
        echo "Received termination signal. Forwarding to child processes..."
        # Find the Java process PID
        JAVA_PID=$(pgrep -P $OUR_PID -f "java")
        if [ -n "$JAVA_PID" ]; then
            echo "Sending SIGTERM to Java process $JAVA_PID"
            kill -TERM "$JAVA_PID"
            # Wait for the Java process to terminate gracefully. Kept just under the pod's
            # terminationGracePeriodSeconds (390s when memory-tier scaling raises it) so the clean
            # shutdown path — whose worst case is the 5-minute failed-document-stream flush — can
            # finish before anything force-kills it.
            TIMEOUT=360
            while kill -0 "$JAVA_PID" 2>/dev/null && [ $TIMEOUT -gt 0 ]; do
                sleep 1
                TIMEOUT=$((TIMEOUT-1))
            done
            # Force kill if needed
            if kill -0 "$JAVA_PID" 2>/dev/null; then
                echo "Java process didn't terminate gracefully, forcing..."
                kill -9 "$JAVA_PID"
            fi
        else
            echo "No Java process found to terminate"
        fi
        exit 0
    }

    # Register the trap for SIGTERM and other relevant signals
    trap cleanup SIGTERM SIGINT SIGQUIT

    while true; do
        /rfs-app/runJavaWithClasspath.sh "$@" &
        JAVA_PID=$!
        # Wait for the Java process to finish
        wait $JAVA_PID
        EXIT_CODE=$?
        if [ $EXIT_CODE -ne 0 ]; then
            exit $EXIT_CODE
        fi
        echo "Process exited with code 0, restarting..."
    done
fi

# ── Supervised loop (memory-tier scaling on) ──────────────────────────────────────────────────────

LAUNCHER="${RFS_WORKER_LAUNCHER:-/rfs-app/runJavaWithClasspath.sh}"
# Recent crashes reported to each new worker; older ones are always swept by the time this many
# later workers have started.
CRASHES_REMEMBERED=5
# Kept just under the tier pods' terminationGracePeriodSeconds (390s) so the worker's clean shutdown
# (worst case: the 5-minute failed-document-stream flush) finishes before the kubelet's SIGKILL.
GRACE_SECONDS=360
SHORT_LIVED_SECONDS=30
CRASHES_BEFORE_BACKOFF=3
MAX_BACKOFF_SECONDS=300

# Trap handlers can run while a function is executing, where "$@" is that function's arguments, so
# the worker's arguments are captured once, quoted, and replayed with eval.
quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}
WORKER_ARGS=""
for arg in "$@"; do
    WORKER_ARGS="$WORKER_ARGS $(quote "$arg")"
done

JAVA_PID=""
SLEEP_PID=""
UNSWEPT_CRASH=0
LAUNCHES=0
SHORT_CRASHES=0
CONTAINER_STARTED_AT=$(date +%s)
PREDECESSOR_CRASHES=""
WORKER_INSTANCE_ID=""

EVIDENCE_FILE="${RFS_CRASH_EVIDENCE_FILE:-}"
EVIDENCE_WAIT_SECONDS="${RFS_EVIDENCE_WAIT_SECONDS:-120}"
CRASHES_FILE=""
OPEN_LAUNCH_FILE=""
if [ -n "${RFS_STATE_DIR:-}" ] && mkdir -p "$RFS_STATE_DIR" 2>/dev/null; then
    CRASHES_FILE="$RFS_STATE_DIR/crashes"
    OPEN_LAUNCH_FILE="$RFS_STATE_DIR/open-launch"
fi

log() {
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [rfs-supervisor] $*"
}

crash_known() {
    case ",$PREDECESSOR_CRASHES," in
        *",$1="*) return 0 ;;
    esac
    return 1
}

persist_crashes() {
    if [ -n "$CRASHES_FILE" ]; then
        printf '%s' "$PREDECESSOR_CRASHES" > "$CRASHES_FILE"
    fi
}

trim_crashes() {
    PREDECESSOR_CRASHES=$(printf '%s' "$PREDECESSOR_CRASHES" | tr ',' '\n' | tail -n "$CRASHES_REMEMBERED" | paste -sd, -)
}

# Folds the outside witness's "<instanceId>=<outcome>" entries (if any) into the crash list.
merge_evidence() {
    if [ -z "$EVIDENCE_FILE" ] || [ ! -r "$EVIDENCE_FILE" ]; then
        return 0
    fi
    for entry in $(tr ',' '\n' < "$EVIDENCE_FILE"); do
        case "$entry" in
            =*|*=) continue ;;
            *=*) ;;
            *) continue ;;
        esac
        if ! crash_known "${entry%%=*}"; then
            PREDECESSOR_CRASHES="${PREDECESSOR_CRASHES:+$PREDECESSOR_CRASHES,}$entry"
        fi
    done
    trim_crashes
    persist_crashes
}

# Per-launch arguments, quoted for the eval'd launch lines.
launch_args() {
    printf -- "--worker-instance-id %s" "$(quote "$WORKER_INSTANCE_ID")"
    if [ -n "$PREDECESSOR_CRASHES" ]; then
        printf -- " --predecessor-crashes %s" "$(quote "$PREDECESSOR_CRASHES")"
    fi
}

# Kernel OOM kills of this container's processes (cgroup v2, falling back to v1).
oom_kill_count() {
    for events_file in /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory/memory.oom_control; do
        if [ -r "$events_file" ]; then
            awk '$1 == "oom_kill" { print $2; found = 1 } END { if (!found) print 0 }' "$events_file"
            return
        fi
    done
    echo 0
}

random_below() {
    n=$(od -An -N2 -tu2 /dev/urandom 2>/dev/null | tr -d ' ')
    echo $(( ${n:-0} % $1 ))
}

record_crash() {
    PREDECESSOR_CRASHES="${PREDECESSOR_CRASHES:+$PREDECESSOR_CRASHES,}$WORKER_INSTANCE_ID=$CRASH_OUTCOME"
    trim_crashes
    persist_crashes
}

stop_worker_within_grace() {
    remaining=$GRACE_SECONDS
    while kill -0 "$JAVA_PID" 2>/dev/null && [ "$remaining" -gt 0 ]; do
        sleep 1
        remaining=$((remaining - 1))
    done
    if kill -0 "$JAVA_PID" 2>/dev/null; then
        log "Worker $JAVA_PID did not stop within ${GRACE_SECONDS}s; killing it"
        kill -9 "$JAVA_PID"
    fi
}

on_term() {
    log "Received termination signal"
    if [ -n "$SLEEP_PID" ]; then
        kill "$SLEEP_PID" 2>/dev/null
    fi
    if [ -n "$JAVA_PID" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
        log "Forwarding SIGTERM to worker $JAVA_PID"
        kill -TERM "$JAVA_PID"
        stop_worker_within_grace
    elif [ "$UNSWEPT_CRASH" -eq 1 ]; then
        log "No worker is running and the last crash is unswept; running a sweep-only worker"
        WORKER_INSTANCE_ID="$CONTAINER_STARTED_AT-sweep"
        eval "\"\$LAUNCHER\" $WORKER_ARGS $(launch_args) --sweep-only &"
        JAVA_PID=$!
        stop_worker_within_grace
    fi
    exit 0
}

trap on_term TERM INT QUIT

# Recover state a previous incarnation of this container left behind (kernel OOM group kill,
# runtime kill): reload the crash list, and if a launch opened but never recorded an outcome, give
# the evidence channel a bounded chance to say why it died before the next worker sweeps blind.
if [ -n "$CRASHES_FILE" ] && [ -r "$CRASHES_FILE" ]; then
    PREDECESSOR_CRASHES=$(cat "$CRASHES_FILE")
fi
if [ -n "$OPEN_LAUNCH_FILE" ] && [ -r "$OPEN_LAUNCH_FILE" ]; then
    OPEN_ID=$(cat "$OPEN_LAUNCH_FILE")
    rm -f "$OPEN_LAUNCH_FILE"
    if [ -n "$OPEN_ID" ] && ! crash_known "$OPEN_ID"; then
        UNSWEPT_CRASH=1
        merge_evidence
        if [ -n "$EVIDENCE_FILE" ] && ! crash_known "$OPEN_ID"; then
            log "Launch $OPEN_ID died without a recorded outcome; waiting up to ${EVIDENCE_WAIT_SECONDS}s for evidence"
            WAITED=0
            while [ "$WAITED" -lt "$EVIDENCE_WAIT_SECONDS" ]; do
                sleep 5 &
                SLEEP_PID=$!
                wait "$SLEEP_PID"
                SLEEP_PID=""
                WAITED=$((WAITED + 5))
                merge_evidence
                if crash_known "$OPEN_ID"; then
                    break
                fi
            done
        fi
        if crash_known "$OPEN_ID"; then
            log "Evidence names launch $OPEN_ID; the sweep will classify its lease accordingly"
        else
            log "No evidence for launch $OPEN_ID; any lease it held will be released at the same tier"
        fi
    fi
fi

while true; do
    LAUNCHES=$((LAUNCHES + 1))
    WORKER_INSTANCE_ID="$CONTAINER_STARTED_AT-$LAUNCHES"
    merge_evidence
    if [ -n "$OPEN_LAUNCH_FILE" ]; then
        printf '%s' "$WORKER_INSTANCE_ID" > "$OPEN_LAUNCH_FILE"
    fi
    OOM_KILLS_BEFORE=$(oom_kill_count)
    STARTED_AT=$(date +%s)
    eval "\"\$LAUNCHER\" $WORKER_ARGS $(launch_args) &"
    JAVA_PID=$!
    # From here the worker owns the sweep: its startup sweep, or its shutdown hook if SIGTERM
    # arrives first.
    UNSWEPT_CRASH=0
    wait "$JAVA_PID"
    EXIT_CODE=$?
    JAVA_PID=""
    if [ -n "$OPEN_LAUNCH_FILE" ]; then
        rm -f "$OPEN_LAUNCH_FILE"
    fi
    LIFETIME=$(( $(date +%s) - STARTED_AT ))
    SLEEP_FOR=0

    case "$EXIT_CODE" in
        0|2)
            SHORT_CRASHES=0
            ;;
        4|7)
            SHORT_CRASHES=0
            SLEEP_FOR=$((15 + $(random_below 16)))
            ;;
        6)
            SHORT_CRASHES=0
            SLEEP_FOR=$((60 + $(random_below 31)))
            ;;
        *)
            CRASH_OUTCOME=$EXIT_CODE
            if [ "$EXIT_CODE" -eq 137 ] && [ "$(oom_kill_count)" -gt "$OOM_KILLS_BEFORE" ]; then
                CRASH_OUTCOME=OOMKilled
            fi
            record_crash
            UNSWEPT_CRASH=1
            if [ "$LIFETIME" -lt "$SHORT_LIVED_SECONDS" ]; then
                SHORT_CRASHES=$((SHORT_CRASHES + 1))
            else
                SHORT_CRASHES=0
            fi
            if [ "$SHORT_CRASHES" -gt "$CRASHES_BEFORE_BACKOFF" ]; then
                SLEEP_FOR=5
                n=$((SHORT_CRASHES - CRASHES_BEFORE_BACKOFF - 1))
                while [ "$n" -gt 0 ] && [ "$SLEEP_FOR" -lt "$MAX_BACKOFF_SECONDS" ]; do
                    SLEEP_FOR=$((SLEEP_FOR * 2))
                    n=$((n - 1))
                done
                if [ "$SLEEP_FOR" -gt "$MAX_BACKOFF_SECONDS" ]; then
                    SLEEP_FOR=$MAX_BACKOFF_SECONDS
                fi
            fi
            ;;
    esac

    if [ "$SLEEP_FOR" -gt 0 ]; then
        log "Worker exited with code $EXIT_CODE after ${LIFETIME}s; relaunching in ${SLEEP_FOR}s"
        sleep "$SLEEP_FOR" &
        SLEEP_PID=$!
        wait "$SLEEP_PID"
        SLEEP_PID=""
    else
        log "Worker exited with code $EXIT_CODE after ${LIFETIME}s; relaunching"
    fi
done
