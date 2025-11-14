#!/bin/sh

# Store our PID for later use
OUR_PID=$$

# Setup signal handling
cleanup() {
    echo "Received termination signal. Forwarding to child processes..."
    # Find the Java process PID
    JAVA_PID=$(pgrep -P $OUR_PID -f "java")
    if [ -n "$JAVA_PID" ]; then
        echo "Sending SIGTERM to Java process $JAVA_PID"
        kill -TERM "$JAVA_PID"
        # Wait for Java process to terminate gracefully (up to 30 seconds)
        TIMEOUT=30
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
