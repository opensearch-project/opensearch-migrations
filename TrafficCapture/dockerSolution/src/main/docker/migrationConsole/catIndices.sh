#!/bin/bash

# Default values
source_endpoint="https://capture-proxy-es:9200"
source_auth_user_and_pass="admin:admin"
source_no_auth=false
target_no_auth=false

# Check for the presence of COPILOT_SERVICE_NAME environment variable
if [ -n "$MIGRATION_DOMAIN_ENDPOINT" ]; then
    target_endpoint="https://${MIGRATION_DOMAIN_ENDPOINT}:443"
    target_auth_user_and_pass="admin:Admin123!"
else
    target_endpoint="https://opensearchtarget:9200"
    target_auth_user_and_pass="admin:admin"
fi

# Override default values with optional command-line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --target_endpoint)
            target_endpoint="$2"
            shift
            shift
            ;;
        --target_auth_user_and_pass)
            target_auth_user_and_pass="$2"
            shift
            shift
            ;;
        --target_no_auth)
            target_no_auth=true
            shift
            ;;
        --source_endpoint)
            source_endpoint="$2"
            shift
            shift
            ;;
        --source_auth_user_and_pass)
            source_auth_user_and_pass="$2"
            shift
            shift
            ;;
        --source_no_auth)
            source_no_auth=true
            shift
            ;;
        *)
            shift
            ;;
    esac
done

source_auth_string="-u $source_auth_user_and_pass"
target_auth_string="-u $target_auth_user_and_pass"

if [ "$source_no_auth" = true ]; then
    source_auth_string=""
fi
if [ "$target_no_auth" = true ]; then
    target_auth_string=""
fi

echo "SOURCE CLUSTER"
echo "curl $source_endpoint/_cat/indices?v"
curl $source_endpoint/_cat/indices?v --insecure $source_auth_string
echo ""
echo "TARGET CLUSTER"
echo "curl $target_endpoint/_cat/indices?v"
curl $target_endpoint/_cat/indices?v --insecure $target_auth_string
echo ""