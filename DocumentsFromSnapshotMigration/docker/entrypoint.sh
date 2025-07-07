#! /bin/bash

# Fail the script if any command fails
set -e

# Store our PID for later use
OUR_PID=$$

# Setup signal handling
cleanup() {
    echo "Received termination signal. Forwarding to child processes..."
    # Find the Java process PID specifically
    JAVA_PID=$(pgrep -f "java.*org\.opensearch\.migrations\.RfsMigrateDocuments")
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


SAFE_PRINT_COMMAND="$RFS_COMMAND"
if [[ "$RFS_COMMAND" == *"--target-password"* || "$RFS_COMMAND" == *"--targetPassword"* ]]; then
    SAFE_PRINT_COMMAND=$(echo "$RFS_COMMAND" | sed -E 's/--target(-)?[Pp]assword[ =]"?[^"[:space:]]+"?/--target-password=******/g')
fi
echo "Executing RFS_COMMAND: $SAFE_PRINT_COMMAND"

# Extract the value passed after --s3-local-dir or --s3LocalDir
S3_LOCAL_DIR=$(echo "$RFS_COMMAND" | sed -n 's/.*--\(s3-local-dir\|s3LocalDir\)\s\+\("[^"]\+"\|[^ ]\+\).*/\2/p' | tr -d '"')
# Extract the value passed after --lucene-dir or --luceneDir
LUCENE_DIR=$(echo "$RFS_COMMAND"  | sed -n 's/.*--\(lucene-dir\|luceneDir\)\s\+\("[^"]\+"\|[^ ]\+\).*/\2/p' | tr -d '"')
if [[ -n "$S3_LOCAL_DIR" ]]; then
    echo "Will delete S3 local directory between runs: $S3_LOCAL_DIR"
else
    echo "--s3-local-dir or --s3LocalDir argument not found in RFS_COMMAND. Will not delete S3 local directory between runs."
fi

if [[ -n "$LUCENE_DIR" ]]; then
    echo "Will delete lucene local directory between runs: $LUCENE_DIR"
else
    echo "--lucene-dir or --luceneDir argument not found in RFS_COMMAND. This is required."
    exit 1
fi

cleanup_directories() {
    if [[ -n "$S3_LOCAL_DIR" ]]; then
        echo "Cleaning up S3 local directory: $S3_LOCAL_DIR"
        rm -rf "$S3_LOCAL_DIR"
        echo "Directory $S3_LOCAL_DIR has been cleaned up."
    fi

    if [[ -n "$LUCENE_DIR" ]]; then
        echo "Cleaning up Lucene local directory: $LUCENE_DIR"
        rm -rf "$LUCENE_DIR"
        echo "Directory $LUCENE_DIR has been cleaned up."
    fi
}



[ -z "$RFS_COMMAND" ] && \
{ echo "Warning: RFS_COMMAND is empty! Exiting."; exit 1; } || \
while true; do
    # Run command in background and get its PID
    eval "$RFS_COMMAND" &
    COMMAND_PID=$!
    # Wait for the command to finish
    wait $COMMAND_PID
    EXIT_CODE=$?
    if [ $EXIT_CODE -ne 0 ]; then
        echo "Command exited with code $EXIT_CODE"
        break
    fi
    echo "Cleaning up directories before the next run."
    cleanup_directories
done
