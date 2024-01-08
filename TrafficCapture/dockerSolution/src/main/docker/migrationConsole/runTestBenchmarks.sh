#!/bin/bash

# Usage: ./combinedRunner.sh [--unique-id unique_id] [--endpoint endpoint] [--auth_user user] [--auth_pass pass] [--no-auth] [--no-ssl]

# Default values
endpoint="https://capture-proxy-es:9200"
auth_user="admin"
auth_pass="admin"
no_auth=false
no_ssl=false
unique_id="" # Default unique ID is empty
FOLDERS=("geonames" "nyc_taxis" "nested" "http_logs")

# Functions used in the script
populate_auth_string() {
    if [ "$no_auth" = true ]; then
        auth_string=""
    else
        auth_string=",basic_auth_user:${auth_user},basic_auth_password:${auth_pass}"
    fi

    if [ "$no_ssl" = true ]; then
        base_options_string=""
    else
        base_options_string="use_ssl:true,verify_certs:false"
    fi

    client_options="${base_options_string}${auth_string}"
}

run_benchmarks() {
    echo "Running opensearch-benchmark workloads against ${endpoint}"
    for WORKLOAD in "${FOLDERS[@]}"; do
        echo "Running opensearch-benchmark w/ '${WORKLOAD}' workload..." &&
        opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload-path=${WORKLOAD}/workload.json --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options=$client_options
    done
}

restore_files() {
    for FOLDER in "${FOLDERS[@]}"; do
        WORKLOAD_FILE="${FOLDER}/workload.json"
        DEFAULT_FILE="${FOLDER}/test_procedures/default.json"
        WORKLOAD_BACKUP="${WORKLOAD_FILE}.backup"
        DEFAULT_BACKUP="${DEFAULT_FILE}.backup"

        if [ -f "$WORKLOAD_BACKUP" ]; then
            mv "$WORKLOAD_BACKUP" "$WORKLOAD_FILE"
        fi
        if [ -f "$DEFAULT_BACKUP" ]; then
            mv "$DEFAULT_BACKUP" "$DEFAULT_FILE"
        fi
    done
    echo "Files have been restored to their original states."
}

trap restore_files EXIT

# Process command-line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --unique-id)
            unique_id="$2"
            shift
            shift
            ;;
        --endpoint)
            endpoint="$2"
            shift
            shift
            ;;
        --auth_user)
            auth_user="$2"
            shift
            shift
            ;;
        --auth_pass)
            auth_pass="$2"
            shift
            shift
            ;;
        --no-auth)
            no_auth=true
            shift
            ;;
        --no-ssl)
            no_ssl=true
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# Replace testID with unique_id in JSON files
SEARCH_STRING="testID"

for FOLDER in "${FOLDERS[@]}"; do
    WORKLOAD_FILE="${FOLDER}/workload.json"
    DEFAULT_FILE="${FOLDER}/test_procedures/default.json"
    WORKLOAD_BACKUP="${WORKLOAD_FILE}.backup"
    DEFAULT_BACKUP="${DEFAULT_FILE}.backup"

    cp "$WORKLOAD_FILE" "$WORKLOAD_BACKUP"
    cp "$DEFAULT_FILE" "$DEFAULT_BACKUP"

    sed -i'' "s/$SEARCH_STRING/$unique_id/g" "$WORKLOAD_FILE"
    sed -i'' "s/$SEARCH_STRING/$unique_id/g" "$DEFAULT_FILE"
done

# Run the benchmark tests
populate_auth_string
run_benchmarks
