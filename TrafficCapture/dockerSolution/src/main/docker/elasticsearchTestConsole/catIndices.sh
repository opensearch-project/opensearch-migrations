#!/bin/bash


# Check for the presence of SOURCE_DOMAIN_ENDPOINT environment variable
if [ -n "$SOURCE_DOMAIN_ENDPOINT" ]; then
    source_endpoint="${SOURCE_DOMAIN_ENDPOINT}"
    source_auth_user_and_pass="admin:admin"
else
    source_endpoint="https://capture-proxy:9200"
    source_auth_user_and_pass="admin:admin"
fi

# Check for the presence of MIGRATION_DOMAIN_ENDPOINT environment variable
if [ -n "$MIGRATION_DOMAIN_ENDPOINT" ]; then
    target_endpoint="${MIGRATION_DOMAIN_ENDPOINT}"
    target_auth_user_and_pass="admin:myStrongPassword123!"
else
    target_endpoint="https://opensearchtarget:9200"
    target_auth_user_and_pass="admin:myStrongPassword123!"
fi

# Default values
source_no_auth=false
target_no_auth=false

usage() {
  echo ""
  echo "Script to display all indices and doc counts for both the source cluster and target cluster."
  echo ""
  echo "Usage: "
  echo "  ./catIndices.sh <>"
  echo ""
  echo "Options:"
  echo "  --target-endpoint                           The target endpoint to query."
  echo "  --target-auth-user-and-pass                 The basic auth user and pass to use for target requests, e.g. 'admin:admin'."
  echo "  --target-no-auth                            Flag to provide no auth in target requests."
  echo "  --source-endpoint                           The source endpoint to query."
  echo "  --source-auth-user-and-pass                 The basic auth user and pass to use for source requests, e.g. 'admin:admin'."
  echo "  --source-no-auth                            Flag to provide no auth in source requests."
  echo ""
  exit 1
}

# Override default values with optional command-line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --target-endpoint)
            target_endpoint="$2"
            shift
            shift
            ;;
        --target-auth-user-and-pass)
            target_auth_user_and_pass="$2"
            shift
            shift
            ;;
        --target-no-auth)
            target_no_auth=true
            shift
            ;;
        --source-endpoint)
            source_endpoint="$2"
            shift
            shift
            ;;
        --source-auth-user-and-pass)
            source_auth_user_and_pass="$2"
            shift
            shift
            ;;
        --source-no-auth)
            source_no_auth=true
            shift
            ;;
        -h|--h|--help)
          usage
          ;;
        -*)
          echo "Unknown option $1"
          usage
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
curl $source_endpoint/_refresh --insecure $source_auth_string &> /dev/null
echo "curl $source_endpoint/_cat/indices?v"
curl $source_endpoint/_cat/indices?v --insecure $source_auth_string
echo ""
echo "TARGET CLUSTER"
curl $target_endpoint/_refresh --insecure $target_auth_string &> /dev/null
echo "curl $target_endpoint/_cat/indices?v"
curl $target_endpoint/_cat/indices?v --insecure $target_auth_string
echo ""